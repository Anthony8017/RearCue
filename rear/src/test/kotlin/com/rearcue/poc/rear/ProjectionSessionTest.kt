package com.rearcue.poc.rear

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 投送会话（CONTEXT.md「投送会话」）：外部行为 = 『shell 兜底链按锁屏状态路由 + E14 词表判定 +
 * 保活生命周期 + 归还搬走的任务』。日志词形是 tools/ex 判定链契约（`task-move word=…`），
 * 这里按 byte 断言——改词形即红（同 RearProjectionCommandsTest 先例）。用假 [Shell] 记录命令、
 * 假 [WakeGuard] 记录保活起停（两个 adapter = 真 seam），不引 Android。
 */
class ProjectionSessionTest {

    private class FakeShell(availableStart: Boolean = true) : Shell {
        var availableNow: Boolean = availableStart
        override val available: Boolean get() = availableNow
        override val permissionRequired: Boolean = false
        override val diagnostic: String = "fake"
        val commands = mutableListOf<String>()
        var respond: (String) -> ShellResult = { ShellResult(exitCode = 0, output = "") }

        override fun run(command: String): ShellResult {
            commands += command
            return respond(command)
        }
    }

    private class FakeWake : WakeGuard {
        val starts = mutableListOf<Int>()
        var stops = 0
        override fun start(rearDisplayId: Int) {
            starts += rearDisplayId
        }

        override fun stop() {
            stops++
        }
    }

    private fun transcript(): ShellTranscript =
        ShellTranscript(File(System.getProperty("java.io.tmpdir"), "rearcue-session-test-${SEQ.incrementAndGet()}"))

    private class Harness(
        val shell: FakeShell,
        val wake: FakeWake,
        val logs: MutableList<String>,
        val warns: MutableList<String>,
        val session: ProjectionSession,
    )

    private fun harness(shell: FakeShell = FakeShell(), locked: () -> Boolean = { false }): Harness {
        val wake = FakeWake()
        val logs = mutableListOf<String>()
        val warns = mutableListOf<String>()
        return Harness(
            shell = shell,
            wake = wake,
            logs = logs,
            warns = warns,
            session = ProjectionSession(
                packageName = PKG,
                shell = shell,
                wake = wake,
                transcript = transcript(),
                keyguardLocked = locked,
                log = { logs += it },
                warn = { warns += it },
            ),
        )
    }

    // ---------- 词表契约（tools/ex 判定链，byte 不可改） ----------

    @Test
    fun `E14 词表词形逐字钉死`() {
        assertEquals("OK", CastVerdict.OK.word)
        assertEquals("NO-TASK", CastVerdict.NO_TASK.word)
        assertEquals("TXN-BROKEN", CastVerdict.TXN_BROKEN.word)
        assertEquals("NO-EFFECT", CastVerdict.NO_EFFECT.word)
        assertEquals(null, CastVerdict.CHANNEL_DOWN.word)
    }

    // ---------- 路由：锁屏走任务搬运，未锁屏走 am start --display ----------

    @Test
    fun `锁屏稳态直达任务搬运事务，不碰 am start --display`() {
        val h = harness(locked = { true })
        h.shell.respond = taskMoveRespond()
        val result = h.session.cast(1, ICONS, reason = "测试")

        assertEquals(CastVerdict.OK, result.verdict)
        assertTrue(result.onDisplay)
        assertTrue(h.shell.commands.any { it == "service call activity_task 51 i32 7 i32 1" })
        assertFalse(h.shell.commands.any { it.startsWith("am start --display") })
        assertTrue(h.logs.any { it.startsWith("project 兜底：锁屏稳态") })
    }

    @Test
    fun `未锁屏走 am start --display 兜底`() {
        val h = harness(locked = { false })
        h.shell.respond = { cmd ->
            when {
                cmd.startsWith("dumpsys activity activities | grep") -> ShellResult(0, READBACK_ON)
                else -> ShellResult(0, "")
            }
        }
        val result = h.session.cast(1, ICONS, reason = "应用内启动被拒")

        assertEquals(CastVerdict.OK, result.verdict)
        assertEquals(
            "am start --display 1 --activity-reorder-to-front -n $COMPONENT",
            h.shell.commands.first(),
        )
        // 回读两遍是既定行为：run(plan) 里作证据落盘一次，判定再读一次。
        assertEquals(2, h.shell.commands.count { it.startsWith("dumpsys activity activities | grep") })
        assertEquals(
            listOf("project iconSet=$ICONS -> onDisplay=true（Shizuku 兜底，原因：应用内启动被拒）"),
            h.logs,
        )
    }

