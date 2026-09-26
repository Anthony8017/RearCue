package com.rearcue.poc.rear

/**
 * 投送确认的纯决策（两段式的第二段）：给一次观测的结果，判「确认 / 继续等 / 超时走兜底」。
 *
 * 第二段 = 应用内投送发出后，等系统给出「真的上屏了」的证据（E2 实测：被背屏白名单拒绝时
 * `startActivity` 不抛异常也不报错，所以「调用没报错」不算证据）。等待机制（轮询间隔、超时上限）
 * 留在调用方；这里只定「下一步是什么」，竞态时序因此可以钉在 JVM 测试里。
 *
 * 优先级 = 证据强度：界面已在屏 > 界面创建信号 > 回读任务栈 > 超时。
 */
object ConfirmPolicy {

    /**
     * 一次确认判定。
     *
     * @param present 界面实例已在屏（重复投送/重投时的第一证据，不必再等生命周期回调）
     * @param created 监听到 [RearDashboardActivity] 被创建（第一个可信信号）
     * @param onDisplay 回读背屏任务栈见到 Dashboard（系统事实的第二个来源）
     * @param remainingMs 距确认超时还剩多少；≤0 即超时（走 shell 兜底链）
     */
    fun step(
        present: Boolean,
        created: Boolean,
        onDisplay: Boolean,
        remainingMs: Long,
    ): ConfirmStep = when {
        present -> ConfirmStep.Confirmed(ConfirmVia.ALREADY_ON_SCREEN)
        created -> ConfirmStep.Confirmed(ConfirmVia.LAUNCHED)
        onDisplay -> ConfirmStep.Confirmed(ConfirmVia.TASK_STACK)
        remainingMs <= 0 -> ConfirmStep.TimedOut
        else -> ConfirmStep.WaitMore
    }
}

/** 确认途径：投送确认靠哪个信号定的，也决定日志与状态里的说法。 */
enum class ConfirmVia(val detailText: String) {
    ALREADY_ON_SCREEN("界面已在屏"),
    LAUNCHED("应用内启动"),
    TASK_STACK("应用内启动，任务栈确认"),
}

/** 确认这一步的结论。 */
sealed interface ConfirmStep {
    data class Confirmed(val via: ConfirmVia) : ConfirmStep

    /** 证据还不够、也没到超时：继续等下一次观测。 */
    data object WaitMore : ConfirmStep

    /** 超时未获确认（可能被背屏白名单或后台启动限制拦下）：交给 shell 兜底链。 */
    data object TimedOut : ConfirmStep
}
