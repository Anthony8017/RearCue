package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 默认入口由 DefaultSessionListTest 覆盖；这里验证显式选页与既有投送/详情/门控契约。 */
class ContentPageTest {
    private val wechat = "com.tencent.mm"
    private val qq = "com.tencent.mobileqq"
    private fun core(logs: MutableList<String>? = null) = DashboardCore(nowMs = { 0L }, log = { logs?.add(it) })
    private fun working(id: String = "s1", time: Long = 100L) = DashboardEvent.AgentSessionUpdated(
        AgentSessionState(id, status = AgentStatus.WORKING, updatedAt = time),
    )
    private fun waiting(id: String = "s1", time: Long = 100L) = DashboardEvent.AgentSessionUpdated(
        AgentSessionState(id, status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = time),
    )
    private fun post(core: DashboardCore, pkg: String = wechat, key: String = "k1") =
        core.onEvent(DashboardEvent.NotificationPosted(pkg, key, "标题", "正文"))
    private fun remove(core: DashboardCore, pkg: String = wechat, key: String = "k1") =
        core.onEvent(DashboardEvent.NotificationRemoved(pkg, key))
    private fun notificationOnly() = core().apply {
        onEvent(DashboardEvent.ProjectionReady)
        post(this)
        onEvent(DashboardEvent.AgentPickerNotificationShortcut)
    }
    private fun agentOnly() = core().apply {
        onEvent(DashboardEvent.ProjectionReady)
        onEvent(working())
        onEvent(DashboardEvent.SessionLock(DashboardEvent.SessionLockMode.Auto))
    }
    private fun bothPages() = notificationOnly().apply { onEvent(working()) }

    @Test fun `显式通知页与列表可双向切换`() {
        val core = bothPages()
        core.onEvent(DashboardEvent.ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)
        core.onEvent(DashboardEvent.ContentPageToggle)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertFalse(core.agentPicker)
    }

    @Test fun `无会话仍可进入空列表且普通通知更新不推翻选择`() {
        val core = notificationOnly()
        core.onEvent(DashboardEvent.ContentPageToggle)
        assertTrue(core.agentPicker)
        post(core, qq, "k2")
        assertTrue(core.agentPicker)
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `手动空通知页不会被Agent新输出踢走`() {
        val core = agentOnly()
        core.onEvent(DashboardEvent.ContentPageToggle)
        core.onEvent(working(time = 200))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `新通知不离开正在看的Agent正文`() {
        val core = agentOnly()
        post(core)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertFalse(core.agentPicker)
    }

    @Test fun `手动通知页内容消失仍保留选择_恢复也不抢页`() {
        val core = bothPages()
        remove(core)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        post(core)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `手动Agent正文会话消失可停空态但不新增持有理由`() {
        val core = agentOnly()
        post(core)
        core.onEvent(DashboardEvent.AgentSessionsRemoved(setOf("s1")))
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertNull(core.agentState)
    }

    @Test fun `断线保留正文_恢复边沿回列表_重复在线不抢页`() {
        val core = agentOnly()
        core.onEvent(DashboardEvent.AgentConnectionChanged(false))
        assertFalse(core.agentPicker)
        core.onEvent(DashboardEvent.AgentConnectionChanged(true))
        assertTrue(core.agentPicker)
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        core.onEvent(DashboardEvent.AgentConnectionChanged(true))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `链路恢复没有会话但已在屏也回空列表`() {
        val core = notificationOnly()
        core.onEvent(DashboardEvent.AgentConnectionChanged(false))
        core.onEvent(DashboardEvent.AgentConnectionChanged(true))
        assertTrue(core.agentPicker)
        assertNull(core.agentState)
    }

    @Test fun `链路恢复不在屏且无显示理由不投送`() {
        val core = core()
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.AgentConnectionChanged(false))
        assertTrue(core.onEvent(DashboardEvent.AgentConnectionChanged(true)).isEmpty())
        assertNull(core.contentPage)
    }

    @Test fun `批准插队禁止切走_解决恢复原通知详情`() {
        val core = bothPages()
        core.onEvent(DashboardEvent.DetailToggled(wechat))
        remove(core)
        core.onEvent(waiting())
        core.onEvent(DashboardEvent.ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)
        core.onEvent(working(time = 200))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertEquals("k1", core.detail?.key)
    }

    @Test fun `多会话批准全部结束才回原通知页`() {
        val core = bothPages()
        core.onEvent(waiting("a"))
        core.onEvent(waiting("b", 200))
        core.onEvent(working("a", 300))
        assertEquals(ContentPage.AGENT, core.contentPage)
        core.onEvent(working("b", 400))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `批准期间重投不清掉被打断的通知详情`() {
        val core = bothPages()
        core.onEvent(DashboardEvent.DetailToggled(wechat))
        core.onEvent(waiting())
        core.onEvent(DashboardEvent.TakeoverDetected)
        core.onEvent(working(time = 200))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertEquals("k1", core.detail?.key)
    }

    @Test fun `退屏再首投仍默认列表`() {
        val core = notificationOnly()
        core.onEvent(DashboardEvent.ManualExit)
        post(core, qq, "k2")
        assertTrue(core.agentPicker)
    }

    @Test fun `两边都空继续按原规则退出`() {
        val core = agentOnly()
        core.onEvent(DashboardEvent.AgentSessionsRemoved(setOf("s1")))
        assertNull(core.contentPage)
        val notification = notificationOnly()
        remove(notification)
        assertEquals(DashboardCore.EXIT_GRACE_MS, notification.exitGraceDeadlineMs)
    }

    @Test fun `充电只持屏并画背景不抢手动通知页`() {
        val core = bothPages()
        core.onEvent(DashboardEvent.ChargingAnimation(true))
        core.onEvent(DashboardEvent.PowerConnected)
        assertTrue(core.chargingOnScreen)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.ManualCast)
        assertTrue(core.agentPicker)
        assertTrue(core.chargingOnScreen)
    }

    @Test fun `姿态门只拦投送_开门后直接是列表`() {
        val core = core()
        core.onEvent(DashboardEvent.ProjectionReady)
        core.onEvent(DashboardEvent.PostureGateEnabled(true))
        core.onEvent(DashboardEvent.PostureGate(false))
        core.onEvent(working())
        post(core)
        assertNull(core.contentPage)
        core.onEvent(DashboardEvent.PostureGate(true))
        assertTrue(core.agentPicker)
    }

    @Test fun `默认和切换及批准继续使用既有日志词形`() {
        val logs = mutableListOf<String>()
        val core = core(logs)
        core.onEvent(DashboardEvent.ProjectionReady)
        post(core)
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        core.onEvent(waiting())
        core.onEvent(working(time = 200))
        core.onEvent(DashboardEvent.ManualCast)
        assertTrue(logs.contains("content page toggle notification"))
        assertTrue(logs.contains("content page wfa enter notification"))
        assertTrue(logs.contains("content page wfa exit notification"))
        assertTrue(logs.contains("content page reset agent"))
    }
}
