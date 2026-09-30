package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentArchiveState
import com.rearcue.poc.agent.AgentMembership
import com.rearcue.poc.agent.AgentMembershipFact
import com.rearcue.poc.agent.AgentMembershipReason
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentArchiveTruthSourceMembershipTest {

    private fun canonical(source: String, raw: String): String =
        if (source == AgentSources.ZCODE) raw else "bridge:$raw"

    private fun state(source: String, raw: String, status: AgentStatus = AgentStatus.IDLE): AgentSessionState =
        AgentSessionState(
            sessionId = canonical(source, raw),
            status = status,
            source = source,
        )

    private fun active(source: String, raw: String, generation: Long, revision: Long = generation) =
        AgentMembershipFact.active(source, raw, generation, revision, state = state(source, raw))

    private fun archived(source: String, raw: String, generation: Long) =
        AgentMembershipFact.archived(source, raw, generation, generation, state = state(source, raw))

    private fun absent(source: String, raw: String, generation: Long) =
        AgentMembershipFact.absent(source, raw, generation, generation)

    private fun assertArchiveRoundTrip(source: String, raw: String) {
        val first = AgentArchiveTruth.Empty.apply(active(source, raw, generation = 1))
        assertEquals(setOf(canonical(source, raw)), first.currentRosterIds())

        val tombstoned = first.apply(archived(source, raw, generation = 2))
        assertEquals(emptySet(), tombstoned.currentRosterIds())

        val restored = tombstoned.apply(active(source, raw, generation = 3))
        assertEquals(setOf(canonical(source, raw)), restored.currentRosterIds())
    }

    @Test
    fun `ZCode来源_ACTIVE到ARCHIVED到_ACTIVE按任务表事实恢复`() {
        assertArchiveRoundTrip(AgentSources.ZCODE, "z-1")
    }

    @Test
    fun `Codex来源_显式membership契约可逆归档并保持bridge前缀`() {
        assertArchiveRoundTrip(AgentSources.CODEX, "c-1")
    }

    @Test
    fun `Claude来源_显式membership契约可逆归档并保持bridge前缀`() {
        assertArchiveRoundTrip(AgentSources.CLAUDE, "cl-1")
    }

    @Test
    fun `DSH来源_移除与重建走同一在册事实路径`() {
        assertArchiveRoundTrip(AgentSources.DSH, "d-1")
    }

    @Test
    fun `四个来源各证_ACTIVE到ABSENT_且旧代正事实不能复活`() {
        listOf(
            AgentSources.ZCODE to "z-absent",
            AgentSources.CODEX to "c-absent",
            AgentSources.CLAUDE to "cl-absent",
            AgentSources.DSH to "d-absent",
        ).forEach { (source, raw) ->
            val truth = AgentArchiveTruth.Empty
                .apply(active(source, raw, generation = 2))
                .apply(absent(source, raw, generation = 3))
            assertEquals(emptySet(), truth.currentRosterIds(), source)

            val stale = truth.apply(active(source, raw, generation = 1, revision = 99))
            assertEquals(emptySet(), stale.currentRosterIds(), source)

            val lateActivity = stale.observe(state(source, raw, AgentStatus.WORKING))
            assertEquals(emptySet(), lateActivity.currentRosterIds(), source)
        }
    }

    @Test
    fun `来源键隔离_同原始id不同来源不会互相归档`() {
        val truth = AgentArchiveTruth.Empty
            .apply(active(AgentSources.CODEX, "same", generation = 1))
            .apply(active(AgentSources.CLAUDE, "same", generation = 1))
            .apply(archived(AgentSources.CODEX, "same", generation = 2))

        assertEquals(setOf("bridge:same"), truth.currentRosterIds())
        val codexState = truth.observe(state(AgentSources.CODEX, "same", AgentStatus.WORKING))
        assertEquals(setOf("bridge:same"), codexState.currentRosterIds())
        assertEquals(
            AgentSources.CLAUDE,
            codexState.currentRoster().single().source,
        )
    }

    @Test
    fun `代数序号相同重复事实是幂等_不改变在册集合`() {
        val fact = active(AgentSources.CODEX, "same", generation = 4)
        val truth = AgentArchiveTruth.Empty.apply(fact).apply(fact)
        assertEquals(setOf("bridge:same"), truth.currentRosterIds())
        assertEquals(1, truth.currentRoster().size)
    }
}
