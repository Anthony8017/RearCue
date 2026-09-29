package com.rearcue.poc.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        /** 来源（codex / claude / dsh）；旧事件缺省 null。 */
        val source: String? = null,
        /**
         * 一句话摘要（spec 0018-1 契约留位）：可缺省，缺省退化为 null（不认识该字段的旧桥不发）。
         */
        val summary: String? = null,
        /**
         * 问答流（spec 0017 / 票 #169）：机主提问与 agent 输出按时间顺序。
         * 空列表 ＝ 桥未升级（旧事件只有 [latestReply]），手机端回落旧口径渲染。
         */
        val turns: List<AgentTurn> = emptyList(),
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
                summary = o.str("summary"),
                turns = o.turns(),
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 在册快照（`GET /snapshot`，spec 0016 / 票 #155）：桥当前在册会话键集及其最小字段
     * → [AgentSessionState]（键加 [SESSION_PREFIX]，与事件同一键空间）。手机每次链路重新
     * 连上后对账一次——快照是「桥侧现状」的权威答复，不在快照里的桥会话才算确实不在册。
     *
     * 容错同 [parsePage]：页面不可解析（字段漂移/非法 JSON）→ null（调用方保锁等下轮，不崩）；
     * 单条坏（缺 sessionId / 未知 status）→ 跳过该条，其余照常。空快照是**合法答复**
     * （桥在册为空 ⇒ 空列表），与解析失败的 null 不是一回事。
     */
    fun parseSnapshot(body: String): List<AgentSessionState>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val sessions = root["sessions"]?.jsonArray ?: return null
        sessions.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val sessionId = o.str("sessionId") ?: return@mapNotNull null
            val status = o.str("status") ?: return@mapNotNull null
            toSessionState(
                sessionId = sessionId,
                workspace = o.str("workspace"),
                status = status,
                currentAction = o.str("currentAction"),
                latestReply = o.str("latestReply"),
                source = o.str("source"),
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 来源能力表（`GET /snapshot` 的 `capabilities`，spec 0018-2 / 票 #172）：桥对每来源声明
     * 能力词（[SourceCapabilities.WAITING] 等），批准入口判定（票 #174）读它。
     * 容错同族：整块坏 / 旧桥没发 → [SourceCapabilities.DEFAULTS]（照常工作，只是无桥侧声明）；
     * 单来源坏（非字符串数组）→ 跳过该来源。能力表是增量声明，**永不因它挡快照**。
     */
    fun parseCapabilities(body: String): SourceCapabilities = try {
        val declared = json.parseToJsonElement(body).jsonObject["capabilities"] as? JsonObject
            ?: return SourceCapabilities.DEFAULTS
        val map = mutableMapOf<String, Set<String>>()
        for ((key, value) in declared) {
            val array = value as? JsonArray ?: continue
            val words = array.mapNotNull { el ->
                // 能力词只认字符串原语：数字/布尔/JSON null 一律当坏词跳过（isString 全挡）。
                (el as? JsonPrimitive)?.takeIf { it.isString }?.content
            }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            if (words.isEmpty()) continue
            map[key.trim().lowercase()] = words
        }
        if (map.isEmpty()) {
            SourceCapabilities.DEFAULTS
        } else {
            SourceCapabilities.merge(SourceCapabilities.DEFAULTS, SourceCapabilities(map))
        }
    } catch (_: Exception) {
        SourceCapabilities.DEFAULTS
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
    fun toSessionState(event: BridgeEvent): AgentSessionState? = toSessionState(
        sessionId = event.sessionId,
        workspace = event.workspace,
        status = event.status,
        currentAction = event.currentAction,
        latestReply = event.latestReply,
        source = event.source,
        summary = event.summary,
        turns = event.turns,
    )

    /** 事件与快照共用的字段映射（一处口径：status 词表、键前缀、到达时间戳、source 归一）。 */
    private fun toSessionState(
        sessionId: String,
        workspace: String?,
        status: String,
        currentAction: String?,
        latestReply: String?,
        source: String?,
        summary: String? = null,
        turns: List<AgentTurn> = emptyList(),
    ): AgentSessionState? {
        val normalized = when (status) {
            "working" -> AgentStatus.WORKING
            "waiting" -> AgentStatus.WAITING_FOR_APPROVAL
            "idle" -> AgentStatus.IDLE
            // 会话级出错（spec 0018-3）：词表第四词——来源报错即认，为「出错」提醒供源。
            "error" -> AgentStatus.ERROR
            else -> return null
        }
        return AgentSessionState(
            sessionId = SESSION_PREFIX + sessionId,
            workspace = workspace,
            status = normalized,
            currentAction = currentAction,
            latestReply = latestReply,
            // 到达时间戳，不用桥侧 PC 时钟（评审：跨源 updatedAt 同档比较——ZCode 与桥
            // 若来自不同机器，时钟偏差会扭曲多会话仲裁；到达时间与手机时钟同源）。
            updatedAt = System.currentTimeMillis(),
            source = source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            summary = summary,
            turns = turns,
        )
    }

    /**
     * 解一条事件的 `turns`（spec 0017 / 票 #169）。容错口径与事件同族：单条坏（角色不认识 /
     * 正文为空）→ 跳过该条、其余照常；`turns` 整块不是数组 → 当没有（回落旧字段）。
     */
    private fun JsonObject.turns(): List<AgentTurn> = runCatching {
        this["turns"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val role = when (o.str("role")) {
                "user" -> AgentTurnRole.USER
                "assistant" -> AgentTurnRole.AGENT
                else -> return@mapNotNull null
            }
            val text = o.str("text") ?: return@mapNotNull null
            AgentTurn(
                role = role,
                text = text,
                ts = o.long("ts") ?: 0L,
                open = o.boolean("open"),
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 取一个可选字符串字段。**JSON null 与缺键同义**（票 #156 实机验收发现）：桥侧统一事件
     * 里的可选字段会显式发 `null`（`"workspace":null`），而 [kotlinx.serialization.json.JsonNull]
     * 也是 [kotlinx.serialization.json.JsonPrimitive]，直接取 `content` 会得到字符串 "null"，
     * 一路走到背屏列表上变成一行标题「null」。
     */
    private fun JsonObject.str(key: String): String? =
        runCatching {
            this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()

    /** 可选布尔：缺键 / JSON null / 非布尔都当「未给」（不抛）。 */
    private fun JsonObject.boolean(key: String): Boolean =
        runCatching {
            this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        }.getOrNull() ?: false
}
