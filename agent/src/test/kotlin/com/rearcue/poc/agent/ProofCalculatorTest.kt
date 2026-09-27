package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProofCalculatorTest {

    /** 生产中继验证过的向量：E1 capture 20260927-135526（一次性随机凭据，服务端返回 auth_ack）。 */
    @Test
    fun `复现生产中继验证过的 proof`() {
        val proof = ProofCalculator.proof(
            passHash = "T9WfvV8mKKA6l85A3gFFdTRnVCfpn1KxzsizZdwzqRQ",
            nonce = "WsTV-6vBI8KSNZDXBgltbcEH",
            role = "device",
            deviceSid = "d_LoZcBCe6C5LVGJcaKcbPCN",
        )
        assertEquals("gTBdk3i1LxTMKO_1li5uYzSVA6WiKZfi6eEz1wlX_sQ", proof)
    }

    /** bundle 测试向量基座（zcode.cjs wXi 的 passHash）：不同输入产出不同、格式为 base64url 无填充。 */
    @Test
    fun `proof 是 base64url 无填充且随输入变化`() {
        val base = ProofCalculator.proof("dGVzdF9oYXNo", "nonce1", "terminal", "d_abc")
        assertEquals("dGVzdF9oYXNo".length % 4, 0) // sanity：基座 hash 是合法 base64 串
        assertTrue(base.none { it == '+' || it == '/' || it == '=' }, "必须是 base64url 无填充：$base")
        assertNotEquals(base, ProofCalculator.proof("dGVzdF9oYXNo", "nonce2", "terminal", "d_abc"))
        assertNotEquals(base, ProofCalculator.proof("dGVzdF9oYXNo", "nonce1", "device", "d_abc"))
        assertNotEquals(base, ProofCalculator.proof("dGVzdF9oYXNo", "nonce1", "terminal", "d_other"))
        assertNotEquals(base, ProofCalculator.proof("other", "nonce1", "terminal", "d_abc"))
    }
}
