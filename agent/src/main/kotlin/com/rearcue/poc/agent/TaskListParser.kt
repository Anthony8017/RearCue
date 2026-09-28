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
     * 供「锁定会话不在册」判定（接线层喂 core 的在册对账事件）与后续 T2 列表 UI 复用；
     * [parse] 的单条语义（取 updatedAt 最大者，空/全归档 → null）由此派生、行为不变。
     */
    fun parseAll(text: String): List<AgentSessionState>? {
        val obj = runCatching { RelayEnvelope.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val result = obj["result"] as? JsonObject ?: return null
        val tasks = result["tasks"] as? kotlinx.serialization.json.JsonArray ?: return null
        return tasks
            .asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { task -> (task["archived"] as? JsonPrimitive)?.content != "true" }
            .mapNotNull { task ->
                val taskId = (task["taskId"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val status = when ((task["displayStatus"] as? JsonPrimitive)?.content) {
                    "running" -> AgentStatus.WORKING
                    else -> AgentStatus.IDLE
                }
                AgentSessionState(
                    sessionId = taskId,
                    workspace = (task["workspaceLabel"] as? JsonPrimitive)?.content,
                    status = status,
                    currentAction = (task["title"] as? JsonPrimitive)?.content,
                    latestReply = null,
                    updatedAt = (task["updatedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                )
            }
            .toList()
    }

    /** 订阅/轮询请求（workspace-list-request；票 #86 实测响应 ~0.7s）。 */
    fun listRequest(requestId: String): String =
        """{"zcode_type":"workspace-list-request","requestId":"$requestId"}"""

    /** bootstrap 请求（链路建立后首个控制帧，响应含全量任务表与视图状态）。 */
    fun bootstrapRequest(requestId: String): String =
        """{"zcode_type":"bootstrap-request","requestId":"$requestId"}"""
}
