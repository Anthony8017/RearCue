package com.rearcue.poc.agent

/**
 * Conversation V4 订阅面（spec 0010 / 票 #82）：把中继推来的逻辑消息流归一成
 * [AgentSessionState]——v4/conversation/subscribe 订阅请求 + frame 推送（snapshot/delta）
 * 的消费器，纯 JVM 可测。
 *
 * ⚠️ v4 的 subscribe 参数与 frame payload 的精确 wire 形态待 T1 phase B（真凭据实抓）回填；
 * 本类按调研事实（方法名路由表 + 行模型）写成**容错形态**：认得出的走归一化，认不出的
 * 忽略不崩（[ConversationProjector.rowFrom] 是字段级调整点，本类是方法级调整点）。
 *
 * 去重语义：rpc-frame 层的 ack+messageSeq 已保证不重投递（票 #80 codec）；会话行的
 * upsert 以 rowId 为键天然幂等——本项目不另设 afterSeq 记账（phase B 若证实需要行级
 * 序号补漏，在此扩展）。
 */
class ConversationFeed(
    private val sessionId: String = DEFAULT_SESSION_ID,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val projector = ConversationProjector(sessionId)

    /**
     * 单条逻辑消息（重组后的 JSON 文本）入。识别两类：
     * - `v4/conversation/frame`（服务端推送）：params 带 rows → 逐行 upsert；
     * - snapshot/resync 形态（params 或顶层带 snapshot/rows 且行集完整）→ 全量替换。
     * 返回归一化后的最新会话状态（无可识别内容返回 null，调用方忽略即可）。
     */
    fun apply(text: String): AgentSessionState? {
        val obj = RelayEnvelope.parseObject(text) ?: return null
        val method = RelayEnvelope.primitiveOrNull(obj, "method")
        val params = (obj["params"] as? kotlinx.serialization.json.JsonObject) ?: obj

        var touched = false
        when {
            method == METHOD_FRAME -> {
                val rows = params["rows"] as? kotlinx.serialization.json.JsonArray
                if (rows != null) {
                    val full = RelayEnvelope.primitiveOrNull(params, "kind") == "snapshot" ||
                        params["snapshot"] != null
                    if (full) {
                        projector.reset(rows)
                    } else {
                        for (row in rows) projector.apply(row)
                    }
                    touched = true
                } else {
                    params["row"]?.let {
                        projector.apply(it)
                        touched = true
                    }
                }
            }

            method == METHOD_SUBSCRIBE || method == METHOD_RESYNC -> {
                val rows = params["rows"] as? kotlinx.serialization.json.JsonArray
                if (rows != null) {
                    projector.reset(rows)
                    touched = true
                }
            }
        }
        if (!touched) return null
        return projector.project(nowMs())
    }

    /** 订阅请求（出站逻辑消息）：精确参数待 phase B 回填，当前只发方法名（对端 WRONG_PARAM 也不崩）。 */
    fun subscribeRequest(): String =
        "{\"method\":\"$METHOD_SUBSCRIBE\",\"params\":{}}"

    companion object {
        /** 单实例机主的会话键（core 仲裁映射的 key；多会话待 phase B 给真实 id）。 */
        const val DEFAULT_SESSION_ID = "zcode"

        const val METHOD_SUBSCRIBE = "v4/conversation/subscribe"
        const val METHOD_RESYNC = "v4/conversation/resync"
        const val METHOD_FRAME = "v4/conversation/frame"
    }
}
