package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.AgentConnectionChanged
import com.rearcue.poc.core.DashboardEvent.AgentRoster
import com.rearcue.poc.core.DashboardEvent.AgentSessionUpdated
import com.rearcue.poc.core.DashboardEvent.ManualCast
import com.rearcue.poc.core.DashboardEvent.ManualExit
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.NotificationRemoved
import com.rearcue.poc.core.DashboardEvent.PostureGate
import com.rearcue.poc.core.DashboardEvent.PostureGateEnabled
import com.rearcue.poc.core.DashboardEvent.PowerConnected
import com.rearcue.poc.core.DashboardEvent.PowerDisconnected
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEffect.ExitDashboard
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import com.rearcue.poc.core.DashboardEffect.UpdateIconSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent Mirror 仲裁测试（spec 0010 / 票 #83；内容页重排 grilling #112）：
 * AGENT 源的独立触发（理由 = 连接在线且有在册会话，空闲也持屏）、门控语义（受姿态门）、
 * 断连回落、内容页选择 [DashboardCore.contentPage]（spec 0013：通知页 / Agent 页平权，
 * Waiting-for-Approval 自动插队）。
 * 只断言「事件序列 → 效果序列 + 只读投影」。
 */
class AgentArbitrationTest {

    private val wechat = "com.tencent.mm"

    private fun core() = DashboardCore(nowMs = { 0L })

    private fun working(sessionId: String = "s1", updatedAt: Long = 100L) =
        AgentSessionUpdated(AgentSessionState(sessionId, workspace = "ws", status = AgentStatus.WORKING, updatedAt = updatedAt))

    private fun waiting(sessionId: String = "s1", updatedAt: Long = 100L) =
        AgentSessionUpdated(
            AgentSessionState(
                sessionId,
                workspace = "ws",
                status = AgentStatus.WAITING_FOR_APPROVAL,
                currentAction = "bash rm -rf build",
                updatedAt = updatedAt,
            ),
        )

    private fun idle(sessionId: String = "s1") =
        AgentSessionUpdated(AgentSessionState(sessionId, status = AgentStatus.IDLE, updatedAt = 999L))

    private fun readyUp(core: DashboardCore) {
        core.onEvent(ProjectionReady)
    }

    // ---------- 独立触发与门控 ----------

