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

    /**
     * waiting 期的 pair_status_query 心跳（票 #86 phase B 实测）：matched 由 relay 在桌面端
     * 踢除重连周期完成后推送，官方客户端以 10s 心跳拉取状态更新；不发心跳可能永远等不到
     * matched 帧。complete/timeout 即停。
     */
    private val heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "agent-pair-heartbeat").apply { isDaemon = true }
    }

    @Volatile
    private var heartbeatTask: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * 认证等待窗默认 45s（票 #86 实机修订）：auth_ack(waiting) 后 **matched 会在同一连接上晚到**——
     * 桌面日志实证：终端到达后 relay 踢桌面一轮（KICKED→约 1s 重连→waiting_terminal），配对在
     * 这个踢除重连周期（约 13s）完成后才落地；10s 的旧超时永远差几秒错过。
     */
    fun authenticate(creds: RelayCredentials, timeoutMs: Long = 45_000L): AuthOutcome {
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
        heartbeatTask?.cancel(false)
        heartbeatTask = null
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
                    // waiting=桌面端在线但配对未落地（实机实证）：起 10s 心跳拉状态更新，
                    // 沿官方客户端 pair_status_query 语义。
                    "waiting", null -> startHeartbeat()
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

    /**
     * waiting 期的心跳（pair_status_query）：**停用**（票 #88 因果实证，docs/poc-logs/
     * 20260927-88-heartbeat-kick.md）——fresh-waiting 发查询 → relay 同帧回 error KICKED 并断连
     * （10s 查询 + 拆链 ≈ 15s，即票 #86 的 18:50 A/B 掐断序列）；官方客户端 fresh-waiting
     * 同样静默（stopHeartbeat + waitingTimer），matched 后才查询。WS 层 10s ping 已够保活，
     * matched 后也无需应用层心跳。保留 45s 等待窗 + 既有退避重连。
     */
    private fun startHeartbeat() {
        // 停用：见 KDoc（证据 docs/poc-logs/20260927-88-heartbeat-kick.md）。
    }

    private fun complete(outcome: AuthOutcome) {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
        val inFlight = pending ?: return
        if (inFlight.outcome == null) {
            inFlight.outcome = outcome
            inFlight.latch.countDown()
        }
    }
}
