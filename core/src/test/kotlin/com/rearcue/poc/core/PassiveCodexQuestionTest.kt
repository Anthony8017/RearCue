package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentUserQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassiveCodexQuestionTest {
    private val question = AgentSessionState(
        "codex-question", source = "codex", status = AgentStatus.WORKING, updatedAt = 20,
        pendingQuestions = listOf(AgentUserQuestion("call:0", "选哪个？", listOf("甲", "乙"))),
    )

    @Test fun `新题不关闭列表 不换会话 不改变手动通知页`() {
        val core = DashboardCore(nowMs = { 0L })
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.AgentSessionUpdated(AgentSessionState("original", source = "codex", status = AgentStatus.WORKING)))
        val original = core.agentState?.sessionId
        assertTrue(core.agentPicker)
        core.onEvent(DashboardEvent.AgentSessionUpdated(question))
        assertTrue(core.agentPicker)
        assertEquals(original, core.agentState?.sessionId)
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        core.onEvent(DashboardEvent.AgentSessionUpdated(question.copy(updatedAt = 30)))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `新题不唤醒已退出的Dashboard`() {
        val core = DashboardCore(nowMs = { 0L })
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.ManualExit)
        val effects = core.onEvent(DashboardEvent.AgentSessionUpdated(question))
        assertFalse(effects.any { it is DashboardEffect.LaunchDashboard })
        assertFalse(core.agentOnScreen)
    }

    @Test fun `真正批准仍按既有规则插队`() {
        val core = DashboardCore(nowMs = { 0L })
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.AgentSessionUpdated(question))
        core.onEvent(DashboardEvent.AgentSessionUpdated(question.copy(status = AgentStatus.WAITING_FOR_APPROVAL)))
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertFalse(core.agentPicker)
    }
}
