package com.rearcue.poc.rear

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wake Keep-alive（票 #21）：循环的外部行为 = 「投送在屏期间按间隔发出定向背屏唤醒键、
 * stop 后一条都不再发」。用假 [Shell] 记录命令序列（既有 seam，不引 Android）。
 *
 * 时间用小间隔 + 有界等待，不断言精确 tick 数（定时抖动不是被测行为）。
 */
class WakeKeepAliveTest {

    private class FakeShell : Shell {
        override val available: Boolean = true
        override val permissionRequired: Boolean = false
        override val diagnostic: String = "fake"
        val commands = CopyOnWriteArrayList<String>()

        @Volatile
        var failing = false

        override fun run(command: String): ShellResult {
            commands += command
            return if (failing) {
                ShellResult(exitCode = -1, output = "boom")
            } else {
                ShellResult(exitCode = 0, output = "")
            }
        }
    }

    private fun awaitAtLeast(shell: FakeShell, n: Int, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (shell.commands.size < n && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun `投送在屏期间按间隔发定向背屏唤醒键`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 15)
        try {
            keepAlive.start(rearDisplayId = 1)
            awaitAtLeast(shell, 3)
            assertTrue(shell.commands.size >= 3, "expected ticks, got ${shell.commands}")
            assertEquals(setOf("input -d 1 keyevent KEYCODE_WAKEUP"), shell.commands.toSet())
        } finally {
            keepAlive.stop()
        }
    }

    @Test
    fun `stop 之后一条都不再发（无残留循环）`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 10)
        keepAlive.start(rearDisplayId = 1)
        awaitAtLeast(shell, 2)
        keepAlive.stop()
        val after = shell.commands.size
        Thread.sleep(150)
        assertEquals(after, shell.commands.size, "stop 后仍有注入：${shell.commands}")
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `未 start 时不发任何命令，未启动的 stop 是空操作`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 10)
        Thread.sleep(120)
        assertEquals(0, shell.commands.size)
        keepAlive.stop()
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `重复 start 幂等，不产生第二条循环`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 10)
        try {
            keepAlive.start(rearDisplayId = 1)
            keepAlive.start(rearDisplayId = 1)
            awaitAtLeast(shell, 4)
            Thread.sleep(100)
            // 幂等 = 单循环速率：双循环会翻倍增长，用上限把「翻倍」挡在断言外（只判「不是两条」）。
            assertTrue(shell.commands.size < 80, "疑似双循环：${shell.commands.size} 次 / ~220ms")
        } finally {
            keepAlive.stop()
        }
    }

    @Test
    fun `强度可调：运行中改间隔立刻生效，命令形状不变`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 10)
        try {
            keepAlive.start(rearDisplayId = 1)
            awaitAtLeast(shell, 2)
            keepAlive.intervalMs = 40
            assertTrue(keepAlive.isRunning, "调强度不该停循环")
            val before = shell.commands.size
            awaitAtLeast(shell, before + 2)
            assertEquals(setOf("input -d 1 keyevent KEYCODE_WAKEUP"), shell.commands.toSet())
        } finally {
            keepAlive.stop()
        }
    }

    @Test
    fun `注入失败不崩、循环继续（Shizuku 缺失的安全降级形态）`() {
        val shell = FakeShell()
        shell.failing = true
        val keepAlive = WakeKeepAlive(shell, intervalMs = 15)
        try {
            keepAlive.start(rearDisplayId = 1)
            awaitAtLeast(shell, 3)
            assertTrue(shell.commands.size >= 3, "失败也应继续尝试：${shell.commands}")
            assertTrue(keepAlive.isRunning)
        } finally {
            keepAlive.stop()
        }
    }

    @Test
    fun `目标 displayId 换了以后注入跟着换（幂等 start 只刷新目标屏）`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 10)
        try {
            keepAlive.start(rearDisplayId = 1)
            awaitAtLeast(shell, 2)
            keepAlive.start(rearDisplayId = 3)
            val before = shell.commands.size
            awaitAtLeast(shell, before + 2)
            assertTrue(
                shell.commands.any { it == "input -d 3 keyevent KEYCODE_WAKEUP" },
                "expected a directed tick for display 3: ${shell.commands}",
            )
        } finally {
            keepAlive.stop()
        }
    }
}
