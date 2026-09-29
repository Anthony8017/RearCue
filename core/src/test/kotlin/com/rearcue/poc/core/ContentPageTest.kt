package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.AgentConnectionChanged
import com.rearcue.poc.core.DashboardEvent.AgentSessionUpdated
import com.rearcue.poc.core.DashboardEvent.ContentPageToggle
import com.rearcue.poc.core.DashboardEvent.DetailToggled
import com.rearcue.poc.core.DashboardEvent.ExitGraceElapsed
import com.rearcue.poc.core.DashboardEvent.ManualCast
import com.rearcue.poc.core.DashboardEvent.ManualExit
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.NotificationRemoved
import com.rearcue.poc.core.DashboardEvent.PostureGate
import com.rearcue.poc.core.DashboardEvent.PostureGateEnabled
import com.rearcue.poc.core.DashboardEvent.PowerConnected
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.ProjectionUnavailable
import com.rearcue.poc.core.DashboardEvent.TakeoverDetected
import com.rearcue.poc.core.DashboardEffect.ExitDashboard
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import com.rearcue.poc.core.DashboardEffect.UpdateIconSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 内容页模型判例（spec 0013 / 票 #132）：只断言「事件序列 → 效果序列 + 只读投影」。
 *
 * 分支覆盖：默认页、单向/双向切换、空边 no-op、新内容不抢页、内容消失兜底、恢复不切回、
 * WFA 插队/忽略切换/全部解决回原页、退屏/重投重置、投送门/充电背景不回归、日志锚。
 */
class ContentPageTest {

    private val wechat = "com.tencent.mm"
    private val qq = "com.tencent.mobileqq"

    private fun core(logs: MutableList<String>? = null) = DashboardCore(
        nowMs = { 0L },
        log = { line -> logs?.add(line) },
    )

    private fun working(sessionId: String = "s1", updatedAt: Long = 100L) =
        AgentSessionUpdated(
            AgentSessionState(
                sessionId,
                workspace = "ws",
                status = AgentStatus.WORKING,
                updatedAt = updatedAt,
            ),
        )

    private fun waiting(sessionId: String = "s1", updatedAt: Long = 100L) =
        AgentSessionUpdated(
            AgentSessionState(
                sessionId,
                workspace = "ws",
                status = AgentStatus.WAITING_FOR_APPROVAL,
                updatedAt = updatedAt,
            ),
        )

    private fun idle(sessionId: String = "s1", updatedAt: Long = 999L) =
        AgentSessionUpdated(
            AgentSessionState(sessionId, status = AgentStatus.IDLE, updatedAt = updatedAt),
        )

    private fun post(core: DashboardCore, pkg: String = wechat, key: String = "k1") {
        core.onEvent(NotificationPosted(pkg, key, "标题", "正文"))
    }

    private fun remove(core: DashboardCore, pkg: String = wechat, key: String = "k1") {
        core.onEvent(NotificationRemoved(pkg, key))
    }

    private fun notificationOnly(): DashboardCore = core().apply {
        onEvent(ProjectionReady)
        post(this)
    }

    private fun agentOnly(): DashboardCore = core().apply {
        onEvent(ProjectionReady)
        onEvent(working())
    }

    private fun bothPages(): DashboardCore = core().apply {
        onEvent(ProjectionReady)
        post(this)
        onEvent(working())
    }

    // ---------- 默认页与手动切换 ----------

    @Test
    fun `首投时两页都有内容默认通知页`() {
        val core = core()
        core.onEvent(working())
        post(core)

        assertTrue(core.onEvent(ProjectionReady).any { it is LaunchDashboard })
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertEquals(CastSource.AGENT, core.castSource)
    }

    @Test
    fun `两页都有内容默认通知页`() {
        val core = bothPages()

        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertEquals(CastSource.AGENT, core.castSource) // 投送记账与内容页选择彼此独立
    }

