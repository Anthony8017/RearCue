package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentMembershipFact
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSourceSession

/**
 * Archive Synchrony 的纯在册/缓存归约器（spec 0023 / 票 #238）。
 *
 * Android 层只把来源回调翻译成输入，并把这里的 [AgentArchiveReduction] 搬到现有缓存字段；
 * 是否出册、迟到活动是否放行、缓存如何收缩都由本对象决定。
 */
data class AgentArchiveCache(
    val taskRoster: List<AgentSessionState> = emptyList(),
    val bridgeRoster: List<AgentSessionState> = emptyList(),
    val taskState: AgentSessionState? = null,
    val v4State: AgentSessionState? = null,
    val bridgeRosterKnown: Boolean = false,
)

data class AgentArchiveReduction(
    val truth: AgentArchiveTruth,
    val cache: AgentArchiveCache,
    val removedIds: Set<String> = emptySet(),
    val acceptedState: AgentSessionState? = null,
)

object AgentArchiveReconciler {
    /** 状态活动先进真值；墓碑拦截后返回 acceptedState=null。 */
    fun observe(
        truth: AgentArchiveTruth,
        cache: AgentArchiveCache,
        state: AgentSessionState?,
    ): AgentArchiveReduction {
        if (state == null || state.sessionId.isBlank()) return AgentArchiveReduction(truth, cache)
        val nextTruth = truth.observe(state)
        val normalizedId = AgentSourceSession.fromSessionState(state).sessionId
        val accepted = state.copy(sessionId = normalizedId).takeIf { nextTruth.isCurrent(normalizedId) }
        val nextCache = when {
            accepted == null -> cache
            accepted.source == com.rearcue.poc.agent.AgentSources.ZCODE ->
                cache.copy(taskRoster = AgentStateLogic.mergeRoster(cache.taskRoster, listOf(accepted)))
            else -> cache.copy(bridgeRoster = AgentStateLogic.mergeRoster(cache.bridgeRoster, listOf(accepted)))
        }
        return AgentArchiveReduction(nextTruth, nextCache, acceptedState = accepted)
    }

    /** 来源 membership 事实：真值先收口，再按来源收缩/恢复对应缓存。 */
    fun applyFacts(
        truth: AgentArchiveTruth,
        cache: AgentArchiveCache,
        facts: List<AgentMembershipFact>,
    ): AgentArchiveReduction {
        if (facts.isEmpty()) return AgentArchiveReduction(truth, cache)
        var nextTruth = truth
        var nextCache = cache
        facts.forEach { fact ->
            nextTruth = nextTruth.apply(fact)
            val id = fact.identity.sessionId
            val isZcode = fact.source == com.rearcue.poc.agent.AgentSources.ZCODE
            if (fact.tombstone) {
                nextCache = if (isZcode) {
                    nextCache.copy(
                        taskRoster = nextCache.taskRoster.filterNot { it.sessionId == id },
                        taskState = nextCache.taskState?.takeIf { it.sessionId != id },
                        v4State = nextCache.v4State?.takeIf { it.sessionId != id },
                    )
                } else {
                    nextCache.copy(bridgeRoster = nextCache.bridgeRoster.filterNot { it.sessionId == id })
                }
            } else {
                fact.state?.let { restored ->
                    nextCache = if (isZcode) {
                        nextCache.copy(
                            taskRoster = AgentStateLogic.mergeRoster(nextCache.taskRoster, listOf(restored)),
                            taskState = restored.takeIf { nextCache.taskState?.sessionId == id } ?: nextCache.taskState,
                        )
                    } else {
                        nextCache.copy(
                            bridgeRoster = AgentStateLogic.mergeRoster(nextCache.bridgeRoster, listOf(restored)),
                            v4State = nextCache.v4State?.takeIf { it.sessionId != id } ?: nextCache.v4State,
                        )
                    }
                }
            }
        }
        return AgentArchiveReduction(nextTruth, nextCache)
    }

    /** 来源快照对账：缺席出册，新的快照只更新在册成员，不复活墓碑。 */
    fun reconcileSource(
        truth: AgentArchiveTruth,
        cache: AgentArchiveCache,
        previous: List<AgentSessionState>,
        next: List<AgentSessionState>,
        source: String,
    ): AgentArchiveReduction {
        val dropped = AgentStateLogic.rosterIds(previous) - AgentStateLogic.rosterIds(next)
        val nextTruth = dropped.fold(truth) { acc, id -> acc.archive(id) }.observe(next)
        val nextCache = when (source) {
            "task",
            com.rearcue.poc.agent.AgentSources.ZCODE -> cache.copy(taskRoster = next)
            else -> cache.copy(bridgeRoster = next)
        }
        return AgentArchiveReduction(nextTruth, nextCache, dropped)
    }

    /** 兼容的显式归档/取消归档入口；来源边界优先走 [applyFacts]。 */
    fun applyExplicit(
        truth: AgentArchiveTruth,
        cache: AgentArchiveCache,
        sessionId: String,
        archived: Boolean,
        restored: AgentSessionState? = null,
    ): AgentArchiveReduction {
        val nextTruth = if (archived) truth.archive(sessionId) else truth.unarchive(sessionId, restored)
        val nextCache = if (archived) {
            cache.copy(
                taskRoster = cache.taskRoster.filterNot { it.sessionId == sessionId },
                bridgeRoster = cache.bridgeRoster.filterNot { it.sessionId == sessionId },
                taskState = cache.taskState?.takeIf { it.sessionId != sessionId },
                v4State = cache.v4State?.takeIf { it.sessionId != sessionId },
            )
        } else {
            val state = restored ?: nextTruth.currentRoster().firstOrNull { it.sessionId == sessionId }
            if (state == null) cache else cache.copy(
                taskRoster = if (state.source == com.rearcue.poc.agent.AgentSources.ZCODE) {
                    AgentStateLogic.mergeRoster(cache.taskRoster, listOf(state))
                } else cache.taskRoster,
                bridgeRoster = if (state.source != com.rearcue.poc.agent.AgentSources.ZCODE) {
                    AgentStateLogic.mergeRoster(cache.bridgeRoster, listOf(state))
                } else cache.bridgeRoster,
            )
        }
        return AgentArchiveReduction(nextTruth, nextCache)
    }

    /** 统一当前在册集：core 对账与两个屏幕都从这里读同一份集合。 */
    fun currentRoster(truth: AgentArchiveTruth): List<AgentSessionState> = truth.currentRoster()
}
