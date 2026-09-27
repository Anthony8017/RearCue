package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectPolicyTest {

    @Test
    fun `指数退避序列 1-2-4-8-16-32-60 封顶`() {
        val policy = ReconnectPolicy(random = { 0 }) // 无抖动，断言基值
        val delays = (1..10).map { policy.nextDelayMs() }
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L, 60_000L),
            delays,
        )
    }

    @Test
    fun `成功后序列归零`() {
        val policy = ReconnectPolicy(random = { 0 })
        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun `抖动在 0 到基值 20% 之内`() {
        val policy = ReconnectPolicy() // 真 RNG
        repeat(50) {
            val delay = policy.nextDelayMs()
            policy.reset()
            assertTrue(delay >= 1_000L && delay <= 1_200L, "delay=$delay 应在 [1000,1200]")
        }
    }

    @Test
    fun `不可重试错误的分类`() {
        assertFalse(RelayError("AUTH_FAILED").retryable)
        assertFalse(RelayError("WRONG_PARAM").retryable)
        assertTrue(RelayError("KICKED").retryable)
        assertTrue(RelayError("INTERNAL").retryable)
        assertFalse(RelayCloseCodes.retryable(RelayCloseCodes.SESSION_EXPIRED))
        assertFalse(RelayCloseCodes.retryable(RelayCloseCodes.INVALID_MOBILE_CONNECTION))
        assertFalse(RelayCloseCodes.retryable(RelayCloseCodes.SESSION_NOT_FOUND))
        assertTrue(RelayCloseCodes.retryable(RelayCloseCodes.DESKTOP_DISCONNECTED))
    }
}
