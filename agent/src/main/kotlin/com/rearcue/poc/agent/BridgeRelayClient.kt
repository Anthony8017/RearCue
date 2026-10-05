package com.rearcue.poc.agent

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * PC 桥链路事实（spec 0031）：主屏 Agent 卡顶部与背屏会话状态标识共用。
 * - [DISABLED]：未配置 URL 或 Agent 镜像总开关关——链路不维护；
 * - [CONNECTING]：已起链路、首连尚未成功；
 * - [CONNECTED]：长轮询在线（事件在流）；
 * - [RETRYING]：请求失败，按指数退避重连中。
 * - [DISCONNECTED]：连续失败跨判停窗，仍自动重连；与主动停用区分。
 */
enum class BridgeLinkStatus { DISABLED, CONNECTING, CONNECTED, RETRYING, DISCONNECTED }

/**
 * PC 桥长轮询客户端（ADR 0006 / 票 #116）：对着桥的 `GET /events?since=<cursor>` 做
 * 长轮询，页面解码走 [BridgeEventCodec]，会话事实经 [onSession] 出口给接线层
 * （→ DashboardEvent.AgentSessionUpdated，与 ZCode 源同一事实模型）。
 *
 * 每条链路（重）连上后另取一次 `GET /snapshot`（spec 0016 / 票 #155）：桥当前在册会话全量，
 * 经 [onSnapshot] 出口——接线层据此对账桥来源的 Session Lock（缺席才清）。取不到不影响
 * 事件流，下一轮继续重试；未对账期间接线层保守维持上次已知在册集。
 *
 * 生命周期与 [com.rearcue.poc.agentmirror.AgentRelayClient] 同形：
 * [start] 幂等（同 URL 在跑直接忽略）、[stop] 断开不再重连；连接成功 → [onLinkUp]
 * （仅上升沿一次），请求失败 → [onLinkDown]（零打扰回落）+ [ReconnectPolicy] 指数退避
 * 重连，成功即归零。
 *
 * 超时判停（spec 0019 / 票 #187）：连续失败累计跨 [retryGiveUpMs]（默认 150s）后，
 * 对外状态降级 [BridgeLinkStatus.DISCONNECTED]——仍按退避自动重连，桥恢复应答即回到 CONNECTED；
 * 不能把连接失败当成用户停用（spec 0031）。
 *
 * 分页取件（issue #309）：每次长轮询带 `limit`，桥按条数与字节双封顶回一小页；本页取满
 * ＝桥侧还有积压 → 立刻续取（不睡），取不满 → 才是追平、进长轮询持有。首连（游标 0）
 * 因此是「一页页追」，而不是一次拉回整个事件环。响应体另设 [MAX_RESPONSE_BYTES] 硬闸：
 * 对面没分页时按一次失败回落，不把手机内存交给对面决定。
 *
 * 线程模型：独立 daemon 轮询线程，全部状态收口在 synchronized 面；回调在轮询线程触发，
 * 调用方自行切线程。读面走 GET；主动写面携带 Bridge URL fragment 里的访问凭据，
 * token 不进入日志/界面（spec 0024）。
 */
