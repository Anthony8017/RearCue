package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 正文轻格式化判例（spec 0017 / 票 #169）：Markdown 文本 → 段落/代码块分段结果。
 *
 * 契约一句话：**去掉标记符号、保留换行与缩进**；围栏行不显示、块内走等宽；
 * 行内标记（`**`/`__`/反引号/`~~`）连同符号一起去掉，被标记的文字一个字不丢。
 * 不做链接渲染、不做表格重排、不引入 Markdown 库——解析范围就锁在这几条里。
 */
class AgentMarkdownTest {

    @Test
    fun `无标记的纯文本就是一段`() {
        val blocks = AgentMarkdown.parse("它在改 App.kt，马上好。")
        assertEquals(1, blocks.size)
        val text = blocks.single() as AgentMarkdown.Block.Text
        assertEquals("它在改 App.kt，马上好。", text.text)
        assertTrue(text.inlineCode.isEmpty())
    }

    @Test
    fun `空输入出空分段`() {
        assertTrue(AgentMarkdown.parse("").isEmpty())
        assertTrue(AgentMarkdown.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun `换行原样保留`() {
        val blocks = AgentMarkdown.parse("第一行\n第二行")
        assertEquals("第一行\n第二行", (blocks.single() as AgentMarkdown.Block.Text).text)
    }

    @Test
    fun `缩进原样保留——代码可读性的命根子`() {
        val source = "fun main() {\n    if (x) {\n        go()\n    }\n}"
        val text = AgentMarkdown.parse(source).single() as AgentMarkdown.Block.Text
        assertEquals(source, text.text)
    }

    @Test
    fun `围栏行不显示且块内走代码档`() {
        val blocks = AgentMarkdown.parse("看这段：\n```kotlin\nval x = 1\n```\n好了。")
        assertEquals(3, blocks.size)
        assertEquals("看这段：", (blocks[0] as AgentMarkdown.Block.Text).text)
        val code = blocks[1] as AgentMarkdown.Block.Code
        assertEquals("val x = 1", code.text)
        assertEquals("好了。", (blocks[2] as AgentMarkdown.Block.Text).text)
    }

    @Test
    fun `代码块内部不做行内标记剥离`() {
        // 代码里的 `**` 是语法本身，不许当 Markdown 吃掉。
        val blocks = AgentMarkdown.parse("```\na ** b\nc__d\n```")
        assertEquals("a ** b\nc__d", (blocks.single() as AgentMarkdown.Block.Code).text)
    }

    @Test
    fun `行内标记连同符号去掉且不丢字`() {
        val text = AgentMarkdown.parse("这是 **加粗** 和 __强调__ 还有 ~~删除~~。").single()
            as AgentMarkdown.Block.Text
        assertEquals("这是 加粗 和 强调 还有 删除。", text.text)
    }

    @Test
    fun `行内代码去符号但记住等宽区间`() {
        val text = AgentMarkdown.parse("跑 `gradlew test` 看看").single() as AgentMarkdown.Block.Text
        assertEquals("跑 gradlew test 看看", text.text)
        val span = text.inlineCode.single()
        assertEquals("gradlew test", span.sliceOf(text.text))
    }

    @Test
    fun `行内区间落在文本长度上时不被误丢`() {
        // 区间是半开区间：`end == 文本长度` 是合法的「一直到行尾」。
        val text = AgentMarkdown.parse("`abc`").single() as AgentMarkdown.Block.Text
        assertEquals("abc", text.text)
        assertEquals("abc", text.inlineCode.single().sliceOf(text.text))
    }

    @Test
    fun `同段多个行内代码各自的区间都对`() {
        val text = AgentMarkdown.parse("先 `a` 再 `bc` 收尾").single() as AgentMarkdown.Block.Text
        assertEquals("先 a 再 bc 收尾", text.text)
        assertEquals(
            listOf("a", "bc"),
            text.inlineCode.map { it.sliceOf(text.text) },
        )
    }

    @Test
    fun `去掉段首空行后行内区间跟着左移`() {
        // 段首空行会在 trim 时被吃掉，区间必须跟着移动，否则等宽会套到别的字上。
        val text = AgentMarkdown.parse("\n\n跑 `gosub` 了").single() as AgentMarkdown.Block.Text
        assertEquals("跑 gosub 了", text.text)
        assertEquals("gosub", text.inlineCode.single().sliceOf(text.text))
    }

    // —— 区间坐标系（评审发现的真 bug）：行内标记符号被剥掉会让它**后面**的区间整体左移 ——

    @Test
    fun `标记符号在反引号之前时区间仍指向正确的字`() {
        // `**加粗**` 被剥掉 4 个字符：反引号片段的区间必须跟着左移，
        // 否则等宽会套到后面的错字上（或越界被丢掉，等宽效果静默消失）。
        val text = AgentMarkdown.parse("**加粗** 用 `x` 收尾").single() as AgentMarkdown.Block.Text
        assertEquals("加粗 用 x 收尾", text.text)
        assertEquals("x", text.inlineCode.single().sliceOf(text.text))
    }

    @Test
    fun `多段行内代码各自在剥符号后仍然对齐`() {
        val text = AgentMarkdown.parse("**A** 起 `one` 中 ~~B~~ 末 `two`").single() as AgentMarkdown.Block.Text
        assertEquals("A 起 one 中 B 末 two", text.text)
        assertEquals(
            listOf("one", "two"),
            text.inlineCode.map { it.sliceOf(text.text) },
        )
    }

    @Test
    fun `标题号与行尾井号被剥掉后区间仍然对齐`() {
        val text = AgentMarkdown.parse("## 跑 `gradlew test` ##").single() as AgentMarkdown.Block.Text
        assertEquals("跑 gradlew test", text.text)
        assertEquals("gradlew test", text.inlineCode.single().sliceOf(text.text))
    }

    @Test
    fun `标题号去掉、文字保留`() {
        val text = AgentMarkdown.parse("## 结论").single() as AgentMarkdown.Block.Text
        assertEquals("结论", text.text)
    }

    @Test
    fun `未闭合围栏退化成一整块代码而不是吞掉内容`() {
        val blocks = AgentMarkdown.parse("以下是输出：\n```\nline1\nline2")
        assertEquals(2, blocks.size)
        assertEquals("以下是输出：", (blocks[0] as AgentMarkdown.Block.Text).text)
        assertEquals("line1\nline2", (blocks[1] as AgentMarkdown.Block.Code).text)
    }

    @Test
    fun `段内空行保留为分隔但不吞掉两侧文字`() {
        val text = AgentMarkdown.parse("第一段\n\n第二段").single() as AgentMarkdown.Block.Text
        assertEquals("第一段\n\n第二段", text.text)
    }

    @Test
    fun `不可见字符与行尾空格不进入渲染`() {
        val text = AgentMarkdown.parse("  有内容  \n\n").single() as AgentMarkdown.Block.Text
        assertEquals("有内容", text.text)
    }

    @Test
    fun `单星号与单个下划线不当标记吃字`() {
        // `*` 与 `_` 在普通文本里（乘法、变量名）不该被误剥。
        val text = AgentMarkdown.parse("a * b 与 snake_case").single() as AgentMarkdown.Block.Text
        assertEquals("a * b 与 snake_case", text.text)
    }
}