    @Test
    fun `Shizuku 不可用是 CHANNEL_DOWN，不发任何命令`() {
        val h = harness(shell = FakeShell(false), locked = { true })
        val result = h.session.cast(1, ICONS, reason = "测试")

        assertEquals(CastVerdict.CHANNEL_DOWN, result.verdict)
        assertFalse(result.onDisplay)
        assertEquals("投送失败：测试（锁屏首投走任务搬运） 且 Shizuku 不可用", result.detail)
        assertEquals(listOf("project 失败：测试（锁屏首投走任务搬运） 且 Shizuku 不可用"), h.warns)
        assertTrue(h.shell.commands.isEmpty())
    }

    // ---------- E14 词表矩阵 ----------

    @Test
    fun `没有带 Dashboard 的任务时判 NO-TASK，不搬空任务`() {
        val h = harness(locked = { true })
        h.shell.respond = { cmd ->
            when {
                cmd == "dumpsys activity activities" -> ShellResult(0, DUMP_FOREIGN_TASK)
                else -> ShellResult(0, "")
            }
        }
        val result = h.session.cast(1, ICONS, reason = "测试")

        assertEquals(CastVerdict.NO_TASK, result.verdict)
        assertFalse(result.onDisplay)
        assertEquals("任务事务未执行：没有带 Dashboard 的任务可搬（NO-TASK）", result.detail)
        assertEquals(
            listOf("task-move word=NO-TASK taskId=none displayId=1 onDisplay=false reason=测试（锁屏首投走任务搬运）"),
            h.warns,
        )
        // 判 NO-TASK 前会先用默认屏补一次任务；补完仍没有 Dashboard 任务就不发事务。
        assertTrue(h.shell.commands.any { it == "am start -n $COMPONENT" })
        assertFalse(h.shell.commands.any { it.startsWith("service call activity_task 51") })
    }

    @Test
    fun `服务端回执文本坏通道即 TXN-BROKEN，不看退出码`() {
        listOf("", "Unable to find service", "Unknown transaction", "SecurityException").forEach { reply ->
            val h = harness(locked = { true })
            h.shell.respond = taskMoveRespond(txnOutput = reply, readback = READBACK_OFF)
            val result = h.session.cast(1, ICONS, reason = "测试")

            assertEquals(CastVerdict.TXN_BROKEN, result.verdict, "reply=[$reply]")
            assertFalse(result.onDisplay, "reply=[$reply]")
            assertEquals("任务事务通道坏了（TXN-BROKEN）：$reply", result.detail, "reply=[$reply]")
            // word 行是 tools/ex 判定锚：逐字断言（含 reason 的锁屏后缀）。
            assertEquals(
                "task-move word=TXN-BROKEN taskId=7 displayId=1 onDisplay=false reason=测试（锁屏首投走任务搬运）",
                h.logs.last(),
                "reply=[$reply]",
            )
        }
    }

    @Test
    fun `事务回执正常但没上屏判 NO-EFFECT`() {
        val h = harness(locked = { true })
        h.shell.respond = taskMoveRespond(txnOutput = "Result: Parcel(00000000 00000000)", readback = READBACK_OFF)
        val result = h.session.cast(1, ICONS, reason = "测试")

        assertEquals(CastVerdict.NO_EFFECT, result.verdict)
        assertFalse(result.onDisplay)
        assertEquals(
            "任务事务无效果（NO-EFFECT，taskId=7 未上屏；被拒与否从 logcat 的系统拒绝行判读）",
            result.detail,
        )
        assertEquals(
            "task-move word=NO-EFFECT taskId=7 displayId=1 onDisplay=false reason=测试（锁屏首投走任务搬运）",
            h.logs.last(),
        )
    }

    @Test
    fun `上屏判 OK 并记下搬走的任务，end 时归还主屏`() {
        val h = harness(locked = { true })
        h.shell.respond = taskMoveRespond()
        val result = h.session.cast(1, ICONS, reason = "测试")
        assertEquals(CastVerdict.OK, result.verdict)

        h.session.end()

        assertTrue(h.shell.commands.any { it == "service call activity_task 51 i32 7 i32 0" })
        assertTrue(
            h.logs.any { it.startsWith("task-move hand-back taskId=7 exit=0 out=") },
            "归还锚逐字保留：${h.logs}",
        )
    }

