package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.OkHttpRelayTransport
import com.rearcue.poc.agent.PairingLink
import com.rearcue.poc.agent.RelayCloseCodes
import com.rearcue.poc.agent.RelayConstants
import com.rearcue.poc.agent.RelayCredentials
import com.rearcue.poc.agent.RelayEvent
import com.rearcue.poc.agent.RelaySession
import com.rearcue.poc.agent.RelayTransport

/**
 * Agent Mirror 中继客户端（spec 0010 / 票 #81）：持有一次配对（[PairingLink] → credentials），
 * 维护「连接 → terminal 认证 → 逻辑消息流」的会话生命周期；断线按 [com.rearcue.poc.agent.ReconnectPolicy]
 * 指数退避自动重连，不可重试错误（AUTH_FAILED / 4011 / 4013 等）停机等人工。
 *
 * 事实出口（回调在 OkHttp 线程触发，调用方自行切线程）：
 * - [onLinkUp]/[onLinkDown] → DashboardCore 的 AgentConnectionChanged（spec 0010 仲裁输入）；
 * - [onChannelMessage] → V4 通道消息（二进制，票 #88）→ V4Bridge 握手/帧归一；
 * - [onControlMessage] → 控制面帧（bootstrap/workspace-list/bridge-*）；
 * - [onStatusChanged] → 主屏 Agent 区状态行。
 *
 * 线程模型：全部状态收口在 [start]/[stop]/[connectOnce] 的 synchronized 面；重连用独立
 * daemon 线程 sleep（:agent 无协程依赖，退避等待不值得为此引入）。
 *
 * 连接生命周期：**一条连接一个 [RelaySession] + 一个 [RelayTransport]**——重连前先 [disposeSession]
 * 收掉旧 socket（认证超时/error 帧路径下它仍开着），被撤会话的后续事件由 stale 闸丢弃；
 * 断开原因与重连决策全走 [log]（logcat 词形 `agent ws closed code=…` / `agent reconnect in …ms`）。
 */
