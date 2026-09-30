package com.rearcue.poc.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * sessions-index 订阅帧 → 会话级等待视图（票 #103 P0：锁定档下**非订阅会话**的
 * Waiting-for-Approval 真检测源）。
 *
 * 为什么需要它（P0 调查，2026-09-28）：
 * - 任务表（workspace-list）wire schema `displayStatus ∈ idle|running|completed|error`
 *   ——**没有等待确认语义**，TaskListParser 只能产出 Working/Idle；
 * - conversation V4 按 topic（`conversation/<sessionId>`）一订阅一会话，锁档把订阅钉在锁
 *   会话上，他会话的等待帧根本到不了；
 * - **sessions-index 是工作区级索引订阅**：一条订阅推送该工作区**全部**会话摘要，摘要带
 *   `pendingInteraction`（等批准/等输入）——锁档下 B 进等待由此进入 core，插队→回锁在
 *   真实链路成立。
 *
 * wire 依据（官方消费面逆向 `app.asar`，2026-09-28；帧包裹与 conversation 帧同型，
 * 见 [ConversationFeed] 实抓注释）：
 * - 订阅 `subscribeSessionsIndexV4({workspacePath,...})` → ack `{ack:{subscriptionId}}`；
 * - 事件监听 `onDynamicSessionsIndexFrame({workspacePath})`，帧 topic =
 *   `sessions-index/<workspacePath>`；
 * - 帧 `{wireVersion, kind, deliveryKind, topic, subscriptionId,
 *   frame:{topic, subscriptionId, fromSeq, toSeq, payload:{kind:"snapshot"|"deltas",...}}}`；
 * - snapshot `{protocolVersion, workspaceId, logEpoch, sessions:[摘要...]}`；
 *   deltas `[{op:"session.upserted",session} | {op:"session.removed",sessionId}]`；
 * - 会话摘要 `{sessionId, workspaceId, title, phase, sessionEnded, pendingInteraction?,
 *   pendingInteractionSummary?, lastActivityAt, createdAt, ...}`，其中
 *   `pendingInteraction: {interactionId, kind:"permission"|"userInput", toolName?, ...}`、
 *   `pendingInteractionSummary: {permissionCount, userInputCount}`。
 *
 * 等待判据与官方索引消费面逐字同口径（`pendingInteraction` 在，或 summary 计数和 > 0）：
 * permission＝等批准、userInput＝等输入，合起来正是 CONTEXT.md「Waiting-for-Approval
 * （agent 停下等待用户批准/输入）」。索引含已归档会话（归档在册与否由任务表口径裁决，
 * 见 [TaskListParser.parseAll]），本类只忠实还原 wire 事实。
 */
