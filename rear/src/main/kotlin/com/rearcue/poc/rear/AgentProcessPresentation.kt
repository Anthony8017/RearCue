package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.SessionReadState

/** 按回合折叠过程；原始记录不变，展开时所有条目仍按原始顺序排列（spec 0028）。 */
object AgentProcessPresentation {
    data class Group(val choiceKey: String, val expanded: Boolean, val count: Int)
    data class Projection(val turns: List<AgentTurn>, val headers: Map<String, Group>)

    fun project(
        sessionId: String,
        turns: List<AgentTurn>,
        status: AgentStatus,
        choices: Map<String, Boolean>,
        pendingQuestion: Boolean = false,
    ): Projection {
        val roundKeys = mutableListOf<String>()
        var current = ""
        turns.forEach { turn ->
            current = when {
                !turn.roundId.isNullOrBlank() -> "round:${turn.roundId}"
                turn.role == AgentTurnRole.USER -> "prompt:${identity(turn)}"
                current.isEmpty() -> "prefix:${identity(turn)}"
                else -> current
            }
            roundKeys += current
        }
        val lastRound = roundKeys.lastOrNull()
        val endedRounds = turns.indices.filter { turns[it].roundComplete }.mapTo(hashSetOf()) { roundKeys[it] }
        val terminal = !pendingQuestion && (status == AgentStatus.IDLE || status == AgentStatus.ERROR)
        val indices = turns.indices.filter { isProcess(turns[it]) }.groupBy { roundKeys[it] }
        val groups = indices.mapValues { (key, positions) ->
            val choiceKey = "${sessionId.length}:$sessionId:$key"
            val completed = key in endedRounds || key != lastRound || terminal
            Group(choiceKey, choices[choiceKey] ?: !completed, positions.size)
        }
        val headers = linkedMapOf<String, Group>()
        val visible = buildList {
            turns.forEachIndexed { index, turn ->
                if (!isProcess(turn)) {
                    add(turn)
                } else {
                    val key = roundKeys[index]
                    val group = groups.getValue(key)
                    if (index == indices.getValue(key).first()) {
                        val headerId = "rearcue:process:$key"
                        headers[headerId] = group
                        add(AgentTurn(
                            role = AgentTurnRole.AGENT,
                            text = "${if (group.expanded) "▾" else "▸"} ${activitySummary(indices.getValue(key).map { turns[it] })} · ${group.count} 项",
                            kind = AgentTurnKind.NOTICE,
                            entryId = headerId,
                            ts = turn.ts,
                        ))
                    }
                    if (group.expanded) add(turn)
                }
            }
        }
        return Projection(visible, headers)
    }

    private val processKinds = setOf(AgentTurnKind.PROGRESS, AgentTurnKind.THINKING, AgentTurnKind.TOOL, AgentTurnKind.TOOL_RESULT)
    fun isProcess(turn: AgentTurn): Boolean = turn.role == AgentTurnRole.AGENT &&
        (turn.kind in processKinds || (turn.kind == AgentTurnKind.ERROR && turn.resolved))

    /** Brief activity labels derived from observable actions; never rewrite the final reply. */
    private fun activitySummary(entries: List<AgentTurn>): String {
        val actions = linkedSetOf<String>()
        entries.filter { it.kind == AgentTurnKind.TOOL }.forEach { entry ->
            val action = "${entry.toolName.orEmpty()} ${entry.command.orEmpty()} ${entry.text} ${entry.detail.orEmpty()}"
            if (Regex("(?i)read_file|Get-Content|\\brg\\b|readFile").containsMatchIn(action)) actions += "读取文件"
            if (Regex("(?i)web.*(?:search|run)|web_search|search_query|Invoke-WebRequest").containsMatchIn(action)) actions += "查阅资料"
            if (Regex("(?i)apply_patch|fileChange|write_file|Set-Content").containsMatchIn(action)) actions += "修改文件"
            if (Regex("(?i)--test|\\bunittest\\b|\\bpytest\\b|gradlew.*(?:test|assemble)|npm (?:run )?test").containsMatchIn(action)) actions += "运行验证"
        }
        if (actions.isEmpty()) {
            if (entries.any { it.kind == AgentTurnKind.PROGRESS }) actions += "进度说明"
            if (entries.any { it.kind == AgentTurnKind.THINKING }) actions += "思考记录"
            if (entries.any { it.kind == AgentTurnKind.TOOL || it.kind == AgentTurnKind.TOOL_RESULT }) actions += "工具执行"
            if (entries.any { it.kind == AgentTurnKind.ERROR }) actions += "已恢复报错"
        }
        return "过程 · ${actions.take(3).joinToString("、") }"
    }

    fun identity(turn: AgentTurn): String = turn.entryId?.takeIf { it.isNotBlank() }
        ?: "${turn.ts}:${turn.role}:${turn.text.hashCode()}"
}

/** 列表选定前判断入场资格；自动选台和待处理会话不套用阅读锚点。 */
internal object AgentEntryAnchor {
    data class Request(val sessionId: String, val generation: Long)
    data class Geometry(val topPadding: Int, val bottomPadding: Int, val scroll: Int)

    fun eligible(row: AgentPickerRow?): Boolean = row?.sessionId != null &&
        row.status == AgentStatus.IDLE && row.readState == SessionReadState.UNREAD

    fun latestPrompt(turns: List<AgentTurn>): AgentTurn? = turns.lastOrNull { it.role == AgentTurnRole.USER }

    /** 末尾留白只补到锚点可达；短内容也能把最后提问首行放到标题/渐隐下方。 */
    fun geometry(viewportHeight: Int, readableTop: Int, contentHeight: Int, promptTextTop: Int): Geometry {
        val top = readableTop.coerceAtLeast(0)
        val target = promptTextTop.coerceAtLeast(0)
        val bottom = (viewportHeight - top - contentHeight + target).coerceAtLeast(0)
        return Geometry(top, bottom, target)
    }
}
