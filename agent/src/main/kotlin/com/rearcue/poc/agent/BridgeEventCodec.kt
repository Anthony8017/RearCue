package com.rearcue.poc.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PC 桥统一会话事件的解码（ADR 0006 / 票 #116，纯 JVM——判例沿 [TaskListParser]）：
 * 桥（tools/bridge）长轮询响应 `{"events":[...],"cursor":N}` → [BridgeEvent] 列表 →
 * [AgentSessionState]（喂 DashboardEvent.AgentSessionUpdated 的事实面，与 ZCode 源同一种模型）。
 *
 * 容错契约（ADR 0006 后果：会话文件/hooks 是非稳定接口，桥须扛版本变更）：
 * - 页面结构不可解析（字段类型漂移/非法 JSON）→ 返回 null（调用方按断线重连处理，不崩）；
 * - 单条事件坏（未知 status、缺 sessionId）→ 跳过该条、其余照常（部分降级不整页丢）；
 * - status 词表：working|waiting|idle（桥的归一词表，等待批准=waiting）。
 *
 * sessionId 一律加 [SESSION_PREFIX]：桥事件与 ZCode 中继会话可能同名，core 的
 * agentSessions 按 sessionId 记账，前缀隔离两源的键空间（同会话不双计、仲裁照常）。
 */
object BridgeEventCodec {

    /** 桥源 sessionId 前缀：`bridge:<原始 id>`。 */
    const val SESSION_PREFIX = "bridge:"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 桥的一条会话事件（统一模型；[id] 是桥内单调递增游标）。 */
    data class BridgeEvent(
        val id: Long,
        val sessionId: String,
        val workspace: String?,
        val status: String,
        val currentAction: String?,
        val latestReply: String?,
        val updatedAt: Long,
        /** 来源（codex / claude）；旧事件缺省 null。 */
        val source: String? = null,
    )

    /** 解码一页长轮询响应；页面不可解析返回 null（区别于「空页」的空列表）。 */
    fun parsePage(body: String): List<BridgeEvent>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val events = root["events"]?.jsonArray ?: return null
        events.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val sessionId = o.str("sessionId") ?: return@mapNotNull null
            val status = o.str("status") ?: return@mapNotNull null
            BridgeEvent(
                id = o.long("id") ?: return@mapNotNull null,
                sessionId = sessionId,
                workspace = o.str("workspace"),
                status = status,
                currentAction = o.str("currentAction"),
                latestReply = o.str("latestReply"),
                updatedAt = o.long("updatedAt") ?: 0L,
                source = o.str("source"),
            )
        }
    } catch (_: Exception) {
        null
    }

    /** 游标（响应的 `cursor`；缺省取末条事件 id，再缺省 null → 调用方保持原游标）。 */
    fun parseCursor(body: String): Long? = try {
        json.parseToJsonElement(body).jsonObject["cursor"]?.jsonPrimitive?.content?.toLongOrNull()
    } catch (_: Exception) {
        null
    }

    /**
     * 事件 → 镜像事实：status 归一到 [AgentStatus]（未知词 → null，事件被跳过），
     * sessionId 加前缀；其余字段原样搬运（不打码边界沿 spec 0010）。
     */
    fun toSessionState(event: BridgeEvent): AgentSessionState? {
        val status = when (event.status) {
            "working" -> AgentStatus.WORKING
            "waiting" -> AgentStatus.WAITING_FOR_APPROVAL
            "idle" -> AgentStatus.IDLE
            else -> return null
        }
        return AgentSessionState(
            sessionId = SESSION_PREFIX + event.sessionId,
            workspace = event.workspace,
            status = status,
            currentAction = event.currentAction,
            latestReply = event.latestReply,
            // 到达时间戳，不用桥侧 PC 时钟（评审：跨源 updatedAt 同档比较——ZCode 与桥
            // 若来自不同机器，时钟偏差会扭曲多会话仲裁；到达时间与手机时钟同源）。
            updatedAt = System.currentTimeMillis(),
            source = event.source,
        )
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()
}
