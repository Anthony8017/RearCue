package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PseudoStreamRevealTest {
    private fun turn(id: String, text: String, kind: AgentTurnKind = AgentTurnKind.ANSWER, open: Boolean = false) =
        AgentTurn(role = AgentTurnRole.AGENT, text = text, kind = kind, open = open, entryId = id)

    @Test
    fun `历史直接显示_完整新回答渐显_两秒内显示完`() {
        val reveal = PseudoStreamReveal()
        val history = turn("old", "历史")
        assertEquals("历史", reveal.frame(listOf(history), listOf(history), true, 0).turns.single().text)
        val next = turn("new", "新的完整回答")
        val first = reveal.frame(listOf(history, next), listOf(history, next), true, 10)
        assertEquals("新", first.turns.last().text)
        assertTrue(first.active)
        val done = reveal.frame(listOf(history, next), listOf(history, next), true, 2_010)
        assertEquals(next.text, done.turns.last().text)
        assertFalse(done.active)
    }

    @Test
    fun `真实增量与其完成快照都不重播`() {
        val reveal = PseudoStreamReveal()
        reveal.frame(emptyList(), emptyList(), true, 0)
        val first = turn("stream", "实", open = true)
        assertEquals("实", reveal.frame(listOf(first), listOf(first), true, 10).turns.single().text)
        val delta = first.copy(text = "实时", open = true)
        assertEquals("实时", reveal.frame(listOf(delta), listOf(delta), true, 20).turns.single().text)
        val completed = delta.copy(text = "实时回答", open = false)
        assertEquals(completed.text, reveal.frame(listOf(completed), listOf(completed), true, 30).turns.single().text)
    }

    @Test
    fun `思考和工具摘要渐显_错误和提问立即可见`() {
        val reveal = PseudoStreamReveal()
        reveal.frame(emptyList(), emptyList(), true, 0)
        val thinking = turn("think", "正在思考", AgentTurnKind.THINKING)
        val tool = turn("tool", "正在读取", AgentTurnKind.TOOL)
        val error = turn("error", "执行失败", AgentTurnKind.ERROR)
        val question = turn("question", "请确认", AgentTurnKind.QUESTION)
        val all = listOf(thinking, tool, error, question)
        val frame = reveal.frame(all, all, true, 10)
        assertEquals("正", frame.turns[0].text)
        assertEquals("", frame.turns[1].text)
        assertEquals("执行失败", frame.turns[2].text)
        assertEquals("请确认", frame.turns[3].text)
    }

    @Test
    fun `回看和切出后直接显示全文_不补播`() {
        val reveal = PseudoStreamReveal()
        reveal.frame(emptyList(), emptyList(), true, 0)
        val next = turn("new", "一段完整内容")
        reveal.frame(listOf(next), listOf(next), true, 10)
        assertEquals(next.text, reveal.frame(listOf(next), listOf(next), false, 20).turns.single().text)
        assertEquals(next.text, reveal.frame(listOf(next), listOf(next), true, 30).turns.single().text)
    }

    @Test
    fun `回看冻结画面时后台新增内容_恢复跟随直接接全文`() {
        val reveal = PseudoStreamReveal()
        val old = turn("old", "正在看的回答")
        reveal.frame(listOf(old), listOf(old), true, 0)
        val next = turn("new", "回看期间完成")
        val live = listOf(old, next)
        assertEquals(listOf(old), reveal.frame(live, listOf(old), false, 10).turns)
        assertEquals(live, reveal.frame(live, live, true, 20).turns)
    }

    @Test
    fun `后取回的历史和折叠后展开的过程不补播`() {
        val reveal = PseudoStreamReveal()
        reveal.frame(emptyList(), emptyList(), true, 0)
        val old = turn("old", "旧历史")
        assertEquals(old.text, reveal.frame(listOf(old), listOf(old), true, 10, setOf("old")).turns.single().text)
        val hidden = turn("hidden", "旧过程", AgentTurnKind.THINKING)
        reveal.frame(listOf(old, hidden), listOf(old), true, 20)
        assertEquals(hidden.text, reveal.frame(listOf(old, hidden), listOf(old, hidden), true, 30).turns.last().text)
    }

    @Test
    fun `历史插入前部不补播_尾部新回答仍显现`() {
        val reveal = PseudoStreamReveal()
        val current = turn("current", "当前")
        reveal.frame(listOf(current), listOf(current), true, 0)
        val old = turn("older", "更早的回答")
        val next = turn("next", "刚到的回答")
        val turns = listOf(old, current, next)
        val frame = reveal.frame(turns, turns, true, 10)
        assertEquals(old.text, frame.turns.first().text)
        assertEquals("刚", frame.turns.last().text)
    }

    @Test
    fun `链接与围栏代码不会暴露半截结构`() {
        val reveal = PseudoStreamReveal()
        reveal.frame(emptyList(), emptyList(), true, 0)
        val link = turn("link", "[文档](https://example.com) 后续")
        assertEquals("[文档](https://example.com)", reveal.frame(listOf(link), listOf(link), true, 10).turns.single().text)
        reveal.finish()
        val code = turn("code", "```kotlin\nval value = 1\n```\n后续")
        assertEquals("```kotlin\nval value = 1\n```\n", reveal.frame(listOf(link, code), listOf(link, code), true, 20).turns.last().text)
    }

    @Test
    fun `组合 Emoji 与表格不拆成破碎结构`() {
        val reveal = PseudoStreamReveal()
        reveal.frame(emptyList(), emptyList(), true, 0)
        val emoji = turn("emoji", "👨‍👩‍👧‍👦 后续")
        assertEquals("👨‍👩‍👧‍👦", reveal.frame(listOf(emoji), listOf(emoji), true, 10).turns.single().text)
        reveal.finish()
        val table = turn("table", "名称|值\n---|---\n一|二\n尾注")
        assertEquals("名称|值\n---|---\n一|二\n", reveal.frame(listOf(emoji, table), listOf(emoji, table), true, 20).turns.last().text)
    }

    @Test
    fun `积压时跳过动画且点击可揭示全文`() {
        val reveal = PseudoStreamReveal()
        reveal.frame(emptyList(), emptyList(), true, 0)
        val turns = (1..4).map { turn("$it", "完整回答$it") }
        val frame = reveal.frame(turns, turns, true, 10)
        assertFalse(frame.active)
        assertEquals(turns, frame.turns)
        val more = turn("more", "立即揭示")
        reveal.frame(turns + more, turns + more, true, 20)
        reveal.finish()
        assertEquals(more.text, reveal.frame(turns + more, turns + more, true, 30).turns.last().text)
    }

    @Test
    fun `积压消失后已显示的前缀不会倒退`() {
        val reveal = PseudoStreamReveal()
        reveal.frame(emptyList(), emptyList(), true, 0)
        val first = turn("first", "正在逐字显示很长的一段内容")
        val second = turn("second", "另一段也很长的内容")
        val both = listOf(first, second)
        reveal.frame(both, both, true, 10)
        val before = reveal.frame(both, both, true, 500).turns.first().text
        val after = reveal.frame(both, listOf(first), true, 510).turns.first().text
        assertTrue(after.startsWith(before))
    }
}
