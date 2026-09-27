package com.rearcue.poc.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Conversation V4 帧消费（spec 0010 / 票 #88 wire 实证回填）：`onDynamicConversationFrame`
 * 事件推送的帧 → [ConversationProjector] 行集 → [AgentSessionState]（回复原文 + 等待确认真检测）。
 *
 * 帧形态（webjs `createTopicFrameSchema` + host 联合 schema）：
 * `{topic, subscriptionId, fromSeq, toSeq, sentAt, payload}`，payload ∈
 * - `{kind:"snapshot", snapshot:{rows:{window:[行...],...},...}}` → 全量替换；
 * - `{kind:"deltas", deltas:[{op:"row.appended"|"row.upserted"|"row.removed"|"row.delta"
 *   |"state.updated",...}]}` → 逐条应用（state.updated 只动会话级状态，行集不变）。
 *
 * 一个实例只服务一个会话（taskId）；换会话重建实例（订阅也随之切换，V4Bridge 负责）。
 */
class ConversationFeed(
    private val sessionId: String = DEFAULT_SESSION_ID,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var projector = ConversationProjector(sessionId)

    /** 换会话（订阅切换）：行集清零，等新订阅的 snapshot 重建。 */
    fun rebind(newSessionId: String): ConversationFeed = ConversationFeed(newSessionId, nowMs)

    val boundSessionId: String get() = sessionId

    /**
     * 入站一条 EventFire 帧数据（[ChannelCodec.ServerFrame.EventFire] 的 data，类型宽容：
     * 带 payload 的帧才处理）。返回归一化后的会话状态；帧内无可识别行返回最近投影
     * （state.updated-only 的 delta 也给投影，调用方按需节流）。
     */
    fun applyFrame(data: kotlinx.serialization.json.JsonElement?): AgentSessionState? {
        val frame = data as? JsonObject ?: return null
        val payload = frame["payload"] as? JsonObject ?: return null
        val kind = RelayEnvelope.primitiveOrNull(payload, "kind")
        return when (kind) {
            "snapshot" -> {
                val window = ((payload["snapshot"] as? JsonObject)?.get("rows") as? JsonObject)
                    ?.get("window") as? JsonArray ?: return null
                projector.reset(window)
                projector.project(nowMs())
            }

            "deltas" -> {
                val deltas = payload["deltas"] as? JsonArray ?: return null
                for (element in deltas) {
                    val op = element as? JsonObject ?: continue
                    when (RelayEnvelope.primitiveOrNull(op, "op")) {
                        "row.appended", "row.upserted" ->
                            (op["row"] as? JsonObject)?.let { projector.upsert(it) }

                        "row.removed" ->
                            RelayEnvelope.primitiveOrNull(op, "fromRowId")?.toLongOrNull()
                                ?.let { projector.removeFrom(it) }

                        "row.delta" -> {
                            val rowId = RelayEnvelope.primitiveOrNull(op, "rowId")?.toLongOrNull()
                            val path = op["path"]
                            val pathText = (path as? kotlinx.serialization.json.JsonPrimitive)?.content
                                ?: (path as? JsonArray)?.firstOrNull()
                                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            val append = RelayEnvelope.primitiveOrNull(op, "append")
                            if (rowId != null && pathText == "text" && append != null) {
                                projector.appendText(rowId, append)
                            }
                        }

                        else -> Unit // state.updated / workflowRun.* 不动行集
                    }
                }
                projector.project(nowMs())
            }

            else -> null
        }
    }

    /** 对外投影（无新帧时的兜底刷新）。 */
    fun snapshotState(): AgentSessionState = projector.project(nowMs())

    companion object {
        /** 单实例机主的默认会话键（core 仲裁映射的 key）。 */
        const val DEFAULT_SESSION_ID = "zcode"
    }
}
