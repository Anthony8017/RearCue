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

    /** 桥源 sessionId 前缀：`bridge:`；新键为 `bridge:<source>:<原始 id>`。 */
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
        /**
         * 等待应答的选择题选项（spec 0018-4 / 票 #174）：非空＝提问类等待（批准入口按选项
         * 点选渲染）。可缺省，旧桥不发即空列表（确认类等待的正常形态）。
         */
        val pendingOptions: List<AgentPendingOption> = emptyList(),
        /**
         * 批准无果标记（review 2026-09-30 / spec 0018-4 AC3 补遗）：桥侧对「已受理（accepted）
         * 之后无人取走/请求过期」的会话动作发一条终态——手机据此走失败提示链（不悬挂、不重试）。
         * 只在带标记的那条事件上为真，不粘连后续事件。
         */
        val actionExpired: Boolean = false,
    )

    /** 解码一页长轮询响应；页面不可解析返回 null（区别于「空页」的空列表）。 */
    fun parsePage(body: String): List<BridgeEvent>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val events = root["events"]?.jsonArray ?: return null
        events.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            // membership 是生命周期事实，不是活动状态；它必须走 parseMembershipPage，
            // 否则带 status 的恢复事实会被当成普通活动绕过代数保护。
            if (o.str("kind") == "membership") return@mapNotNull null
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
                pendingOptions = o.pendingOptions(),
                actionExpired = o.boolean("actionExpired"),
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 来源在册事实页（spec 0023 / 票 #236）：与普通状态事件同页时按 `kind:"membership"`
     * 过滤；旧桥没有该事件时返回空列表。整页坏仍返回 null，单条坏跳过。
     */
    fun parseMembershipPage(body: String): List<AgentMembershipFact>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val events = root["events"]?.jsonArray ?: return null
        events.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            if (o.str("kind") != "membership") return@mapNotNull null
            membershipFact(o)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 快照里的来源在册事实（重连对账）：`memberships` 是权威代数快照；
     * 旧桥缺字段时返回空列表，不把「没有契约」误判为「全部出册」。
     */
    fun parseMembershipSnapshot(body: String): List<AgentMembershipFact>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val memberships = root["memberships"]?.jsonArray ?: return emptyList()
        memberships.mapNotNull { element ->
            membershipFact(element as? JsonObject ?: return@mapNotNull null)
        }
    } catch (_: Exception) {
        null
    }

    private fun membershipFact(o: JsonObject): AgentMembershipFact? {
        val source = o.str("source")?.lowercase() ?: return null
        val sourceSessionId = o.str("sourceSessionId") ?: o.str("sessionId") ?: return null
        val generation = o.long("generation") ?: return null
        val revision = o.long("revision") ?: generation
        val membershipWord = o.str("membership")?.lowercase()
        val membership = when (membershipWord) {
            "present", "active", "unknown" -> AgentMembership.PRESENT
            "absent", "archived" -> AgentMembership.ABSENT
            else -> return null
        }
        val archiveState = when (o.str("archiveState")?.lowercase() ?: membershipWord) {
            "active", "present" -> AgentArchiveState.ACTIVE
            "archived" -> AgentArchiveState.ARCHIVED
            "unknown", "absent" -> AgentArchiveState.UNKNOWN
            else -> return null
        }
        val reason = when (o.str("reason")?.lowercase()) {
            "authoritative-snapshot", "authoritative_snapshot" -> AgentMembershipReason.AUTHORITATIVE_SNAPSHOT
            "source-created", "source_created" -> AgentMembershipReason.SOURCE_CREATED
            "source-removed", "source_removed" -> AgentMembershipReason.SOURCE_REMOVED
            "archive" -> AgentMembershipReason.ARCHIVE
            "unarchive" -> AgentMembershipReason.UNARCHIVE
            "membership-contract", "membership_contract" -> AgentMembershipReason.MEMBERSHIP_CONTRACT
            "unknown" -> AgentMembershipReason.UNKNOWN
            null -> when {
                archiveState == AgentArchiveState.ARCHIVED -> AgentMembershipReason.ARCHIVE
                membership == AgentMembership.ABSENT -> AgentMembershipReason.SOURCE_REMOVED
                archiveState == AgentArchiveState.UNKNOWN -> AgentMembershipReason.UNKNOWN
                else -> AgentMembershipReason.MEMBERSHIP_CONTRACT
            }
            else -> return null
        }
        val restored = o.str("status")?.let { status ->
            toSessionState(
                sessionId = sourceSessionId,
                workspace = o.str("workspace"),
                status = status,
                currentAction = o.str("currentAction"),
                latestReply = o.str("latestReply"),
                source = source,
                summary = o.str("summary"),
            )
        }
        return runCatching {
            AgentMembershipFact(
                source = source,
                sourceSessionId = sourceSessionId,
                generation = generation,
                revision = revision,
                membership = membership,
                archiveState = archiveState,
                reason = reason,
                state = restored,
            )
        }.getOrNull()
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
            // membership 是生命周期事实，不是活动状态；它必须走 parseMembershipPage，
            // 否则带 status 的恢复事实会被当成普通活动绕过代数保护。
            if (o.str("kind") == "membership") return@mapNotNull null
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
     * 完整历史（`GET /history?sessionId=`，spec 0018-7 / 票 #177）：当前会话**全量**问答流
     * （比事件里的尾部窗口多出更早条目，条目形状与事件 turns 同族）。
     * 容错同族：整页坏 → null（调用方保现状不抹内容）；空列表是**合法答复**
     * （桥没有更多/会话刚下册），与解析失败的 null 不是一回事。单条坏跳过（[turns] 口径）。
     */
    fun parseHistory(body: String): List<AgentTurn>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        // 显式带 turns 键才算合法应答；缺键走 null，别把「没有更多」与「解析失败」混为一谈。
        if (!root.containsKey("turns")) null else root.turns()
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
     * status 词表 → [AgentStatus]（review 2026-09-30：词表映射收口一处，调试注入等
     * 其余面复用，不再各写一份 `when`）；未知词 → null（事件被跳过/注入被忽略）。
     */
    fun statusFromWord(word: String?): AgentStatus? = when (word?.trim()) {
        "working" -> AgentStatus.WORKING
        "waiting" -> AgentStatus.WAITING_FOR_APPROVAL
        "idle" -> AgentStatus.IDLE
        // 会话级出错（spec 0018-3）：词表第四词——来源报错即认，为「出错」提醒供源。
        "error" -> AgentStatus.ERROR
        else -> null
    }

    /** canonical 桥会话键：有 source 时为 `bridge:<source>:<raw>`，旧事件缺 source 时保留 `bridge:<raw>`。 */
    fun sessionId(source: String?, sourceSessionId: String): String =
        source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { AgentSessionKeys.bridge(it, sourceSessionId) }
            ?: (SESSION_PREFIX + sourceSessionId)

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
        pendingOptions = event.pendingOptions,
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
        pendingOptions: List<AgentPendingOption> = emptyList(),
    ): AgentSessionState? {
        val normalized = statusFromWord(status) ?: return null
        return AgentSessionState(
            sessionId = sessionId(source, sessionId),
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
            pendingOptions = pendingOptions,
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
     * 解一条事件的 `pendingOptions`（spec 0018-4 / 票 #174）：容错同族——单条坏（缺 id/label）
     * 跳过该条；整块不是数组 → 当没有（确认类等待）。
     */
    private fun JsonObject.pendingOptions(): List<AgentPendingOption> = runCatching {
        this["pendingOptions"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val label = o.str("label") ?: return@mapNotNull null
            AgentPendingOption(id = id, label = label)
        }
    }.getOrDefault(emptyList())

    /**
     * 会话动作应答（`POST /action`，spec 0018-4）：解析为 [ActionReceipt] 词表。
     * 容错同族：不可解析 / 未知词 → [ActionReceipt.MALFORMED]（当失败处理，不悬挂）。
     */
    fun parseActionReceipt(body: String): ActionReceipt = try {
        val root = json.parseToJsonElement(body).jsonObject
        ActionReceipt.fromWire(root.str("receipt"))
    } catch (_: Exception) {
        ActionReceipt.MALFORMED
    }

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
