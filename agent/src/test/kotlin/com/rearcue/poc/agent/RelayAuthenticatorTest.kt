package com.rearcue.poc.agent

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 假传输：脚本化出站帧记录 + 入站帧注入（authenticate 跑在 worker 线程，集合必须并发安全）。 */
private class FakeTransport : RelayTransport {

    val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var listenerRef: RelayTransport.Listener? = null
    var closedWith: Pair<Int, String>? = null

    override fun connect(endpoint: String, headers: Map<String, String>) {
        listenerRef?.onOpen()
    }

    override fun send(text: String) {
        sent.add(text)
    }

    override fun close(code: Int, reason: String) {
        closedWith = code to reason
    }

    override fun setListener(listener: RelayTransport.Listener?) {
        listenerRef = listener
    }

    fun serverSends(text: String) {
        listenerRef?.onText(text)
    }

    /** 带超时的轮询：worker 线程产出满足条件或超时失败，绝不死循环。 */
    fun awaitSent(timeoutMs: Long = 5_000L, predicate: (String) -> Boolean): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            sent.firstOrNull(predicate)?.let { return it }
            Thread.sleep(5)
        }
        return null
    }

    fun lastSent(): String = sent.last()
}

class RelayAuthenticatorTest {

    private fun nonceFrame(nonce: String) =
        """{"type":"auth_challenge","server_ts":1,"nonce":"$nonce"}"""

    private fun ackFrame(pairStatus: String, terminalSid: String = "t_1") =
        """{"type":"auth_ack","server_ts":1,"device_sid":"d_x","terminal_sid":"$terminalSid","pair_status":"$pairStatus"}"""

    /** authenticator 不自持监听器——测试里手工接线（同 RelaySession 的宿主职责）。 */
    private fun wire(transport: FakeTransport, auth: RelayAuthenticator) {
        transport.setListener(object : RelayTransport.Listener {
            override fun onOpen() = Unit
            override fun onText(text: String) = auth.onText(text)
            override fun onClosed(code: Int, reason: String) = auth.onClosed(code, reason)
            override fun onFailure(error: Throwable) = auth.onFailure(error)
        })
    }

    @Test
    fun `完整握手发 auth_init 和正确 proof 并 matched`() {
        val transport = FakeTransport()
        val auth = RelayAuthenticator(transport, clockMs = { 42L })
        val creds = RelayCredentials("d_LoZcBCe6C5LVGJcaKcbPCN", "T9WfvV8mKKA6l85A3gFFdTRnVCfpn1KxzsizZdwzqRQ", deviceMid = null)

        val outcomeRef = AtomicReference<AuthOutcome>()
        wire(transport, auth)
        val worker = Thread { outcomeRef.set(auth.authenticate(creds, timeoutMs = 5_000L)) }
        worker.start()

        val init = transport.awaitSent { it.contains("auth_init") }
        assertTrue(init != null, "auth_init 未发出")
        transport.serverSends(nonceFrame("WsTV-6vBI8KSNZDXBgltbcEH"))
        val response = transport.awaitSent { it.contains("auth_response") }
        assertTrue(response != null, "auth_response 未发出")
        transport.serverSends(ackFrame("matched", "t_9"))
        worker.join(5_000)

        assertTrue(init!!.contains("\"role\":\"terminal\""))
        assertTrue(init.contains("\"device_sid\":\"d_LoZcBCe6C5LVGJcaKcbPCN\""))
        assertTrue(
            response!!.contains(ProofCalculator.proof(creds.passHash, "WsTV-6vBI8KSNZDXBgltbcEH", "terminal", creds.deviceSid)),
            "proof 必须按 E1 公式计算",
        )
        assertEquals(AuthOutcome.Matched("t_9"), outcomeRef.get())
    }

    @Test
    fun `waiting 状态不判定_继续等 matched`() {
        val transport = FakeTransport()
        val auth = RelayAuthenticator(transport, clockMs = { 1L })
        val creds = RelayCredentials("d_x", "hash", deviceMid = null)

        val outcomeRef = AtomicReference<AuthOutcome>()
        wire(transport, auth)
        val worker = Thread { outcomeRef.set(auth.authenticate(creds, timeoutMs = 5_000L)) }
        worker.start()
        transport.awaitSent { it.contains("auth_init") }
        transport.serverSends("""{"type":"auth_ack","pair_status":"waiting","terminal_sid":""}""")
        transport.serverSends(nonceFrame("n1"))
        assertTrue(transport.awaitSent { it.contains("auth_response") } != null, "waiting 后仍应完成 challenge")
        transport.serverSends(ackFrame("matched"))
        worker.join(5_000)
        assertTrue(outcomeRef.get() is AuthOutcome.Matched)
    }

    @Test
    fun `AUTH_FAILED 拒绝`() {
        val transport = FakeTransport()
        val auth = RelayAuthenticator(transport, clockMs = { 1L })
        val creds = RelayCredentials("d_x", "bad-hash", deviceMid = null)

        val outcomeRef = AtomicReference<AuthOutcome>()
        wire(transport, auth)
        val worker = Thread { outcomeRef.set(auth.authenticate(creds, timeoutMs = 5_000L)) }
        worker.start()
        transport.awaitSent { it.contains("auth_init") }
        transport.serverSends("""{"type":"error","code":"AUTH_FAILED"}""")
        worker.join(5_000)
        assertEquals(AuthOutcome.Rejected(RelayError("AUTH_FAILED")), outcomeRef.get())
    }

    @Test
    fun `无应答超时`() {
        val transport = FakeTransport()
        val auth = RelayAuthenticator(transport, clockMs = { 1L })
        val creds = RelayCredentials("d_x", "hash", deviceMid = null)
        val outcome = auth.authenticate(creds, timeoutMs = 50L)
        assertTrue(outcome is AuthOutcome.NoAnswer)
    }
}

class PairingLinkTest {

    @Test
    fun `解析官方 QR 链接`() {
        val link = PairingLink.parse(
            "https://zcode.z.ai/remote/v4?sid=d_LoZcBCe6C5LVGJcaKcbPCN&hash=T9WfvV8mKKA6l85A3gFFdTRnVCfpn1KxzsizZdwzqRQ" +
                "&t=1790488526267&mid=9f8e7d6c-1a2b-3c4d-5e6f-7a8b9c0d1e2f&name=%E6%A1%8C%E9%9D%A2&app_version=3.14.3",
        )
        assertEquals("d_LoZcBCe6C5LVGJcaKcbPCN", link.deviceSid)
        assertEquals("T9WfvV8mKKA6l85A3gFFdTRnVCfpn1KxzsizZdwzqRQ", link.passHash)
        assertEquals(1790488526267L, link.issuedAtMs)
        assertEquals("9f8e7d6c-1a2b-3c4d-5e6f-7a8b9c0d1e2f", link.deviceMid)
        assertEquals("桌面", link.deviceName)
        assertEquals("3.14.3", link.appVersion)
    }

    @Test
    fun `缺 sid 或 hash 拒绝`() {
        assertFailsWith<IllegalArgumentException> { PairingLink.parse("https://zcode.z.ai/remote/v4?hash=abc") }
        assertFailsWith<IllegalArgumentException> { PairingLink.parse("https://zcode.z.ai/remote/v4?sid=d_1") }
    }

    @Test
    fun `非 http 链接拒绝`() {
        assertFailsWith<IllegalArgumentException> { PairingLink.parse("ftp://x?sid=a&hash=b") }
        assertFailsWith<IllegalArgumentException> { PairingLink.parse("随便一串文本") }
    }
}
