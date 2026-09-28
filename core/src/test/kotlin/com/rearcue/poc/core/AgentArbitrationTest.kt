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
 * Agent Mirror 仲裁测试（spec 0010 / 票 #83）：AGENT 源的独立触发、优先级链
 * （WaitingForApproval > Working > Charging > AUTO）、门控语义（受姿态门）、
 * 空闲/断连回落。只断言「事件序列 → 效果序列 + 只读投影」。
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

    // ---------- 空闲/断连回落 ----------

    @Test
    fun `空闲回落——有通知交还 auto 留屏_无通知判退`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        readyUp(core)
        core.onEvent(working())
        assertEquals(CastSource.AGENT, core.castSource)

        // 回落 1：会话转 Idle，通知还在 → 交还 auto 留屏（不退出）
        val effects = core.onEvent(idle())
        assertEquals(emptyList(), effects)
        assertEquals(CastSource.AUTO, core.castSource)
        assertFalse(core.agentOnScreen)

        // 回落 2：再一轮工作→Idle，通知已清 → 判退（交还 auto 后统一出口判 Icon Set 空）
        core.onEvent(working(sessionId = "s2"))
        assertEquals(CastSource.AGENT, core.castSource)
        core.onEvent(NotificationRemoved(wechat, "k1"))
        val exitEffects = core.onEvent(idle("s2"))
        assertEquals(listOf(ExitDashboard), exitEffects)
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
    fun `重连本身不投_恢复不是到达`() {
        val core = core()
        readyUp(core)
        core.onEvent(AgentConnectionChanged(connected = true))
        assertNull(core.castSource)
    }

    // ---------- 优先级链 ----------

    @Test
    fun `充电在屏被 agent 插队_agent 理由消失后充电收回`() {
        val core = core()
        readyUp(core)
        core.onEvent(PowerConnected)
        assertEquals(CastSource.CHARGING, core.castSource)

        // agent 理由出现 → 改记 AGENT（不重投，内容投影切换）
        assertEquals(emptyList(), core.onEvent(working()))
        assertEquals(CastSource.AGENT, core.castSource)
        assertFalse(core.chargingOnScreen)
        assertTrue(core.agentOnScreen)

        // agent 理由消失 → 充电理由还在 → 收回 charging
        core.onEvent(idle())
        assertEquals(CastSource.CHARGING, core.castSource)
    }

    @Test
    fun `agent 在屏时插电不改记充电`() {
        val core = core()
        readyUp(core)
        core.onEvent(working())
        assertEquals(CastSource.AGENT, core.castSource)
        core.onEvent(PowerConnected)
        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.agentOnScreen)
        assertFalse(core.chargingOnScreen)

        // agent 结束后充电自然接管
        core.onEvent(idle())
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
