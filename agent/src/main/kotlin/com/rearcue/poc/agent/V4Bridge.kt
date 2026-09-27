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
) {
    private val lock = Any()
    private val idGen = AtomicLong(1)

    private var workspaceKey: String? = null
    private var workspacePath: String? = null
    private var activeTaskId: String? = null

    private var bridgeOpened = false
    private var bridgeSessionId: String? = null
    private var openAttempts = 0

    private var helloId: Long? = null
    private var initializeId: Long? = null
    private var listenId: Long? = null
    private var subscribeId: Long? = null
    private var subscriptionId: String? = null // subscribe ack 里的订阅键（退订用）
    private var subscribedTaskId: String? = null

    private var feed: ConversationFeed? = null
    private var handshakeDone = false

    /** 每次链路（re)上线调用：清桥状态、订阅与行集，等新一轮 workspace-list 重建。 */
    fun reset() {
        synchronized(lock) {
            workspaceKey = null
            workspacePath = null
            activeTaskId = null
            bridgeOpened = false
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
        }
    }

    /** 当前订阅中的任务（验收/调试读数）。 */
    fun subscribedTask(): String? = synchronized(lock) { subscribedTaskId }

    // ---------- 控制面入站 ----------

    fun onControl(text: String) {
        val obj = RelayEnvelope.parseObject(text) ?: return
        when (RelayEnvelope.primitiveOrNull(obj, "zcode_type")) {
            "workspace-list-response", "workspace-list-updated" -> onWorkspaceList(obj)
            "workspace-bridge-ready" -> onBridgeReady(obj)
            "workspace-bridge-error" ->
                log(
                    "v4 bridge-error reason=${RelayEnvelope.primitiveOrNull(obj, "reason")} " +
                        "error=${RelayEnvelope.primitiveOrNull(obj, "error")}",
                )

            else -> Unit
        }
    }

    private fun onWorkspaceList(obj: JsonObject) {
        val result = obj["result"] as? JsonObject ?: return
        val task = selectTask(result)
        var shouldOpen = false
        var shouldSubscribe: String? = null
        synchronized(lock) {
            val key = RelayEnvelope.primitiveOrNull(result, "activeWorkspaceKey")
                ?: firstWorkspaceKey(result)
            if (!key.isNullOrBlank() && workspaceKey == null) workspaceKey = key
            if (task != null) activeTaskId = task
            if (workspaceKey != null && !bridgeOpened && openAttempts < MAX_OPEN_ATTEMPTS) {
                openAttempts++
                shouldOpen = true
            } else if (bridgeSessionId != null && handshakeDone && task != null && task != subscribedTaskId) {
                shouldSubscribe = task
            }
        }
        if (shouldOpen) openBridge()
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
            if (!path.isNullOrBlank()) workspacePath = path
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
            when (frame.id) {
                helloId -> {
                    val clientMode = (frame.data as? JsonObject)
                        ?.let { RelayEnvelope.primitiveOrNull(it, "clientMode") }
                    initializeLocked(clientMode)
                }

                initializeId -> {
                    handshakeDone = true
                    eventListenLocked()
                    activeTaskId?.let { subscribeTaskLocked(it) }
                }

                subscribeId -> {
                    subscriptionId = (frame.data as? JsonObject)
                        ?.get("ack")
                        ?.let { (it as? JsonObject) }
                        ?.let { RelayEnvelope.primitiveOrNull(it, "subscriptionId") }
                    log("v4 subscribe ack subId=$subscriptionId task=$subscribedTaskId")
                }

                else -> Unit
            }
        }
    }

    private fun onError(frame: ChannelCodec.ServerFrame.Error) {
        log("v4 chan error id=${frame.id} data=${frame.data}")
        synchronized(lock) {
            // 订阅失败（任务过期等）：清订阅态，等下一次 workspace-list 换任务重试。
            if (frame.id == subscribeId) {
                subscribedTaskId = null
                subscribeId = null
            }
        }
    }

    private fun onEvent(frame: ChannelCodec.ServerFrame.EventFire) {
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
        sendChannel(
            ChannelCodec.encodePromise(initializeId!!, ChannelCodec.CHANNEL_ZCODE_AGENT, "initializeConversationV4", args),
        )
    }

    private fun eventListenLocked() {
        val path = workspacePath ?: return
        val id = nextId()
        listenId = id
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

    // ---------- 任务选择（与 TaskListParser 同口径：排除 archived，updatedAt 最新者优先） ----------

    private fun selectTask(result: JsonObject): String? {
        RelayEnvelope.primitiveOrNull(result, "activeTaskId")?.let { return it }
        val tasks = result["tasks"] as? JsonArray ?: return null
        var bestId: String? = null
        var bestUpdated = Long.MIN_VALUE
        for (element in tasks) {
            val task = element as? JsonObject ?: continue
            if ((task["archived"] as? JsonPrimitive)?.content == "true") continue
            val id = (task["taskId"] as? JsonPrimitive)?.content ?: continue
            val updated = (task["updatedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            if (bestId == null || updated > bestUpdated) {
                bestId = id
                bestUpdated = updated
            }
        }
        return bestId
    }

    private fun firstWorkspaceKey(result: JsonObject): String? =
        (result["workspaces"] as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("workspacePath") }
            ?.let { (it as? JsonPrimitive)?.content }

    companion object {
        /** bridge-open 无响应时的重试上限（随下一轮 workspace-list 再试）。 */
        const val MAX_OPEN_ATTEMPTS = 3
    }
}
