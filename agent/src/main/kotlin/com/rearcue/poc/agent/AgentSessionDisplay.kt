package com.rearcue.poc.agent

/**
 * 会话显示名的单处派生（spec 0022 / issue #213、#249）：标识行、背屏选择器、主屏列表、
 * 状态行、通知提醒与锁定名全部从这里取，不允许各面自行拼 title / workspace / sessionId。
 */
data class AgentSessionDisplay(
    /** 主行：agent 当前标题 → 机主首条提问首行 → workspace 目录名 → 未命名会话。 */
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
        private const val UNTITLED_SESSION = "未命名会话"

        /** 单条显示派生；同名会话按机主定案原样重复，不生成任何 sessionId 消歧尾码。 */
        fun forState(session: AgentSessionState): AgentSessionDisplay = AgentSessionDisplay(
            title = baseTitle(session),
            subtitle = subtitleFor(session),
        )

        /**
         * 同一份在册集的显示表。输入中的重复 sessionId 由调用方先去重；同名主行原样重复，
         * 不附编号或 sessionId 尾码（2026-10-02 机主定案）。
         */
        fun forRoster(roster: List<AgentSessionState>): Map<String, AgentSessionDisplay> =
            roster.distinctBy { it.sessionId }.associate { session ->
                session.sessionId to forState(session)
            }

        /** 无在册上下文的主行兜底：绝不再把 sessionId 全串或尾码送上界面。 */
        fun fallback(@Suppress("UNUSED_PARAMETER") sessionId: String): AgentSessionDisplay =
            AgentSessionDisplay(title = UNTITLED_SESSION, subtitle = null)

        /** 单条主行（无列表撞名上下文）。 */
        fun title(session: AgentSessionState): String = forState(session).title

        /** 同列标题表（兼容投影/测试读取；副行请用 [forRoster]）。 */
        fun titles(roster: List<AgentSessionState>): Map<String, String> =
            forRoster(roster).mapValues { (_, display) -> display.title }

        /** workspace 的目录名；空 / 纯分隔符回 null，交给「未命名会话」兜底。 */
        fun workspaceDirectoryName(workspace: String?): String? {
            val trimmed = workspace?.trim()?.trimEnd('/', '\\') ?: return null
            if (trimmed.isBlank()) return null
            val end = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
            return trimmed.substring(end + 1).takeIf { it.isNotBlank() }
        }

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
                ?: firstQuestion(session)
                ?: workspaceDirectoryName(session.workspace)
                ?: UNTITLED_SESSION

        /** 无真标题时显示机主首条提问的首行；正文尾部省略由各显示面负责。 */
        private fun firstQuestion(session: AgentSessionState): String? =
            session.turns.firstOrNull { it.role == AgentTurnRole.USER }
                ?.text
                ?.trim()
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()

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
