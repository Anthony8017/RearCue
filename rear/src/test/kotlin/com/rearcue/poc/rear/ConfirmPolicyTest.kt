package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 投送确认的纯决策（两段式的第二段）：优先级 = 证据强度（在屏 > 创建信号 > 任务栈回读 > 超时）。
 * 竞态时序的分类逻辑钉死在这里，等待机制本身留在 Android adapter。
 */
class ConfirmPolicyTest {

    @Test
    fun `界面已在屏是第一证据，压过其余一切`() {
        val step = ConfirmPolicy.step(present = true, created = true, onDisplay = true, remainingMs = -5)
        assertEquals(ConfirmStep.Confirmed(ConfirmVia.ALREADY_ON_SCREEN), step)
    }

    @Test
    fun `界面创建信号压过任务栈回读与超时`() {
        val step = ConfirmPolicy.step(present = false, created = true, onDisplay = true, remainingMs = 0)
        assertEquals(ConfirmStep.Confirmed(ConfirmVia.LAUNCHED), step)
    }

    @Test
    fun `任务栈回读是系统事实，压过超时`() {
        val step = ConfirmPolicy.step(present = false, created = false, onDisplay = true, remainingMs = -1)
        assertEquals(ConfirmStep.Confirmed(ConfirmVia.TASK_STACK), step)
    }

    @Test
    fun `证据不够就等，剩 0 即超时走兜底`() {
        assertEquals(ConfirmStep.WaitMore, ConfirmPolicy.step(false, false, false, remainingMs = 1))
        assertEquals(ConfirmStep.TimedOut, ConfirmPolicy.step(false, false, false, remainingMs = 0))
        assertEquals(ConfirmStep.TimedOut, ConfirmPolicy.step(false, false, false, remainingMs = -1))
    }

    @Test
    fun `确认途径的说法逐字钉死（写进状态与日志）`() {
        assertEquals("界面已在屏", ConfirmVia.ALREADY_ON_SCREEN.detailText)
        assertEquals("应用内启动", ConfirmVia.LAUNCHED.detailText)
        assertEquals("应用内启动，任务栈确认", ConfirmVia.TASK_STACK.detailText)
    }
}
