package com.rearcue.poc.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 桌面任务表 → AgentSessionState 归一化（spec 0010 / 票 #86 phase B 实测回填）。
 *
 * 真实 wire（2026-09-27 capture）：workspace-list-request 的响应 payload =
 * `{requestId, result:{tasks:[{taskId,title,displayStatus:"running"|"completed"|"error",
 * updatedAt,workspaceLabel,archived?,...}], activeWorkspaceKey, activeTaskId}}`。
 * 任务表随桌面会话实时更新——一期镜像以它为状态源（status 级）；回复原文走 v4 bridge，
 * 留待后续（ConversationFeed 保留）。
 *
 * 选择规则：排除 archived，updatedAt 最新者优先——与 core 仲裁的「最近活跃」一致，
 * 等确认插队仍归 core（任务表无等待语义，等确认检测在 v4 桥接落地前由 debug 注入演示）。
 */
object TaskListParser {

    /** 从一条控制面 payload 文本解析任务表并归一化；非任务响应返回 null。 */
    fun parse(text: String): AgentSessionState? = parseAll(text)?.let { latest(it) }

    /**
     * 单条派生（updatedAt 最大者；空/全无键回 null）：[parse] 与接线层「任务表最新一条」
     * 共用这一处口径，不再各写一份 `maxByOrNull`（票 #103 修复清单去重项）。
     */
    fun latest(roster: List<AgentSessionState>): AgentSessionState? = roster.maxByOrNull { it.updatedAt }

    /**
     * 全部会话解析（票 #103）：与 [parse] 完全同口径（排除 archived、字段映射一致），
     * 只是回**全部**未归档会话（保任务表原序、不排序）；非任务响应回 null、
     * 任务响应但表空回空列表（「在册为空」是有效事实，与「非任务响应」可区分）。
     */
    fun parseAll(text: String): List<AgentSessionState>? =
        taskRows(text)?.mapNotNull { row -> stateFor(row.task).takeIf { !row.archived } }

    /**
     * 任务表快照 → 来源在册事实（spec 0023 / 票 #236）。
     *
     * 任务表是 ZCode 的归档真值：表内 `archived:true` 发 `ARCHIVED` 墓碑，
     * 未归档行发 `ACTIVE` 正事实；[previouslySeen] 里有、但本代任务表没有的 id
     * 发 `ABSENT`（来源移除，不猜归档）。旧代/旧序号由 [AgentMembershipFact] 比较拦截。
     */
    fun parseMembership(
        text: String,
        generation: Long,
        previouslySeen: Set<String> = emptySet(),
        revision: Long = generation,
    ): List<AgentMembershipFact>? {
        val rows = taskRows(text) ?: return null
        val facts = rows.mapNotNull { row ->
            val state = stateFor(row.task) ?: return@mapNotNull null
            when {
                row.archived -> AgentMembershipFact.archived(
                    source = AgentSources.ZCODE,
                    sourceSessionId = state.sessionId,
                    generation = generation,
                    revision = revision,
                    state = state,
                )
                else -> AgentMembershipFact.active(
                    source = AgentSources.ZCODE,
                    sourceSessionId = state.sessionId,
                    generation = generation,
                    revision = revision,
                    reason = AgentMembershipReason.AUTHORITATIVE_SNAPSHOT,
                    state = state,
                )
            }
        }
        val seen = rows.mapNotNull { row -> stateFor(row.task)?.sessionId }.toSet()
        val removed = (previouslySeen - seen).map { sessionId ->
            AgentMembershipFact.absent(
                source = AgentSources.ZCODE,
                sourceSessionId = sessionId,
                generation = generation,
                revision = revision,
            )
        }
        return facts + removed
    }

    /** 订阅/轮询请求（workspace-list-request；票 #86 实测响应 ~0.7s）。 */
    fun listRequest(requestId: String): String =
        """{"zcode_type":"workspace-list-request","requestId":"$requestId"}"""

    /** bootstrap 请求（链路建立后首个控制帧，响应含全量任务表与视图状态）。 */
    fun bootstrapRequest(requestId: String): String =
        """{"zcode_type":"bootstrap-request","requestId":"$requestId"}"""

    private data class TaskRow(val task: JsonObject, val archived: Boolean)

    private fun taskRows(text: String): List<TaskRow>? {
        val obj = runCatching { RelayEnvelope.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val result = obj["result"] as? JsonObject ?: return null
        val tasks = result["tasks"] as? kotlinx.serialization.json.JsonArray ?: return null
        return tasks.mapNotNull { it as? JsonObject }.map { task ->
            TaskRow(task = task, archived = (task["archived"] as? JsonPrimitive)?.content == "true")
        }
    }

    private fun stateFor(task: JsonObject): AgentSessionState? {
        val taskId = (task["taskId"] as? JsonPrimitive)?.content ?: return null
        val status = when ((task["displayStatus"] as? JsonPrimitive)?.content) {
            "running" -> AgentStatus.WORKING
            else -> AgentStatus.IDLE
        }
        return AgentSessionState(
            sessionId = taskId,
            workspace = (task["workspaceLabel"] as? JsonPrimitive)?.content,
            status = status,
            currentAction = (task["title"] as? JsonPrimitive)?.content,
            latestReply = null,
            updatedAt = (task["updatedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            source = AgentSources.ZCODE,
        )
    }
}
