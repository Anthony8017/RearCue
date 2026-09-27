package com.rearcue.poc.agent

import kotlinx.serialization.json.JsonObject

/**
 * Conversation V4 snapshot/delta → [AgentSessionState] 归一化。
 *
 * ⚠️ 精确 wire 字段待 T1 phase B（真凭据实抓）回填；[rowFrom] 是唯一调整点，
 * 其余逻辑（状态推导、最新回复、当前动作摘要）已按 ZCode part 模型钉死在单测。
 */
class ConversationProjector(
    private val sessionId: String,
) {
    private val rows = LinkedHashMap<Long, AgentRow>()

    /** 快照：全量替换（v4/conversation/subscribe 的 snapshot 或 resync）。 */
    fun reset(rowsJson: JsonObject) {
        rows.clear()
        val elements = rowsJson["rows"] as? kotlinx.serialization.json.JsonArray
            ?: (rowsJson["snapshot"] as? kotlinx.serialization.json.JsonArray)
            ?: return
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

    fun project(nowMs: Long): AgentSessionState {
        val ordered = rows.values.sortedBy { it.rowId }
        val runningTool = ordered.lastOrNull { it.type == "tool" && it.toolStatus in RUNNING_STATUSES }
        val pendingApproval = ordered.any { it.type == "tool" && it.toolStatus == STATUS_PENDING_APPROVAL }
        val latestReply = ordered.lastOrNull { it.type == "text" && it.role == "assistant" }?.text
            ?: ordered.lastOrNull { it.type == "text" && it.role != "user" }?.text
        val turnOpen = ordered.indexOfLast { it.type == "step-start" } > ordered.indexOfLast { it.type == "step-finish" }
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
        )
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
        private val RUNNING_STATUSES = setOf("running", "inputStreaming")

        /**
         * 容错行解析：字段名取多个候选（wire 精确名 phase B 回填后收紧）。
         * tool 输入摘要接受字符串或对象（对象取 toString 截断）。
         */
        fun rowFrom(obj: JsonObject): AgentRow? {
            val id = (obj["rowId"] ?: obj["id"] ?: obj["entityId"]).let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            } ?: return null
            val type = RelayEnvelope.primitiveOrNull(obj, "type")
                ?: RelayEnvelope.primitiveOrNull(obj, "kind")
                ?: return null
            val state = obj["state"] as? JsonObject
            val toolInput = obj["input"] ?: state?.get("input")
            return AgentRow(
                rowId = id,
                type = type,
                role = RelayEnvelope.primitiveOrNull(obj, "role"),
                text = RelayEnvelope.primitiveOrNull(obj, "text")
                    ?: RelayEnvelope.primitiveOrNull(obj, "content"),
                toolName = RelayEnvelope.primitiveOrNull(obj, "tool")
                    ?: RelayEnvelope.primitiveOrNull(obj, "toolName")
                    ?: RelayEnvelope.primitiveOrNull(obj, "name"),
                toolStatus = state?.let { RelayEnvelope.primitiveOrNull(it, "status") }
                    ?: RelayEnvelope.primitiveOrNull(obj, "status"),
                toolInputSummary = when (toolInput) {
                    null -> null
                    is kotlinx.serialization.json.JsonPrimitive -> toolInput.content
                    else -> toolInput.toString()
                },
            )
        }
    }
}