    @Test
    fun `只有一边有内容时显示该页`() {
        val notification = notificationOnly()
        assertEquals(ContentPage.NOTIFICATION, notification.contentPage)

        val agent = agentOnly()
        assertEquals(ContentPage.AGENT, agent.contentPage)
    }

    @Test
    fun `空白点按可双向切换`() {
        val core = bothPages()

        assertEquals(emptyList(), core.onEvent(ContentPageToggle))
        assertEquals(ContentPage.AGENT, core.contentPage)

        assertEquals(emptyList(), core.onEvent(ContentPageToggle))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test
    fun `另一边为空时点按 no-op`() {
        val notification = notificationOnly()
        notification.onEvent(ContentPageToggle)
        assertEquals(ContentPage.NOTIFICATION, notification.contentPage)

        val agent = agentOnly()
        agent.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, agent.contentPage)
    }

    // ---------- 新内容不抢页 ----------

    @Test
    fun `新通知到达不离开 Agent 页`() {
        val core = agentOnly()
        assertEquals(ContentPage.AGENT, core.contentPage)

        post(core, qq, "q1")

        assertEquals(ContentPage.AGENT, core.contentPage)
        assertEquals(setOf(qq), core.iconSet.toSet())
    }

    @Test
    fun `agent 新输出不离开通知页`() {
        val core = notificationOnly()
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)

