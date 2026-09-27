package com.rearcue.poc.agent

import kotlinx.serialization.json.JsonObject

/**
 * 一次「连接 → terminal 认证 → 逻辑消息流」的会话外壳：T3 拿它做配对状态、T4 在
 * [RelayEvent.ChannelMessage] 上叠 V4 通道握手与订阅。本类只编排协议件，不做重连决策
 * （退避归 [ReconnectPolicy]，调用方接）。
 */
class RelaySession(
    private val transport: RelayTransport,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Phase { IDLE, CONNECTING, AUTHENTICATING, ONLINE, CLOSED }

    private var codec = RpcFrameCodec(clockMs)
    private var bridgeSessionId: String? = null
    private var bridgeGeneration: Int? = null
    private var authenticator: RelayAuthenticator? = null
    private var eventSink: ((RelayEvent) -> Unit)? = null

    var phase: Phase = Phase.IDLE
        private set

    var lastAuthOutcome: AuthOutcome? = null
        private set

    /**
     * 桥会话身份（票 #88：来自 workspace-bridge-ready 响应，**不是** device_sid——
     * 桌面 assembler 按 {bridgeSessionId, bridgeGeneration} 判身份；开桥前二进制出站无效）。
     */
    fun setBridge(id: String, generation: Int?) {
        if (bridgeSessionId != null && bridgeSessionId != id) {
            // 换桥（桌面会话被 supersede）：对端 assembler 是全新实例、期望序号从 1 起——
            // 必须换新 codec（序号与装配表一并归零），否则必踩 sequence/identity fault。
            codec = RpcFrameCodec(clockMs)
        }
        bridgeSessionId = id
        bridgeGeneration = generation
    }

    /** 桥是否已开（V4 通道出站的前置条件）。 */
    fun hasBridge(): Boolean = bridgeSessionId != null

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
                if ((RelayEnvelope.primitiveOrNull(payload, "zcode_type") ?: "rpc-frame") != "rpc-frame") {
                    // 控制面帧（bootstrap/workspace-list/workspace-bridge…，schema kzi）：
                    // 不过帧编解码，直接上抛（票 #86 phase B 实测：桌面据此应答）。
                    onEvent(RelayEvent.ControlMessage(payload.toString()))
                    return
                }
                val assembled = runCatching { codec.onPhysicalFrame(payload) }.getOrNull() ?: return
                assembled.ackText?.let { ack -> transport.send(ack) }
                assembled.logicalBytes?.let { bytes -> onEvent(RelayEvent.ChannelMessage(bytes)) }
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

    /**
     * 出站一条 V4 通道消息（原始字节，rpc-frame 切片 + data envelope；票 #88）。
     * 桥未开时静默丢弃——桥未 ready 前桌面会拒收（readyAnnounced 门控）。
     */
    fun sendChannelMessage(messageBytes: ByteArray) {
        val bridge = bridgeSessionId ?: return
        for (frame in codec.encodeMessage(messageBytes, bridge, bridgeGeneration, clockMs())) {
            transport.send(frame)
        }
    }

    /**
     * 出站一个控制面 payload（schema kzi：bootstrap-request / workspace-list-request 等，
     * 直接作为 data envelope 的 payload，不套 rpc-frame）。[payloadText] 是 payload 的 JSON 文本。
     */
    fun sendDataPayload(payloadText: String) {
        val payload = runCatching { RelayEnvelope.json.parseToJsonElement(payloadText) }.getOrNull() ?: return
        if (payload is JsonObject) {
            transport.send(RelayEnvelope.json.encodeToString(JsonObject.serializer(), RelayEnvelope.wrapData(payload, clockMs())))
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
