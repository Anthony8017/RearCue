package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionKeys
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Archive Synchrony 统一核心判例（spec 0023 / 票 #235）。
 *
 * 只测来源无关的外部行为：在册集合与同一列表投影是否看见、何时恢复；
 * 不测来源解析、提醒/批准/清锁接线（后续票）。
 */
class AgentArchiveTruthTest {

    private data class StatusCase(
        val wireName: String,
        val status: AgentStatus,
        val source: String,
    )

    private fun session(
        id: String,
        status: AgentStatus = AgentStatus.IDLE,
        updatedAt: Long = 0L,
        source: String? = null,
    ) = AgentSessionState(
        sessionId = if (source == null) id else AgentSessionKeys.bridge(source, AgentSessionKeys.bridgeSourceSessionId(id) ?: id),
        workspace = "ws-$id",
        status = status,
        currentAction = id,
        updatedAt = updatedAt,
        source = source,
    )

    @Test
    fun `归档是统一移除事实_工作空闲完成出错等待都从在册与列表投影消失`() {
        val cases = listOf(
            StatusCase("working", AgentStatus.WORKING, AgentSources.ZCODE),
            StatusCase("idle", AgentStatus.IDLE, AgentSources.CODEX),
            // 任务表的 completed 在现有源边界归一为空闲；这里证明归档判定不依赖活动态。
            StatusCase("completed", AgentStatus.IDLE, AgentSources.CLAUDE),
            StatusCase("error", AgentStatus.ERROR, AgentSources.DSH),
            StatusCase("waiting", AgentStatus.WAITING_FOR_APPROVAL, AgentSources.ZCODE),
        )

        cases.forEachIndexed { index, case ->
            val state = session(
                id = "s-${case.wireName}",
                status = case.status,
                updatedAt = 100L + index,
                source = case.source,
            )
            val before = AgentArchiveTruth.Empty.observe(state)
            assertEquals(listOf(state), before.currentRoster(), case.wireName)
            assertTrue(before.projectRoster(SessionLockMode.Auto).drop(1).any { it.sessionId == state.sessionId })

            val archived = before.archive(state.sessionId)
            assertEquals(emptyList(), archived.currentRoster(), case.wireName)
            assertEquals(emptySet(), archived.currentRosterIds(), case.wireName)
            assertEquals(
                listOf(true),
                archived.projectRoster(SessionLockMode.Auto).map { it.auto },
                "${case.wireName} 只应留下固定的自动行",
            )
            // 主屏与背屏不各自筛选：统一在册集合和统一列表投影同时看不见归档行。
            assertEquals(
                archived.projectRoster(SessionLockMode.Auto),
                AgentStateLogic.projectRoster(archived, SessionLockMode.Auto),
                case.wireName,
            )
        }
    }

    @Test
    fun `归档事实先于会话出现_仍阻止活动或名单把它放进当前在册`() {
        val archived = AgentArchiveTruth.Empty.archive("late")

        val afterActivity = archived.observe(session("late", status = AgentStatus.WORKING, updatedAt = 100L))
        assertEquals(emptyList(), afterActivity.currentRoster())

        val afterRoster = afterActivity.observe(
            listOf(
                session("late", status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 200L),
                session("other", updatedAt = 300L),
            ),
        )
        assertEquals(setOf("other"), afterRoster.currentRosterIds())
        assertEquals(listOf(null, "other"), afterRoster.projectRoster(SessionLockMode.Auto).map { it.sessionId })
    }

    @Test
    fun `归档后同来源迟到旧活动与新活动都不复活_不同来源同原始id按来源键隔离`() {
        val original = session(
            id = "bridge:s",
            status = AgentStatus.WORKING,
            updatedAt = 100L,
            source = AgentSources.CODEX,
        )
        val truth = AgentArchiveTruth.Empty.observe(original).archive(original.sessionId)

        val replayed = truth
            .observe(original.copy(status = AgentStatus.IDLE, updatedAt = 1L))
            .observe(original.copy(status = AgentStatus.WORKING, updatedAt = 200L))
            .observe(original.copy(status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 500L))

        assertEquals(emptyList(), replayed.currentRoster())
        assertTrue(replayed.projectRoster(SessionLockMode.Auto).none { it.sessionId == original.sessionId })

        val independentSources = truth
            .observe(original.copy(status = AgentStatus.WORKING, updatedAt = 200L, source = AgentSources.CLAUDE))
            .observe(original.copy(status = AgentStatus.IDLE, updatedAt = 300L, source = AgentSources.DSH))
        assertEquals(2, independentSources.currentRoster().size, "同原始 id 的其它来源是独立来源键")
    }

    @Test
    fun `取消归档恢复归档前最后事实_归档后活动不污染恢复结果`() {
        val beforeArchive = session(
            id = "s",
            status = AgentStatus.WORKING,
            updatedAt = 100L,
            source = AgentSources.CODEX,
        )
        val archived = AgentArchiveTruth.Empty.observe(beforeArchive).archive(beforeArchive.sessionId)
            .observe(beforeArchive.copy(status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 500L))

        val restored = archived.unarchive(beforeArchive.sessionId)

        assertEquals(listOf(beforeArchive), restored.currentRoster())
        assertEquals(listOf(null, beforeArchive.sessionId), restored.projectRoster(SessionLockMode.Auto).map { it.sessionId })
    }

    @Test
    fun `取消归档可带来源恢复事实_排序仍走正常规则_真实等待才置顶`() {
        val waiting = session("waiting", AgentStatus.WORKING, updatedAt = 100L, source = AgentSources.ZCODE)
        val newer = session("newer", AgentStatus.WORKING, updatedAt = 200L, source = AgentSources.CODEX)
        val waitingTruth = AgentArchiveTruth.Empty
            .observe(listOf(waiting, newer))
            .archive(waiting.sessionId)
            .unarchive(
                waiting.sessionId,
                restored = waiting.copy(status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 10L),
            )
        assertEquals(listOf(waiting.sessionId, newer.sessionId), waitingTruth.projectRoster(SessionLockMode.Auto).drop(1).map { it.sessionId })

        val ordinary = session("ordinary", AgentStatus.WORKING, updatedAt = 10L, source = AgentSources.CLAUDE)
        val ordinaryTruth = AgentArchiveTruth.Empty
            .observe(listOf(ordinary, newer))
            .archive(ordinary.sessionId)
            .unarchive(ordinary.sessionId, restored = ordinary.copy(updatedAt = 10L))
        assertEquals(listOf(newer.sessionId, ordinary.sessionId), ordinaryTruth.projectRoster(SessionLockMode.Auto).drop(1).map { it.sessionId })
    }

    @Test
    fun `未见过的会话取消归档后按正常活动规则可重新进入`() {
        val truth = AgentArchiveTruth.Empty.archive("future").unarchive("future")
        assertEquals(emptyList(), truth.currentRoster())

        val observed = truth.observe(session("future", status = AgentStatus.ERROR, updatedAt = 42L))
        assertEquals(setOf("future"), observed.currentRosterIds())
        assertEquals(
            AgentStatus.ERROR,
            observed.projectRoster(SessionLockMode.Auto).drop(1).single().status,
        )
    }
}
