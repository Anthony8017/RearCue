package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 票 #129 的安静执行面契约：高频只读探测（SystemUI dump）走 [Shell.runQuiet]，
 * 日志只报输出长度、不把完整 stdout 写进 logcat；普通调试命令保持「完整输出」原语义。
 *
 * [shellLogLine] 是该日志行的纯 Kotlin seam（logcat 实现收口在 [ShizukuShell]）——
 * 判定只认外部行为：文本进、行出；不断言 Log 调用本身。
 */
class ShizukuShellLogTest {

    private val probe = "dumpsys activity service com.android.systemui/.SystemUIService NotifCollection"

    @Test
    fun `安静执行只报长度，行里不出现 stdout 内容`() {
        val dump = "NotifCollection unsorted/unfiltered notifications: 2\n    [0]  0|com.example.secret|42|null|10370"

        val line = shellLogLine(probe, exitCode = 0, output = dump, quiet = true)

        assertFalse(line.contains("com.example.secret"), "安静执行不得把 dump 内容带进日志：$line")
        assertTrue(line.contains("chars>"), "安静执行要留下长度读数：$line")
        assertEquals("sh [$probe] exit=0 out=<${dump.length} chars>", line)
    }

    @Test
    fun `普通调试命令保留完整 stdout 原语义`() {
        val out = "displayId=1 owner=com.rearcue.poc"

        assertEquals("sh [am display ...] exit=0 out=$out", shellLogLine("am display ...", 0, out, quiet = false))
    }

    @Test
    fun `Shell 默认 runQuiet 仍走 run，既有 fake Shell 判例不受影响`() {
        val shell = object : Shell {
            override val available: Boolean = true
            override val permissionRequired: Boolean = false
            override val diagnostic: String = "fake"
            val commands = mutableListOf<String>()

            override fun run(command: String): ShellResult {
                commands += command
                return ShellResult(exitCode = 0, output = "ok")
            }
        }

        assertEquals(ShellResult(exitCode = 0, output = "ok"), shell.runQuiet("probe"))
        assertEquals(listOf("probe"), shell.commands)
    }
}