package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.AgentConnectionChanged
import com.rearcue.poc.core.DashboardEvent.AgentPickerNotificationShortcut
import com.rearcue.poc.core.DashboardEvent.AgentPickerToggle
import com.rearcue.poc.core.DashboardEvent.AgentSessionUpdated
import com.rearcue.poc.core.DashboardEvent.ContentPageToggle
import com.rearcue.poc.core.DashboardEvent.ManualCast
import com.rearcue.poc.core.DashboardEvent.ManualExit
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.ProjectionUnavailable
import com.rearcue.poc.core.DashboardEvent.SessionLock
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 背屏会话选择器的状态面判例（spec 0016 / 票 #156）：只断言「事件序列 → 只读投影 + 日志锚」。
 *
 * 分支覆盖：标识行开/再点关、非 Agent 页不开、等确认插队自动关、切页/退屏/重投自动关、
 * 不超时自动关（无事件即保持）、列表浮层不改投送记账、日志锚词形。
 */
class AgentPickerTest {

    private val wechat = "com.tencent.mm"
    private val logs = mutableListOf<String>()

    private fun core() = DashboardCore(nowMs = { 0L }, log = { logs += it })

    private fun working(sessionId: String = "s1", updatedAt: Long = 100L) =
        AgentSessionUpdated(
            AgentSessionState(sessionId, workspace = "ws", status = AgentStatus.WORKING, updatedAt = updatedAt),
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

    private fun post(pkg: String = wechat, key: String = "k1") =
        NotificationPosted(pkg, key, "标题", "正文")

    /** Agent 页在显（唯一内容页）且已投送——选择器可开的前置。 */
    private fun agentPage(): DashboardCore = core().apply {
        onEvent(ProjectionReady)
        onEvent(working())
        onEvent(SessionLock(SessionLockMode.Auto))
        logs.clear()
    }

    // ---------- 开与关 ----------

    @Test
    fun `标识行单击开列表_再点关_词形锚`() {
        val core = agentPage()
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertFalse(core.agentPicker)

        assertEquals(emptyList(), core.onEvent(AgentPickerToggle))
        assertTrue(core.agentPicker)
        assertTrue(logs.contains("agent picker open"), "logs=$logs")

        core.onEvent(AgentPickerToggle)
        assertFalse(core.agentPicker)
        assertTrue(logs.contains("agent picker close toggle"), "logs=$logs")
    }

    @Test
    fun `列表是浮层_不改投送记账与内容页`() {        val core = agentPage()
        val castSource = core.castSource
        val contentPage = core.contentPage

        core.onEvent(AgentPickerToggle)
        assertEquals(castSource, core.castSource)
        assertEquals(contentPage, core.contentPage)

        core.onEvent(AgentPickerToggle)
        assertEquals(castSource, core.castSource)
        assertEquals(contentPage, core.contentPage)
    }

    @Test
    fun `选定即关_同档重选也关`() {
        val core = agentPage()
        core.onEvent(AgentPickerToggle)
        assertTrue(core.agentPicker)

        // 点条目 = 选定（走 Session Lock 单入口）+ 关闭
        core.onEvent(SessionLock(SessionLockMode.Locked("s1")))
        assertFalse(core.agentPicker)
        assertEquals(SessionLockMode.Locked("s1"), core.sessionLock)
        assertTrue(logs.contains("agent picker close select"), "logs=$logs")

        // 再开再选「自动」档（同档重选）：列表照样收掉
        core.onEvent(AgentPickerToggle)
        assertTrue(core.agentPicker)
        core.onEvent(SessionLock(SessionLockMode.Auto))
        assertFalse(core.agentPicker)
        assertEquals(SessionLockMode.Auto, core.sessionLock)
        assertEquals(2, logs.count { it == "agent picker close select" }, "logs=$logs")
    }

    @Test
    fun `列表没开时选定不打关闭锚`() {
        val core = agentPage()
        core.onEvent(SessionLock(SessionLockMode.Locked("s1")))
        assertFalse(logs.any { it.startsWith("agent picker close") }, "logs=$logs")
    }

    @Test
    fun `等确认插队期点标识行不开列表_也不打锚`() {
        val core = agentPage()
        core.onEvent(waiting(sessionId = "s2", updatedAt = 50L))
        assertEquals(ContentPage.AGENT, core.contentPage)

        // WFA 存续期忽略开列表（同内容页切换的判例）：不出现「开了又立刻被插队关」的幻影锚
        core.onEvent(AgentPickerToggle)
        assertFalse(core.agentPicker)
        assertEquals(0, logs.count { it == "agent picker open" }, "logs=$logs")
        assertEquals(0, logs.count { it.startsWith("agent picker close") }, "logs=$logs")
    }

    @Test
    fun `内容页不在Agent页时不开列表`() {
        val core = core().apply {
            onEvent(ProjectionReady)
            onEvent(post())
            onEvent(AgentPickerNotificationShortcut)
            logs.clear()
        }
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)

        core.onEvent(AgentPickerToggle)
        assertFalse(core.agentPicker)
        assertFalse(logs.contains("agent picker open"), "logs=$logs")
    }

