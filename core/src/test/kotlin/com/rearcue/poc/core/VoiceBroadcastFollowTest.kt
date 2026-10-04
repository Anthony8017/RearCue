package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceBroadcastFollowTest {

    private fun core() = DashboardCore(nowMs = { 0L }, log = {})

    private fun session(
        id: String,
        status: AgentStatus = AgentStatus.IDLE,
        updatedAt: Long = 1L,
    ) = AgentSessionState(sessionId = id, status = status, updatedAt = updatedAt)

    @Test
    fun `播报开始切到来源会话并强制回屏`() {
        val core = core()
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("a", updatedAt = 1)))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("b", updatedAt = 2)))
        core.onEvent(DashboardEvent.PostureGate(faceDown = false))

        val effects = core.onEvent(DashboardEvent.VoiceBroadcastStarted("a"))

        assertTrue(effects.any { it is DashboardEffect.LaunchDashboard })
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertEquals("a", core.agentState?.sessionId)
        assertEquals(CastSource.VOICE, core.castSource)
    }

    @Test
    fun `等待确认视觉优先_处理完回到播报会话`() {
        val core = core()
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("voice", updatedAt = 1)))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("voice"))
        core.onEvent(
            DashboardEvent.AgentSessionUpdated(
                session("approval", AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 2),
            ),
        )

        assertEquals("approval", core.agentState?.sessionId)
        assertEquals(ContentPage.AGENT, core.contentPage)

        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", updatedAt = 3)))

        assertEquals("voice", core.agentState?.sessionId)
        assertEquals(ContentPage.AGENT, core.contentPage)
    }

    @Test
    fun `播完保留最后播报内容_手动切页后让位`() {
        val core = core()
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("voice", updatedAt = 1)))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("voice"))
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)

        assertEquals("voice", core.agentState?.sessionId)
        assertEquals(ContentPage.AGENT, core.contentPage)

        core.onEvent(DashboardEvent.ContentPageToggle)

        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test
    fun `来源会话不存在时只回屏不跳错会话`() {
        val core = core()
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("other", updatedAt = 1)))
        core.onEvent(DashboardEvent.NotificationPosted("com.tencent.mm", "k1", "t", "b"))

        val effects = core.onEvent(DashboardEvent.VoiceBroadcastStarted("missing"))

        assertTrue(effects.any { it is DashboardEffect.LaunchDashboard })
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertEquals("other", core.agentState?.sessionId)
    }

    @Test
    fun `手动退出后不再被播报回屏抢回`() {
        val core = core()
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("voice")))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("voice"))
        core.onEvent(DashboardEvent.ManualExit)

        val effects = core.onEvent(DashboardEvent.TakeoverDetected)

        assertTrue(effects.none { it is DashboardEffect.LaunchDashboard })
    }

    @Test
    fun `多条播报逐条切来源会话`() {
        val core = core()
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("a", updatedAt = 1)))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("b", updatedAt = 2)))

        core.onEvent(DashboardEvent.VoiceBroadcastStarted("a"))
        assertEquals("a", core.agentState?.sessionId)

        core.onEvent(DashboardEvent.VoiceBroadcastStarted("b"))
        assertEquals("b", core.agentState?.sessionId)
    }
}
