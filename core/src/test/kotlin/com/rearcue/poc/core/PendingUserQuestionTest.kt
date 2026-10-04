package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentUserQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingUserQuestionTest {
    private fun state(id: String = "q", questions: List<AgentUserQuestion> = listOf(AgentUserQuestion("call:0", "选哪个？"))) =
        AgentSessionState(id, status = AgentStatus.WORKING, updatedAt = 10, pendingQuestions = questions)

    private fun core() = DashboardCore(nowMs = { 0L }).apply {
        onEvent(DashboardEvent.ProjectionReady)
        onEvent(DashboardEvent.AgentSessionUpdated(state("original", emptyList())))
    }

    @Test fun `新题关闭列表 继续工作不冲掉 答完回原列表与原锁`() {
        val core = core()
        core.onEvent(DashboardEvent.SessionLock(DashboardEvent.SessionLockMode.Locked("original")))
        core.onEvent(DashboardEvent.AgentPickerToggle)
        assertTrue(core.agentPicker)
        core.onEvent(DashboardEvent.AgentSessionUpdated(state()))
        assertFalse(core.agentPicker)
        assertEquals("q", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.AgentSessionUpdated(state().copy(currentAction = "继续工具")))
        assertEquals("q", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.AgentSessionUpdated(state(questions = emptyList())))
        assertTrue(core.agentPicker)
        assertEquals("original", core.agentState?.sessionId)
    }

    @Test fun `未答能开列表 同题不抢屏 回答不覆盖手动通知页`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(state()))
        core.onEvent(DashboardEvent.AgentPickerToggle)
        assertTrue(core.agentPicker)
        core.onEvent(DashboardEvent.AgentSessionUpdated(state().copy(updatedAt = 20)))
        assertTrue(core.agentPicker)
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.AgentSessionUpdated(state(questions = emptyList())))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `部分回答继续显示剩余题 新题可以再次提醒`() {
        val core = core()
        val q = state(questions = listOf(AgentUserQuestion("a", "一"), AgentUserQuestion("b", "二")))
        core.onEvent(DashboardEvent.AgentSessionUpdated(q))
        core.onEvent(DashboardEvent.AgentSessionUpdated(q.copy(pendingQuestions = q.pendingQuestions.drop(1))))
        assertEquals("q", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.ContentPageToggle)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.AgentSessionUpdated(state(questions = listOf(AgentUserQuestion("new", "新题")))))
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertFalse(core.agentPicker)
    }

    @Test fun `真正批准优先且仍禁止切走 归档问题不留插队`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(state()))
        core.onEvent(DashboardEvent.AgentSessionUpdated(state("approval", emptyList()).copy(status = AgentStatus.WAITING_FOR_APPROVAL)))
        assertEquals("approval", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)
        core.onEvent(DashboardEvent.AgentSessionsRemoved(setOf("q", "approval")))
        assertEquals("original", core.agentState?.sessionId)
    }
}