class BridgeRelayClient(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 长轮询：桥持有 ~25s，读超时留裕量（超时=空页语义之外的失败路径，走重连）。
        .readTimeout(35, TimeUnit.SECONDS)
        .build(),
    /** 快照取件前的静置窗（ms）：跨过桥侧会话文件补读的尾随去抖（~400ms，票 #155 评审修复）。 */
    private val snapshotSettleMs: Long = SNAPSHOT_SETTLE_MS_DEFAULT,
    /** 超时判停窗（ms）：连续失败累计跨窗 → DISCONNECTED（底层继续重连）。 */
    private val retryGiveUpMs: Long = RETRY_GIVE_UP_MS_DEFAULT,
) {
    /** 最近一次对外的链路状态（只在变化时回调，票 #165）。 */
    private var reportedStatus = BridgeLinkStatus.DISABLED

    private val policy = ReconnectPolicy()

    @Volatile
    private var enabled = false

    @Volatile
    private var baseUrl: String? = null

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var running = false

    private var cursor = 0L
    @Volatile
    private var voiceCursor: Long? = null
    private var linkUpNotified = false

    /**
     * 本链路是否已拿到在册快照（spec 0016 / 票 #155）：每条链路对账一次，
     * [start]/断线/ [stop] 复位——重连后必须重新对账，不能拿上一轮的快照当现状。
     */
    @Volatile
    private var snapshotFetched = false

    /** 已排了取快照的静置窗线程（防止每轮轮询各起一条，评审修复）。 */
    @Volatile
    private var snapshotPending = false

    /** 来源能力表（票 #172 数据面）：每次快照到达即刷新；批准入口判定（票 #174）读它。 */
    @Volatile
    private var lastCapabilities: SourceCapabilities = SourceCapabilities.DEFAULTS

    /**
     * 链路世代：每次 [start]/[stop]/失联递增。静置窗线程凭世代判自己是否已被作废
     * （换 URL、停链路、断线重连后，旧线程不得把上一轮链路的快照账记到新一轮上）。
     */
    @Volatile
    private var linkGeneration = 0L

    /**
     * 超时判停的内部子状态：
     * [retrySinceMs] 本链路自首败起的连续失败窗（null = 当前无失败累计），[retryDowngraded]
     * 已跨窗降级。降级后**底层重连不停**（网络抖动误判后恢复要自动翻回 CONNECTED，US9）；
     * 成功/[start]/[stop] 即复位（下次失败重新起窗）。访问收口在 synchronized 面。
     */
    private var retrySinceMs: Long? = null
    private var retryDowngraded = false

    /** 失败窗复位（start／stop／重连成功三处共用；调用方须已持有 this 锁）。 */
    private fun resetRetryWindow() {
        retrySinceMs = null
        retryDowngraded = false
    }

    @Volatile
    var onLinkUp: (() -> Unit)? = null

    @Volatile
    var onLinkDown: (() -> Unit)? = null

    /**
     * 链路状态变化：主屏 Agent 卡顶部与背屏状态标识共用这一份事实——
     * [BridgeLinkStatus.CONNECTING] 首连中、[BridgeLinkStatus.CONNECTED] 已连上（事件在流）、
     * [BridgeLinkStatus.RETRYING] 请求失败、[BridgeLinkStatus.DISCONNECTED] 持续失败（均自动重连），
     * [BridgeLinkStatus.DISABLED] 未配置/停用。
     * 只在**变化**时回调（同值不刷屏）；线程同 [onLinkUp]（轮询线程/调用线程），调用方自行切线程。
     */
    @Volatile
    var onStatusChanged: ((BridgeLinkStatus) -> Unit)? = null

    @Volatile
    var onSession: ((AgentSessionState) -> Unit)? = null

    /**
     * 来源在册/归档事实（spec 0023 / 票 #238）：事件页的 `membership` 与快照的
     * `memberships` 都从这里出。事实先于同页活动/快照回调，确保墓碑先拦迟到活动。
     */
    @Volatile
    var onMembership: ((AgentMembershipFact) -> Unit)? = null

    /**
     * 快照附带的来源在册事实（断线重连对账）。在 [onSnapshot] 前整批交出，
     * 调用方必须先落事实再替换桥名册，避免旧快照复活已归档会话。
     */
    @Volatile
    var onMembershipSnapshot: ((List<AgentMembershipFact>) -> Unit)? = null

    /**
     * 批准无果终态（review 2026-09-30 / spec 0018-4 AC3 补遗）：桥侧把「已受理之后无人取走/
     * 请求过期」的会话动作判死后发的标记（事件里的 `actionExpired`），键已加 [BridgeEventCodec.SESSION_PREFIX]。
     * 接线层走失败提示链（一次提示、不重试）；普通会话事实仍照常经 [onSession] 出。
     */
    @Volatile
    var onActionExpired: ((String) -> Unit)? = null

    /**
     * 在册快照（spec 0016 / 票 #155）：链路重新连上后取到的桥在册会话全量（键已加
     * [BridgeEventCodec.SESSION_PREFIX]，与 [onSession] 同一模型）。这是「桥侧现状」的
     * 权威答复——接线层据此替换桥在册集并允许清桥来源的锁；取不到时本回调不触发
     * （保守维持上次已知集，锁不误清）。
     */
    @Volatile
    var onSnapshot: ((List<AgentSessionState>) -> Unit)? = null

    /**
     * 开始维护桥链路：立即起轮询线程。**幂等**——同 URL 且线程在跑直接忽略
     * （重复 start 不另起线程，防轮询风暴；换 URL 先 stop 再 start）。
     */
    fun start(url: String) {
        val normalized = url.trimEnd('/')
        if (normalized.toHttpUrlOrNull() == null) {
            // External address input must be rejected before the polling thread starts.
            // Otherwise Request.Builder().url() can throw on that daemon thread and kill the app.
            log("bridge start rejected invalid url")
            stop()
            return
        }
        val endpoint = runCatching { BridgeEndpoint.parse(url) }.getOrNull()
        if (endpoint == null) {
            log("bridge start rejected invalid url")
            stop()
            return
        }
        synchronized(this) {
            if (enabled && baseUrl == endpoint.baseUrl && running) {
                accessToken = endpoint.accessToken
                return
            }
            enabled = true
            baseUrl = endpoint.baseUrl
            accessToken = endpoint.accessToken
            cursor = 0L
            voiceCursor = null
            linkUpNotified = false
            snapshotFetched = false
            snapshotPending = false
            linkGeneration++
            policy.reset()
            resetRetryWindow()
            if (!running) {
                running = true
                Thread(::pollLoop, "bridge-poll").apply { isDaemon = true }.start()
            }
            log("bridge start url=${baseUrl}")
        }
        status(BridgeLinkStatus.CONNECTING)
    }

    /** 停止（开关关/清 URL）：断开、不再重连、报一次失联（若曾上线）。 */
    fun stop() {
        synchronized(this) {
            enabled = false
            baseUrl = null
            val wasUp = linkUpNotified
            linkUpNotified = false
            snapshotFetched = false
            snapshotPending = false
            linkGeneration++
            resetRetryWindow()
            if (wasUp) onLinkDown?.invoke()
            log("bridge stop")
        }
        status(BridgeLinkStatus.DISABLED)
    }

    /** 桥链路是否在维护（调试页/日志观测面）。 */
    val isActive: Boolean
        get() = enabled

    private fun pollLoop() {
        while (true) {
            // 退出判定与 start 的起线程判定同锁：stop 后立刻 start 的竞态下，
            // 要么本线程看到 enabled=true 继续跑（不另起），要么先置 running=false
            // （start 随后看到 false 再起）——两条路都恰有一条轮询线程。
            synchronized(this) {
                if (!enabled) {
                    running = false
                    return
                }
            }
            val url = baseUrl ?: continue
            val result = pollOnce(url)
            // 满页（issue #309）：说明桥侧还有积压——不睡，隔一小拍立刻续取，直到追平再进长轮询。
            // 首连时手机游标从 0 起，这条路径就是把「一次拉整环」摊成一小页一小页地追。
            if (result.caughtUp) {
                policy.reset()
                sleep(PAGE_CATCH_UP_DELAY_MS)
                continue
            }
            if (result.ok) {
                policy.reset()
                synchronized(this) {
                    // 恢复即翻案（US9）：失败窗与降级标记随成功归零，下次失败重新起窗。
                    resetRetryWindow()
                }
                continue
            }
            // 失联收口：先报失联（core 零打扰回落），再按退避排下一轮；本链路未对账过
            // 的快照账随链路一起作废（重连后重新对账）。
            val wasUp: Boolean
            val generation: Long
            synchronized(this) {
                snapshotFetched = false
                snapshotPending = false
                voiceCursor = null
                linkGeneration++
                generation = linkGeneration
                // 超时判停（spec 0019 / 票 #187）：自本链路首败起累计失败窗，跨窗即「显示判停」
                // ——对外报 DISCONNECTED（同值去重保证只报一次边沿），底层仍按退避继续重连
                // （网络抖动误判后，恢复自动翻回 CONNECTED，US9）。
                val since = retrySinceMs ?: System.currentTimeMillis().also { retrySinceMs = it }
                if (!retryDowngraded && System.currentTimeMillis() - since >= retryGiveUpMs) {
                    retryDowngraded = true
                    log("bridge retry give-up after ${System.currentTimeMillis() - since}ms（显示降级，底层继续重连）")
                }
                wasUp = linkUpNotified
                linkUpNotified = false
            }
            if (wasUp) onLinkDown?.invoke()
            // 报状态与 [stop]/[start] 在同一把锁里定序（[status] 内部同锁去重）：世代未变才报
            // ——要么本线程先报、stop 的 DISABLED 随后收尾；要么 stop/start 已换代（enabled
            // 翻false 或换了新链路）本线程不再报，杜绝「停用后又闪一次重连中」「新链路上报旧降级」
            //（spec 0019 / 票 #187 临终即时降级不回摆）。
            val proceed = synchronized(this) {
                if (!enabled || linkGeneration != generation) {
                    false
                } else {
                    statusLog("bridge down，退避重连")
                    status(if (retryDowngraded) BridgeLinkStatus.DISCONNECTED else BridgeLinkStatus.RETRYING)
                    true
                }
            }
            if (!proceed) continue
            sleep(policy.nextDelayMs())
        }
    }

    /**
     * 一次长轮询：`ok` = 成功（含空页/满页），`caughtUp` = 本页没取满（桥已无积压）。
     * URL 解析失败与请求/解码失败都归 ok=false（走退避重连，不杀线程）。
     */
    private fun pollOnce(base: String): PollResult {
        val generation = linkGeneration
        // URL parsing belongs to the failure path too. A malformed legacy/pushed URL must
        // produce one failed poll, not an uncaught exception on the polling thread.
        val eventsUrl = "$base/events".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("since", cursor.toString())
            // 单页条数上限（issue #309）：桥按条数与字节双封顶给页，手机满页续取、追平才长轮询。
            ?.addQueryParameter("limit", PAGE_LIMIT.toString())
            ?.build()
            ?: run {
                statusLog("bridge invalid URL")
                return PollResult(ok = false)
            }
        val request = requestBuilder(eventsUrl.toString()).get().build()
        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    statusLog("bridge http ${response.code}")
                    return PollResult(ok = false)
                }
                // 体量闸（issue #309）：单页正常 ≪1MB，超限只说明对面没分页/契约漂移——
                // 按一次失败回落重连，不让 100MB 级页面把手机堆撑爆（实测就是这样崩的）。
                if (response.peekBody(MAX_RESPONSE_BYTES + 1).bytes().size > MAX_RESPONSE_BYTES) {
                    statusLog("bridge 页面过大（>${MAX_RESPONSE_BYTES / 1_000_000}MB）")
                    return PollResult(ok = false)
                }
                response.body?.string() ?: return PollResult(ok = false)
            }
        } catch (e: Exception) {
            statusLog("bridge 请求失败 ${e.javaClass.simpleName}")
            return PollResult(ok = false)
        }
        val events = BridgeEventCodec.parsePage(body) ?: run {
            statusLog("bridge 页面解析失败（版本漂移？）")
            return PollResult(ok = false)
        }
        val memberships = BridgeEventCodec.parseMembershipPage(body) ?: run {
            statusLog("bridge membership 页面解析失败（版本漂移？）")
            return PollResult(ok = false)
        }
        synchronized(this) {
            if (!enabled || baseUrl != base || generation != linkGeneration) return PollResult(ok = true)
        }
        // 先报上线再发事实：接线层依赖「连接在线」语义（AgentSessionUpdated 也会自证连接）。
        val notifyUp = synchronized(this) { !linkUpNotified.also { linkUpNotified = true } }
        if (notifyUp) onLinkUp?.invoke()
        status(BridgeLinkStatus.CONNECTED)
        // 同页先落生命周期，再落活动：ARCHIVED/ABSENT 墓碑会拦下同页或尾随去抖的迟到状态。
        memberships.forEach { fact -> onMembership?.invoke(fact) }
        events.forEach { event ->
            BridgeEventCodec.toSessionState(event)?.let { state ->
                val boundary = voiceCursor
                onSession?.invoke(if (boundary != null && event.id > boundary) state else state.copy(voiceEligible = false))
            }
            if (event.actionExpired) {
                onActionExpired?.invoke(BridgeEventCodec.sessionId(event.source, event.sessionId))
            }
        }
        BridgeEventCodec.parseCursor(body)?.let { cursor = it }
        // 每条链路对账一次在册快照（spec 0016 / 票 #155）：事件照常先发，快照经静置窗随后到
        // ——快照是桥侧现状的全量，接线层以它为准替换桥在册集。失败不阻塞链路，下一轮重试。
        if (!snapshotFetched && !snapshotPending) scheduleSnapshot(base)
        // 满页即「还有积压」（issue #309）：本条链路仍在追平中，不拿满页当追平。
        return PollResult(ok = true, caughtUp = events.size < PAGE_LIMIT)
    }

    /**
     * 排一次在册快照取件（spec 0016 / 票 #155）：**先静置再取**——桥的会话文件补读带
     * ~400ms 尾随去抖（tools/bridge/adapters/tail-util.mjs），链路上线即取会把桥还没来得及
     * 回放的会话读成「不在册」，接着就误清桥来源的锁（评审修复：本窗口虽窄，但清锁会写盘）。
     * 独立线程睡，不占轮询线程（长轮询期间的事件投递不受影响）；每条链路只排一次。
     */
    private fun scheduleSnapshot(base: String) {
        snapshotPending = true
        val scheduled = linkGeneration
        Thread {
            sleep(snapshotSettleMs)
            val stale = synchronized(this) {
                val giveUp = !enabled || snapshotFetched || baseUrl != base || scheduled != linkGeneration
                if (giveUp) snapshotPending = false
                giveUp
            }
            if (stale) return@Thread
            fetchSnapshot(base, scheduled)
            synchronized(this) { if (scheduled == linkGeneration) snapshotPending = false }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 取一次在册快照并交出（spec 0016 / 票 #155）：成功 → [onSnapshot] + 本条链路记账为已对账；
     * 失败（HTTP/解析）→ 保持未对账，本次不触发回调，后续轮询继续重试。桥在册集与桥来源的锁
     * 在未对账期间按上次已知值保守维持，不因「没听到」被清（清锁只在快照确认缺席时发生）。
     */
    private fun fetchSnapshot(base: String, generation: Long) {
        val request = requestBuilder("$base/snapshot").get().build()
        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    statusLog("bridge snapshot http ${response.code}")
                    return
                }
                response.body?.string() ?: return
            }
        } catch (e: Exception) {
            statusLog("bridge snapshot 请求失败 ${e.javaClass.simpleName}")
            return
        }
        val sessions = BridgeEventCodec.parseSnapshot(body) ?: run {
            statusLog("bridge snapshot 解析失败（版本漂移？）")
            return
        }
        val memberships = BridgeEventCodec.parseMembershipSnapshot(body) ?: run {
            statusLog("bridge membership snapshot 解析失败（版本漂移？）")
            return
        }
        synchronized(this) {
            if (!enabled || baseUrl != base || generation != linkGeneration) return
            snapshotFetched = true
            voiceCursor = BridgeEventCodec.parseCursor(body)
        }
        log("bridge snapshot in-roster=${sessions.size}")
        // 来源能力表（票 #172）：随快照刷新，供批准入口判定（票 #174）读取；旧桥没发＝内置默认。
        val capabilities = BridgeEventCodec.parseCapabilities(body)
        synchronized(this) { lastCapabilities = capabilities }
        log("bridge capabilities ${capabilities.bySource.keys.sorted()}")
        // 快照是同一来源代数下的原子事实：先整批落 membership，再替换 sessions。
        if (memberships.isNotEmpty()) onMembershipSnapshot?.invoke(memberships)
        onSnapshot?.invoke(sessions)
    }

    /** 当前已知的来源能力表（票 #172）：快照到达前是内置默认表。 */
    fun capabilities(): SourceCapabilities = lastCapabilities

    /**
     * 完整历史（`GET /history?sessionId=`，spec 0018-7 / 票 #177）：回看当前会话从头到尾的货源。
     * 只对桥来源键有意义（[AgentTurns.shouldFetchFullHistory]）；回调在本方法起的线程触发，
     * 调用方自行切线程（与其余回调同规矩）。`null`＝取失败（调用方保现状不抹内容、下次切回再试）；
     * 空列表＝桥没有更多（会话刚下册等），是**合法答复**。
     */
    fun fetchHistory(sessionId: String, onResult: (List<AgentTurn>?) -> Unit) {
        val base = baseUrl
        if (!enabled || base == null) {
            onResult(null)
            return
        }
        val raw = com.rearcue.poc.agent.AgentSessionKeys.bridgeSourceSessionId(sessionId) ?: sessionId.removePrefix(BridgeEventCodec.SESSION_PREFIX)
        Thread {
            val turns = try {
                val url = "$base/history".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("sessionId", raw)?.build()
                    ?: return@Thread onResult(null)
                val client = http.newBuilder().callTimeout(HISTORY_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
                client.newCall(requestBuilder(url.toString()).get().build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        statusLog("bridge history http ${response.code}")
                        null
                    } else {
                        response.body?.string()?.let { BridgeEventCodec.parseHistory(it) }
                    }
                }
            } catch (e: Exception) {
                statusLog("bridge history 失败 ${e.javaClass.simpleName}")
                null
            }
            log("bridge history session=$sessionId turns=${turns?.size ?: -1}")
            onResult(turns)
        }.apply { isDaemon = true }.start()
    }

    /**
     * 会话动作（Remote Approval）：`POST /action` 承载批准类应答；Remote Codex Conversation
     * 的发起/追问/停止/删除走各自 `/codex/...` 写面。所有写面凭 [BridgeEndpoint] 凭据保护。
     * 本动作恒给明确回执（[ActionReceipt]，
     * 含本地判定的 [ActionReceipt.TIMEDOUT]）：**不悬挂、不自动重试**（AC3，失败提示一次即可）。
     * 回执回调在本方法起的线程触发，调用方自行切线程（与其余回调同规矩）。
     */
    fun sendAction(request: SessionActionRequest, onReceipt: (ActionReceipt) -> Unit) {
        val base = baseUrl
        if (!enabled || base == null) {
            // 无桥可投＝失败的一种：回执照给（不悬挂），界面提示后不重试轰炸。
            onReceipt(ActionReceipt.TIMEDOUT)
            return
        }
        Thread {
            val receipt = try {
                val payload = request.toJson().toRequestBody("application/json".toMediaType())
                // 动作是短请求：另配短超时（长轮询的 35s 读超时不适合这里）。
                val client = http.newBuilder().callTimeout(REMOTE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
                client.newCall(requestBuilder("$base/action").post(payload).build()).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    if (!response.isSuccessful) ActionReceipt.BAD_REQUEST else BridgeEventCodec.parseActionReceipt(text)
                }
            } catch (e: Exception) {
                statusLog("bridge action 失败 ${e.javaClass.simpleName}")
                ActionReceipt.TIMEDOUT
            }
            log("bridge action receipt=${receipt.name.lowercase()} session=${request.sessionId} requestId=${request.requestId}")
            onReceipt(receipt)
        }.apply { isDaemon = true }.start()
    }

    /**
     * 会话已阅回执（ADR 0018 / issue #296）：正文实际打开时把 READ 共享回桥。
     * 未知会话/断线只记日志，不重试轰炸；下一次打开或快照对账仍可补正。
     */
    fun markSessionRead(sessionId: String) {
        val base = baseUrl
        if (!enabled || base == null || sessionId.isBlank()) return
        val raw = AgentSessionKeys.bridgeSourceSessionId(sessionId)
            ?: sessionId.removePrefix(BridgeEventCodec.SESSION_PREFIX)
        val source = AgentSessionKeys.bridgeSource(sessionId)
        val payload = buildString {
            append("{")
            append("\"sessionId\":").append(CodexRemoteCodec.quote(raw))
            if (source != null) {
                append(",\"source\":").append(CodexRemoteCodec.quote(source))
            }
            append(",\"readState\":\"read\"}")
        }
        Thread {
            val ok = try {
                val body = payload.toRequestBody("application/json".toMediaType())
                val client = http.newBuilder().callTimeout(REMOTE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
                client.newCall(requestBuilder("$base/read").post(body).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        statusLog("bridge read http ${response.code}")
                        false
                    } else {
                        true
                    }
                }
            } catch (e: Exception) {
                statusLog("bridge read 失败 ${e.javaClass.simpleName}")
                false
            }
            log("bridge read session=$sessionId ok=$ok")
        }.apply { isDaemon = true }.start()
    }
    /** spec 0024：读取当前 provider 的项目/模型，手机只展示真正可用项。 */
    fun fetchCodexOptions(onResult: (CodexRemoteOptions?) -> Unit) {
        val base = baseUrl
        if (!enabled || base == null) {
            onResult(null)
            return
        }
        Thread {
            val options = try {
                val client = http.newBuilder().callTimeout(REMOTE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
                client.newCall(requestBuilder("$base/codex/options").get().build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()?.let(CodexRemoteCodec::parseOptions)
                }
            } catch (e: Exception) {
                statusLog("bridge codex options 失败 ${e.javaClass.simpleName}")
                null
            }
            onResult(options)
        }.apply { isDaemon = true }.start()
    }

    /** 新建 Codex 会话；结果显式区分 accepted / rejected / unknown / failed。 */
    fun startCodexConversation(request: CodexRemoteRequest, onResult: (CodexRemoteResult) -> Unit) {
        postCodexRemote("/codex/conversations", request.toCreateJson(), onResult)
    }

    /** 对任意 Codex 会话继续追问（不区分最初由手机还是电脑创建）。 */
    fun sendCodexMessage(sessionId: String, request: CodexRemoteRequest, onResult: (CodexRemoteResult) -> Unit) {
        val rawId = rawCodexSessionId(sessionId)
        postCodexRemote("/codex/conversations/$rawId/messages", request.toMessageJson(), onResult)
    }

    fun stopCodexConversation(sessionId: String, requestId: String, onResult: (CodexRemoteResult) -> Unit) {
        val rawId = rawCodexSessionId(sessionId)
        postCodexRemote("/codex/conversations/$rawId/stop", CodexRemoteCodec.quote(requestId).let { "{\"requestId\":$it}" }, onResult)
    }

    fun deleteCodexConversation(sessionId: String, requestId: String, onResult: (CodexRemoteResult) -> Unit) {
        val rawId = rawCodexSessionId(sessionId)
        postCodexRemote("/codex/conversations/$rawId/delete", CodexRemoteCodec.quote(requestId).let { "{\"requestId\":$it}" }, onResult)
    }

    private fun rawCodexSessionId(sessionId: String): String =
        if (AgentSessionKeys.isBridge(sessionId)) {
            sessionId.removePrefix(BridgeEventCodec.SESSION_PREFIX).removePrefix("codex:")
        } else {
            sessionId
        }

    private fun postCodexRemote(path: String, json: String, onResult: (CodexRemoteResult) -> Unit) {
        val base = baseUrl
        if (!enabled || base == null) {
            onResult(CodexRemoteResult.Unknown("电脑未连接"))
            return
        }
        Thread {
            val result = try {
                val payload = json.toRequestBody("application/json".toMediaType())
                val client = http.newBuilder()
                    .callTimeout(CODEX_REMOTE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false)
                    .build()
                client.newCall(requestBuilder("$base$path").post(payload).build()).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    CodexRemoteCodec.parseResult(text, response.isSuccessful)
                }
            } catch (e: Exception) {
                statusLog("bridge codex remote 失败 ${e.javaClass.simpleName}")
                CodexRemoteResult.Unknown(e.javaClass.simpleName)
            }
            onResult(result)
        }.apply { isDaemon = true }.start()
    }

    private fun requestBuilder(url: String): Request.Builder =
        BridgeEndpoint(baseUrl = baseUrl.orEmpty(), accessToken = accessToken)
            .authorize(Request.Builder().url(url))

    private fun statusLog(message: String) {
        log("$message cursor=$cursor")
    }

    /** 链路状态唯一出口：同值不重报，主屏与背屏消费同一份事实。 */
    private fun status(next: BridgeLinkStatus) {
        val changed = synchronized(this) {
            if (reportedStatus == next) {
                false
            } else {
                reportedStatus = next
                true
            }
        }
        if (changed) {
            log("bridge status ${next.name.lowercase()}")
            onStatusChanged?.invoke(next)
        }
    }

    /** 单页结果（issue #309）：成功与否 ＋ 是否已追平（本页没取满＝桥已无积压）。 */
    private data class PollResult(val ok: Boolean, val caughtUp: Boolean = false)

    private companion object {
        /** 单页条数上限（issue #309）：与桥 `BRIDGE_EVENTS_PAGE` 同口径；满页即续取。 */
        const val PAGE_LIMIT = 300

        /** 满页续取之间的一小拍（ms）：不让 catch-up 循环把桥与手机都打满。 */
        const val PAGE_CATCH_UP_DELAY_MS = 250L

        /**
         * 单页响应体硬上限（issue #309）：正常一页 ≪1MB；超过只可能是对面没分页——
         * 宁可当一次失败回落重连，也不让手机堆被单页撑爆（实测崩在这里）。
         */
        const val MAX_RESPONSE_BYTES = 12_000_000L

        /** 静置窗缺省值：桥侧补读去抖 ~400ms + 读盘余量（实机可观测，见 `bridge snapshot` 锚）。 */
        const val SNAPSHOT_SETTLE_MS_DEFAULT = 1_500L

        /** 判停窗缺省（spec 0019「约 2–3 分钟」取 150s）：跨窗后显示降级，底层按退避（封顶 60s）继续重连。 */
        const val RETRY_GIVE_UP_MS_DEFAULT = 150_000L

        /** 会话动作的总超时（spec 0018-4）：超时即 [ActionReceipt.TIMEDOUT] 回执，不悬挂。 */
        const val REMOTE_CALL_TIMEOUT_MS = 10_000L

        /** 发起/追问是长请求；超时必须回 unknown，调用方不得自动重试。 */
        const val CODEX_REMOTE_TIMEOUT_MS = 35_000L

        /** 完整历史取回的总超时（spec 0018-7）：与会话动作各自的短请求超时，不共用一个常量。 */
        const val HISTORY_CALL_TIMEOUT_MS = 10_000L
    }
}
