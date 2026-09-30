package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.AgentConnectionChanged
import com.rearcue.poc.core.DashboardEvent.AgentSessionUpdated
import com.rearcue.poc.core.DashboardEvent.AgentSessionsRemoved
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.SessionLock
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Archive Synchrony 下游 core 判例（spec 0023 / 票 #237）：统一真值给出权威移除后，
 * 镜像仲裁、Waiting-for-Approval 插队与 Session Lock 必须同拍收口。
 */
class AgentArchiveSurfaceTest {

    private val logs = mutableListOf<String>()

    private fun core() = DashboardCore(nowMs = { 0L }, log = { logs += it })

    private fun state(
        id: String,
        status: AgentStatus,
        updatedAt: Long,
    ) = AgentSessionState(
        sessionId = id,
        workspace = "ws-$id",
        status = status,
        updatedAt = updatedAt,
    )

    @Test
    fun `权威移除_撤下等待插队与锁定会话_自动档恢复`() {
        val core = core()
        core.onEvent(NotificationPosted("com.tencent.mm", "k1", "标题", "正文"))
        core.onEvent(ProjectionReady)
        core.onEvent(AgentSessionUpdated(state("waiting", AgentStatus.WAITING_FOR_APPROVAL, 200L)))
        core.onEvent(SessionLock(SessionLockMode.Locked("waiting")))
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertEquals("waiting", core.agentState?.sessionId)

        core.onEvent(AgentSessionsRemoved(setOf("waiting")))

        assertEquals(SessionLockMode.Auto, core.sessionLock)
        assertNull(core.agentState)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertTrue(logs.contains("session lock cleared waiting"), "logs=$logs")
    }

    @Test
    fun `断线保留最后一帧_重连收到权威移除才立即清场`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(AgentSessionUpdated(state("kept", AgentStatus.WORKING, 100L)))
        core.onEvent(AgentConnectionChanged(false))
        assertEquals("kept", core.agentState?.sessionId)

        core.onEvent(AgentSessionsRemoved(setOf("kept")))
        assertNull(core.agentState)

        core.onEvent(AgentConnectionChanged(true))
        assertNull(core.agentState)
    }

    @Test
    fun `锁定会话尚未入core_权威移除仍清锁回自动`() {
        val core = core()
        core.onEvent(SessionLock(SessionLockMode.Locked("missing")))
        core.onEvent(AgentSessionsRemoved(setOf("missing")))
        assertEquals(SessionLockMode.Auto, core.sessionLock)
        assertTrue(logs.contains("session lock cleared missing"), "logs=$logs")
    }

    @Test
    fun `移除普通会话不动别的锁_重复移除幂等`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(AgentSessionUpdated(state("locked", AgentStatus.WORKING, 100L)))
        core.onEvent(AgentSessionUpdated(state("other", AgentStatus.WORKING, 200L)))
        core.onEvent(SessionLock(SessionLockMode.Locked("locked")))

        core.onEvent(AgentSessionsRemoved(setOf("other")))
        assertEquals(SessionLockMode.Locked("locked"), core.sessionLock)
        assertEquals("locked", core.agentState?.sessionId)

        assertEquals(emptyList(), core.onEvent(AgentSessionsRemoved(setOf("other"))))
        assertEquals(SessionLockMode.Locked("locked"), core.sessionLock)
    }
}
