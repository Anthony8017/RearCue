package com.rearcue.poc.agent

private val KNOWN_MEMBERSHIP_SOURCES =
    setOf(AgentSources.ZCODE, AgentSources.CODEX, AgentSources.CLAUDE, AgentSources.DSH)

/**
 * 来源侧的在册/归档事实（spec 0023 / 票 #236）。
 *
 * 这是 source boundary 与 AgentArchiveTruth 之间的最小契约：每条事实只回答
 * `(source, sourceSessionId)` 这个来源键当前是否仍在未归档在册集合。
 * `generation` 是来源快照/生命周期代数，`revision` 是同代内的单调序号；
 * 旧代或同代旧序号的事实和活动都不能覆盖墓碑。
 *
 * `PRESENT/ACTIVE` 是重新进入的唯一正事实；`ABSENT` 或 `ARCHIVED` 都是墓碑。
 * 归档、来源移除、删除和进程退出保持不同 reason，只有 `ARCHIVED` 后的 `ACTIVE`
 * 证明可逆归档。
 */
data class AgentSourceSession(
    val source: String,
    val sourceSessionId: String,
) {
    init {
        require(source in KNOWN_MEMBERSHIP_SOURCES || source == "legacy") { "unsupported source: $source" }
        require(sourceSessionId.isNotBlank()) { "sourceSessionId must not be blank" }
    }

    /** 保持既有键前缀：ZCode 原样，桥来源沿 `bridge:<原始 id>`。 */
    val sessionId: String
        get() = if (source == AgentSources.ZCODE || source == "legacy") {
            sourceSessionId
        } else {
            BridgeEventCodec.SESSION_PREFIX + sourceSessionId
        }

    companion object {
        /** 从镜像事实反推来源键；桥来源剥掉既有 `bridge:` 前缀，不改键空间。 */
        fun fromSessionState(state: AgentSessionState): AgentSourceSession {
            val source = state.source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "legacy"
            val raw = if (source != AgentSources.ZCODE && state.sessionId.startsWith(BridgeEventCodec.SESSION_PREFIX)) {
                state.sessionId.removePrefix(BridgeEventCodec.SESSION_PREFIX)
            } else {
                state.sessionId
            }
            return AgentSourceSession(source = source, sourceSessionId = raw)
        }
    }
}

/** 在册集合的成员状态。 */
enum class AgentMembership {
    PRESENT,
    ABSENT,
}

/** 归档生命周期；`UNKNOWN` 只表示来源没有归档语义，不代表活动停止。 */
enum class AgentArchiveState {
    ACTIVE,
    ARCHIVED,
    UNKNOWN,
}

/** 事实来源语义，避免把活动/文件/进程生命周期误写成归档。 */
enum class AgentMembershipReason {
    AUTHORITATIVE_SNAPSHOT,
    SOURCE_CREATED,
    SOURCE_REMOVED,
    ARCHIVE,
    UNARCHIVE,
    MEMBERSHIP_CONTRACT,
    UNKNOWN,
}

/**
 * 一条来源在册事实。[state] 只在来源同时给出当前状态时携带；
 * 墓碑可以先于状态到达，之后的活动仍由 AgentArchiveTruth 按墓碑拦截。
 */
data class AgentMembershipFact(
    val source: String,
    val sourceSessionId: String,
    val generation: Long,
    val revision: Long = generation,
    val membership: AgentMembership,
    val archiveState: AgentArchiveState,
    val reason: AgentMembershipReason,
    val state: AgentSessionState? = null,
) {
    init {
        require(source in KNOWN_MEMBERSHIP_SOURCES) { "unsupported membership source: $source" }
        require(generation >= 0) { "generation must be >= 0" }
        require(revision >= 0) { "revision must be >= 0" }
        require(state == null || state.sessionId == identity.sessionId) {
            "state must use the same canonical session identity"
        }
    }

    val identity: AgentSourceSession
        get() = AgentSourceSession(source = source, sourceSessionId = sourceSessionId)

    /** 墓碑：离开在册或明确归档都立即隐藏；只有正事实可恢复。 */
    val tombstone: Boolean
        get() = membership == AgentMembership.ABSENT || archiveState == AgentArchiveState.ARCHIVED

    /** 同来源同键的代数/序号比较；旧事实不能覆盖新事实。 */
    fun newerThan(otherGeneration: Long, otherRevision: Long): Boolean =
        generation > otherGeneration || (generation == otherGeneration && revision > otherRevision)

    companion object {
        fun active(
            source: String,
            sourceSessionId: String,
            generation: Long,
            revision: Long = generation,
            reason: AgentMembershipReason = AgentMembershipReason.MEMBERSHIP_CONTRACT,
            state: AgentSessionState? = null,
        ) = AgentMembershipFact(
            source = source,
            sourceSessionId = sourceSessionId,
            generation = generation,
            revision = revision,
            membership = AgentMembership.PRESENT,
            archiveState = AgentArchiveState.ACTIVE,
            reason = reason,
            state = state,
        )

        fun archived(
            source: String,
            sourceSessionId: String,
            generation: Long,
            revision: Long = generation,
            reason: AgentMembershipReason = AgentMembershipReason.ARCHIVE,
            state: AgentSessionState? = null,
        ) = AgentMembershipFact(
            source = source,
            sourceSessionId = sourceSessionId,
            generation = generation,
            revision = revision,
            membership = AgentMembership.ABSENT,
            archiveState = AgentArchiveState.ARCHIVED,
            reason = reason,
            state = state,
        )

        fun absent(
            source: String,
            sourceSessionId: String,
            generation: Long,
            revision: Long = generation,
            reason: AgentMembershipReason = AgentMembershipReason.SOURCE_REMOVED,
            state: AgentSessionState? = null,
        ) = AgentMembershipFact(
            source = source,
            sourceSessionId = sourceSessionId,
            generation = generation,
            revision = revision,
            membership = AgentMembership.ABSENT,
            archiveState = AgentArchiveState.UNKNOWN,
            reason = reason,
            state = state,
        )
    }
}
