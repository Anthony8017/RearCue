package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentApproveShape
import com.rearcue.poc.agent.AgentSessionKeys
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.SessionIndexEntry
import com.rearcue.poc.agent.SourceCapabilities
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Archive Synchrony 下游派生面判例（spec 0023 / 票 #237）：统一在册真值先于列表、
 * 等待视图、提醒账与批准/提问入口；这里用同一份 currentRoster 证明一处移除全处消失。
 */
class AgentArchiveSurfaceTest {

    private val capabilities = SourceCapabilities(
        mapOf("claude" to setOf(SourceCapabilities.WAITING, SourceCapabilities.APPROVE)),
    )

    private fun session(
        id: String,
        status: AgentStatus = AgentStatus.IDLE,
        updatedAt: Long = 0L,
        source: String = "claude",
    ) = AgentSessionState(
        sessionId = if (source == "zcode") id else AgentSessionKeys.bridge(source, AgentSessionKeys.bridgeSourceSessionId(id) ?: id),
        workspace = "ws-$id",
        status = status,
        updatedAt = updatedAt,
        source = source,
    )

    @Test
    fun `统一移除事实_列表等待提醒与批准入口同拍消失`() {
        val waiting = session("waiting", AgentStatus.WAITING_FOR_APPROVAL, 200L)
        val ordinary = session("ordinary", AgentStatus.WORKING, 100L)
        val truth = AgentArchiveTruth.Empty.observe(listOf(waiting, ordinary))
        val tracker = AgentAlertTracker()
        assertEquals(
            listOf(waiting.sessionId, ordinary.sessionId),
            truth.projectRoster(SessionLockMode.Auto).drop(1).map { it.sessionId },
        )
        assertEquals(
            listOf(waiting),
            AgentApprovePolicy.visibleApprovals(truth.currentRoster(), capabilities),
        )
        assertEquals(
            setOf(waiting.sessionId),
            AgentStateLogic.indexWaitingIds(listOf(SessionIndexEntry(waiting.sessionId, true, 1L)), truth.currentRoster()),
        )
        assertEquals(AgentAlertKind.WAITING, tracker.onSessionState(waiting.sessionId, waiting.status, 1L))
        assertEquals(null, tracker.onSessionState(waiting.sessionId, AgentStatus.WORKING, 1L))

        val archived = truth.archive(waiting.sessionId)
        assertEquals(listOf(ordinary.sessionId), archived.projectRoster(SessionLockMode.Auto).drop(1).map { it.sessionId })
        assertEquals(
            emptyList(),
            AgentApprovePolicy.visibleApprovals(archived.currentRoster(), capabilities),
        )
        assertEquals(
            emptySet(),
            AgentStateLogic.indexWaitingIds(listOf(SessionIndexEntry(waiting.sessionId, true, 1L)), archived.currentRoster()),
        )
        tracker.retain(archived.currentRosterIds())
        assertEquals(
            null,
            tracker.onSessionState(waiting.sessionId, AgentStatus.IDLE, 2L),
            "移除后旧工作状态不能把等待退出伪装成干完提醒",
        )
    }

    @Test
    fun `归档后的任务V4桥活动与等待退出占位都不能复活`() {
        val stale = session("removed", AgentStatus.WAITING_FOR_APPROVAL, 500L)
        var truth = AgentArchiveTruth.Empty.observe(stale).archive(stale.sessionId)

        truth = truth.observe(stale.copy(status = AgentStatus.WORKING, updatedAt = 1L))
        assertEquals(emptyList(), truth.currentRoster())

        val placeholder = stale.copy(status = AgentStatus.IDLE, updatedAt = 600L)
        val batch = AgentStateLogic.dispatchBatch(
            roster = truth.currentRoster(),
            task = placeholder,
            v4 = placeholder,
            indexEntries = listOf(SessionIndexEntry(placeholder.sessionId, false, 1L)),
            dispatchedWaiting = setOf(placeholder.sessionId),
        )
        assertTrue(batch.states.isEmpty())
    }

    @Test
    fun `取消归档按普通到达序恢复_真实等待才按既有规则置顶`() {
        val ordinary = session("ordinary", AgentStatus.WORKING, 100L)
        val waiting = session("waiting", AgentStatus.WAITING_FOR_APPROVAL, 50L)
        val archived = AgentArchiveTruth.Empty
            .observe(listOf(ordinary, waiting))
            .archive(waiting.sessionId)

        val restoredIdle = archived.unarchive(waiting.sessionId, waiting.copy(status = AgentStatus.IDLE))
        assertEquals(
            listOf(ordinary.sessionId, waiting.sessionId),
            restoredIdle.projectRoster(SessionLockMode.Auto).drop(1).map { it.sessionId },
        )

        val restoredWaiting = archived.unarchive(waiting.sessionId, waiting)
        assertEquals(
            listOf(waiting.sessionId, ordinary.sessionId),
            restoredWaiting.projectRoster(SessionLockMode.Auto).drop(1).map { it.sessionId },
        )
    }

    @Test
    fun `批准入口形状只吃当前在册事实_移除后问答选项也不再出现`() {
        val question = session(
            "question",
            AgentStatus.WAITING_FOR_APPROVAL,
            100L,
        ).copy(
            pendingOptions = listOf(
                com.rearcue.poc.agent.AgentPendingOption("yes", "Yes"),
                com.rearcue.poc.agent.AgentPendingOption("no", "No"),
            ),
        )
        val truth = AgentArchiveTruth.Empty.observe(question)
        assertTrue(AgentApprovePolicy.shapeFor(question) is AgentApproveShape.Question)
        val archived = truth.archive(question.sessionId)
        assertEquals(emptyList(), AgentApprovePolicy.visibleApprovals(archived.currentRoster(), capabilities))
    }
}