    @Test
    fun `工作中理由出现_倒扣即自动投送记 AGENT`() {
        val core = core()
        readyUp(core)
        val effects = core.onEvent(working())
        assertEquals(listOf(LaunchDashboard(emptySet())), effects)
        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.agentOnScreen)
    }

    @Test
    fun `正放不投_翻正撤下_倒扣补投`() {
        val core = core()
        core.onEvent(PostureGateEnabled(true)) // 票 #100：默认关=旁路，先开门控
        readyUp(core)
        core.onEvent(PostureGate(faceDown = false))
        assertEquals(emptyList(), core.onEvent(working()))
        assertNull(core.castSource)

        // 姿态回来：agent 理由仍在 → 补投 AGENT（不是 auto）
        val effects = core.onEvent(PostureGate(faceDown = true))
        assertEquals(listOf(LaunchDashboard(emptySet())), effects)
        assertEquals(CastSource.AGENT, core.castSource)
    }

    @Test
    fun `在屏 AGENT 被翻正撤下`() {
        val core = core()
        core.onEvent(PostureGateEnabled(true)) // 票 #100：默认关=旁路，先开门控
        readyUp(core)
        core.onEvent(working())
        val effects = core.onEvent(PostureGate(faceDown = false))
        assertEquals(listOf(ExitDashboard), effects)
        assertNull(core.castSource)
        assertFalse(core.agentOnScreen)
    }

    @Test
    fun `无通知独立触发_通道未就绪不投`() {
        val core = core()
        assertEquals(emptyList(), core.onEvent(working()))
        assertNull(core.castSource)
    }

    // ---------- 有在册会话即显示 / 断线保留（grilling #112：空闲不再回落；票 #166：断线保留） ----------

    @Test
    fun `在线即显示——空闲不回落仍持屏镜像`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())
        assertEquals(CastSource.AGENT, core.castSource)

        // 空闲不再回落（有会话即显示）：AGENT 持有不放，镜像投影空闲残影。
        assertEquals(emptyList(), core.onEvent(idle()))
        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.agentOnScreen)
        assertEquals(AgentStatus.IDLE, core.agentState?.status)
    }

    @Test
    fun `断线保留——桥断了仍持屏显示 Agent 页（票 #166）`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())
        assertEquals(CastSource.AGENT, core.castSource)

        // 断连：内容停最后一帧、理由仍在（背屏靠会话状态标识灰档提示已断开）——不再交还 auto。
        assertEquals(emptyList(), core.onEvent(AgentConnectionChanged(connected = false)))
        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.agentOnScreen)
        assertEquals("s1", core.agentState?.sessionId)

        // 机主手动退出：既有收回路径照旧生效。
        assertEquals(listOf(ExitDashboard), core.onEvent(ManualExit))
        assertNull(core.castSource)
    }

    @Test
    fun `断线且无通知也保留（票 #166，取代原零打扰回落）`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())
        core.onEvent(NotificationRemoved(wechat, "k1")) // AGENT 持有：留屏、图标面刷空
        assertEquals(CastSource.AGENT, core.castSource)

        assertEquals(emptyList(), core.onEvent(AgentConnectionChanged(connected = false)))
        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.agentOnScreen)
    }

    @Test
    fun `断线保留_投影通道降级仍交还（既有收回路径不回归）`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())
        core.onEvent(AgentConnectionChanged(connected = false))
        assertEquals(CastSource.AGENT, core.castSource)

        // 投送通道不可用：撤屏（与链路状态无关）——保留不等于霸屏到底。
        assertEquals(listOf(DashboardEffect.Degrade), core.onEvent(DashboardEvent.ProjectionUnavailable))
        assertNull(core.castSource)
    }

    @Test
    fun `重连本身不投——无在册会话时恢复不是到达`() {
        val core = core()
        readyUp(core)
        core.onEvent(AgentConnectionChanged(connected = true))
        assertNull(core.castSource) // 无内容可镜像：等首条会话事实
    }

    @Test
    fun `重连时有在册会话即补投（有会话即显示，含空闲）`() {
        val core = core()
        readyUp(core)
        core.onEvent(working())
        core.onEvent(AgentConnectionChanged(connected = false))
        // 断线保留（票 #166）：断线期间不交还，重连不产生新的投送
        assertEquals(CastSource.AGENT, core.castSource)

        val effects = core.onEvent(AgentConnectionChanged(connected = true))
        // 断线期间屏没交还 ⇒ 重连不产生新投送（幂等）
        assertEquals(emptyList(), effects)
        assertEquals(CastSource.AGENT, core.castSource)
    }

    // ---------- 内容页选择（spec 0013 / 票 #132：两页平权，WFA 自动例外） ----------

    @Test
    fun `有通知也默认列表——agent普通工作更新不关列表`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())

        assertEquals(CastSource.AGENT, core.castSource) // 记账：agent 持有
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)
    }

    @Test
    fun `等确认插队——压过通知显示 Agent 页`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(waiting())

        assertEquals(CastSource.AGENT, core.castSource)
        assertEquals(ContentPage.AGENT, core.contentPage)
    }

    /**
     * spec 0018-2 / 票 #172：DSH 的审批/提问归一为 waiting 后走**同一套**等待仲裁——
     * 插队显示、处理完回原页，与来源无关（脉冲/Approval Glow 消费 status 不看来源，
     * 视觉零新增；本判例证明 DSH 状态到达同一仲裁面）。
     */
    @Test
    fun `DSH 等待确认插队——与既有来源同语义_处理完回原页`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        val dshWaiting = AgentSessionUpdated(
            AgentSessionState(
                "bridge:dsh-1",
                workspace = "E:/dsh/AgentX",
                status = AgentStatus.WAITING_FOR_APPROVAL,
                currentAction = "要执行 bash rm -rf build",
                updatedAt = 100L,
                source = "dsh",
                summary = "要执行 bash rm -rf build",
            ),
        )
        val effects = core.onEvent(dshWaiting)

        assertEquals(CastSource.AGENT, core.castSource)
        assertEquals(ContentPage.AGENT, core.contentPage, "插队压过通知 effects=$effects")

        // 处理完恢复默认列表，不因通知仍在而抢回通知页。
        core.onEvent(
            AgentSessionUpdated(
                AgentSessionState(
                    "bridge:dsh-1",
                    workspace = "E:/dsh/AgentX",
                    status = AgentStatus.WORKING,
                    updatedAt = 200L,
                    source = "dsh",
                ),
            ),
        )
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)
    }

    @Test
    fun `无通知且在线显示 Agent 页——空闲残影也算`() {
        val core = core()
        readyUp(core)
        core.onEvent(working())
        assertEquals(ContentPage.AGENT, core.contentPage)

        core.onEvent(idle()) // 空闲残影：仍显示
        assertEquals(ContentPage.AGENT, core.contentPage)

        core.onEvent(AgentConnectionChanged(connected = false)) // 断线保留：屏不撤、Agent 页仍显示
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentOnScreen)
    }

    @Test
    fun `点开即消后图标空但详情在屏——通知页不撤给 Agent`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working()) // agent 持有；有通知 → 通知层
        core.onEvent(DashboardEvent.AgentPickerNotificationShortcut)
        core.onEvent(DashboardEvent.DetailToggled(wechat)) // 打开即消
        core.onEvent(NotificationRemoved(wechat, "k1")) // 自发消除回执：图标空、详情保留

        assertEquals(emptyList(), core.iconSet)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage) // 详情还是通知内容 → 不切 Agent
        core.onEvent(DashboardEvent.DetailToggled(wechat)) // 用户收起
        assertEquals(ContentPage.NOTIFICATION, core.contentPage) // 手动通知页保留选择
    }

    // ---------- 投送记账面（内容页选择见上节） ----------

    @Test
    fun `充电在屏被 agent 插队_断线保留期间仍记 AGENT（票 #166）`() {
        val core = core()
        readyUp(core)
        core.onEvent(PowerConnected)
        assertEquals(CastSource.CHARGING, core.castSource)

        // agent 理由出现 → 改记 AGENT（不重投，持有权切换）；水位转背景仍显示（#112/#114）。
        assertEquals(emptyList(), core.onEvent(working()))
        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.chargingOnScreen)
        assertTrue(core.agentOnScreen)

        // 断线保留：理由仍在（内容停最后一帧），充电不抢回；机主手动退出即收回（屏撤，充电留待下一次理由）。
        core.onEvent(AgentConnectionChanged(connected = false))
        assertEquals(CastSource.AGENT, core.castSource)
        core.onEvent(ManualExit)
        assertNull(core.castSource)
    }

    @Test
    fun `agent 在屏时插电不改记充电_水位照常垫底`() {
        val core = core()
        readyUp(core)
        core.onEvent(working())
        assertEquals(CastSource.AGENT, core.castSource)
        core.onEvent(PowerConnected)
        assertEquals(CastSource.AGENT, core.castSource) // 记账不被充电抢
        assertTrue(core.agentOnScreen)
        assertTrue(core.chargingOnScreen) // 背景层：充电期间长垫底（grilling #114 语义）

        // 断线保留期间仍记 AGENT；机主手动退出后屏撤（不再自动接管）。
        core.onEvent(AgentConnectionChanged(connected = false))
        assertEquals(CastSource.AGENT, core.castSource)
        core.onEvent(ManualExit)
        assertNull(core.castSource)
    }

    @Test
    fun `auto 在屏被 agent 插队_通知不清不判退`() {
        val core = core()
        readyUp(core)
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        assertEquals(CastSource.AUTO, core.castSource)

        core.onEvent(working())
        assertEquals(CastSource.AGENT, core.castSource)
    }

    @Test
    fun `MANUAL 在屏不被 agent 抢`() {
        val core = core()
        readyUp(core)
        core.onEvent(ManualCast)
        assertEquals(CastSource.MANUAL, core.castSource)
        core.onEvent(working())
        assertEquals(CastSource.MANUAL, core.castSource)
    }

    @Test
    fun `多会话等确认永远优先_同档取最近活跃`() {
        val core = core()
        readyUp(core)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))
        core.onEvent(waiting(sessionId = "b", updatedAt = 50L))
        assertEquals("b", core.agentState?.sessionId)

        core.onEvent(idle("b"))
        assertEquals("a", core.agentState?.sessionId)

        core.onEvent(AgentSessionUpdated(AgentSessionState("c", status = AgentStatus.WORKING, updatedAt = 200L)))
        assertEquals("c", core.agentState?.sessionId)
    }

    @Test
    fun `等确认理由触发投送与工作同权`() {
        val core = core()
        readyUp(core)
        core.onEvent(waiting())
        assertEquals(CastSource.AGENT, core.castSource)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, core.agentState?.status)
        assertEquals("bash rm -rf build", core.agentState?.currentAction)
    }

    // ---------- 通道恢复 ----------

    @Test
    fun `Degrade 恢复后按优先级重投 agent`() {
        val core = core()
        readyUp(core)
        core.onEvent(working())
        core.onEvent(DashboardEvent.ProjectionUnavailable)
        assertNull(core.castSource)
        val effects = core.onEvent(ProjectionReady)
        assertEquals(listOf(LaunchDashboard(emptySet())), effects)
        assertEquals(CastSource.AGENT, core.castSource)
    }

    @Test
    fun `AGENT 持有期间通知清空留屏并同步空图标面`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())
        assertEquals(CastSource.AGENT, core.castSource)

        val effects = core.onEvent(NotificationRemoved(wechat, "k1"))
        assertEquals(listOf(UpdateIconSet(emptySet())), effects)
        assertEquals(CastSource.AGENT, core.castSource)
    }
}
