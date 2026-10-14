package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentUserQuestion
import com.rearcue.poc.core.DashboardEvent.ContentPageToggle
import com.rearcue.poc.core.DashboardEvent.DetailToggled
import com.rearcue.poc.core.DashboardEvent.DismissAppNotifications
import com.rearcue.poc.core.DashboardEvent.IconClearReturnElapsed
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.NotificationRemoved
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.SessionLock
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IconClearReturnTest {
    private val app = "com.tencent.mm"
    private val other = "com.tencent.mobileqq"
    private var now = 0L

    private fun core(priority: Boolean = false) = DashboardCore(
        initialAllowlist = setOf(app, other),
        nowMs = { now },
    ).apply {
        onEvent(ProjectionReady)
        if (priority) onEvent(DashboardEvent.PriorityApps(setOf(app)))
        onEvent(session())
    }

    private fun session(
        status: AgentStatus = AgentStatus.WORKING,
        questions: List<AgentUserQuestion> = emptyList(),
        source: String? = null,
    ) = DashboardEvent.AgentSessionUpdated(
        AgentSessionState(
            "s1",
            status = status,
            updatedAt = 1L,
            source = source,
            pendingQuestions = questions,
        ),
    )

    private fun post(core: DashboardCore, key: String = "k1", pkg: String = app, clearable: Boolean = true) {
        core.onEvent(NotificationPosted(pkg, key, "标题", "正文", clearable = clearable))
    }

    private fun clear(core: DashboardCore, key: String = "k1", pkg: String = app) {
        core.onEvent(DismissAppNotifications(pkg))
        core.onEvent(NotificationRemoved(pkg, key))
    }

    private fun elapse(core: DashboardCore) {
        now += DashboardCore.EXIT_GRACE_MS
        core.onEvent(IconClearReturnElapsed)
    }

    @Test fun `清光并等退场结束后回会话列表且不改选中`() {
        val core = core()
        post(core)
        core.onEvent(SessionLock(SessionLockMode.Locked("s1")))
        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)

        clear(core)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertEquals(DashboardCore.EXIT_GRACE_MS, core.iconClearReturnDeadlineMs)
        core.onEvent(ContentPageToggle)
        core.onEvent(DetailToggled(app))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertNull(core.detail)

        elapse(core)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)
        assertEquals(SessionLockMode.Locked("s1"), core.sessionLock)
        assertNull(core.iconClearReturnDeadlineMs)
    }

    @Test fun `还有别的图标或划不掉的不回列表`() {
        val core = core()
        post(core)
        post(core, "q1", other)
        core.onEvent(ContentPageToggle)
        clear(core)
        assertNull(core.iconClearReturnDeadlineMs)
        elapse(core)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)

        val stuck = core()
        post(stuck, "k1", clearable = true)
        post(stuck, "k2", clearable = false)
        stuck.onEvent(ContentPageToggle)
        stuck.onEvent(DismissAppNotifications(app))
        stuck.onEvent(NotificationRemoved(app, "k1"))
        assertNull(stuck.iconClearReturnDeadlineMs)
        assertEquals(ContentPage.NOTIFICATION, stuck.contentPage)
    }

    @Test fun `下拉栏划掉或点开详情不回列表`() {
        val core = core()
        post(core)
        core.onEvent(ContentPageToggle)
        core.onEvent(NotificationRemoved(app, "k1"))
        elapse(core)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)

        val detail = core()
        post(detail)
        detail.onEvent(ContentPageToggle)
        detail.onEvent(DetailToggled(app))
        detail.onEvent(NotificationRemoved(app, "k1"))
        assertNull(detail.iconClearReturnDeadlineMs)
        assertEquals(app, detail.detail?.app)
    }

    @Test fun `退场途中来新通知就留下`() {
        val core = core()
        post(core)
        core.onEvent(ContentPageToggle)
        clear(core)
        post(core, "k2")
        assertNull(core.iconClearReturnDeadlineMs)
        elapse(core)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertTrue(app in core.iconSet)
    }

    @Test fun `没有留屏理由时不闪列表并照旧收回`() {
        val core = DashboardCore(initialAllowlist = setOf(app), nowMs = { now }).apply {
            onEvent(ProjectionReady)
        }
        post(core)
        core.onEvent(ContentPageToggle)
        clear(core)
        assertNull(core.iconClearReturnDeadlineMs)
        assertEquals(DashboardCore.EXIT_GRACE_MS, core.exitGraceDeadlineMs)
        now += DashboardCore.EXIT_GRACE_MS
        core.onEvent(DashboardEvent.ExitGraceElapsed)
        assertNull(core.contentPage)
        assertFalse(core.agentPicker)
    }

    @Test fun `只有充电留屏时回空列表`() {
        val core = DashboardCore(initialAllowlist = setOf(app), nowMs = { now }).apply {
            onEvent(ProjectionReady)
            onEvent(DashboardEvent.PowerConnected)
        }
        post(core)
        core.onEvent(ContentPageToggle)
        clear(core)
        elapse(core)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)
        assertNull(core.agentState)
        assertTrue(core.chargingOnScreen)
    }

    @Test fun `待确认先看完再回列表 途中的优先通知改去通知页`() {
        val core = core(priority = true)
        post(core)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        clear(core)
        core.onEvent(session(AgentStatus.WAITING_FOR_APPROVAL))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        elapse(core)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertFalse(core.agentPicker)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, core.agentState?.status)

        post(core, "next")
        core.onEvent(session())
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertFalse(core.agentPicker)
    }

    @Test fun `待确认期间的普通新通知不抢 结束后仍回列表`() {
        val core = core(priority = true)
        post(core)
        clear(core)
        core.onEvent(session(AgentStatus.WAITING_FOR_APPROVAL))
        elapse(core)
        post(core, "plain", other)
        core.onEvent(session())
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)
        assertTrue(other in core.iconSet)
    }

    @Test fun `待答不因此弹出`() {
        val core = core()
        post(core)
        core.onEvent(ContentPageToggle)
        clear(core)
        core.onEvent(
            session(
                questions = listOf(AgentUserQuestion("q", "要哪个")),
                source = "claude",
            ),
        )
        elapse(core)
        assertTrue(core.agentPicker)
        assertEquals(ContentPage.AGENT, core.contentPage)
    }

    @Test fun `语音途中自己改页后就留在那里`() {
        val core = core()
        post(core)
        core.onEvent(ContentPageToggle)
        clear(core)
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("s1"))
        elapse(core)
        assertFalse(core.agentPicker)
        core.onEvent(ContentPageToggle)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertFalse(core.agentPicker)
    }

    @Test fun `回到列表后下一条优先通知仍会拉走`() {
        val core = core(priority = true)
        post(core)
        clear(core)
        elapse(core)
        assertTrue(core.agentPicker)
        post(core, "again")
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertFalse(core.agentPicker)
    }
}
