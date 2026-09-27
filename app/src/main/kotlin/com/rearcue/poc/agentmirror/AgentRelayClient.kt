package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.OkHttpRelayTransport
import com.rearcue.poc.agent.PairingLink
import com.rearcue.poc.agent.RelayCloseCodes
import com.rearcue.poc.agent.RelayConstants
import com.rearcue.poc.agent.RelayCredentials
import com.rearcue.poc.agent.RelayEvent
import com.rearcue.poc.agent.RelaySession

/**
 * Agent Mirror 中继客户端（spec 0010 / 票 #81）：持有一次配对（[PairingLink] → credentials），
 * 维护「连接 → terminal 认证 → 逻辑消息流」的会话生命周期；断线按 [com.rearcue.poc.agent.ReconnectPolicy]
 * 指数退避自动重连，不可重试错误（AUTH_FAILED / 4011 / 4013 等）停机等人工。
 *
 * 事实出口（回调在 OkHttp 线程触发，调用方自行切线程）：
 * - [onLinkUp]/[onLinkDown] → DashboardCore 的 AgentConnectionChanged（spec 0010 仲裁输入）；
 * - [onLogicalMessage] → T4 的订阅归一化（会话状态）；
 * - [onStatusChanged] → 主屏 Agent 区状态行。
 *
 * 线程模型：全部状态收口在 [start]/[stop]/[connectOnce] 的 synchronized 面；重连用独立
 * daemon 线程 sleep（:agent 无协程依赖，退避等待不值得为此引入）。
 */
class AgentRelayClient(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    private val transport = OkHttpRelayTransport()
    private val policy = com.rearcue.poc.agent.ReconnectPolicy()
    private var session: RelaySession? = null
    private var creds: RelayCredentials? = null

    @Volatile
    private var enabled = false

    @Volatile
    private var reconnectPending = false

    @Volatile
    var onLinkUp: (() -> Unit)? = null

    @Volatile
    var onLinkDown: (() -> Unit)? = null

    @Volatile
    var onLogicalMessage: ((String) -> Unit)? = null

    @Volatile
    var onControlMessage: ((String) -> Unit)? = null

    @Volatile
    var onStatusChanged: ((AgentLinkStatus) -> Unit)? = null

    /** 开始维护链路（配对成功或开关打开）：立即发起首连。 */
    fun start(link: PairingLink) {
        synchronized(this) {
            enabled = true
            creds = RelayCredentials(link.deviceSid, link.passHash, link.deviceMid)
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
            reconnectPending = false
            creds = null
            session?.disconnect()
            session = null
            status(AgentLinkStatus.DISABLED)
            onLinkDown?.invoke()
        }
    }

    /** 出站一条逻辑消息（在线才有意义；离线静默丢弃——订阅请求随每次上线重发）。 */
    fun sendLogicalMessage(text: String) {
        synchronized(this) {
            if (enabled) session?.sendLogicalMessage(text)
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
        val s = RelaySession(transport)
        session = s
        val headers = buildMap {
            c.deviceMid?.let { put("X-Device-ID", it) }
        }
        s.start(RelayConstants.DEFAULT_ENDPOINT, headers, c) { event ->
            when (event) {
                is RelayEvent.Online -> {
                    policy.reset()
                    status(AgentLinkStatus.CONNECTED)
                    onLinkUp?.invoke()
                }

                is RelayEvent.LogicalMessage -> onLogicalMessage?.invoke(event.text)

                is RelayEvent.ControlMessage -> onControlMessage?.invoke(event.text)

                is RelayEvent.ServerError -> scheduleReconnect(event.error.retryable)

                is RelayEvent.Closed -> scheduleReconnect(RelayCloseCodes.retryable(event.code))

                is RelayEvent.Failed -> scheduleReconnect(retryable = true)
            }
        }
    }

    /** 断线收口：先报失联（core 回落零打扰），可重试则按退避排下一轮。 */
    private fun scheduleReconnect(retryable: Boolean) {
        onLinkDown?.invoke()
        if (!enabled || !retryable) {
            if (enabled) status(AgentLinkStatus.DISCONNECTED) // 不可重试：停机等人工（新配对/重开开关）
            return
        }
        if (reconnectPending) return
        reconnectPending = true
        status(AgentLinkStatus.RECONNECTING)
        val delay = policy.nextDelayMs()
        Thread {
            sleep(delay)
            synchronized(this) {
                reconnectPending = false
                if (enabled) connectOnce()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun status(s: AgentLinkStatus) {
        onStatusChanged?.invoke(s)
    }
}
