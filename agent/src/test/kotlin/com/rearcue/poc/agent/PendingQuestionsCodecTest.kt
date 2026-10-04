package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class PendingQuestionsCodecTest {
    @Test fun `快照与增量同样恢复问题 真工作状态保留 坏题逐条跳过`() {
        val session = """{"id":1,"sessionId":"s","source":"codex","status":"working","updatedAt":123,"pendingQuestions":[{"id":"c:0","title":"选谁","options":["A","B"]},null,{"title":"缺id"}]}"""
        val event = BridgeEventCodec.toSessionState(BridgeEventCodec.parsePage("""{"events":[$session]}""")!!.single())!!
        val snapshot = BridgeEventCodec.parseSnapshot("""{"sessions":[$session]}""")!!.single()
        assertEquals(event.pendingQuestions, snapshot.pendingQuestions)
        assertEquals(1L, event.bridgeRevision)
        assertEquals(event.bridgeRevision, snapshot.bridgeRevision)
        assertEquals(listOf(AgentUserQuestion("c:0", "选谁", listOf("A", "B"))), event.pendingQuestions)
        assertEquals(AgentStatus.WORKING, snapshot.status)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, snapshot.attentionStatus)
    }

    @Test fun `显式空集解除待答 旧桥无字段兼容`() {
        for (extra in listOf("", ",\"pendingQuestions\":[]", ",\"pendingQuestions\":{}")) {
            val state = BridgeEventCodec.parseSnapshot("""{"sessions":[{"sessionId":"s","status":"working"$extra}]}""")!!.single()
            assertEquals(emptyList(), state.pendingQuestions)
            assertEquals(AgentStatus.WORKING, state.attentionStatus)
        }
    }
}
