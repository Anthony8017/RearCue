package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.AgentConnectionChanged
import com.rearcue.poc.core.DashboardEvent.AgentRoster
import com.rearcue.poc.core.DashboardEvent.AgentSessionUpdated
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.SessionLock
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZCode 经 PC 桥进入后与 Codex / Claude / DSH 同一条 Session Lock 外部行为（#234/#242）：
 * 等待插队、断线保留、快照对账清锁都不给 ZCode 单开例外。
 */
class BridgeZCodeSessionLockTest {

    private val zcodeId = "bridge:zcode-2468"
    private val otherId = "bridge:codex-1357"

    private fun state(
        id: String,
        source: String,
        status: AgentStatus,
        updatedAt: Long,
        title: String? = null,
    ) = AgentSessionState(
        sessionId = id,
        title = title,
        workspace = "C:/work/RearCue",
        status = status,
        updatedAt = updatedAt,
        source = source,
    )

    private fun core(log: (String) -> Unit = {}) = DashboardCore(log = log)

    @Test
    fun `ZCode桥会话等待插队_处理完回锁`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(AgentSessionUpdated(state(otherId, AgentSources.CODEX, AgentStatus.WORKING, 100L)))
        core.onEvent(SessionLock(SessionLockMode.Locked(otherId)))

        core.onEvent(AgentSessionUpdated(state(zcodeId, AgentSources.ZCODE, AgentStatus.WAITING_FOR_APPROVAL, 200L)))
        assertEquals(zcodeId, core.agentState?.sessionId)
        assertEquals(SessionLockMode.Locked(otherId), core.sessionLock)

        core.onEvent(AgentSessionUpdated(state(zcodeId, AgentSources.ZCODE, AgentStatus.IDLE, 300L)))
        assertEquals(otherId, core.agentState?.sessionId)
        assertEquals(SessionLockMode.Locked(otherId), core.sessionLock)
        assertTrue(core.agentOnScreen)
    }

    @Test
    fun `断桥保留ZCode最后一帧_未对账缺席不清锁`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(AgentSessionUpdated(state(zcodeId, AgentSources.ZCODE, AgentStatus.WORKING, 100L, title = "断桥前标题")))
        core.onEvent(SessionLock(SessionLockMode.Locked(zcodeId)))

        core.onEvent(AgentConnectionChanged(false))
        assertEquals(zcodeId, core.agentState?.sessionId)
        assertEquals("断桥前标题", core.agentState?.title)
        assertTrue(core.agentOnScreen)

        core.onEvent(AgentRoster(setOf(otherId), bridgeRosterKnown = false))
        assertEquals(SessionLockMode.Locked(zcodeId), core.sessionLock)
        assertEquals(zcodeId, core.agentState?.sessionId)
    }

    @Test
    fun `恢复快照确认ZCode离册才清锁_仍在册则继续跟随`() {
        val logs = mutableListOf<String>()
        val core = core { logs += it }
        core.onEvent(SessionLock(SessionLockMode.Locked(zcodeId)))
        core.onEvent(AgentRoster(setOf(otherId), bridgeRosterKnown = true))
        assertEquals(SessionLockMode.Auto, core.sessionLock)
        assertTrue(logs.contains("session lock cleared $zcodeId"))

        core.onEvent(SessionLock(SessionLockMode.Locked(zcodeId)))
        core.onEvent(AgentRoster(setOf(otherId, zcodeId), bridgeRosterKnown = true))
        assertEquals(SessionLockMode.Locked(zcodeId), core.sessionLock)
    }
}
