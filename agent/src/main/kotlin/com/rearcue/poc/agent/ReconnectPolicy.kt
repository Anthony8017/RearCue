package com.rearcue.poc.agent

/**
 * 断线重连退避（纯决策，单测钉死）。
 *
 * 序列 1s→2s→4s→8s→16s→32s→60s 封顶，±20% 抖动；成功认证即归零。
 * 不可重试错误（AUTH_FAILED/4011/4013 等，见 [RelayError.retryable] 与
 * [RelayCloseCodes.retryable]）由调用方不调 [recordFailure] 直接停止——本类只管「要不要等、等多久」。
 */
class ReconnectPolicy(
    private val random: (Int) -> Int = { bound -> (0 until bound).random() },
) {
    private var failureCount = 0

    /** 连接/认证成功后调用：序列归零。 */
    fun reset() {
        failureCount = 0
    }

    /**
     * 失败一次后取下一次重连前要等的毫秒数。
     * @return 0 表示立即重试（首次失败），>0 等待，精度毫秒。
     */
    fun nextDelayMs(): Long {
        val index = failureCount.coerceAtMost(BASE_DELAYS_MS.size - 1)
        failureCount++
        val base = BASE_DELAYS_MS[index]
        val jitter = random((base * JITTER_NUMERATOR).toInt() / JITTER_DENOMINATOR + 1)
        return base + jitter
    }

    companion object {
        private val BASE_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L)
        private const val JITTER_NUMERATOR = 2
        private const val JITTER_DENOMINATOR = 10 // +0..20%
    }
}