    @Test
    fun `空态Agent页无在册会话_标识行照样开列表`() {
        // #200：空窗期（链路断/重订阅/真空态）Agent 页画空态但保留标识行——
        // 开列表只看「Agent 页在显 ∧ 非 WFA」，不依赖在册会话。
        val core = core().apply {
            onEvent(ProjectionReady)
            onEvent(post())
            onEvent(AgentPickerNotificationShortcut)
        }
        core.onEvent(ContentPageToggle) // 票 #171：空页也切得过去（手动）
        assertEquals(ContentPage.AGENT, core.contentPage)

        assertTrue(core.agentPicker)
        core.onEvent(AgentPickerToggle)
        assertFalse(core.agentPicker)
        core.onEvent(AgentPickerToggle)
        assertTrue(core.agentPicker, "logs=$logs")
        assertTrue(logs.contains("agent picker open"), "logs=$logs")
    }

    @Test
    fun `退屏后列表关_且不再开`() {
        val core = agentPage()
        core.onEvent(AgentPickerToggle)
        assertTrue(core.agentPicker)

        // 投送通道降级撤下 ⇒ 不在屏（contentPage=null）⇒ 列表随屏收掉
        core.onEvent(ProjectionUnavailable)
        assertEquals(null, core.contentPage)
        assertFalse(core.agentPicker)
        assertTrue(logs.contains("agent picker close page"), "logs=$logs")

        core.onEvent(AgentPickerToggle)
        assertFalse(core.agentPicker)
        // 屏不在，标识行也没有——再点按不会再开（开锚只出现一次）
        assertEquals(1, logs.count { it == "agent picker open" }, "logs=$logs")
    }

    // ---------- 「自动」右侧空白去通知页 ----------

    @Test
    fun `自动右侧空白切通知页_不改会话锁_列表退出`() {
        val core = agentPage()
        core.onEvent(SessionLock(SessionLockMode.Locked("s1")))
        core.onEvent(AgentPickerToggle)
        assertTrue(core.agentPicker)

        assertEquals(emptyList(), core.onEvent(AgentPickerNotificationShortcut))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertFalse(core.agentPicker)
        assertEquals(SessionLockMode.Locked("s1"), core.sessionLock)
        assertTrue(logs.contains("agent picker close page"), "logs=$logs")
        assertTrue(logs.contains("content page toggle notification"), "logs=$logs")
    }

