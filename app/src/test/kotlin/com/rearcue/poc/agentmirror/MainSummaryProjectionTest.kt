package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentUserQuestion
import com.rearcue.poc.core.UsabilityReason
import kotlin.test.Test
import kotlin.test.assertEquals

class MainSummaryProjectionTest {
    @Test fun `home counts pending sessions once including read-only questions and debug session`() {
        val approval = AgentSessionState("approval", status = AgentStatus.WAITING_FOR_APPROVAL)
        val question = AgentSessionState("question", status = AgentStatus.WORKING,
            pendingQuestions = listOf(AgentUserQuestion("q1", "选择", canAnswer = false)))
        val roster = listOf(approval, approval.copy(title = "same session"), question,
            AgentSessionState("idle"), AgentSessionState("working", status = AgentStatus.WORKING))
        val debug = AgentSessionState("debug", status = AgentStatus.WAITING_FOR_APPROVAL)
        assertEquals(AgentSurfaceProjection.MainSummary(3, true),
            AgentSurfaceProjection.mainSummary(roster, debug, true, true, null))
        assertEquals(AgentSurfaceProjection.MainSummary(2, true),
            AgentSurfaceProjection.mainSummary(roster, approval, true, true, null))
    }

    @Test fun `home shows attention when listener channel or usability requires handling`() {
        assertEquals(AgentSurfaceProjection.MainSummary(0, false),
            AgentSurfaceProjection.mainSummary(emptyList(), null, false, true, null))
        assertEquals(AgentSurfaceProjection.MainSummary(0, false),
            AgentSurfaceProjection.mainSummary(emptyList(), null, true, false, null))
        assertEquals(AgentSurfaceProjection.MainSummary(0, false),
            AgentSurfaceProjection.mainSummary(emptyList(), null, true, true, setOf(UsabilityReason.LISTENER_UNHEALTHY)))
        assertEquals(AgentSurfaceProjection.MainSummary(0, true),
            AgentSurfaceProjection.mainSummary(emptyList(), null, true, true, null))
    }
}
