package com.rearcue.poc.agent

/**
 * 会话显示名的单处派生（spec 0022 / issue #213）：标识行、背屏选择器、主屏列表、
 * 状态行、通知提醒与锁定名全部从这里取，不允许各面自行拼 title / workspace / sessionId。
 */
data class AgentSessionDisplay(
    /** 主行：真标题 → workspace 目录名 → sessionId 尾 4 位（同列撞名时已附尾号）。 */
    val title: String,
    /** 副行：「来源 · 目录名」；无目录名只剩来源；两者皆无时为 null。 */
    val subtitle: String? = null,
) {
    /** 状态行/锁定名的内联口径：「主行 · 副行」，副行本身已是「来源 · 目录名」。 */
    val inline: String
        get() = if (subtitle.isNullOrBlank()) title else "$title · $subtitle"

    /** 通知提醒正文里的会话名只取主行，不带来源/目录副行。 */
    fun notificationTitle(): String = title

    companion object {
        private const val SESSION_TAIL_LENGTH = 4

        /** 单条无撞名上下文的显示派生；列表面请用 [forRoster] 保证同列撞名口径一致。 */
        fun forState(session: AgentSessionState): AgentSessionDisplay = AgentSessionDisplay(
            title = baseTitle(session),
            subtitle = subtitleFor(session),
        )

        /**
         * 同一份在册集的显示表：同列主行撞名时全部附 sessionId 尾 4 位，保证列表可区分。
         * 输入中的重复 sessionId 由调用方先去重；这里仍按 sessionId 产出，调用方按键取用。
         */
        fun forRoster(roster: List<AgentSessionState>): Map<String, AgentSessionDisplay> {
            val unique = roster.distinctBy { it.sessionId }
            val baseBySession = unique.associate { it.sessionId to baseTitle(it) }
            val counts = baseBySession.values.groupingBy { it }.eachCount()
            return unique.associate { session ->
                val base = baseBySession.getValue(session.sessionId)
                val title = if ((counts[base] ?: 0) > 1) {
                    "$base · ${sessionTail(session.sessionId)}"
                } else {
                    base
                }
                session.sessionId to AgentSessionDisplay(title = title, subtitle = subtitleFor(session))
            }
        }

        /** 无在册上下文的主行兜底：只允许退到 sessionId 尾 4 位，绝不显示全串。 */
        fun fallback(sessionId: String): AgentSessionDisplay =
            AgentSessionDisplay(title = sessionTail(sessionId), subtitle = null)

        /** 单条主行（无列表撞名上下文）。 */
        fun title(session: AgentSessionState): String = forState(session).title

        /** 同列标题表（兼容投影/测试读取；副行请用 [forRoster]）。 */
        fun titles(roster: List<AgentSessionState>): Map<String, String> =
            forRoster(roster).mapValues { (_, display) -> display.title }

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

        private fun baseTitle(session: AgentSessionState): String =
            session.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: workspaceDirectoryName(session.workspace)
                ?: sessionTail(session.sessionId)

        private fun subtitleFor(session: AgentSessionState): String? {
            val source = sourceLabel(session.source)
            val directory = workspaceDirectoryName(session.workspace)
            return when {
                source != null && directory != null -> "$source · $directory"
                source != null -> source
                else -> directory
            }
        }
    }
}
