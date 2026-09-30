package com.rearcue.poc.agent

/**
 * 会话标题与来源标记的单处纯逻辑（spec 0016 / 票 #154）：主屏列表、背屏列表与
 * 背屏会话标识行共同消费，不允许各面再自行拼 workspace / sessionId。
 */
object AgentSessionDisplay {

    private const val SESSION_TAIL_LENGTH = 4

    /**
     * 单条会话标题：真实 [AgentSessionState.title] 优先（ZCode 取会话索引标题），
     * 其次 workspace 目录名，最后 sessionId 尾 4 位兜底。空白标题按没给处理。
     */
    fun title(session: AgentSessionState): String =
        session.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: workspaceDirectoryName(session.workspace)
            ?: sessionTail(session.sessionId)

    /**
     * 同一份在册集的标题表：同目录重名时全部附 sessionId 尾 4 位，保证列表可区分。
     * 输入中的重复 sessionId 由调用方先去重；这里仍按 sessionId 产出，调用方按键取用。
     */
    fun titles(roster: List<AgentSessionState>): Map<String, String> {
        val unique = roster.distinctBy { it.sessionId }
        val baseBySession = unique.associate { it.sessionId to title(it) }
        val counts = baseBySession.values.groupingBy { it }.eachCount()
        return baseBySession.mapValues { (sessionId, base) ->
            if ((counts[base] ?: 0) > 1) "$base · ${sessionTail(sessionId)}" else base
        }
    }

    /** workspace 的目录名；空 / 纯分隔符回 null，交给尾 4 位兜底。 */
    fun workspaceDirectoryName(workspace: String?): String? {
        val trimmed = workspace?.trim()?.trimEnd('/', '\\') ?: return null
        if (trimmed.isBlank()) return null
        val end = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        return trimmed.substring(end + 1).takeIf { it.isNotBlank() }
    }

    /** sessionId 尾 4 位；短 id 原样返回。 */
    fun sessionTail(sessionId: String): String = sessionId.takeLast(SESSION_TAIL_LENGTH)

    /** 来源标记文案：稳定中英混排词形；未知来源原样透出，旧事件 null 不显示。 */
    fun sourceLabel(source: String?): String? {
        val raw = source?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (raw.lowercase()) {
            AgentSources.ZCODE -> "ZCode"
            AgentSources.CODEX -> "Codex"
            AgentSources.CLAUDE -> "Claude"
            AgentSources.DSH -> "DSH"
            else -> raw
        }
    }
}