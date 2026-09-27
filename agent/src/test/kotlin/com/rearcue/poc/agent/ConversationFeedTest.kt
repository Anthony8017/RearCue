package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ConversationFeedTest {

    private val clock = longArrayOf(1_000L)

    private fun feed() = ConversationFeed(sessionId = "zcode", nowMs = { clock[0] })

    private fun frame(vararg rows: String, kind: String? = null): String {
        val rowsJson = rows.joinToString(",") { row -> row }
        val kindField = kind?.let { "\"kind\":\"$it\"," } ?: ""
        return """{"method":"v4/conversation/frame","params":{$kindField"rows":[$rowsJson]}}"""
    }

    private fun textRow(id: Long, role: String, text: String) =
        """{"rowId":$id,"type":"text","role":"$role","text":"$text"}"""

    @Test
    fun `frame 推送逐行 upsert_产出会话状态`() {
        val feed = feed()
        val state = feed.apply(frame(textRow(1, "assistant", "在改文件")))
        assertNotNull(state)
        assertEquals("zcode", state.sessionId)
        assertEquals("在改文件", state.latestReply)
        assertEquals(AgentStatus.IDLE, state.status)
    }

    @Test
    fun `工具行触达 Working_pendingApproval 触发等待确认`() {
        val feed = feed()
        feed.apply(frame(textRow(1, "assistant", "好的")))
        val working = feed.apply(
            frame("""{"rowId":2,"type":"tool","tool":"edit","state":{"status":"running","input":"A.kt"}}"""),
        )
        assertEquals(AgentStatus.WORKING, working?.status)

        val waiting = feed.apply(
            frame("""{"rowId":3,"type":"tool","tool":"bash","state":{"status":"pendingApproval","input":"rm -rf"}}"""),
        )
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, waiting?.status)
    }

    @Test
    fun `snapshot 形态全量替换`() {
        val feed = feed()
        feed.apply(frame(textRow(1, "assistant", "旧行")))
        val snapshot = feed.apply(
            frame(textRow(9, "assistant", "快照行"), kind = "snapshot"),
        )
        assertEquals("快照行", snapshot?.latestReply)
    }

    @Test
    fun `同一消息重复投递幂等_rowId upsert 天然去重`() {
        val feed = feed()
        val row = textRow(1, "assistant", "同一条")
        feed.apply(frame(row))
        feed.apply(frame(row))
        val state = feed.apply(frame(row))
        assertEquals("同一条", state?.latestReply)
    }

    @Test
    fun `非会话消息忽略返回 null`() {
        val feed = feed()
        assertNull(feed.apply("""{"method":"v4/telemetry/event","params":{"x":1}}"""))
        assertNull(feed.apply("not-json"))
    }

    @Test
    fun `订阅请求形态`() {
        val feed = feed()
        val request = feed.subscribeRequest()
        assertEquals(
            buildJsonObject {
                put("method", "v4/conversation/subscribe")
                putJsonObject("params") {}
            }.toString(),
            Json.parseToJsonElement(request).toString(),
        )
    }
}
