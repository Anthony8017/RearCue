package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.core.DashboardEvent.SessionLockMode

/**
 * Archive Synchrony 的统一在册真值（spec 0023 / 票 #235）。
 *
 * 这里只回答一个来源无关的问题：某个会话现在是否属于「未归档当前在册集合」。
 * 活动只是状态事实，永远不会改变归档生命周期：
 * - 归档先记下移除事实，已知会话从 [currentRoster] 消失，未知会话也不能再进入；
 * - 归档后的旧活动、新活动或另一来源同键活动一律忽略，不会复活会话；
 * - 只有显式取消归档才是恢复入口。恢复优先采用来源随恢复事实给的新状态，
 *   否则回到归档前最后已知状态；两者都没有时先保持空，但允许后续正常活动进入。
 *
 * 主屏与背屏不得各自维护筛选口径：两者都从 [currentRoster] 取同一份集合，
 * 或直接调用 [projectRoster]/[AgentStateLogic.projectRoster] 取同一份列表投影。
 */
class AgentArchiveTruth private constructor(
    private val members: Map<String, Member>,
    private val nextOrder: Int,
) {

    /** 接入一条来源活动/状态事实。 */
    fun observe(session: AgentSessionState): AgentArchiveTruth {
        val id = session.sessionId
        if (id.isBlank()) return this
        val existing = members[id]
        return when {
            // 归档真值高于任何活动真值：迟到旧消息、新消息、跨来源同键更新都不能重进。
            existing?.archived == true -> this
            existing != null -> put(id, existing.copy(lastKnown = session))
            else -> insert(id, Member(lastKnown = session, archived = false))
        }
    }

    /** 接入一份来源状态列表；只更新未归档成员，不改变归档生命周期。 */
    fun observe(roster: List<AgentSessionState>): AgentArchiveTruth =
        roster.fold(this) { truth, session -> truth.observe(session) }

    /**
     * 记下归档/移除事实。幂等；会话尚未出现时也记墓碑，保证它之后不能从活动或快照进入。
     */
    fun archive(sessionId: String): AgentArchiveTruth {
        if (sessionId.isBlank()) return this
        val existing = members[sessionId]
        return when {
            existing == null -> insert(sessionId, Member(lastKnown = null, archived = true))
            existing.archived -> this
            else -> put(sessionId, existing.copy(archived = true))
        }
    }

    /**
     * 取消归档，是唯一恢复入口。
     *
     * [restored] 是来源随恢复事实给出的当前状态；缺省时恢复到归档前最后已知状态。
     * 从未见过且没有恢复状态的会话仍解除墓碑，之后可按正常活动规则首次进入。
     * 重新出现在列表时按新到达序参与现有排序，不获得人为优先级。
     */
    fun unarchive(sessionId: String, restored: AgentSessionState? = null): AgentArchiveTruth {
        if (sessionId.isBlank()) return this
        if (restored != null && restored.sessionId != sessionId) return this
        val existing = members[sessionId]
        return when {
            existing == null -> insert(sessionId, Member(lastKnown = restored, archived = false))
            !existing.archived && restored == null -> this
            else -> put(
                sessionId,
                Member(
                    lastKnown = restored ?: existing.lastKnown,
                    archived = false,
                    order = if (existing.archived) nextOrder else existing.order,
                ),
            )
        }
    }

    /** 当前未归档在册集合：主屏、背屏和 core 对账都读这一份。 */
    fun currentRoster(): List<AgentSessionState> =
        members.entries
            .asSequence()
            .filter { !it.value.archived && it.value.lastKnown != null }
            .sortedBy { it.value.order }
            .mapNotNull { it.value.lastKnown }
            .toList()

    /** 当前未归档在册键集。 */
    fun currentRosterIds(): Set<String> =
        currentRoster().mapTo(linkedSetOf()) { it.sessionId }

    /** 当前是否属于未归档在册集合；所有派生入口的放行判据。 */
    fun isCurrent(sessionId: String): Boolean = sessionId in currentRosterIds()

    /** 统一列表投影；只吃 [currentRoster]，归档成员没有进入或复活路径。 */
    fun projectRoster(mode: SessionLockMode): List<AgentListRow> =
        AgentStateLogic.projectRoster(currentRoster(), mode)

    private fun insert(id: String, member: Member): AgentArchiveTruth =
        AgentArchiveTruth(members + (id to member.copy(order = nextOrder)), nextOrder + 1)

    private fun put(id: String, member: Member): AgentArchiveTruth =
        AgentArchiveTruth(
            members + (id to member),
            maxOf(nextOrder, member.order + 1),
        )

    private data class Member(
        val lastKnown: AgentSessionState?,
        val archived: Boolean,
        val order: Int = 0,
    )

    companion object {
        /** 空真值。 */
        val Empty: AgentArchiveTruth = AgentArchiveTruth(emptyMap(), 0)
    }
}
