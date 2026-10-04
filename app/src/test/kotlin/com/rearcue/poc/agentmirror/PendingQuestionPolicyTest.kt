package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentUserQuestion
import com.rearcue.poc.agent.SourceCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PendingQuestionPolicyTest {
    @Test fun `重连的旧题不能覆盖当前已答快照`() {
        val current = AgentSessionState("s", bridgeRevision = 200)
        val oldQuestion = current.copy(bridgeRevision = 100, pendingQuestions = listOf(AgentUserQuestion("old", "已过期")))
        assertEquals(true, AgentStateLogic.isStaleActivity(current, oldQuestion))
        assertFalse(AgentStateLogic.isStaleActivity(current, oldQuestion.copy(bridgeRevision = 300)))
    }
    @Test fun `问题只提醒一次 部分回答不重叫 全部解除可撤旧通知`() {
        val tracker = AgentAlertTracker()
        assertEquals(QuestionAlertChange.NEW, tracker.onQuestions("s", setOf("a", "b")))
        assertEquals(QuestionAlertChange.NONE, tracker.onQuestions("s", setOf("a", "b")))
        assertEquals(QuestionAlertChange.NONE, tracker.onQuestions("s", setOf("b")))
        assertEquals(QuestionAlertChange.CLEARED, tracker.onQuestions("s", emptySet()))
        assertEquals(QuestionAlertChange.NEW, tracker.onQuestions("s", setOf("c")))
    }

    @Test fun `Codex有题仍工作不出现批准按钮 列表排序为待处理`() {
        val session = AgentSessionState("s", source = "codex", status = AgentStatus.WORKING,
            pendingQuestions = listOf(AgentUserQuestion("a", "问题")))
        val capabilities = SourceCapabilities(mapOf("codex" to setOf(SourceCapabilities.APPROVE)))
        assertFalse(AgentApprovePolicy.canApprove(session, capabilities))
        val rows = AgentStateLogic.projectRoster(listOf(AgentSessionState("other", status = AgentStatus.WORKING, updatedAt = 100), session), com.rearcue.poc.core.DashboardEvent.SessionLockMode.Auto)
        assertEquals("s", rows[1].sessionId)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, rows[1].status)
    }
}
