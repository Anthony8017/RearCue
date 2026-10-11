package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.AgentUserQuestion
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexPassiveReadingTest {
    private val question = AgentUserQuestion("call:0", "选哪个？", listOf("甲", "乙"))

    @Test fun `Codex 新题不替换当前正文或旧版最新回复`() {
        val answer = AgentTurn(role = AgentTurnRole.AGENT, text = "正在阅读的正文", ts = 10)
        val state = AgentSessionState(
            "codex", source = "codex", status = AgentStatus.WORKING,
            turns = listOf(answer), pendingQuestions = listOf(question),
        )
        assertEquals(listOf(answer), state.readingTurns())
        val legacy = state.copy(turns = emptyList(), latestReply = "旧版正文")
        assertEquals("旧版正文", legacy.readingTurns().single().text)
    }

    @Test fun `其他来源维持既有待答正文展示`() {
        val state = AgentSessionState("other", source = "dsh", status = AgentStatus.WORKING, pendingQuestions = listOf(question))
        assertEquals("选哪个？\n- 甲\n- 乙", state.readingTurns().single().text)
    }
}