class SessionIndexFeed(
    /** 订阅用的工作区路径（本地工作区无 identity，topic 就取它）。 */
    private val workspacePath: String,
) {
    /** 本订阅的帧 topic 守卫（同 [ConversationFeed]：一条监听会收到别的订阅的帧）。 */
    val topic: String = "sessions-index/$workspacePath"

    private val sessions = LinkedHashMap<String, SessionIndexEntry>()

    /** 基线序号（snapshot/toSeq 记账；null = 尚无快照基线）。 */
    private var seq: Long? = null

    /** 当前全量视图（会话键序稳定，供接线层变化判定）。 */
    fun entries(): List<SessionIndexEntry> = sessions.values.toList()

    /** 换订阅/断链后的清零（等新快照重建；不清零会把过期等待钉在视图里）。 */
    fun clear() {
        sessions.clear()
        seq = null
    }

    /**
     * 入站一帧（EventFire 的 data）：返回 [IndexFrameOutcome.Applied]（changed=视图有变，
     * 接线层据此决定是否补发）、[IndexFrameOutcome.Gap]（序列断裂，需退订重订换新快照）
     * 或 [IndexFrameOutcome.Ignored]（别的 topic / 非本订阅可识别的帧，静默跳过）。
     */
    fun applyFrame(data: JsonElement?): IndexFrameOutcome {
        val wire = data as? JsonObject ?: return IndexFrameOutcome.Ignored
        val frame = wire["frame"] as? JsonObject ?: wire
        val topic = RelayEnvelope.primitiveOrNull(frame, "topic")
        if (topic != null && topic != this.topic) return IndexFrameOutcome.Ignored
        val payload = frame["payload"] as? JsonObject ?: return IndexFrameOutcome.Ignored
        val fromSeq = RelayEnvelope.primitiveOrNull(frame, "fromSeq")?.toLongOrNull()
        val toSeq = RelayEnvelope.primitiveOrNull(frame, "toSeq")?.toLongOrNull()
        return when (RelayEnvelope.primitiveOrNull(payload, "kind")) {
            "snapshot" -> {
                val list = ((payload["snapshot"] as? JsonObject)?.get("sessions") as? JsonArray)
                    ?: return IndexFrameOutcome.Ignored
                val next = LinkedHashMap<String, SessionIndexEntry>()
                for (element in list) {
                    (element as? JsonObject)?.let { obj -> entryFrom(obj)?.let { next[it.sessionId] = it } }
                }
                val changed = next != sessions
                sessions.clear()
                sessions.putAll(next)
                seq = toSeq
                IndexFrameOutcome.Applied(changed)
            }

            "deltas" -> {
                val baseline = seq
                if (baseline == null) return IndexFrameOutcome.Gap
                // 官方消费面同序：toSeq 不前进＝重放/过期帧（跳过），fromSeq 对不上＝丢帧（换快照）。
                if (toSeq == null || toSeq <= baseline) return IndexFrameOutcome.Ignored
                if (fromSeq != baseline) return IndexFrameOutcome.Gap
                val deltas = payload["deltas"] as? JsonArray ?: return IndexFrameOutcome.Ignored
                var changed = false
                for (element in deltas) {
                    val op = element as? JsonObject ?: continue
                    when (RelayEnvelope.primitiveOrNull(op, "op")) {
                        "session.upserted" ->
                            (op["session"] as? JsonObject)?.let { obj ->
                                val entry = entryFrom(obj) ?: return@let
                                if (sessions[entry.sessionId] != entry) changed = true
                                sessions[entry.sessionId] = entry
                            }

                        "session.removed" ->
                            RelayEnvelope.primitiveOrNull(op, "sessionId")?.let { id ->
                                if (sessions.remove(id) != null) changed = true
                            }

                        else -> Unit // 别的 op（未来扩展）不动视图
                    }
                }
                seq = toSeq
                IndexFrameOutcome.Applied(changed)
            }

            else -> IndexFrameOutcome.Ignored
        }
    }

    /** 单条会话摘要归一（wire 字段缺失一律不当等待，缺数据不虚构等确认）。 */
    fun entryFrom(obj: JsonObject): SessionIndexEntry? {
        val sessionId = RelayEnvelope.primitiveOrNull(obj, "sessionId") ?: return null
        val pending = obj["pendingInteraction"] is JsonObject ||
            ((obj["pendingInteractionSummary"] as? JsonObject)?.let { summary ->
                countOf(summary, "permissionCount") + countOf(summary, "userInputCount") > 0
            } == true)
        return SessionIndexEntry(
            sessionId = sessionId,
            waiting = pending,
            lastActivityAt = RelayEnvelope.primitiveOrNull(obj, "lastActivityAt")?.toLongOrNull() ?: 0L,
            title = RelayEnvelope.primitiveOrNull(obj, "title")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun countOf(obj: JsonObject, key: String): Int =
        RelayEnvelope.primitiveOrNull(obj, key)?.toIntOrNull() ?: 0
}

/** sessions-index 单会话摘要（等待视图 + 会话标题条目）。 */
data class SessionIndexEntry(
    /** 会话键（＝任务表 taskId）。 */
    val sessionId: String,
    /** 等待确认（等批准/等输入任一未决）——官方索引消费面同判据。 */
    val waiting: Boolean,
    /** 最近活动时刻（wire `lastActivityAt`，毫秒）。 */
    val lastActivityAt: Long,
    /**
     * ZCode 会话索引自带的真标题（wire `title`）。缺键/空白/JSON null 都保持 null，
     * 交给 [AgentSessionDisplay] 做目录名/尾 4 位兜底；标题更新随 snapshot/delta 实时替换。
     */
    val title: String? = null,
)

/** [SessionIndexFeed.applyFrame] 的结果。 */
sealed interface IndexFrameOutcome {
    /** 已应用；[changed] = 全量视图有变。 */
    data class Applied(val changed: Boolean) : IndexFrameOutcome

    /** 序列断裂（缺基线或 fromSeq 对不上）：退订重订换新快照。 */
    data object Gap : IndexFrameOutcome

    /** 与本订阅无关/不可识别：跳过。 */
    data object Ignored : IndexFrameOutcome
}
