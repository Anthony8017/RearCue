package com.rearcue.poc.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 配对凭据（来自 [PairingLink]，存取归 T3 的 DataStore；本模块只消费）。 */
data class RelayCredentials(
    val deviceSid: String,
    val passHash: String,
    val deviceMid: String?,
)

/** 认证握手结果。 */
sealed interface AuthOutcome {
    /** pair_status=matched，会话可用；terminalSid 是会话内终端槽位标识。 */
    data class Matched(val terminalSid: String?) : AuthOutcome

    /** 服务端明确拒绝（AUTH_FAILED / WRONG_PARAM，E1 实测拒绝语义）。 */
    data class Rejected(val error: RelayError) : AuthOutcome

    /** 超时或传输失败。 */
    data class NoAnswer(val reason: String) : AuthOutcome
}

/**
 * terminal 路径认证状态机（E1 固化：官方手机端不走 register，直接 auth_init）：
 *
 * ```
 * C: {type:"auth_init", role:"terminal", device_sid, meta:{platform:"web",version,name:"mobile-browser"}, client_ts}
 * S: {type:"auth_challenge", nonce}
 * C: {type:"auth_response", device_sid, proof, client_ts}
 * S: {type:"auth_ack"|"pair_status_ack", pair_status, terminal_sid}
 * ```
 *
 * 不自持传输监听——宿主（RelaySession）把 WS 文本喂给 [onText]，避免监听器互相覆盖。
 */
class RelayAuthenticator(
    private val transport: RelayTransport,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private class PendingAuth(val creds: RelayCredentials, val latch: CountDownLatch, var outcome: AuthOutcome? = null)

    @Volatile
    private var pending: PendingAuth? = null

    fun authenticate(creds: RelayCredentials, timeoutMs: Long = 10_000L): AuthOutcome {
        val pendingAuth = PendingAuth(creds, CountDownLatch(1))
        check(pending == null) { "authenticate already in flight" }
        pending = pendingAuth
        val init = buildJsonObject {
            put("type", "auth_init")
            put("role", RelayConstants.ROLE_TERMINAL)
            put("device_sid", creds.deviceSid)
            putJsonObject("meta") {
                put("platform", "web")
                put("version", "web")
                put("name", "mobile-browser")
            }
            put("client_ts", clockMs())
        }
        transport.send(RelayEnvelope.json.encodeToString(JsonObject.serializer(), init))

        val answered = pendingAuth.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pending = null
        return pendingAuth.outcome ?: AuthOutcome.NoAnswer(if (answered) "no outcome" else "timeout")
    }

    /** 宿主喂 WS 文本；认证无关的帧一律忽略。 */
    fun onText(text: String) {
        RelayEnvelope.errorOf(text)?.let { error ->
            complete(AuthOutcome.Rejected(error))
            return
        }
        val obj = RelayEnvelope.parseObject(text) ?: return
        when (RelayEnvelope.primitiveOrNull(obj, "type")) {
            "auth_challenge" -> {
                val nonce = RelayEnvelope.primitiveOrNull(obj, "nonce") ?: return
                val inFlight = pending ?: return
                val proof = ProofCalculator.proof(
                    inFlight.creds.passHash,
                    nonce,
                    RelayConstants.ROLE_TERMINAL,
                    inFlight.creds.deviceSid,
                )
                val response = buildJsonObject {
                    put("type", "auth_response")
                    put("device_sid", inFlight.creds.deviceSid)
                    put("proof", proof)
                    put("client_ts", clockMs())
                }
                transport.send(RelayEnvelope.json.encodeToString(JsonObject.serializer(), response))
            }
            "auth_ack", "pair_status_ack" -> {
                val pairStatus = RelayEnvelope.primitiveOrNull(obj, "pair_status")
                val terminalSid = RelayEnvelope.primitiveOrNull(obj, "terminal_sid")
                when (pairStatus) {
                    "matched" -> complete(AuthOutcome.Matched(terminalSid))
                    // waiting=桌面端在线但握手未完（E1 capture 实证）；继续等 challenge/ack，不判定。
                    "waiting", null -> Unit
                    else -> complete(AuthOutcome.NoAnswer("pair_status=$pairStatus"))
                }
            }
        }
    }

    fun onClosed(code: Int, reason: String) {
        complete(AuthOutcome.NoAnswer("closed code=$code reason=$reason"))
    }

    fun onFailure(error: Throwable) {
        complete(AuthOutcome.NoAnswer("transport failure: ${error.message}"))
    }

    private fun complete(outcome: AuthOutcome) {
        val inFlight = pending ?: return
        if (inFlight.outcome == null) {
            inFlight.outcome = outcome
            inFlight.latch.countDown()
        }
    }
}