        core.onEvent(working())

        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertEquals(CastSource.AGENT, core.castSource)
    }

    // ---------- 内容消失兜底与恢复不切回 ----------

    @Test
    fun `通知页内容消失兜底 Agent 页_通知恢复不切回`() {
        val core = bothPages()

        remove(core, wechat, "k1")
        assertEquals(ContentPage.AGENT, core.contentPage)

        post(core, wechat, "k2")
        assertEquals(ContentPage.AGENT, core.contentPage)
    }

    @Test
    fun `Agent 页内容消失兜底通知页_Agent 恢复不切回`() {
        val core = bothPages()
        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)

        core.onEvent(AgentConnectionChanged(connected = false))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)

        core.onEvent(AgentConnectionChanged(connected = true))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test
    fun `两边都空按既有规则退屏`() {
        val now = LongArray(1)
        val core = DashboardCore(nowMs = { now[0] })
        core.onEvent(ProjectionReady)
        post(core)

        assertEquals(listOf(UpdateIconSet(emptySet())), core.onEvent(NotificationRemoved(wechat, "k1")))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        now[0] = DashboardCore.EXIT_GRACE_MS
        assertEquals(listOf(ExitDashboard), core.onEvent(ExitGraceElapsed))
        assertNull(core.castSource)
        assertNull(core.contentPage)

        val agent = agentOnly()
        assertEquals(listOf(ExitDashboard), agent.onEvent(AgentConnectionChanged(connected = false)))
        assertNull(agent.contentPage)
    }

    // ---------- Waiting-for-Approval 自动例外 ----------

    @Test
    fun `WFA 自动跳 Agent 页_期间忽略切换_解决后回原页且详情仍开`() {
        val core = core()
        core.onEvent(ProjectionReady)
        post(core)
        core.onEvent(DetailToggled(wechat))
        val detailBefore = core.detail
        remove(core, wechat, "k1") // 点开即消回执：图标空、详情保留
        assertEquals(emptyList(), core.iconSet)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)

        core.onEvent(waiting())
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentOnScreen)

        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage) // 存续期忽略切换

        core.onEvent(idle())
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertEquals(detailBefore, core.detail)
    }

    @Test
    fun `多会话 WFA 期间一直守 Agent 页_全部解决才回原页`() {
        val core = core()
        core.onEvent(ProjectionReady)
        post(core)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))

        core.onEvent(waiting(sessionId = "a", updatedAt = 101L))
        core.onEvent(waiting(sessionId = "b", updatedAt = 102L))
        assertEquals(ContentPage.AGENT, core.contentPage)

        core.onEvent(idle(sessionId = "a"))
        assertEquals(ContentPage.AGENT, core.contentPage)

        core.onEvent(idle(sessionId = "b"))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test
    fun `WFA 从 Agent 页进入_解决后仍回 Agent 页`() {
        val core = agentOnly()
        core.onEvent(waiting())
        assertEquals(ContentPage.AGENT, core.contentPage)

        core.onEvent(idle())
        assertEquals(ContentPage.AGENT, core.contentPage)
    }

    @Test
    fun `WFA 原页内容消失_解决后按兜底规则处理`() {
        val core = bothPages()
        core.onEvent(waiting())
        assertEquals(ContentPage.AGENT, core.contentPage)

        remove(core, wechat, "k1")
        assertEquals(ContentPage.AGENT, core.contentPage)

        core.onEvent(idle())
        assertEquals(ContentPage.AGENT, core.contentPage) // 原通知页无内容 → 兜底 Agent 页
    }

    // ---------- 生命周期重置 ----------

    @Test
    fun `手动重投后回默认页`() {
        val core = bothPages()
        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(ManualCast))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test
    fun `退屏再投送回默认页`() {
        val core = bothPages()
        core.onEvent(ContentPageToggle)

        assertEquals(listOf(ExitDashboard), core.onEvent(ManualExit))
        assertNull(core.contentPage)

        assertTrue(core.onEvent(ManualCast).any { it is LaunchDashboard })
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test
    fun `Takeover 重投后回默认页`() {
        val core = bothPages()
        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)

        assertTrue(core.onEvent(TakeoverDetected).any { it is LaunchDashboard })
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test
    fun `通道恢复重投后回默认页`() {
        val core = bothPages()
        core.onEvent(ContentPageToggle)
        core.onEvent(ProjectionUnavailable)
        assertNull(core.contentPage)

        assertTrue(core.onEvent(ProjectionReady).any { it is LaunchDashboard })
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    // ---------- 既有投送规则不回归 ----------

    @Test
    fun `充电背景不参与内容页选择`() {
        val core = core()
        core.onEvent(PowerConnected)
        core.onEvent(ProjectionReady)
        post(core)
        core.onEvent(working())

        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.chargingOnScreen)
    }

    @Test
    fun `姿态门仍只拦投送_开门后按默认内容页投出`() {
        val core = core()
        core.onEvent(PostureGateEnabled(true))
        core.onEvent(PostureGate(faceDown = false))
        core.onEvent(ProjectionReady)
        post(core)
        core.onEvent(working())
        assertNull(core.contentPage)

        assertTrue(core.onEvent(PostureGate(faceDown = true)).any { it is LaunchDashboard })
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    // ---------- 日志锚 ----------

    @Test
    fun `内容页变化日志覆盖 toggle fallback WFA reset`() {
        val logs = mutableListOf<String>()
        val core = DashboardCore(nowMs = { 0L }, log = logs::add)
        core.onEvent(ProjectionReady)
        post(core)
        core.onEvent(working())

        core.onEvent(ContentPageToggle)
        assertTrue(logs.contains("content page toggle agent"), "logs=$logs")

        core.onEvent(ManualCast)
        assertTrue(logs.contains("content page reset notification"), "logs=$logs")

        core.onEvent(waiting())
        assertTrue(logs.contains("content page wfa enter notification"), "logs=$logs")
        core.onEvent(idle())
        assertTrue(logs.contains("content page wfa exit notification"), "logs=$logs")

        val fallbackLogs = mutableListOf<String>()
        val fallbackCore = DashboardCore(nowMs = { 0L }, log = fallbackLogs::add)
        fallbackCore.onEvent(ProjectionReady)
        post(fallbackCore)
        fallbackCore.onEvent(working())
        fallbackCore.onEvent(NotificationRemoved(wechat, "k1"))
        assertTrue(fallbackLogs.contains("content page fallback agent"), "logs=$fallbackLogs")
    }
}
