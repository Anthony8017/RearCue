package com.rearcue.poc.agent

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionReplyTest {
    private fun question(id: String = "a", multiple: Boolean = false) = AgentUserQuestion(id, "题目", listOf("甲：说明", "乙"), "group", "turn", true, listOf("甲", "乙"), multiple)
    @Test fun `未答题或表外答案不能发送 单选与多选遵循原题`() {
        assertFalse(QuestionReplyPolicy.canSubmit(listOf(question()), emptyMap()))
        assertFalse(QuestionReplyPolicy.canSubmit(listOf(question()), mapOf("a" to setOf("甲：说明"))))
        assertFalse(QuestionReplyPolicy.canSubmit(listOf(question()), mapOf("a" to setOf("甲", "乙"))))
        assertTrue(QuestionReplyPolicy.canSubmit(listOf(question(multiple = true)), mapOf("a" to setOf("甲", "乙"))))
        assertFalse(QuestionReplyPolicy.canSubmit(listOf(question(), question("b")), mapOf("a" to setOf("甲"))))
        assertFalse(QuestionReplyPolicy.canSubmit(listOf(question(), question("b").copy(groupId = "other")), mapOf("a" to setOf("甲"), "b" to setOf("乙"))))
    }
    @Test fun `Desktop旧题不授予作答能力 请求值不混入选项描述`() {
        assertFalse(QuestionReplyPolicy.canSubmit(listOf(question().copy(canAnswer = false)), mapOf("a" to setOf("甲"))))
        val request = QuestionReplyRequest("bridge:codex:s", "[group]", "turn", mapOf("a" to listOf("甲")), "r")
        val parsed = Json.parseToJsonElement(request.toJson("native-id")).jsonObject
        assertEquals("native-id", parsed["sessionId"]!!.jsonPrimitive.content)
        assertEquals("甲", parsed["answers"]!!.jsonObject["a"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(QuestionReplyPolicy.draftKey("s1", "q") != QuestionReplyPolicy.draftKey("s2", "q"))
    }
    @Test fun `快照与增量都保留受控请求元数据与真实任务状态`() {
        val session = """{"id":4,"source":"codex","sessionId":"s","status":"working","pendingQuestions":[{"id":"a","title":"题目","options":["甲：说明","乙"],"groupId":"group","turnId":"turn","canAnswer":true,"optionValues":["甲","乙"],"multiple":false}]}"""
        val snapshot = BridgeEventCodec.parseSnapshot("{\"sessions\":[$session]}")!!.single()
        val event = BridgeEventCodec.toSessionState(BridgeEventCodec.parsePage("{\"events\":[$session]}")!!.single())!!
        assertEquals(listOf(question()), snapshot.pendingQuestions)
        assertEquals(snapshot.pendingQuestions, event.pendingQuestions)
        assertEquals(AgentStatus.WORKING, event.status)
    }
}
