package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentTurnRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 渲染输入的落差兜底判例（spec 0017 / 票 #169 的 Testing Decisions 明确点名这一条）：
 * 桥可能比 App 旧——旧事件只有 `latestReply`、没有 `turns`。此时必须回落成「一条 agent 输出」
 * 照常上屏，而不是黑屏。
 */
class AgentReadingTurnsTest {

    @Test
    fun `有问答流时以问答流为准，latestReply 不再是渲染输入`() {
        val state = AgentSessionState(
            sessionId = "s",
            latestReply = "这条旧字段不该被渲染",
            turns = listOf(
                com.rearcue.poc.agent.AgentTurn(AgentTurnRole.USER, "我的提问"),
                com.rearcue.poc.agent.AgentTurn(AgentTurnRole.AGENT, "它的回答"),
            ),
        )
        assertEquals(
            listOf(AgentTurnRole.USER to "我的提问", AgentTurnRole.AGENT to "它的回答"),
            state.readingTurns().map { it.role to it.text },
        )
    }

    @Test
    fun `只有旧字段时回落成一条 agent 输出`() {
        val state = AgentSessionState(sessionId = "s", latestReply = "旧桥发来的单条正文")
        val turns = state.readingTurns()
        assertEquals(1, turns.size)
        assertEquals(AgentTurnRole.AGENT, turns.single().role)
        assertEquals("旧桥发来的单条正文", turns.single().text)
    }

    @Test
    fun `旧字段是空白时给空流（不画一条空段落）`() {
        assertTrue(AgentSessionState(sessionId = "s", latestReply = "   ").readingTurns().isEmpty())
        assertTrue(AgentSessionState(sessionId = "s").readingTurns().isEmpty())
    }

    @Test
    fun `旧字段回落时带上会话时间戳（排版不依赖它，但别丢事实）`() {
        val state = AgentSessionState(sessionId = "s", latestReply = "正文", updatedAt = 12345L)
        assertEquals(12345L, state.readingTurns().single().ts)
    }
}