    @Test
    fun `从通知页手动返回_直接恢复会话列表`() {
        val core = agentPage()
        core.onEvent(AgentPickerToggle)
        core.onEvent(AgentPickerNotificationShortcut)
        assertFalse(core.agentPicker)

        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker, "logs=$logs")
        assertEquals(2, logs.count { it == "agent picker open" }, "logs=$logs")
    }

    @Test
    fun `快捷入口不选自动档_自动行文字区仍走原选定入口`() {
        val core = agentPage()
        core.onEvent(SessionLock(SessionLockMode.Locked("s1")))
        core.onEvent(AgentPickerToggle)

        // 右侧空白：只切页，不碰锁。
        core.onEvent(AgentPickerNotificationShortcut)
        assertEquals(SessionLockMode.Locked("s1"), core.sessionLock)
        assertFalse(logs.any { it.startsWith("agent picker select") }, "logs=$logs")

        // 返回列表后点「自动」左侧：仍按原有 SessionLock 入口选自动。
        core.onEvent(ContentPageToggle)
        core.onEvent(SessionLock(SessionLockMode.Auto))
        assertEquals(SessionLockMode.Auto, core.sessionLock)
        assertFalse(core.agentPicker)
        assertTrue(logs.contains("agent picker close select"), "logs=$logs")
    }

    @Test
    fun `等待确认优先_快捷切页在插队期被忽略`() {
        val core = agentPage()
        core.onEvent(waiting(sessionId = "s2", updatedAt = 50L))
        assertEquals(ContentPage.AGENT, core.contentPage)

        assertEquals(emptyList(), core.onEvent(AgentPickerNotificationShortcut))
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertFalse(logs.contains("content page toggle notification"), "logs=$logs")
    }

    @Test
    fun `快捷去通知页后进等待确认_列表仍让位且不改锁`() {
        val core = agentPage()
        core.onEvent(SessionLock(SessionLockMode.Locked("s1")))
        core.onEvent(AgentPickerToggle)
        core.onEvent(AgentPickerNotificationShortcut)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)

        core.onEvent(waiting(sessionId = "s2", updatedAt = 50L))
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertFalse(core.agentPicker)
        assertEquals(SessionLockMode.Locked("s1"), core.sessionLock)
    }

    @Test
    fun `重投后手动返回仍按通用规则开列表`() {
        val core = agentPage()
        core.onEvent(AgentPickerToggle)
        core.onEvent(AgentPickerNotificationShortcut)

        core.onEvent(NotificationPosted(wechat, "k2", "标题", "正文"))
        core.onEvent(ManualCast)
        assertTrue(core.agentPicker)
        core.onEvent(AgentPickerNotificationShortcut)
        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker, "logs=$logs")
    }

    // ---------- 自动关 ----------

    @Test
    fun `会话进等待确认_列表自动关并让位插队`() {
        val core = agentPage()
        core.onEvent(AgentPickerToggle)
        assertTrue(core.agentPicker)

        core.onEvent(waiting(sessionId = "s2", updatedAt = 50L))
        assertFalse(core.agentPicker)
        assertTrue(logs.contains("agent picker close wfa"), "logs=$logs")
        // 插队会话照既有规则显示（内容页由 WFA 投影强制 Agent 页）
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertEquals("s2", core.agentState?.sessionId)
    }

    @Test
    fun `手动切到通知页_列表自动关`() {
        val core = core().apply {
            onEvent(ProjectionReady)
            onEvent(post())
            onEvent(working())
            onEvent(AgentPickerNotificationShortcut)
        }
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)

        // 手动切到 Agent 页时列表默认展开
        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker, "logs=$logs")
        assertTrue(logs.contains("agent picker open"), "logs=$logs")

        // 会话列表是浮层：再点标识行关掉，不影响内容页
        core.onEvent(AgentPickerToggle)
        assertFalse(core.agentPicker)
        assertTrue(logs.contains("agent picker close toggle"), "logs=$logs")

        // 切回通知页后，下一次手动切到 Agent 页仍默认展开
        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertFalse(core.agentPicker)
        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker, "logs=$logs")

        core.onEvent(ContentPageToggle)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertFalse(core.agentPicker)
        assertTrue(logs.contains("agent picker close page"), "logs=$logs")
    }

    @Test
    fun `断线保留_页面还在列表不关；手动退出才关（票 #166）`() {
        val core = agentPage()
        core.onEvent(AgentPickerToggle)
        assertTrue(core.agentPicker)

        // 断线保留：Agent 页照旧在屏 ⇒ 列表不关（内容页没变，旧「断连退屏关列表」不成立）
        core.onEvent(AgentConnectionChanged(connected = false))
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)

        // 机主手动退出 ⇒ 页没了 ⇒ 列表按 page 理由关
        core.onEvent(ManualExit)
        assertEquals(null, core.contentPage)
        assertFalse(core.agentPicker)
        assertTrue(logs.contains("agent picker close page"), "logs=$logs")
    }

    @Test
    fun `不超时自动关_无事件即保持展开`() {
        val core = agentPage()
        core.onEvent(AgentPickerToggle)

        // 无关事件反复到达（图标集刷新、投送就绪）都不收列表——列表不设时限
        core.onEvent(post(pkg = "com.tencent.mobileqq", key = "k9"))
        core.onEvent(ProjectionReady)
        assertTrue(core.agentPicker, "logs=$logs")
    }
}
