package com.rearcue.poc.agent

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * V4 workspace-bridge 会话编排（spec 0010 / 票 #88）：控制面（workspace-list →
 * workspace-bridge-open）与二进制通道面（hello → initialize → onDynamicConversationFrame →
 * subscribe）按服务端响应逐级推进，消费会话帧产出 [AgentSessionState]（回复原文 + 等待确认真检测）。
 *
 * wire 依据（2026-09-27 实抓 + 官方 web 客户端/asar 双端逆向）：
 * - `workspace-bridge-open {requestId, bridgeSessionId, bridgeGeneration, workspaceKey, taskId?}` →
 *   `workspace-bridge-ready {bridgeSessionId, bridgeGeneration, bridge:{workspacePath,...}}`；
 * - 通道（VQL 二进制，[ChannelCodec]，channel="zcode-agent"）：`helloConversationV4()` →
 *   `initializeConversationV4({kind:"clientHello",protocolVersion:3,clientId,clientKind,
 *   appVersion:"unknown",capabilities:{workspaceHookReviewUi:true}})` →
 *   `onDynamicConversationFrame({workspacePath})`（102 事件监听，arg 单值）→
 *   `subscribeConversationV4({workspacePath, sessionId:taskId})`（100 请求，args 位置数组）；
 * - 换订阅：`unsubscribeConversationV4({subscriptionId})`；
 * - 帧经 [ConversationFeed.applyFrame] 归一。
 *
 * 线程模型：入站回调都在传输线程（顺序执行），出站经注入回调（调用方已做同步）；
 * 状态用实例锁兜底（调试探针可能从主线程读）。
 */
