package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentArchiveState
import com.rearcue.poc.agent.AgentMembership
import com.rearcue.poc.agent.AgentMembershipFact
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSourceSession
import com.rearcue.poc.core.DashboardEvent.SessionLockMode

/**
 * Archive Synchrony 的统一在册真值（spec 0023 / 票 #235/#236）。
 *
 * 这里只回答一个来源键 `(source, sourceSessionId)` 是否属于「未归档当前在册集合」。
 * 来源活动只是状态事实，永远不会改变归档生命周期：
 * - [AgentMembershipFact] 的 `ABSENT`/`ARCHIVED` 先记墓碑，已知会话从 [currentRoster] 消失，
 *   未知会话也不能再进入；
 * - 归档后的旧活动、新活动或同来源迟到快照一律忽略，不会复活会话；
 * - `generation`/`revision` 旧代事实不能覆盖新事实，防止断线重放把已移除会话写回来；
 * - 只有更新的 `PRESENT/ACTIVE` 正事实才是恢复入口。
 *
 * 主屏与背屏不得各自维护筛选口径：两者都从 [currentRoster] 取同一份集合，
 * 或直接调用 [projectRoster]/[AgentStateLogic.projectRoster] 取同一份列表投影。
 */
class AgentArchiveTruth private constructor(
    private val members: Map<AgentSourceSession, Member>,
    private val nextOrder: Int,
) {

    /** 接入一条来源活动/状态事实；归档/缺席成员永不被活动复活。 */
    fun observe(session: AgentSessionState, generation: Long? = null, revision: Long? = null): AgentArchiveTruth {
        val key = AgentSourceSession.fromSessionState(session)
        val existing = members[key]
        val normalized = session.copy(
            sessionId = key.sessionId,
            // 最近活跃只前进，快照迟到回放不得把已见的新活动拉回旧位置。
            updatedAt = maxOf(session.updatedAt, existing?.lastKnown?.updatedAt ?: 0L),
        )
        if (existing?.archived == true) return this
        if (existing != null && generation != null && revision != null &&
            !com.rearcue.poc.agent.AgentFactVersion.newer(generation, revision, existing.generation, existing.revision)
        ) {
            return this
        }
        val next = existing?.copy(
            lastKnown = normalized,
            generation = maxOf(existing.generation, generation ?: existing.generation),
            revision = maxOf(existing.revision, revision ?: existing.revision),
        ) ?: Member(
            identity = key,
            lastKnown = normalized,
            archived = false,
            membership = AgentMembership.PRESENT,
            archiveState = AgentArchiveState.ACTIVE,
            generation = generation ?: -1L,
            revision = revision ?: -1L,
        )
        return put(key, next)
    }

    /** 接入一份来源状态列表；只更新未归档成员，不改变归档生命周期。 */
    fun observe(roster: List<AgentSessionState>): AgentArchiveTruth =
        roster.fold(this) { truth, session -> truth.observe(session) }

    /**
     * 接入来源在册/归档事实。旧代/同代旧序号返回原真值；
     * 墓碑保存最后状态用于显式恢复，但绝不在 [currentRoster] 出现。
     */
    fun apply(fact: AgentMembershipFact): AgentArchiveTruth {
        val key = fact.identity
        val existing = members[key]
        if (existing != null && !fact.newerThan(existing.generation, existing.revision)) return this
        // UNKNOWN 是「坏文件/缺席时不知道」的容错观测，不是归档或取消归档：
        // 它不得清除既有墓碑，也不得把 ACTIVE 改写成假归档；只有 ACTIVE/PRESENT 可回册。
        val restores = fact.membership == AgentMembership.PRESENT &&
            fact.archiveState == AgentArchiveState.ACTIVE
        val remainsTombstone = fact.tombstone || (existing?.archived == true && !restores)
        val next = Member(
            identity = key,
            lastKnown = fact.state?.copy(sessionId = key.sessionId) ?: existing?.lastKnown,
            archived = remainsTombstone,
            membership = if (remainsTombstone) AgentMembership.ABSENT else AgentMembership.PRESENT,
            archiveState = when {
                fact.tombstone -> fact.archiveState
                restores -> AgentArchiveState.ACTIVE
                existing != null -> existing.archiveState
                else -> AgentArchiveState.UNKNOWN
            },
            generation = if (fact.archiveState == AgentArchiveState.UNKNOWN && existing != null) {
                existing.generation
            } else {
                fact.generation
            },
            revision = if (fact.archiveState == AgentArchiveState.UNKNOWN && existing != null) {
                existing.revision
            } else {
                fact.revision
            },
            order = if (existing == null) nextOrder else existing.order,
        )
        return put(key, next)
    }

    /** 批量接入来源事实；保持输入顺序，每条独立做代数保护。 */
    fun apply(facts: List<AgentMembershipFact>): AgentArchiveTruth =
        facts.fold(this) { truth, fact -> truth.apply(fact) }

    /**
     * 记下归档/移除事实。幂等；会话尚未出现时也记墓碑，保证它之后不能从活动或快照进入。
     * 兼容入口只处理既有 canonical id；来源边界请使用 [apply] 的来源事实。
     */
    fun archive(sessionId: String): AgentArchiveTruth {
        if (sessionId.isBlank()) return this
        val matching = members.values.filter {
            it.lastKnown?.sessionId == sessionId || it.identity.sessionId == sessionId
        }
        if (matching.isEmpty()) {
            val key = AgentSourceSession("legacy", sessionId)
            return put(
                key,
                Member(
                    identity = key,
                    lastKnown = null,
                    archived = true,
                    membership = AgentMembership.ABSENT,
                    archiveState = AgentArchiveState.UNKNOWN,
                ),
            )
        }
        return matching.fold(this) { truth, member ->
            truth.put(
                member.identity,
                member.copy(
                    archived = true,
                    membership = AgentMembership.ABSENT,
                    archiveState = AgentArchiveState.UNKNOWN,
                ),
            )
        }
    }

    /**
     * 取消归档，是兼容入口的唯一恢复来源。
     *
     * [restored] 是来源随恢复事实给出的当前状态；缺省时恢复到归档前最后已知状态。
     * 来源边界优先使用 [apply] 的更新 `PRESENT/ACTIVE`，它带来源代数保护。
     */
    fun unarchive(sessionId: String, restored: AgentSessionState? = null): AgentArchiveTruth {
        if (sessionId.isBlank()) return this
        if (restored != null && restored.sessionId != sessionId) return this
        val matching = members.values.filter {
            it.lastKnown?.sessionId == sessionId || it.identity.sessionId == sessionId
        }
        val targets = matching.ifEmpty {
            listOf(
                Member(
                    identity = AgentSourceSession("legacy", sessionId),
                    lastKnown = restored?.copy(sessionId = AgentSourceSession("legacy", sessionId).sessionId),
                    archived = true,
                    membership = AgentMembership.ABSENT,
                    archiveState = AgentArchiveState.UNKNOWN,
                ),
            )
        }
        return targets.fold(this) { truth, member ->
            truth.put(
                member.identity,
                member.copy(
                    lastKnown = restored?.copy(sessionId = member.identity.sessionId) ?: member.lastKnown,
                    archived = false,
                    membership = AgentMembership.PRESENT,
                    archiveState = AgentArchiveState.ACTIVE,
                    order = if (member.archived) truth.nextOrder else member.order,
                ),
            )
        }
    }

    /** 当前未归档在册集合：主屏、背屏和 core 对账都读这一份。 */
    fun currentRoster(): List<AgentSessionState> =
        members.values
            .asSequence()
            .filter { !it.archived && it.membership == AgentMembership.PRESENT && it.archiveState != AgentArchiveState.ARCHIVED }
            .sortedBy { it.order }
            .mapNotNull { it.lastKnown }
            .toList()

    /** 当前未归档在册键集。 */
    fun currentRosterIds(): Set<String> =
        currentRoster().mapTo(linkedSetOf()) { it.sessionId }

    /** 当前是否属于未归档在册集合；所有派生入口的放行判据。 */
    fun isCurrent(sessionId: String): Boolean = sessionId in currentRosterIds()

    /** 统一列表投影；只吃 [currentRoster]，归档成员没有进入或复活路径。 */
    fun projectRoster(mode: SessionLockMode): List<AgentListRow> =
        AgentStateLogic.projectRoster(currentRoster(), mode)


    private fun put(
        key: AgentSourceSession,
        member: Member,
    ): AgentArchiveTruth {
        val order = if (member.order >= 0) member.order else nextOrder
        return AgentArchiveTruth(
            members + (key to member.copy(order = order)),
            maxOf(nextOrder, order + 1),
        )
    }

    private data class Member(
        val identity: AgentSourceSession,
        val lastKnown: AgentSessionState?,
        val archived: Boolean,
        val membership: AgentMembership,
        val archiveState: AgentArchiveState,
        val generation: Long = -1L,
        val revision: Long = -1L,
        val order: Int = -1,
    )

    companion object {
        /** 空真值。 */
        val Empty: AgentArchiveTruth = AgentArchiveTruth(emptyMap(), 0)
    }
}
