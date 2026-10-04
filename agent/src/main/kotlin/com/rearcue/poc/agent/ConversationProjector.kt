package com.rearcue.poc.agent

import kotlinx.serialization.json.JsonObject

/**
 * Conversation V4 snapshot/delta → [AgentSessionState] 归一化（票 #88 wire 实证回填）。
 *
 * 行 schema（app.asar host chunk-B7L5 联合 + 官方 web 客户端消费面）：
 * 基座 `{rowId, turnId, entityId?, createdAt, createdAtSeq}`，`kind ∈ turnHeader|userInput|
 * assistantText|reasoning|toolCall|artifact|subagent|...`；toolCall 的状态在**顶层** `status`
 * （inputStreaming|pendingApproval|running|success|error|cancelled），assistantText 带
 * `text` + `state(streaming|complete|...)`；turnHeader 带 `state(running|completedSuccess|...)`。
 *
 * [rowFrom] 把 wire kind 归一到本类使用的简写（assistantText/userInput→text、toolCall→tool），
 * 状态推导（等确认插队 / 进行中 / 最新回复）在 [project]。
 */
class ConversationProjector(
    private val sessionId: String,
) {
    private val rows = LinkedHashMap<Long, AgentRow>()

    /** 快照：全量替换（v4/conversation/subscribe 的 snapshot 或 resync）。 */
    fun reset(rowsJson: JsonObject) {
        val elements = rowsJson["rows"] as? kotlinx.serialization.json.JsonArray
            ?: (rowsJson["snapshot"] as? kotlinx.serialization.json.JsonArray)
            ?: return
        reset(elements)
    }

    /** 快照的行数组直入形态（[ConversationFeed] 的推送路径用，不绕道合成对象）。 */
    fun reset(elements: kotlinx.serialization.json.JsonArray) {
        rows.clear()
        for (element in elements) {
            (element as? JsonObject)?.let { rowFrom(it)?.let { row -> rows[row.rowId] = row } }
        }
    }

    /** 增量：单行 upsert（v4/frame 推送）。delete 走 [remove]。 */
    fun upsert(rowJson: JsonObject) {
        rowFrom(rowJson)?.let { rows[it.rowId] = it }
    }

    fun remove(rowId: Long) {
        rows.remove(rowId)
    }

    /** 容错批量入口：snapshot 形态（{rows:[...]}）、单行、{rows:[...]} 增量都吃。 */
    fun apply(element: kotlinx.serialization.json.JsonElement) {
        val obj = element as? JsonObject ?: return
        when {
            obj["rows"] is kotlinx.serialization.json.JsonArray ->
                for (item in (obj["rows"] as kotlinx.serialization.json.JsonArray)) {
                    (item as? JsonObject)?.let { upsert(it) }
                }
            else -> upsert(obj)
        }
    }

    /** `row.delta` 文本增量（path=ext 严格等于 "text" 才追加，其余路径忽略）。 */
    fun appendText(rowId: Long, delta: String) {
        val old = rows[rowId] ?: return
        rows[rowId] = old.copy(text = (old.text.orEmpty()) + delta)
    }

    /** `row.removed {fromRowId}`：保留 rowId < fromRowId，删除其后全部（官方 web 消费面语义）。 */
    fun removeFrom(fromRowId: Long) {
        rows.keys.filter { it >= fromRowId }.forEach { rows.remove(it) }
    }

    fun project(nowMs: Long): AgentSessionState {
        val ordered = rows.values.sortedBy { it.rowId }
        val runningTool = ordered.lastOrNull { it.type == "tool" && it.toolStatus in RUNNING_STATUSES }
        val pendingApproval = ordered.any { it.type == "tool" && it.toolStatus == STATUS_PENDING_APPROVAL }
        val latestReply = ordered.lastOrNull { it.type == "text" && it.role == "assistant" }?.text
            ?: ordered.lastOrNull { it.type == "text" && it.role != "user" }?.text
        val turnOpen = ordered.any { it.type == "turnHeader" && it.toolStatus == TURN_RUNNING } ||
            ordered.indexOfLast { it.type == "step-start" } > ordered.indexOfLast { it.type == "step-finish" }
        val status = when {
            pendingApproval -> AgentStatus.WAITING_FOR_APPROVAL
            turnOpen || runningTool != null -> AgentStatus.WORKING
            else -> AgentStatus.IDLE
        }
        return AgentSessionState(
            sessionId = sessionId,
            status = status,
            currentAction = runningTool?.let { summarizeAction(it) },
            latestReply = latestReply,
            updatedAt = nowMs,
            source = AgentSources.ZCODE,
            // 问答流（spec 0017 / 票 #169）：中继的行集里本来就有 userInput 行，
            // spec 0010 时代在投影这一步被整类丢掉——背屏只看得到无头无尾的回答。
            turns = turnsOf(ordered),
        )
    }

    /**
     * 行集 → 全信息问答流（spec 0026）：按行号（时间）顺序保留提问、回答、思考与工具摘要；
     * 工具输入原文进入 detail，背屏主流只显示摘要。步骤/产物等无法可靠解释的行仍不冒充正文。
     */
    private fun turnsOf(ordered: List<AgentRow>): List<AgentTurn> {
        val out = mutableListOf<AgentTurn>()
        for (row in ordered) {
            val role = if (row.role == "user") AgentTurnRole.USER else AgentTurnRole.AGENT
            val text = when (row.type) {
                "text" -> row.text?.trim().orEmpty()
                "reasoning" -> row.text?.trim().orEmpty()
                "tool" -> summarizeAction(row)
                else -> ""
            }
            if (text.isEmpty()) continue
            val kind = when {
                role == AgentTurnRole.USER -> AgentTurnKind.PROMPT
                row.type == "reasoning" -> AgentTurnKind.THINKING
                row.type == "tool" && row.toolStatus == "error" -> AgentTurnKind.ERROR
                row.type == "tool" && row.toolStatus in setOf("success", "completed") -> AgentTurnKind.TOOL_RESULT
                row.type == "tool" -> AgentTurnKind.TOOL
                else -> AgentTurnKind.ANSWER
            }
            val detail = row.toolInputSummary?.trim()?.takeIf { it.isNotEmpty() }
            val last = out.lastOrNull()
            if (last != null && last.role == role && last.kind == kind && last.text == text) continue
            out += AgentTurn(
                role = role,
                kind = kind,
                text = text,
                detail = detail,
                entryId = "zcode-row-${row.rowId}",
            )
        }
        return AgentTurns.window(out)
    }

    fun rowIds(): Set<Long> = rows.keys.toSet()

    private fun summarizeAction(row: AgentRow): String {
        val name = row.toolName ?: "tool"
        val input = row.toolInputSummary
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        val summary = if (input.isEmpty()) name else "$name $input"
        return if (summary.length > ACTION_SUMMARY_MAX_CHARS) {
            summary.take(ACTION_SUMMARY_MAX_CHARS - 1) + "…"
        } else {
            summary
        }
    }

    companion object {
        const val STATUS_PENDING_APPROVAL = "pendingApproval"

        /** turnHeader.state 进行中（其余态 completedSuccess/completedInterrupted/failed 均非进行中）。 */
        const val TURN_RUNNING = "running"
        private val RUNNING_STATUSES = setOf("running", "inputStreaming")

        /**
         * 容错行解析：字段名取多个候选（wire 精确名 phase B 回填后收紧）。
         * tool 输入摘要接受字符串或对象（对象取 toString 截断）。
         */
        fun rowFrom(obj: JsonObject): AgentRow? {
            val id = (obj["rowId"] ?: obj["id"] ?: obj["entityId"]).let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            } ?: return null
            val kind = RelayEnvelope.primitiveOrNull(obj, "kind")
                ?: RelayEnvelope.primitiveOrNull(obj, "type")
                ?: return null
            val type = when (kind) {
                "assistantText", "userInput" -> "text"
                "toolCall" -> "tool"
                else -> kind
            }
            val state = obj["state"] as? JsonObject
            val toolInput = obj["inputText"] ?: obj["input"] ?: state?.get("input")
            return AgentRow(
                rowId = id,
                type = type,
                role = when (kind) {
                    "assistantText" -> "assistant"
                    "userInput" -> "user"
                    else -> RelayEnvelope.primitiveOrNull(obj, "role")
                },
                text = RelayEnvelope.primitiveOrNull(obj, "text")
                    ?: RelayEnvelope.primitiveOrNull(obj, "content"),
                toolName = RelayEnvelope.primitiveOrNull(obj, "tool")
                    ?: RelayEnvelope.primitiveOrNull(obj, "toolName")
                    ?: RelayEnvelope.primitiveOrNull(obj, "name"),
                toolStatus = RelayEnvelope.primitiveOrNull(obj, "status")
                    ?: state?.let { RelayEnvelope.primitiveOrNull(it, "status") }
                    ?: if (kind == "turnHeader") RelayEnvelope.primitiveOrNull(obj, "state") else null,
                toolInputSummary = when (toolInput) {
                    null -> null
                    is kotlinx.serialization.json.JsonPrimitive -> toolInput.content
                    else -> toolInput.toString()
                },
            )
        }
    }
}
