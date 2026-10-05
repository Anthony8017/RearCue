package com.rearcue.poc.voice

import com.rearcue.poc.rear.VoiceBroadcastAnchorPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceBroadcastTextTest {

    @Test
    fun `普通正文和列表自然读出`() {
        val text = VoiceBroadcastText.normalize("# 标题\n- 第一项\n**第二项**")
        assertEquals("标题 第一项 第二项", text)
    }

    @Test
    fun `代码和表格只报略过`() {
        val raw = """
            前文
            ```kotlin
            val x = 1
            ```
            | A | B |
            |---|---|
            | 1 | 2 |
            后文
        """.trimIndent()
        val text = VoiceBroadcastText.normalize(raw)
        assertEquals("前文 代码内容已略过。 表格内容已略过。 后文", text)
    }

    @Test
    fun `链接读显示名且裸链接不逐字念`() {
        assertEquals(
            "项目地址 链接已略过。",
            VoiceBroadcastText.normalize("[项目地址](https://example.com/a) https://example.com/b"),
        )
    }

    @Test
    fun `文件路径只读文件名且保留周围正文`() {
        assertEquals(
            "已修改 Main.kt、配置.json 和 README.md。",
            VoiceBroadcastText.normalize("已修改 C:\\work\\src\\Main.kt、/home/user/项目/配置.json 和 docs/README.md。"),
        )
        assertEquals(
            "Main.kt README.md config.json LICENSE",
            VoiceBroadcastText.normalize(".\\src\\Main.kt ../docs/README.md ~/project/config.json \\\\server\\share\\LICENSE"),
        )
        assertEquals("a.txt,b.txt;c.txt", VoiceBroadcastText.normalize("/tmp/a.txt,/tmp/b.txt;/tmp/c.txt"))
    }

    @Test
    fun `带空格的代码和引号内路径只读文件名`() {
        assertEquals(
            "报告 终稿.md 和 \"Main.kt\"。",
            VoiceBroadcastText.normalize("`C:\\Users\\Test User\\报告 终稿.md` 和 \"/home/Test User/Main.kt\"。"),
        )
        assertEquals(
            "Main.kt",
            VoiceBroadcastText.normalize("[C:\\Users\\Test User\\Main.kt](<C:/Users/Test User/Main.kt>)"),
        )
    }

    @Test
    fun `文件链接读文件名并略过行号`() {
        assertEquals(
            "Main.kt README.md 查看配置",
            VoiceBroadcastText.normalize(
                "[C:\\work\\src\\Main.kt:42:5](C:/work/src/Main.kt:42:5) " +
                    "[docs/README.md#L12](docs/README.md#L12) [查看配置](/home/user/config.json)",
            ),
        )
    }

    @Test
    fun `普通斜杠表达和网址不当作文件路径`() {
        assertEquals(
            "读/写 3/4 2026/10/05 \"读/写\" and/or https 链接已略过。",
            VoiceBroadcastText.normalize("读/写 3/4 2026/10/05 \"读/写\" `and/or` https https://example.com/file.md"),
        )
    }

    @Test
    fun `文件名播报保留完整路径作为视觉锚`() {
        val raw = "已修改 `C:\\Users\\Test User\\Main.kt`。下一句。"
        val spoken = VoiceBroadcastText.spokenSentences(raw)

        assertEquals(listOf("已修改 Main.kt。", "下一句。"), spoken.map { it.text })
        assertEquals(listOf("已修改 C:\\Users\\Test User\\Main.kt。", "下一句。"), spoken.map { it.anchorText })
        assertEquals(listOf(true, true), spoken.map { it.visualAnchor })
        assertEquals(listOf("已修改 Main.kt。", "下一句。"), VoiceBroadcastText.sentences(raw))
        val display = "已修改 C:\\Users\\Test User\\Main.kt。下一句。"
        assertEquals(0, VoiceBroadcastAnchorPolicy.displayOffset(display, spoken[0].anchorText))
        assertEquals(
            display.indexOf("下一句"),
            VoiceBroadcastAnchorPolicy.displayOffset(display, spoken[1].anchorText, listOf(spoken[0].anchorText)),
        )
    }

    @Test
    fun `失败原因内的文件路径也只读文件名`() {
        assertEquals(
            "任务失败。无法读取 config.json",
            VoiceBroadcastText.spokenText(VoiceBroadcastKind.ERROR, errorReason = "无法读取 C:\\work\\config.json\n详细堆栈"),
        )
    }

    @Test
    fun `失败只读首行简短原因`() {
        assertEquals(
            "任务失败。连接电脑桥超时",
            VoiceBroadcastText.spokenText(
                VoiceBroadcastKind.ERROR,
                errorReason = "连接电脑桥超时\nException in thread main\nat Foo.bar",
            ),
        )
    }

    @Test
    fun `空正文有固定播报并按句切分`() {
        assertEquals(VoiceBroadcastText.EMPTY_REPLY, VoiceBroadcastText.spokenText(VoiceBroadcastKind.DONE, "  "))
        assertEquals(
            listOf("第一句。", "第二句！", "第三句？"),
            VoiceBroadcastText.sentences("第一句。第二句！第三句？"),
        )
    }
    @Test
    fun `代码表格略过句不设视觉锚_正文句可跟随`() {
        val spoken = VoiceBroadcastText.spokenSentences(
            """
            前文
            ```kotlin
            val x = 1
            ```
            后文。
            """.trimIndent(),
        )
        assertEquals(
            listOf(true, false, true),
            spoken.map { it.visualAnchor },
        )
        assertEquals(listOf("前文", "代码内容已略过。", "后文。"), spoken.map { it.text })
    }

    @Test
    fun `分号和装饰符号不拆句避免多余停顿`() {
        val raw = "第一句；第二句——第三句……第四句（括号）。下一句！"

        assertEquals("第一句；第二句，第三句，第四句括号。下一句！", VoiceBroadcastText.normalize(raw))
        assertEquals(
            listOf("第一句；第二句，第三句，第四句括号。", "下一句！"),
            VoiceBroadcastText.sentences(raw),
        )
    }
}
