package com.rearcue.poc.voice

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
}
