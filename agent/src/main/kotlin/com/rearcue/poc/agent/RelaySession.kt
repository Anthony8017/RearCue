package com.rearcue.poc.agent

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject

/**
 * 一次「连接 → terminal 认证 → 逻辑消息流」的会话外壳：T3 拿它做配对状态、T4 在
 * [RelayEvent.LogicalMessage] 上叠订阅与状态归一化。本类只编排协议件，不做重连决策
 * （退避归 [ReconnectPolicy]，调用方接）。
 */
class RelaySession(
    private val transport: RelayTransport,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Phase { IDLE, CONNECTING, AUTHENTICATING, ONLINE, CLOSED }

    private val codec = RpcFrameCodec(clockMs)
    private val seq = AtomicLong(1)
    private var bridgeSessionId: String? = null
    private var authenticator: RelayAuthenticator? = null
    private var eventSink: ((RelayEvent) -> Unit)? = null

    var phase: Phase = Phase.IDLE
        private set

    var lastAuthOutcome: AuthOutcome? = null
        private set

    /** 认证后业务帧的桥接会话键（v4 实际取值语义待 phase B 回填，先用 device_sid）。 */
    fun setBridgeSessionId(id: String) {
        bridgeSessionId = id
    }

    /**
     * 发起连接并认证。[onEvent] 在传输回调线程上被调（UI 侧自行切线程）。
     * [headers] 至少带 X-Device-ID（E1：服务端未强制但官方带）。
     */
    fun start(
        endpoint: String,
        headers: Map<String, String>,
        creds: RelayCredentials,
        onEvent: (RelayEvent) -> Unit,
    ) {
        eventSink = onEvent
        setPhase(Phase.CONNECTING)
        val auth = RelayAuthenticator(transport, clockMs)
        authenticator = auth
        transport.setListener(object : RelayTransport.Listener {
            override fun onOpen() {
                setPhase(Phase.AUTHENTICATING)
                Thread {
                    val outcome = auth.authenticate(creds)
                    lastAuthOutcome = outcome
                    when (outcome) {
                        is AuthOutcome.Matched -> {
                            setBridgeSessionId(creds.deviceSid)
                            setPhase(Phase.ONLINE)
                            onEvent(RelayEvent.Online(outcome.terminalSid))
                        }
                        is AuthOutcome.Rejected -> {
                            onEvent(RelayEvent.ServerError(outcome.error))
                            setPhase(Phase.CLOSED)
                        }
                        is AuthOutcome.NoAnswer -> {
                            onEvent(RelayEvent.Failed(IllegalStateException(outcome.reason)))
                            setPhase(Phase.CLOSED)
                        }
                    }
                }.start()
            }

            override fun onText(text: String) {
                val authInFlight = authenticator
                if (phase == Phase.AUTHENTICATING && authInFlight != null) {
                    authInFlight.onText(text)
                    return
                }
                if (phase != Phase.ONLINE) return
                RelayEnvelope.errorOf(text)?.let { error ->
                    onEvent(RelayEvent.ServerError(error))
                    return
                }
                val payload = RelayEnvelope.unwrapData(text) ?: return
                val assembled = runCatching { codec.onPhysicalFrame(payload) }.getOrNull() ?: return
                assembled.ackText?.let { ack -> transport.send(ack) }
                assembled.logicalMessage?.let { logical -> onEvent(RelayEvent.LogicalMessage(logical)) }
            }

            override fun onClosed(code: Int, reason: String) {
                if (phase == Phase.AUTHENTICATING) authenticator?.onClosed(code, reason)
                setPhase(Phase.CLOSED)
                onEvent(RelayEvent.Closed(code, reason))
            }

            override fun onFailure(error: Throwable) {
                if (phase == Phase.AUTHENTICATING) authenticator?.onFailure(error)
                setPhase(Phase.CLOSED)
                onEvent(RelayEvent.Failed(error))
            }
        })
        transport.connect(endpoint, headers)
    }

    /** 发送一条业务 JSON 文本（自动 rpc-frame 切片 + data envelope）。 */
    fun sendLogicalMessage(logicalJsonText: String) {
        val bridge = bridgeSessionId ?: return
        val firstSeq = seq.getAndIncrement()
        for (frame in codec.encodeLogicalMessage(logicalJsonText, bridge, firstSeq, clockMs())) {
            transport.send(frame)
        }
    }

    fun disconnect() {
        transport.close(CLOSE_CODE_NORMAL, "client disconnect")
        setPhase(Phase.CLOSED)
    }

    private fun setPhase(next: Phase) {
        phase = next
    }

    companion object {
        const val CLOSE_CODE_NORMAL = 1000
    }
}

/** JsonObject → WS 文本（供 T4 订阅协议用）。 */
fun JsonObject.toJsonText(): String =
    RelayEnvelope.json.encodeToString(JsonObject.serializer(), this)
