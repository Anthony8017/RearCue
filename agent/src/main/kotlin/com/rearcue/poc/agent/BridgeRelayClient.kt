package com.rearcue.poc.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * PC 桥链路状态（票 #165）：**一份事实、三处显示**（主屏设置页 / 主页概览 / 背屏状态点）。
 * - [DISABLED]：未配置 URL 或 Agent 镜像总开关关——链路不维护；
 * - [CONNECTING]：已起链路、首连尚未成功；
 * - [CONNECTED]：长轮询在线（事件在流）；
 * - [RETRYING]：请求失败，按指数退避重连中。
 */
enum class BridgeLinkStatus { DISABLED, CONNECTING, CONNECTED, RETRYING }

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
 * 线程模型：独立 daemon 轮询线程，全部状态收口在 synchronized 面；回调在轮询线程触发，
 * 调用方自行切线程。HTTP 只读 GET，无凭据（隧道 URL 即地址面；无鉴权的敞口与后续加固
 * 记 tools/bridge/README）。
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
) {
    /** 最近一次对外的链路状态（只在变化时回调，票 #165）。 */
    private var reportedStatus = BridgeLinkStatus.DISABLED

    private val policy = ReconnectPolicy()

    @Volatile
    private var enabled = false

    @Volatile
    private var baseUrl: String? = null

    @Volatile
    private var running = false

    private var cursor = 0L
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

    @Volatile
    var onLinkUp: (() -> Unit)? = null

    @Volatile
    var onLinkDown: (() -> Unit)? = null

    /**
     * 链路状态变化（票 #165）：主屏设置页 / 主页概览 / 背屏状态点共用这一份事实——
     * [BridgeLinkStatus.CONNECTING] 首连中、[BridgeLinkStatus.CONNECTED] 已连上（事件在流）、
     * [BridgeLinkStatus.RETRYING] 请求失败、按退避重连中、[BridgeLinkStatus.DISABLED] 未配置/停用。
     * 只在**变化**时回调（同值不刷屏）；线程同 [onLinkUp]（轮询线程/调用线程），调用方自行切线程。
     */
    @Volatile
    var onStatusChanged: ((BridgeLinkStatus) -> Unit)? = null

    @Volatile
    var onSession: ((AgentSessionState) -> Unit)? = null

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
        synchronized(this) {
            if (enabled && baseUrl == url && running) return
            enabled = true
            baseUrl = url.trimEnd('/')
            cursor = 0L
            linkUpNotified = false
            snapshotFetched = false
            snapshotPending = false
            linkGeneration++
            policy.reset()
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
            val ok = pollOnce(url)
            if (!ok) {
                // 失联收口：先报失联（core 零打扰回落），再按退避排下一轮；本链路未对账过
                // 的快照账随链路一起作废（重连后重新对账）。
                val wasUp = synchronized(this) {
                    snapshotFetched = false
                    snapshotPending = false
                    linkGeneration++
                    linkUpNotified.also { linkUpNotified = false }
                }
                if (wasUp) onLinkDown?.invoke()
                if (!enabled) continue
                statusLog("bridge down，退避重连")
                status(BridgeLinkStatus.RETRYING)
                sleep(policy.nextDelayMs())
            } else {
                policy.reset()
            }
        }
    }

    /** 一次长轮询：true = 成功（含空页）；false = 请求/解码失败。 */
    private fun pollOnce(base: String): Boolean {
        val request = Request.Builder().url("$base/events?since=$cursor").get().build()
        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    statusLog("bridge http ${response.code}")
                    return false
                }
                response.body?.string() ?: return false
            }
        } catch (e: Exception) {
            statusLog("bridge 请求失败 ${e.javaClass.simpleName}")
            return false
        }
        val events = BridgeEventCodec.parsePage(body) ?: run {
            statusLog("bridge 页面解析失败（版本漂移？）")
            return false
        }
        // 先报上线再发事实：接线层依赖「连接在线」语义（AgentSessionUpdated 也会自证连接）。
        val notifyUp = synchronized(this) { !linkUpNotified.also { linkUpNotified = true } }
        if (notifyUp) onLinkUp?.invoke()
        status(BridgeLinkStatus.CONNECTED)
        events.forEach { event ->
            BridgeEventCodec.toSessionState(event)?.let { state -> onSession?.invoke(state) }
        }
        BridgeEventCodec.parseCursor(body)?.let { cursor = it }
        // 每条链路对账一次在册快照（spec 0016 / 票 #155）：事件照常先发，快照经静置窗随后到
        // ——快照是桥侧现状的全量，接线层以它为准替换桥在册集。失败不阻塞链路，下一轮重试。
        if (!snapshotFetched && !snapshotPending) scheduleSnapshot(base)
        return true
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
            fetchSnapshot(base)
            snapshotPending = false
        }.apply { isDaemon = true }.start()
    }

    /**
     * 取一次在册快照并交出（spec 0016 / 票 #155）：成功 → [onSnapshot] + 本条链路记账为已对账；
     * 失败（HTTP/解析）→ 保持未对账，本次不触发回调，后续轮询继续重试。桥在册集与桥来源的锁
     * 在未对账期间按上次已知值保守维持，不因「没听到」被清（清锁只在快照确认缺席时发生）。
     */
    private fun fetchSnapshot(base: String) {
        val request = Request.Builder().url("$base/snapshot").get().build()
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
        synchronized(this) { snapshotFetched = true }
        log("bridge snapshot in-roster=${sessions.size}")
        // 来源能力表（票 #172）：随快照刷新，供批准入口判定（票 #174）读取；旧桥没发＝内置默认。
        val capabilities = BridgeEventCodec.parseCapabilities(body)
        synchronized(this) { lastCapabilities = capabilities }
        log("bridge capabilities ${capabilities.bySource.keys.sorted()}")
        onSnapshot?.invoke(sessions)
    }

    /** 当前已知的来源能力表（票 #172）：快照到达前是内置默认表。 */
    fun capabilities(): SourceCapabilities = lastCapabilities

    private fun statusLog(message: String) {
        log("$message cursor=$cursor")
    }

    /** 链路状态出口（票 #165）：同值不重报——UI 三处显示的是一份事实。 */
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

    private companion object {
        /** 静置窗缺省值：桥侧补读去抖 ~400ms + 读盘余量（实机可观测，见 `bridge snapshot` 锚）。 */
        const val SNAPSHOT_SETTLE_MS_DEFAULT = 1_500L
    }
}
