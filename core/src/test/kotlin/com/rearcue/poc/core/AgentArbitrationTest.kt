package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.AgentConnectionChanged
import com.rearcue.poc.core.DashboardEvent.AgentSessionUpdated
import com.rearcue.poc.core.DashboardEvent.ManualCast
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
 * Agent Mirror 仲裁测试（spec 0010 / 票 #83；内容层重排 grilling #112）：
 * AGENT 源的独立触发（理由 = 连接在线且有在册会话，空闲也持屏）、门控语义（受姿态门）、
 * 断连回落、内容层选择 [DashboardCore.agentContentOnScreen]（WFA > 通知 > agent）。
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

    // ---------- 在线即显示 / 断连回落（grilling #112：空闲不再回落） ----------

    @Test
    fun `在线即显示——空闲不回落仍持屏镜像，断连才交还 auto`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())
        assertEquals(CastSource.AGENT, core.castSource)

        // 空闲不再回落（连接在线即显示）：AGENT 持有不放，镜像投影空闲残影。
        assertEquals(emptyList(), core.onEvent(idle()))
        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.agentOnScreen)
        assertEquals(AgentStatus.IDLE, core.agentState?.status)

        // 断连 → 交还 auto 留屏（有通知）。
        assertEquals(emptyList(), core.onEvent(AgentConnectionChanged(connected = false)))
        assertEquals(CastSource.AUTO, core.castSource)
        assertFalse(core.agentOnScreen)
    }

    @Test
    fun `断连且无通知判退（零打扰回落）`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())
        core.onEvent(NotificationRemoved(wechat, "k1")) // AGENT 持有：留屏、图标面刷空
        assertEquals(CastSource.AGENT, core.castSource)

        assertEquals(listOf(ExitDashboard), core.onEvent(AgentConnectionChanged(connected = false)))
        assertNull(core.castSource)
    }

    @Test
    fun `断连回落_零打扰交还 auto`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())
        assertEquals(CastSource.AGENT, core.castSource)

        val effects = core.onEvent(AgentConnectionChanged(connected = false))
        assertEquals(emptyList(), effects)
        assertEquals(CastSource.AUTO, core.castSource)
    }

    @Test
    fun `重连本身不投——无在册会话时恢复不是到达`() {
        val core = core()
        readyUp(core)
        core.onEvent(AgentConnectionChanged(connected = true))
        assertNull(core.castSource) // 无内容可镜像：等首条会话事实
    }

    @Test
    fun `重连时有在册会话即补投（在线即显示，含空闲）`() {
        val core = core()
        readyUp(core)
        core.onEvent(working())
        core.onEvent(AgentConnectionChanged(connected = false))
        assertNull(core.castSource)

        val effects = core.onEvent(AgentConnectionChanged(connected = true))
        assertEquals(listOf(LaunchDashboard(emptySet())), effects)
        assertEquals(CastSource.AGENT, core.castSource)
    }

    // ---------- 内容层选择（grilling #112：WFA > 通知 > agent） ----------

    @Test
    fun `有通知时显示通知——agent 工作中也不插队`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())

        assertEquals(CastSource.AGENT, core.castSource) // 记账：agent 持有
        assertFalse(core.agentContentOnScreen) // 内容：通知层显示
    }

    @Test
    fun `等确认插队——压过通知显示镜像`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(waiting())

        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.agentContentOnScreen)
    }

    @Test
    fun `无通知且在线显示镜像——空闲残影也算`() {
        val core = core()
        readyUp(core)
        core.onEvent(working())
        assertTrue(core.agentContentOnScreen)

        core.onEvent(idle()) // 空闲残影：仍显示
        assertTrue(core.agentContentOnScreen)

        core.onEvent(AgentConnectionChanged(connected = false)) // 断连回落：图层灭
        assertFalse(core.agentContentOnScreen)
    }

    @Test
    fun `点开即消后图标空但详情在屏——通知层不撤给 agent`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working()) // agent 持有；有通知 → 通知层
        core.onEvent(DashboardEvent.DetailToggled(wechat)) // 打开即消
        core.onEvent(NotificationRemoved(wechat, "k1")) // 自发消除回执：图标空、详情保留

        assertEquals(emptyList(), core.iconSet)
        assertFalse(core.agentContentOnScreen) // 详情还是通知内容 → 不切 agent
        core.onEvent(DashboardEvent.DetailToggled(wechat)) // 用户收起
        assertTrue(core.agentContentOnScreen) // 通知面没了 → agent 残影上台
    }

    // ---------- 优先级链（投送记账面；内容层选择见上节） ----------

    @Test
    fun `充电在屏被 agent 插队_断连后充电收回（水位是背景不随持有权隐现）`() {
        val core = core()
        readyUp(core)
        core.onEvent(PowerConnected)
        assertEquals(CastSource.CHARGING, core.castSource)

        // agent 理由出现 → 改记 AGENT（不重投，持有权切换）；水位转背景仍显示（#112/#114）。
        assertEquals(emptyList(), core.onEvent(working()))
        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.chargingOnScreen)
        assertTrue(core.agentOnScreen)

        // 断连（理由消失）→ 充电理由还在 → 收回 charging。
        core.onEvent(AgentConnectionChanged(connected = false))
        assertEquals(CastSource.CHARGING, core.castSource)
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

        // 断连后充电自然接管持有权。
        core.onEvent(AgentConnectionChanged(connected = false))
        assertEquals(CastSource.CHARGING, core.castSource)
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
