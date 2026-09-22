package com.rearcue.poc.rear

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wake Keep-alive（票 #21/#24）：外部行为 = 「投送在屏期间让定向背屏的唤醒键按间隔注入、
 * stop 后一条都不再发、不残留」。票 #24 起定时器在**设备侧自驱循环**（shell uid，锁屏冻结
 * 杀不死），本类的外部行为因此是「起/停/调强度的命令形状与幂等」——命令形状就是被测行为
 * （同 RearProjectionCommandsTest 先例：写错定向/写错退出语义即红）。用假 [Shell] 记录命令
 * 序列（既有 seam，不引 Android）。
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

    /** 每例独享的应用侧 stop 标记（临时文件先删掉，让「存在 = 停令已写」有意义）。 */
    private fun stopFile(): File =
        File.createTempFile("rearcue-wake-loop", ".stop").apply { delete() }

    @Test
    fun `默认注入间隔定档 5000ms（不改任何设置即默认强度）`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell) // 不传 intervalMs：吃默认值
        assertEquals(5000L, WakeKeepAlive.DEFAULT_INTERVAL_MS)
        assertEquals(WakeKeepAlive.DEFAULT_INTERVAL_MS, keepAlive.intervalMs)
        keepAlive.start(rearDisplayId = 1)
        assertEquals(1, shell.commands.size)
        // 启动命令把默认强度带给设备侧循环（间隔文件 = `毫秒 秒` 两个字段）。
        assertTrue(
            shell.commands[0].contains("echo '5000 5.000'"),
            "默认强度没进启动命令：${shell.commands}",
        )
    }

    @Test
    fun `投送在屏期间自驱循环注入定向背屏唤醒键（形状钉死，写错定向即红）`() {
        val shell = FakeShell()
        val stopMarker = stopFile()
        val keepAlive = WakeKeepAlive(shell, appStopFile = stopMarker, intervalMs = 5000)
        keepAlive.start(rearDisplayId = 1)
        assertEquals(1, shell.commands.size)
        val start = shell.commands[0]
        // 注入物 = 定向背屏的唤醒键（不带 -d 会翻主屏电源态，E13 实测过）——字面钉死。
        assertEquals("input -d 1 keyevent KEYCODE_WAKEUP", RearProjectionCommands.wakeKeyCommand(1))
        assertEquals("input -d 3 keyevent KEYCODE_WAKEUP", RearProjectionCommands.wakeKeyCommand(3))
        assertTrue(start.contains("input -d 1 keyevent KEYCODE_WAKEUP"), start)
        // 自驱：后台 sh（nohup + &），且 sh -c 立即返回（输出不占执行通道）。
        assertTrue(start.contains("nohup sh -c '"), start)
        assertTrue(start.trimEnd().endsWith("&"), start)
        // 不残留的三条腿都在命令里：进程消失看门狗 + 两个 stop 文件判定 + 退出日志锚。
        assertTrue(start.contains("pidof com.rearcue.poc"), start)
        assertTrue(start.contains("[ ! -f " + RearProjectionCommands.WAKE_LOOP_STOP_FILE + " ]"), start)
        assertTrue(start.contains("[ ! -f " + stopMarker.absolutePath + " ]"), start)
        assertTrue(start.contains("wake-keep-alive stop ticks="), start)
    }

    @Test
    fun `stop 之后一条都不再发（无残留循环）`() {
        val shell = FakeShell()
        val stopMarker = stopFile()
        val keepAlive = WakeKeepAlive(shell, appStopFile = stopMarker, intervalMs = 5000)
        keepAlive.start(rearDisplayId = 1)
        keepAlive.stop()
        assertEquals(2, shell.commands.size)
        assertEquals("touch " + RearProjectionCommands.WAKE_LOOP_STOP_FILE, shell.commands[1])
        Thread.sleep(120)
        assertEquals(2, shell.commands.size, "stop 后仍有命令：${shell.commands}")
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `stop 直写应用侧 stop 标记，Shizuku 掉线也送达（不残留契约）`() {
        val shell = FakeShell()
        val stopMarker = stopFile()
        val keepAlive = WakeKeepAlive(shell, appStopFile = stopMarker, intervalMs = 5000)
        keepAlive.start(rearDisplayId = 1)
        shell.failing = true // Shizuku 掉线：shell 通道写不进 stop 文件

        keepAlive.stop()

        // 应用侧标记是纯文件 IO：停令不依赖 shell，照样送达（循环每拍判定它）。
        assertTrue(stopMarker.exists(), "应用侧 stop 标记没写成：残留循环会继续注入")
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `未 start 的 stop 也清残留：应用侧标记照样写，shell 零命令`() {
        val shell = FakeShell()
        val stopMarker = stopFile()
        val keepAlive = WakeKeepAlive(shell, appStopFile = stopMarker, intervalMs = 5000)

        keepAlive.stop()

        // 上代进程留下的残留循环也要能被这代的 stop 停掉；shell 命令保持零（幂等空操作不变）。
        assertTrue(stopMarker.exists(), "应用侧 stop 标记没写成：上代残留循环停不掉")
        assertEquals(0, shell.commands.size)
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `start 先清上一代 stop 标记再起循环（否则新循环第一拍就自停）`() {
        val shell = FakeShell()
        val stopMarker = stopFile()
        val keepAlive = WakeKeepAlive(shell, appStopFile = stopMarker, intervalMs = 5000)
        stopMarker.writeText("stop") // 上一代留下的停令

        keepAlive.start(rearDisplayId = 1)

        assertFalse(stopMarker.exists(), "上一代 stop 标记没清掉：新循环起不来")
        assertEquals(1, shell.commands.size)
        // 启动命令自带清残留（双 stop 文件 + pid 文件），进程重启也不会出双循环。
        assertTrue(
            shell.commands[0].contains("rm -f " + RearProjectionCommands.WAKE_LOOP_STOP_FILE + " " + stopMarker.absolutePath),
            shell.commands[0],
        )
    }

    @Test
    fun `未 start 时不发任何命令，未启动的 stop 是空操作`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 5000)
        assertEquals(0, shell.commands.size)
        keepAlive.stop()
        assertFalse(keepAlive.isRunning)
        assertEquals(0, shell.commands.size)
    }

    @Test
    fun `重复 start 幂等，不产生第二条循环`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 5000)
        keepAlive.start(rearDisplayId = 1)
        keepAlive.start(rearDisplayId = 1)
        assertEquals(1, shell.commands.size, "同目标重复 start 应是空操作：${shell.commands}")
    }

    @Test
    fun `强度可调：运行中改间隔立刻生效（循环间隔文件即改即读）`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 5000)
        keepAlive.intervalMs = 7000 // 未运行：只改内存值，不该碰设备
        assertEquals(0, shell.commands.size)
        keepAlive.start(rearDisplayId = 1) // 起循环按当前值写间隔文件
        assertTrue(
            shell.commands[0].contains("echo '7000 7.000' > " + RearProjectionCommands.WAKE_LOOP_INTERVAL_FILE),
            "启动命令没按当前强度写间隔文件：${shell.commands}",
        )
        keepAlive.intervalMs = 400
        assertTrue(keepAlive.isRunning, "调强度不该停循环")
        assertEquals(2, shell.commands.size)
        assertTrue(
            shell.commands[1].contains("echo '400 0.400'"),
            "间隔文件没按新值改写：${shell.commands}",
        )
    }

    @Test
    fun `shell 失败不抛异常（Shizuku 缺失的安全降级形态）`() {
        val shell = FakeShell()
        shell.failing = true
        val keepAlive = WakeKeepAlive(shell, intervalMs = 5000)
        keepAlive.start(rearDisplayId = 1) // 不该抛
        assertFalse(keepAlive.isRunning, "启动命令失败就不该自称在跑")
        keepAlive.stop() // 幂等空操作，也不该抛
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `目标 displayId 换了以后注入跟着换（重启循环，启动命令自带清遗留）`() {
        val shell = FakeShell()
        val keepAlive = WakeKeepAlive(shell, intervalMs = 5000)
        keepAlive.start(rearDisplayId = 1)
        keepAlive.start(rearDisplayId = 3)
        assertEquals(2, shell.commands.size)
        val restart = shell.commands[1]
        assertTrue(restart.contains("input -d 3 keyevent KEYCODE_WAKEUP"), restart)
        // 清遗留循环在同一条启动命令里（pid 文件 + kill），双循环不可能来自「换了目标忘了杀旧」。
        assertTrue(restart.contains("kill "), restart)
        assertTrue(restart.contains(RearProjectionCommands.WAKE_LOOP_PID_FILE), restart)
    }
}
