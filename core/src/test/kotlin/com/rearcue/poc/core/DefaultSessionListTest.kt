package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentUserQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultSessionListTest {
    private fun session(id: String, status: AgentStatus = AgentStatus.WORKING, time: Long = 1L) =
        AgentSessionState(id, status = status, updatedAt = time)

    private fun core() = DashboardCore(nowMs = { 0L }).apply {
        onEvent(DashboardEvent.ProjectionReady)
        onEvent(DashboardEvent.ManualCast)
    }

    private fun assertList(core: DashboardCore) {
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)
    }

    @Test fun `默认入口覆盖通知单会话锁定及空会话`() {
        for (hasNotification in listOf(false, true)) {
            for (hasSession in listOf(false, true)) {
                val core = core()
                if (hasNotification) core.onEvent(DashboardEvent.NotificationPosted("com.tencent.mm", "k", "t", "b"))
                if (hasSession) {
                    core.onEvent(DashboardEvent.AgentSessionUpdated(session("one")))
                    core.onEvent(DashboardEvent.SessionLock(DashboardEvent.SessionLockMode.Locked("one")))
                }
                core.onEvent(DashboardEvent.ManualCast)
                assertList(core)
            }
        }
    }

    @Test fun `只有通知的自动首投也直接是空列表`() {
        val core = DashboardCore(nowMs = { 0L })
        core.onEvent(DashboardEvent.ProjectionReady)
        assertNull(core.contentPage)
        val effects = core.onEvent(DashboardEvent.NotificationPosted("com.tencent.mm", "k", "t", "b"))
        assertTrue(effects.any { it is DashboardEffect.LaunchDashboard })
        assertList(core)
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.ContentPageToggle)
        assertList(core)
    }

    @Test fun `重投与链路恢复重开列表但普通输出不抢页`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("one")))
        core.onEvent(DashboardEvent.SessionLock(DashboardEvent.SessionLockMode.Auto))
        assertFalse(core.agentPicker)
        core.onEvent(DashboardEvent.TakeoverDetected)
        assertList(core)
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("one", time = 2)))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.AgentConnectionChanged(false))
        core.onEvent(DashboardEvent.AgentConnectionChanged(true))
        assertList(core)
    }

    @Test fun `三类插队重叠并重投后仍回最初列表`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("voice")))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("voice"))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("q", time = 2).copy(
            pendingQuestions = listOf(AgentUserQuestion("q1", "继续？")),
        )))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL, 3)))
        assertEquals("approval", core.agentState?.sessionId)
        assertFalse(core.agentPicker)
        core.onEvent(DashboardEvent.TakeoverDetected)
        core.onEvent(DashboardEvent.AgentConnectionChanged(false))
        core.onEvent(DashboardEvent.AgentConnectionChanged(true))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.IDLE, 4)))
        assertEquals("q", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("q", time = 5)))
        assertEquals("voice", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertList(core)
    }

    @Test fun `每类插队结束均恢复原列表`() {
        for (kind in 0..2) {
            val core = core()
            core.onEvent(DashboardEvent.AgentSessionUpdated(session("one")))
            when (kind) {
                0 -> core.onEvent(DashboardEvent.AgentSessionUpdated(session("one", AgentStatus.WAITING_FOR_APPROVAL)))
                1 -> core.onEvent(DashboardEvent.AgentSessionUpdated(session("one").copy(
                    pendingQuestions = listOf(AgentUserQuestion("q1", "继续？")),
                )))
                else -> core.onEvent(DashboardEvent.VoiceBroadcastStarted("one"))
            }
            assertFalse(core.agentPicker)
            if (kind == 2) core.onEvent(DashboardEvent.VoiceBroadcastFinished)
            else core.onEvent(DashboardEvent.AgentSessionUpdated(session("one", time = 2)))
            assertList(core)
        }
    }

    @Test fun `自动档正文被插队后恢复原会话而非最近插队会话`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("original")))
        core.onEvent(DashboardEvent.SessionLock(DashboardEvent.SessionLockMode.Auto))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("voice", AgentStatus.IDLE, 2)))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("voice"))
        assertEquals("voice", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertFalse(core.agentPicker)
        assertEquals("original", core.agentState?.sessionId)
        assertEquals(DashboardEvent.SessionLockMode.Auto, core.sessionLock)
    }

    @Test fun `源会话失效不能恢复归档正文`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("original")))
        core.onEvent(DashboardEvent.SessionLock(DashboardEvent.SessionLockMode.Auto))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("voice", AgentStatus.IDLE, 2)))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("voice"))
        core.onEvent(DashboardEvent.AgentSessionsRemoved(setOf("original")))
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertList(core)
        assertEquals("voice", core.agentState?.sessionId)
    }

    @Test fun `同组后续播报及结束不覆盖手动通知页`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("a")))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("b", time = 2)))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("a"))
        core.onEvent(DashboardEvent.ContentPageToggle)
        val effects = core.onEvent(DashboardEvent.VoiceBroadcastStarted("b"))
        assertTrue(effects.none { it is DashboardEffect.LaunchDashboard })
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `插队前不在屏时结束回列表且无理由时退屏`() {
        val core = DashboardCore(nowMs = { 0L })
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("one")))
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.ManualExit)
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("one"))
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertList(core)
        val empty = DashboardCore(nowMs = { 0L })
        empty.onEvent(DashboardEvent.ProjectionReady)
        empty.onEvent(DashboardEvent.VoiceBroadcastStarted("missing"))
        empty.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertNull(empty.contentPage)
    }

    @Test fun `插队期间手动退出后同组后续条目不回屏`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("one")))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("one"))
        core.onEvent(DashboardEvent.ManualExit)
        val effects = core.onEvent(DashboardEvent.VoiceBroadcastStarted("one"))
        assertTrue(effects.none { it is DashboardEffect.LaunchDashboard })
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertNull(core.contentPage)
    }

    @Test fun `播报先到而投送通道稍后就绪_无原页面结束仍回列表`() {
        val core = DashboardCore(nowMs = { 0L })
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("one")))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("one"))
        assertNull(core.contentPage)
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertList(core)
    }

    @Test fun `插队期间通道暂失又恢复_仍恢复此前正文而非默认列表`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("one")))
        core.onEvent(DashboardEvent.SessionLock(DashboardEvent.SessionLockMode.Auto))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("voice", AgentStatus.IDLE, 2)))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("voice"))
        core.onEvent(DashboardEvent.ProjectionUnavailable)
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertEquals("one", core.agentState?.sessionId)
        assertFalse(core.agentPicker)
        assertEquals(CastSource.MANUAL, core.castSource)
    }

    @Test fun `归档当前播报只移除来源_后续排队期间不提前回列表`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("a")))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("b", time = 2)))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("a"))
        core.onEvent(DashboardEvent.AgentSessionsRemoved(setOf("a")))
        assertTrue(core.contentInterrupted)
        assertFalse(core.agentPicker)
        assertEquals("b", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("b"))
        assertFalse(core.agentPicker)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertList(core)
    }

    @Test fun `播报来源不明不推翻原通知页`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("missing"))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `播报期间手动重投以列表作为最新选择`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("voice")))
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("voice"))
        core.onEvent(DashboardEvent.ManualCast)
        assertList(core)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertList(core)
    }
}
