package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.SessionReadState

/** 按回合折叠过程；原始记录不变，展开时所有条目仍按原始顺序排列（spec 0028）。 */
internal object AgentProcessPresentation {
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
                            text = "${if (group.expanded) "▾" else "▸"} 过程 · ${group.count} 项",
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

    private val processKinds = setOf(AgentTurnKind.THINKING, AgentTurnKind.TOOL, AgentTurnKind.TOOL_RESULT)
    fun isProcess(turn: AgentTurn): Boolean = turn.role == AgentTurnRole.AGENT && turn.kind in processKinds

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