class V4Bridge(
    /** 出站控制面 payload（data envelope 直载 zcode_type 帧）。 */
    private val sendControl: (String) -> Unit,
    /** 出站通道二进制消息（relay client 须已 setBridge）。 */
    private val sendChannel: (ByteArray) -> Unit,
    /** 桥就绪 → relay 会话写入桥身份（须在任何通道出站之前完成）。 */
    private val onBridgeSession: (bridgeSessionId: String, generation: Int?) -> Unit,
    /** 帧归一后的会话状态（回复/等确认）——调用方与任务表状态合并后进 core。 */
    private val onState: (AgentSessionState) -> Unit,
    /** 关键节点日志（进 logcat，验收锚）。 */
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /**
     * Session Lock 锁定会话键（票 #103）：非 null（锁定档）时订阅选择**优先锁会话**且
     * **绕过 30s 换向节流**（锁换向即换订阅）；null（自动档）维持既有 selectTask 口径。
     * 接线层从 DashboardCore 的 sessionLock 投影取值（传输线程读，调用方须保证可见性）。
     */
    private val lockedTaskId: () -> String? = { null },
    /**
     * sessions-index 帧归一后的全量等待视图（票 #103 P0）：锁档下他会话进等待的**唯一**
     * 协议级真检测（任务表无等待语义、conversation 帧只覆盖单订阅会话——见 [SessionIndexFeed]）。
     * 接线层把 entries 合成会话状态喂 core；调用在传输线程（与 [onState] 同线程模型）。
     */
    private val onIndex: (List<SessionIndexEntry>) -> Unit = {},
) {
    private val lock = Any()
    private val idGen = AtomicLong(1)

    private var workspaceKey: String? = null
    private var workspacePath: String? = null
    private var activeTaskId: String? = null

    private var bridgeOpened = false
    private var openInFlight = false
    private var bridgeSessionId: String? = null
    private var openAttempts = 0

    private var helloId: Long? = null
    private var initializeId: Long? = null
    private var initializeSent = false
    private var listenId: Long? = null
    private var listenSent = false
    private var subscribeId: Long? = null
    private var subscriptionId: String? = null // subscribe ack 里的订阅键（退订用）
    private var subscribedTaskId: String? = null

    private var feed: ConversationFeed? = null
    private var handshakeDone = false
    private var lastSwitchAtMs = 0L

    /**
     * 最近一次任务表的**在册**会话键（排除 archived，[eligibleTasks] 口径）：锁换向跟随
     * （[onSessionLockChanged]）没有当帧任务表可查，用它做与 workspace-list 路径同口径的
     * 在册校验——锁到幽灵 id 不改订。
     */
    private var knownTaskIds: Set<String> = emptySet()

    /**
     * 最近一次任务表出现过的工作区路径（＝要订 sessions-index 的集合）：握手完成时按它
     * 补订（首订可能早于握手），后续任务表新增工作区随轮询补订。
     */
    private var knownIndexPaths: Set<String> = emptySet()

    /** workspacePath → sessions-index 订阅记账（一工作区一订阅，见 [SessionIndexFeed]）。 */
    private val indexSubs = LinkedHashMap<String, IndexSub>()

    /** 事件监听 id → workspacePath（EventFire 按监听 id 分派到对应索引馈送）。 */
    private val indexListenIds = LinkedHashMap<Long, String>()

    /** 最近一次外发的索引全量视图（变化才回调，省接线层刷新）。 */
    private var lastIndexEmitted: List<SessionIndexEntry> = emptyList()

    /** 单工作区的 sessions-index 订阅记账（订阅/ack 两段 id + 馈送；监听 id 在 [indexListenIds]）。 */
    private inner class IndexSub(val workspacePath: String) {
        val feed = SessionIndexFeed(workspacePath)
        var subscribeId: Long? = null
        var subscriptionId: String? = null
    }

    /** 每次链路（re)上线调用：清桥状态、订阅与行集，等新一轮 workspace-list 重建。 */
    fun reset() {
        synchronized(lock) {
            workspaceKey = null
            workspacePath = null
            activeTaskId = null
            bridgeOpened = false
            openInFlight = false
            bridgeSessionId = null
            openAttempts = 0
            helloId = null
            initializeId = null
            listenId = null
            subscribeId = null
            subscriptionId = null
            subscribedTaskId = null
            feed = null
            handshakeDone = false
            knownTaskIds = emptySet()
            clearIndexLocked()
        }
    }

    /**
     * 索引订阅清零（链路重置/换桥）：新链路是新的通道上下文，旧监听与订阅随之作废，
     * 等握手后重建；外发视图缓存一并清掉，让重建后的首个快照按变化重新回调。
     */
    private fun clearIndexLocked() {
        indexSubs.clear()
        indexListenIds.clear()
        lastIndexEmitted = emptyList()
    }

    /** 当前订阅中的任务（验收/调试读数）。 */
    fun subscribedTask(): String? = synchronized(lock) { subscribedTaskId }

    // ---------- 控制面入站 ----------

    fun onControl(text: String) {
        val obj = RelayEnvelope.parseObject(text) ?: return
        when (RelayEnvelope.primitiveOrNull(obj, "zcode_type")) {
            "workspace-list-response", "workspace-list-updated" -> onWorkspaceList(obj)
            "workspace-bridge-ready" -> onBridgeReady(obj)
            "workspace-bridge-error" -> {
                synchronized(lock) { openInFlight = false } // 清闸：下一轮 workspace-list 可重试
                log(
                    "v4 bridge-error reason=${RelayEnvelope.primitiveOrNull(obj, "reason")} " +
                        "error=${RelayEnvelope.primitiveOrNull(obj, "error")}",
                )
            }

            else -> Unit
        }
    }

    private fun onWorkspaceList(obj: JsonObject) {
        val result = obj["result"] as? JsonObject ?: return
        val eligible = eligibleTasks(result)
        // Session Lock（票 #103）：锁定档优先订锁会话；锁 id 不在本表在册（消失竞态、
        // core 尚未清锁的一拍）时不订幽灵会话，退回最近活跃，下一轮对齐。锁在册即绕过
        // 30s 换向节流——锁换向即换订阅（粘滞只管自动档的跟头抖动）。
        val lockId = lockedTaskId()?.takeIf { id -> eligible.containsId(id) }
        val task = lockId ?: eligible.latestTaskId()
        // sessions-index（票 #103 P0）：任务表出现过的工作区各订一条索引——等待确认视图
        // 按工作区推送，缺一条就漏一个工作区的插队来源。桥工作区并入（首订兜底）。
        val indexPaths = eligible.mapNotNull { workspacePathOf(it) }.toMutableSet()
        synchronized(lock) { workspacePath }?.let { indexPaths += it }
        var shouldOpen = false
        var shouldSubscribe: String? = null
        synchronized(lock) {
            knownTaskIds = eligible.mapNotNull { taskIdOf(it) }.toSet()
            knownIndexPaths = indexPaths.toSet()
            val key = RelayEnvelope.primitiveOrNull(result, "activeWorkspaceKey")
                ?: firstWorkspaceKey(result)
            if (!key.isNullOrBlank() && workspaceKey == null) workspaceKey = key
            if (task != null) activeTaskId = task
            // 闸门：bootstrap 与 poll 响应会背靠背到达（实机 130ms 内两条），只许开一座桥——
            // 多桥并存会让桌面侧各自的 assembler 抢同一序号空间（#88 实机 rpc-transport-fault 教训）。
            if (workspaceKey != null && !bridgeOpened && !openInFlight && openAttempts < MAX_OPEN_ATTEMPTS) {
                openAttempts++
                openInFlight = true
                shouldOpen = true
            } else if (
                bridgeSessionId != null && handshakeDone && task != null && task != subscribedTaskId &&
                (lockId != null ||
                    // 粘滞：多会话并发跑时 updatedAt 每秒都在换头——无节流地跟头会让订阅/行集
                    // 每 10s 重建一次（实机 22 次 resubscribe，snapshot 尾窗把回复洗成 null）。
                    // 换向节流 30s：掉队半分钟才跟随新的最活跃任务（自动档专用，锁定档上面已放行）。
                    nowMs() - lastSwitchAtMs >= TASK_SWITCH_THROTTLE_MS)
            ) {
                shouldSubscribe = task
            }
        }
        if (shouldOpen) openBridge()
        shouldSubscribe?.let { task -> synchronized(lock) { subscribeTaskLocked(task) } }
        ensureIndexLocked(indexPaths)
    }

    /**
     * 锁定换向即时跟随（票 #103）：接线层在锁定档换档（含调换锁定目标）后调用——锁会话
     * 立即改订（绕过 30s 换向节流，不必等下一轮 workspace-list）；自动档不动订阅（回既有
     * 节流跟随口径）。握手未完/无桥时记下 [activeTaskId]，握手首订自然取锁定会话。
     *
     * 在册护栏（与 [onWorkspaceList] 同口径）：本帧没有任务表可查，用最近一次任务表的
     * 在册集校验——锁到幽灵 id（已消失/已归档的会话）既不改 [activeTaskId] 也不改订，
     * 等下一轮任务表按正常路径对齐。
     */
    fun onSessionLockChanged() {
        val lockedId = lockedTaskId() ?: return
        var shouldSubscribe: String? = null
        var skipped = false
        synchronized(lock) {
            if (lockedId !in knownTaskIds) {
                skipped = true
            } else {
                activeTaskId = lockedId
                if (bridgeSessionId != null && handshakeDone && lockedId != subscribedTaskId) {
                    shouldSubscribe = lockedId
                }
            }
        }
        if (skipped) {
            log("v4 lock-follow skip task=$lockedId not in task list")
            return
        }
        shouldSubscribe?.let { task -> synchronized(lock) { subscribeTaskLocked(task) } }
    }

    private fun onBridgeReady(obj: JsonObject) {
        val bridgeId = RelayEnvelope.primitiveOrNull(obj, "bridgeSessionId") ?: return
        val generation = (obj["bridgeGeneration"] as? JsonPrimitive)?.content?.toIntOrNull()
        val path = (obj["bridge"] as? JsonObject)
            ?.let { RelayEnvelope.primitiveOrNull(it, "workspacePath") }
        synchronized(lock) {
            bridgeSessionId = bridgeId
            bridgeOpened = true
            openInFlight = false
            if (!path.isNullOrBlank()) workspacePath = path
            // 每座桥是新的 ChannelServer：旧订阅/监听作废，握手从 hello 重来，
            // initialize 成功分支会按 activeTaskId 重新 subscribe。
            subscriptionId = null
            subscribedTaskId = null
            feed = null
            handshakeDone = false
            initializeSent = false
            listenSent = false
            clearIndexLocked()
        }
        onBridgeSession(bridgeId, generation)
        log("v4 bridge-ready id=$bridgeId gen=$generation path=$path")
        val hello = synchronized(lock) {
            helloId = nextId()
            helloId!!
        }
        sendChannel(ChannelCodec.encodePromise(hello, ChannelCodec.CHANNEL_ZCODE_AGENT, "helloConversationV4", buildJsonArray { }))
    }

    // ---------- 通道面入站 ----------

    fun onChannel(bytes: ByteArray) {
        when (val frame = ChannelCodec.decode(bytes)) {
            is ChannelCodec.ServerFrame.Initialize -> log("v4 chan initialize")
            is ChannelCodec.ServerFrame.Success -> onSuccess(frame)
            is ChannelCodec.ServerFrame.Error -> onError(frame)
            is ChannelCodec.ServerFrame.EventFire -> onEvent(frame)
            is ChannelCodec.ServerFrame.Unknown ->
                log("v4 chan unknown type=${frame.type} id=${frame.id}")

            null -> log("v4 chan decode fail (${bytes.size}B)")
        }
    }

    private fun onSuccess(frame: ChannelCodec.ServerFrame.Success) {
        synchronized(lock) {
            indexSubs.values.find { it.subscribeId == frame.id }?.let { sub ->
                sub.subscriptionId = ((frame.data as? JsonObject)?.get("ack") as? JsonObject)
                    ?.let { RelayEnvelope.primitiveOrNull(it, "subscriptionId") }
                log("v4 sessions-index ack path=${sub.workspacePath} subId=${sub.subscriptionId}")
                return
            }
            when (frame.id) {
                helloId -> {
                    // 幂等护栏：桌面出站帧在 ack 未落地前会重放——重放的 Success 不得重跑
                    // 下一步（否则 clientChanged 风暴 + listenId 被覆盖导致 EventFire 全部丢弃）。
                    if (initializeSent) return
                    val clientMode = (frame.data as? JsonObject)
                        ?.let { RelayEnvelope.primitiveOrNull(it, "clientMode") }
                    initializeLocked(clientMode)
                }

                initializeId -> {
                    if (listenSent) return
                    handshakeDone = true
                    log("v4 chan initialize-ack → listen+subscribe")
                    eventListenLocked()
                    activeTaskId?.let { subscribeTaskLocked(it) }
                    // sessions-index（票 #103 P0）：握手即按已知工作区补订等待视图。
                    ensureIndexLocked(knownIndexPaths + listOfNotNull(workspacePath))
                }

                subscribeId -> {
                    val ackObj = (frame.data as? JsonObject)?.get("ack") as? JsonObject
                    subscriptionId = ackObj?.let { RelayEnvelope.primitiveOrNull(it, "subscriptionId") }
                    log(
                        "v4 subscribe ack subId=$subscriptionId mode=" +
                            ackObj?.let { RelayEnvelope.primitiveOrNull(it, "mode") } +
                            " task=$subscribedTaskId",
                    )
                }

                else -> Unit
            }
        }
    }

    private fun onError(frame: ChannelCodec.ServerFrame.Error) {
        log("v4 chan error id=${frame.id} data=${frame.data}")
        synchronized(lock) {
            // 索引订阅失败（工作区不可订阅等）：留在记账里本链路不重试（防 10s 轮询刷日志），
            // 会话面照常；下次链路重建（reset）自然再试。
            indexSubs.values.find { it.subscribeId == frame.id }?.let { sub ->
                sub.subscribeId = null
                log("v4 sessions-index subscribe fail path=${sub.workspacePath}")
                return
            }
            // 订阅失败（任务过期等）：清订阅态，等下一次 workspace-list 换任务重试。
            if (frame.id == subscribeId) {
                subscribedTaskId = null
                subscribeId = null
            }
        }
    }

    private fun onEvent(frame: ChannelCodec.ServerFrame.EventFire) {
        // sessions-index 帧按监听 id 分派（票 #103 P0）：索引监听与会话监听并存，
        // 索引帧不落会话日志/行集。
        val indexPath = synchronized(lock) { indexListenIds[frame.id] }
        if (indexPath != null) {
            onIndexFrame(indexPath, frame.data)
            return
        }
        val payload = (frame.data as? JsonObject)?.get("payload") as? JsonObject
        log(
            "v4 frame id=${frame.id} listen=$listenId kind=${payload?.let { RelayEnvelope.primitiveOrNull(it, "kind") }}" +
                " raw=${frame.data}",
        )
        val task = synchronized(lock) {
            if (frame.id != listenId) return
            subscribedTaskId ?: return
        }
        val active = synchronized(lock) {
            val existing = feed
            if (existing != null && existing.boundSessionId == task) existing
            else ConversationFeed(task, nowMs).also { feed = it }
        }
        active.applyFrame(frame.data)?.let { onState(it) }
    }

    /**
     * sessions-index 一帧（票 #103 P0）：应用成功且视图有变 → 外发全量等待视图；
     * 序列断裂 → 清馈送后退订重订（换新快照）；与本订阅无关 → 跳过。
     * 回调在锁外（与 [onState] 同线程模型）。
     */
    private fun onIndexFrame(workspacePath: String, data: kotlinx.serialization.json.JsonElement?) {
        var emit: List<SessionIndexEntry>? = null
        synchronized(lock) {
            val sub = indexSubs[workspacePath] ?: return
            when (val outcome = sub.feed.applyFrame(data)) {
                IndexFrameOutcome.Ignored -> log("v4 sessions-index frame ignored path=$workspacePath")
                IndexFrameOutcome.Gap -> {
                    log("v4 sessions-index gap path=$workspacePath → resubscribe")
                    resubscribeIndexLocked(sub)
                }

                is IndexFrameOutcome.Applied -> {
                    // 实机验收锚（票 #103 P0）：帧到达与等待判读各留一行（会话面有 raw 帧日志，
                    // 索引面帧小而稀，记紧凑形态即可——waiting 列表就是插队候选的真来源）。
                    log(
                        "v4 sessions-index frame path=$workspacePath n=${sub.feed.entries().size}" +
                            " waiting=${sub.feed.entries().filter { it.waiting }.map { it.sessionId }}",
                    )
                    if (outcome.changed) emit = snapshotIndexLocked()
                }
            }
        }
        emit?.let { onIndex(it) }
    }

    /** 全量视图（跨工作区合并）：与上次外发一致返回 null（免接线层空刷）。 */
    private fun snapshotIndexLocked(): List<SessionIndexEntry>? {
        val union = LinkedHashMap<String, SessionIndexEntry>()
        for (sub in indexSubs.values) {
            for (entry in sub.feed.entries()) union[entry.sessionId] = entry
        }
        val next = union.values.toList()
        if (next == lastIndexEmitted) return null
        lastIndexEmitted = next
        return next
    }

    /** 按工作区补订 sessions-index（一工作区一条；已记账的不重试，含订阅失败者）。 */
    private fun ensureIndexLocked(paths: Collection<String>) {
        synchronized(lock) {
            if (!handshakeDone) return
            for (path in paths) {
                if (path.isBlank() || indexSubs.containsKey(path)) continue
                val sub = IndexSub(path)
                indexSubs[path] = sub
                val listenId = nextId()
                indexListenIds[listenId] = path
                sendChannel(
                    ChannelCodec.encodeEventListen(
                        listenId,
                        ChannelCodec.CHANNEL_ZCODE_AGENT,
                        "onDynamicSessionsIndexFrame",
                        buildJsonObject { put("workspacePath", path) },
                    ),
                )
                sendIndexSubscribeLocked(sub)
            }
        }
    }

    /** 序列断裂恢复：清馈送（不让过期等待滞留）→ 退旧订阅 → 重订换新快照（复用监听）。 */
    private fun resubscribeIndexLocked(sub: IndexSub) {
        sub.feed.clear()
        sub.subscriptionId?.let { old ->
            val unsubId = nextId()
            sendChannel(
                ChannelCodec.encodePromise(
                    unsubId,
                    ChannelCodec.CHANNEL_ZCODE_AGENT,
                    "unsubscribeSessionsIndexV4",
                    buildJsonArray { add(buildJsonObject { put("subscriptionId", old) }) },
                ),
            )
        }
        sub.subscriptionId = null
        sendIndexSubscribeLocked(sub)
    }

    private fun sendIndexSubscribeLocked(sub: IndexSub) {
        sub.subscribeId = nextId()
        log("v4 -> sessions-index subscribe path=${sub.workspacePath} id=${sub.subscribeId}")
        sendChannel(
            ChannelCodec.encodePromise(
                sub.subscribeId!!,
                ChannelCodec.CHANNEL_ZCODE_AGENT,
                "subscribeSessionsIndexV4",
                buildJsonArray { add(buildJsonObject { put("workspacePath", sub.workspacePath) }) },
            ),
        )
    }

    // ---------- 出站序列 ----------

    private fun openBridge() {
        val key = synchronized(lock) { workspaceKey } ?: return
        val requestId = "rb-${idGen.getAndIncrement()}"
        val bridgeId = "rearcue-bridge-${idGen.getAndIncrement()}"
        val payload = buildJsonObject {
            put("zcode_type", "workspace-bridge-open")
            put("requestId", requestId)
            put("bridgeSessionId", bridgeId)
            put("bridgeGeneration", 1)
            put("workspaceKey", key)
            synchronized(lock) { activeTaskId }?.let { put("taskId", it) }
        }
        log("v4 -> bridge-open key=$key")
        sendControl(RelayEnvelope.json.encodeToString(JsonObject.serializer(), payload))
    }

    private fun initializeLocked(clientMode: String?) {
        initializeSent = true
        val args = buildJsonArray {
            add(
                buildJsonObject {
                    put("kind", "clientHello")
                    put("protocolVersion", 3)
                    put("clientId", UUID.randomUUID().toString())
                    put("clientKind", if (clientMode == "desktop-continuous") "desktop" else "web")
                    put("appVersion", "unknown")
                    put("capabilities", buildJsonObject { put("workspaceHookReviewUi", true) })
                },
            )
        }
        initializeId = nextId()
        log("v4 -> initialize id=$initializeId")
        sendChannel(
            ChannelCodec.encodePromise(initializeId!!, ChannelCodec.CHANNEL_ZCODE_AGENT, "initializeConversationV4", args),
        )
    }

    private fun eventListenLocked() {
        val path = workspacePath ?: return
        val id = nextId()
        listenId = id
        listenSent = true
        log("v4 -> eventListen id=$listenId")
        sendChannel(
            ChannelCodec.encodeEventListen(
                id,
                ChannelCodec.CHANNEL_ZCODE_AGENT,
                "onDynamicConversationFrame",
                buildJsonObject { put("workspacePath", path) },
            ),
        )
    }

    private fun subscribeTaskLocked(taskId: String) {
        // 旧订阅先退（换任务时），保证同一时刻只有一个活动订阅。
        val oldSub = subscriptionId
        if (oldSub != null) {
            val unsubId = nextId()
            sendChannel(
                ChannelCodec.encodePromise(
                    unsubId,
                    ChannelCodec.CHANNEL_ZCODE_AGENT,
                    "unsubscribeConversationV4",
                    buildJsonArray { add(buildJsonObject { put("subscriptionId", oldSub) }) },
                ),
            )
            subscriptionId = null
        }
        val path = workspacePath ?: return
        subscribedTaskId = taskId
        lastSwitchAtMs = nowMs()
        feed = ConversationFeed(taskId, nowMs)
        val args = buildJsonArray {
            add(
                buildJsonObject {
                    put("workspacePath", path)
                    put("sessionId", taskId)
                },
            )
        }
        subscribeId = nextId()
        log("v4 -> subscribe task=$taskId")
        sendChannel(
            ChannelCodec.encodePromise(subscribeId!!, ChannelCodec.CHANNEL_ZCODE_AGENT, "subscribeConversationV4", args),
        )
    }

    private fun nextId(): Long = idGen.getAndIncrement()

    // ---------- 任务选择（与 TaskListParser 完全同口径：排除 archived，updatedAt 最新者优先） ----------
    // ⚠️ 不用 activeTaskId 打头：实机实证它会停在过期任务上，而任务表状态源取 updatedAt 最新
    // （真正在跑的会话）——两边会话键不一致时合并层丢弃 v4 回复原文（reply=null，#88 验收现场）。
    // 在册判定、最新选择、索引工作区收集共用 [eligibleTasks] 一处过滤（Standards #1 口径收口）。

    /** 在册任务（排除 archived）——锁在册校验与最新选择的唯一过滤口径。 */
    private fun eligibleTasks(result: JsonObject): List<JsonObject> =
        (result["tasks"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { task -> (task["archived"] as? JsonPrimitive)?.content != "true" }

    /** 在册集是否含该会话键（[onWorkspaceList] 与 [onSessionLockChanged] 同口径的前置）。 */
    private fun List<JsonObject>.containsId(taskId: String): Boolean = any { taskIdOf(it) == taskId }

    /** 在册里 updatedAt 最大者的会话键（无键任务不参选；平局取先到者，与原 selectTask 逐字同）。 */
    private fun List<JsonObject>.latestTaskId(): String? =
        mapNotNull { task -> taskIdOf(task)?.let { id -> id to updatedAtOf(task) } }
            .maxByOrNull { it.second }
            ?.first

    private fun taskIdOf(task: JsonObject): String? = (task["taskId"] as? JsonPrimitive)?.content

    private fun updatedAtOf(task: JsonObject): Long =
        (task["updatedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun workspacePathOf(task: JsonObject): String? = (task["workspacePath"] as? JsonPrimitive)?.content

    private fun firstWorkspaceKey(result: JsonObject): String? =
        (result["workspaces"] as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("workspacePath") }
            ?.let { (it as? JsonPrimitive)?.content }

    companion object {
        /** bridge-open 无响应时的重试上限（随下一轮 workspace-list 再试）。 */
        const val MAX_OPEN_ATTEMPTS = 3

        /** 换订阅节流：距上次换向至少间隔（并发会话跟头抖动的止血带）。 */
        const val TASK_SWITCH_THROTTLE_MS = 30_000L
    }
}