    @Test
    fun `缺任务时先用默认屏补建任务再搬运`() {
        val h = harness(locked = { true })
        var dumpCount = 0
        h.shell.respond = { cmd ->
            when {
                cmd == "dumpsys activity activities" -> ShellResult(0, if (dumpCount++ == 0) DUMP_FOREIGN_TASK else DUMP_WITH_TASK)
                cmd == "am start -n $COMPONENT" -> ShellResult(0, "Starting: Intent")
                cmd.startsWith("service call activity_task 51") -> ShellResult(0, "Result: Parcel(00000000 00000000)")
                cmd.startsWith("dumpsys activity activities | grep") -> ShellResult(0, READBACK_ON)
                else -> ShellResult(0, "")
            }
        }
        val result = h.session.cast(1, ICONS, reason = "测试")

        assertEquals(CastVerdict.OK, result.verdict)
        assertTrue(h.logs.any { it.startsWith("task-move create-task exit=0 out=Starting: Intent") })
        assertTrue(h.shell.commands.any { it == "service call activity_task 51 i32 7 i32 1" })
    }

    // ---------- 保活生命周期（ADR 0003：跟投送走，不残留） ----------

    @Test
    fun `投送即起、失败即停、成功不误停`() {
        val ok = harness(locked = { true })
        ok.shell.respond = taskMoveRespond()
        ok.session.begin(1)
        ok.session.cast(1, ICONS, reason = "测试")
        assertEquals(listOf(1), ok.wake.starts)
        assertEquals(0, ok.wake.stops, "上屏成功不该停保活")

        val failed = harness(locked = { true })
        failed.shell.respond = taskMoveRespond(txnOutput = "Result: Parcel(00000000 00000000)", readback = READBACK_OFF)
        failed.session.begin(1)
        failed.session.cast(1, ICONS, reason = "测试")
        assertEquals(1, failed.wake.stops, "没上屏 = 没有守护对象，必须停")
    }

    @Test
    fun `end 停保活并归还任务；stopGuard 只停保活`() {
        val h = harness(locked = { true })
        h.shell.respond = taskMoveRespond()
        h.session.begin(1)
        h.session.cast(1, ICONS, reason = "测试")
        h.session.stopGuard()
        assertEquals(1, h.wake.stops)
        assertFalse(h.shell.commands.any { it == "service call activity_task 51 i32 7 i32 0" }, "stopGuard 不动任务记账")

        h.session.end()
        assertEquals(2, h.wake.stops)
        assertTrue(h.shell.commands.any { it == "service call activity_task 51 i32 7 i32 0" })
    }

    private companion object {
        const val PKG = "com.rearcue.poc"
        const val COMPONENT = "com.rearcue.poc/com.rearcue.poc.rear.RearDashboardActivity"
        val ICONS = listOf("com.tencent.mm")
        val SEQ = AtomicInteger()

        /** 真机 dumpsys 形状（RearTaskLocator fixture）：本应用任务 + Dashboard 实例。 */
        val DUMP_WITH_TASK = """
            * Task{abc123 #7 type=standard A=10339:com.rearcue.poc}
                ActivityRecord{deadbeef u0 com.rearcue.poc/com.rearcue.poc.rear.RearDashboardActivity t7}
        """.trimIndent()

        /** 别的任务（MainActivity 空任务不算，NO-TASK 时**不搬**）。 */
        val DUMP_FOREIGN_TASK = """
            * Task{fff000 #9 type=standard A=1000:com.android.systemui}
        """.trimIndent()

        /** 背屏任务栈回读：Dashboard 在 Display #1（短名写法，见 RearProjectionVerifier）。 */
        val READBACK_ON = """
            Display #1
                ActivityRecord{deadbeef u0 com.rearcue.poc/.rear.RearDashboardActivity t7}
        """.trimIndent()

        val READBACK_OFF = """
            Display #1
                ActivityRecord{cafe0000 u0 com.xiaomi.subscreencenter/.SubScreenLauncher t3}
        """.trimIndent()

        /** 任务搬运一轮的标准应答：两次 dumpsys（找任务/回读）+ 事务回执 + 背屏块回读。 */
        fun taskMoveRespond(
            txnOutput: String = "Result: Parcel(00000000 00000000)",
            readback: String = READBACK_ON,
        ): (String) -> ShellResult = { cmd ->
            when {
                cmd == "dumpsys activity activities" -> ShellResult(0, DUMP_WITH_TASK)
                cmd.startsWith("service call activity_task 51") -> ShellResult(0, txnOutput)
                cmd.startsWith("dumpsys activity activities | grep") -> ShellResult(0, readback)
                else -> ShellResult(0, "")
            }
        }
    }
}
