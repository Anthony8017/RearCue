package com.rearcue.poc.rear

/**
 * 决定背屏归属广播是否需要触发重投。
 *
 * 原生背屏明确亮起时，Native Rear Screen 可能已经 Takeover，必须重投；其他广播只是
 * 归属可能变化的提示。只要 Dashboard 实例仍存在，就不要重复 startActivity——让现有
 * Dashboard 与 Wake Keep-alive 继续工作，真正消失后再由 [RearDashboardHost] 触发恢复。
 */
object RearDisplaySignalPolicy {
    fun shouldRetake(action: String, dashboardInstanceCount: Int): Boolean =
        action == RearDisplaySignals.SUB_SCREEN_ON || dashboardInstanceCount <= 0
}
