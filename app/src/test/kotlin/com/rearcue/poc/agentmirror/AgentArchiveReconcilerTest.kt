package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentMembershipFact
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentArchiveReconcilerTest {

    private fun state(id: String, source: String = AgentSources.CODEX) = AgentSessionState(
        sessionId = if (source == AgentSources.ZCODE) id else "bridge:$source:$id",
        source = source,
        status = AgentStatus.WORKING,
    )

    @Test
    fun `membership墓碑同时收缩来源缓存_迟到活动不再进入`() {
        val active = state("same")
        val fact = AgentMembershipFact.active(AgentSources.CODEX, "same", generation = 1, state = active)
        val added = AgentArchiveReconciler.applyFacts(
            AgentArchiveTruth.Empty,
            AgentArchiveCache(),
            listOf(fact),
        )
        assertEquals(listOf(active.copy(sessionId = "bridge:codex:same")), added.cache.bridgeRoster)

        val archived = AgentMembershipFact.archived(AgentSources.CODEX, "same", generation = 2)
        val removed = AgentArchiveReconciler.applyFacts(
            added.truth,
            added.cache,
            listOf(archived),
        )
        assertEquals(emptyList(), removed.cache.bridgeRoster)
        assertNull(
            AgentArchiveReconciler.observe(removed.truth, removed.cache, active).acceptedState,
        )
    }

    @Test
    fun `来源快照对账把缺席和缓存形状一起归约`() {
        val kept = state("kept")
        val dropped = state("dropped")
        val initial = AgentArchiveTruth.Empty.observe(listOf(kept, dropped))
        val cache = AgentArchiveCache(bridgeRoster = listOf(kept, dropped))
        val reduction = AgentArchiveReconciler.reconcileSource(
            initial,
            cache,
            previous = listOf(kept, dropped),
            next = listOf(kept),
            source = "bridge",
        )
        assertEquals(setOf("bridge:codex:dropped"), reduction.removedIds)
        assertEquals(listOf("bridge:codex:kept"), reduction.cache.bridgeRoster.map { it.sessionId })
        assertEquals(setOf("bridge:codex:kept"), reduction.truth.currentRosterIds())
    }

    @Test
    fun `ZCode归档缓存同时清任务状态和V4状态`() {
        val state = state("z-1", AgentSources.ZCODE)
        val cache = AgentArchiveCache(
            taskRoster = listOf(state),
            taskState = state,
            v4State = state,
        )
        val reduction = AgentArchiveReconciler.applyFacts(
            AgentArchiveTruth.Empty.observe(state),
            cache,
            listOf(AgentMembershipFact.archived(AgentSources.ZCODE, "z-1", generation = 2)),
        )
        assertEquals(emptyList(), reduction.cache.taskRoster)
        assertNull(reduction.cache.taskState)
        assertNull(reduction.cache.v4State)
    }
}
