package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.AgentSessionUpdated
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEffect.AgentPulse
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 等待确认视觉强调（spec 0010 / 票 #85）：一次强调、30s 冷却、非等确认更新不触发。
 * 插队与优先级本身在 AgentArbitrationTest（票 #83）。
 */
class AgentPulseTest {

    private val clock = longArrayOf(0L)

    private fun core() = DashboardCore(nowMs = { clock[0] })

    private fun waiting(sessionId: String = "s1") = AgentSessionUpdated(
        AgentSessionState(sessionId, status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = clock[0]),
    )

    private fun working(sessionId: String = "s1") = AgentSessionUpdated(
        AgentSessionState(sessionId, status = AgentStatus.WORKING, updatedAt = clock[0]),
    )

    @Test
    fun `进入等待确认_脉冲一次_窗为 3 秒`() {
        val core = core()
        core.onEvent(ProjectionReady)
        val effects = core.onEvent(waiting())
        assertTrue(effects.contains(AgentPulse(3_000L)), "effects=$effects")
    }

    @Test
    fun `冷却窗内再次等确认不重复强调`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(waiting("a"))
        clock[0] += 1_000 // 强调窗内
        val again = core.onEvent(waiting("b"))
        assertEquals(emptyList(), again.filterIsInstance<AgentPulse>())
    }

    @Test
    fun `冷却窗外再入等确认_重新强调`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(waiting("a"))
        clock[0] += DashboardCore.AGENT_PULSE_COOLDOWN_MS + 1
        val effects = core.onEvent(waiting("b"))
        assertTrue(effects.contains(AgentPulse(clock[0] + DashboardCore.AGENT_PULSE_MS)))
    }

    @Test
    fun `工作与空闲更新不触发强调`() {
        val core = core()
        core.onEvent(ProjectionReady)
        assertEquals(emptyList(), core.onEvent(working()).filterIsInstance<AgentPulse>())
        assertEquals(
            emptyList(),
            core.onEvent(
                AgentSessionUpdated(AgentSessionState("s1", status = AgentStatus.IDLE, updatedAt = 1L)),
            ).filterIsInstance<AgentPulse>(),
        )
    }

    @Test
    fun `强调与投送互不干扰`() {
        val core = core()
        core.onEvent(ProjectionReady)
        val effects = core.onEvent(waiting())
        // 一次事件同时产出：投送（AGENT 持有）＋强调——两个语义面并行不悖。
        assertTrue(effects.contains(LaunchDashboard(emptySet())))
        assertTrue(effects.contains(AgentPulse(3_000L)))
    }
}