class AgentRelayClient(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** 每条连接一个传输（测试注入假传输；生产共享一个 OkHttpClient）。 */
    private val transportFactory: () -> RelayTransport = { OkHttpRelayTransport() },
    /** 关键节点日志（断开码/认证结果/重连决策，进 logcat——验收锚）。 */
    private val log: (String) -> Unit = {},
    /** 认证等待窗（测试注入短窗；生产 45s）。 */
    private val authTimeoutMs: Long = RelaySession.DEFAULT_AUTH_TIMEOUT_MS,
) {

    private val policy = com.rearcue.poc.agent.ReconnectPolicy()

    /** 当前会话；事件回调线程只读，写入收口在 [connectOnce]/[disposeSession]/[stop] 的 synchronized 面。 */
    @Volatile
    private var session: RelaySession? = null

    private var creds: RelayCredentials? = null

    @Volatile
    private var enabled = false

    @Volatile
    private var reconnectPending = false

    /**
     * 链路世代：[start]/[stop] 递增。退避等待中的重连线程凭世代判自己是否已被作废——
     * 否则 start() 把 [reconnectPending] 清零后，睡着的旧线程仍会再连一条（双连接互踢，
     * #88 实机教训的另一半）。
     */
    @Volatile
    private var epoch = 0

    @Volatile
    var onLinkUp: (() -> Unit)? = null

    @Volatile
    var onLinkDown: (() -> Unit)? = null

    @Volatile
    var onChannelMessage: ((ByteArray) -> Unit)? = null

    @Volatile
    var onControlMessage: ((String) -> Unit)? = null

    @Volatile
    var onStatusChanged: ((AgentLinkStatus) -> Unit)? = null

    /**
     * 开始维护链路（配对成功或开关打开）：立即发起首连。
     * **幂等**：同凭据且已有会话在跑（在线或重连中）时直接忽略——重复 start 会另起一条
     * 连接与旧会话互踢（后到踢先到），链路抖动成风暴（#88 实机教训）。
     */
    fun start(link: PairingLink) {
        synchronized(this) {
            val next = RelayCredentials(link.deviceSid, link.passHash, link.deviceMid)
            if (enabled && next == creds && session != null) return
            enabled = true
            creds = next
            epoch++
            policy.reset()
            reconnectPending = false
            status(AgentLinkStatus.CONNECTING)
            connectOnce()
        }
    }

    /** 停止维护（解除配对或总开关关闭）：断开、不再重连。 */
    fun stop() {
        synchronized(this) {
            enabled = false
            epoch++
            reconnectPending = false
            creds = null
            disposeSession()
            status(AgentLinkStatus.DISABLED)
            onLinkDown?.invoke()
        }
    }

    /** 出站一条 V4 通道消息（二进制；桥未开/离线静默丢弃——握手序列随每次上线重建）。 */
    fun sendChannelMessage(bytes: ByteArray) {
        synchronized(this) {
            if (enabled) session?.sendChannelMessage(bytes)
        }
    }

    /** 写入桥身份（workspace-bridge-ready 后、任何通道出站之前；票 #88）。 */
    fun setBridge(bridgeSessionId: String, generation: Int?) {
        synchronized(this) {
            session?.setBridge(bridgeSessionId, generation)
        }
    }

    /** 出站一个控制面 payload（bootstrap/workspace-list 等，data envelope 直载；调试探针与后续接线共用）。 */
    fun sendControlPayload(payloadText: String) {
        synchronized(this) {
            if (enabled) session?.sendDataPayload(payloadText)
        }
    }

    private fun connectOnce() {
        val c = creds ?: return
        // 重连前必须收掉上一条：认证超时/服务端 error 路径下旧 socket 仍是开的，
        // 留着会与新连接并存 → 中继「后到踢先到」把它的断连事件打给当前会话 → 永久互踢
        // （#106 验收末段的持续 RECONNECTING；判例 AgentRelayClientReconnectTest）。
        disposeSession()
        val s = RelaySession(transportFactory())
        session = s
        val headers = buildMap {
            c.deviceMid?.let { put("X-Device-ID", it) }
        }
        s.start(RelayConstants.DEFAULT_ENDPOINT, headers, c, authTimeoutMs) { event ->
            if (session !== s) {
                log("agent stale event ignored: ${event.toString().take(120)}")
                return@start
            }
            when (event) {
                is RelayEvent.Online -> {
                    policy.reset()
                    log("agent link online (auth=${s.lastAuthOutcome})")
                    status(AgentLinkStatus.CONNECTED)
                    onLinkUp?.invoke()
                }

                is RelayEvent.ChannelMessage -> onChannelMessage?.invoke(event.bytes)

                is RelayEvent.ControlMessage -> onControlMessage?.invoke(event.text)

                is RelayEvent.ServerError -> {
                    log("agent server error code=${event.error.code} retryable=${event.error.retryable}")
                    scheduleReconnect(event.error.retryable)
                }

                is RelayEvent.Closed -> {
                    log(
                        "agent ws closed code=${event.code} reason=${event.reason} " +
                            "retryable=${RelayCloseCodes.retryable(event.code)} auth=${s.lastAuthOutcome}",
                    )
                    scheduleReconnect(RelayCloseCodes.retryable(event.code))
                }

                is RelayEvent.Failed -> {
                    log("agent ws failed: ${event.error} auth=${s.lastAuthOutcome}")
                    scheduleReconnect(retryable = true)
                }
            }
        }
    }

    /** 断线收口：先报失联（core 回落零打扰），可重试则按退避排下一轮。 */
    private fun scheduleReconnect(retryable: Boolean) {
        // 断开即收口：error 帧路径下 socket 可能还开着，不收就是下一轮的僵尸连接。
        disposeSession()
        onLinkDown?.invoke()
        if (!enabled || !retryable) {
            // 不可重试：停机等人工（新配对/重开开关）
            if (enabled) {
                log("agent link stop: non-retryable")
                status(AgentLinkStatus.DISCONNECTED)
            }
            return
        }
        if (reconnectPending) {
            log("agent reconnect suppressed (already pending)")
            return
        }
        reconnectPending = true
        status(AgentLinkStatus.RECONNECTING)
        val delay = policy.nextDelayMs()
        val scheduledEpoch = epoch
        log("agent reconnect in ${delay}ms")
        Thread {
            sleep(delay)
            synchronized(this) {
                if (scheduledEpoch != epoch) {
                    log("agent reconnect cancelled (link restarted/stopped meanwhile)")
                    return@synchronized
                }
                reconnectPending = false
                if (enabled) connectOnce()
            }
        }.apply { isDaemon = true }.start()
    }

    /** 撤下当前会话并关其连接；被撤会话后续事件由 [connectOnce] 的 stale 闸丢弃。 */
    private fun disposeSession() {
        synchronized(this) {
            val old = session
            session = null
            old?.disconnect()
        }
    }

    private fun status(s: AgentLinkStatus) {
        onStatusChanged?.invoke(s)
    }
}
