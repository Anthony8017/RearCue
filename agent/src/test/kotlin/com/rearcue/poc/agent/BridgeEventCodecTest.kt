package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PC 桥事件解码测试（ADR 0006 / 票 #116）：页面结构、单条容错（未知 status 跳过）、
 * status 归一、sessionId 前缀隔离两源键空间。坏输入不抛异常是硬契约（版本漂移不崩桥端链路）。
 */
class BridgeEventCodecTest {

    private val page = """
        {"events":[
          {"id":1,"sessionId":"c-1","workspace":"C:/work/repo","status":"working",
           "currentAction":"edit A.kt","latestReply":"正在改……","updatedAt":1758000000000},
          {"id":2,"sessionId":"d-2","status":"idle","updatedAt":1758000001000}
        ],"cursor":2}
    """.trimIndent()

    @Test
    fun `正常页解码——字段取值与缺省`() {
        val events = BridgeEventCodec.parsePage(page)!!
        assertEquals(2, events.size)
        assertEquals(1L, events[0].id)
        assertEquals("c-1", events[0].sessionId)
        assertEquals("working", events[0].status)
        assertEquals("edit A.kt", events[0].currentAction)
        assertEquals("C:/work/repo", events[0].workspace)
        // 第二条：可选字段缺省
        assertEquals(null, events[1].workspace)
        assertEquals(null, events[1].latestReply)
        assertEquals(1758000001000L, events[1].updatedAt)
        assertEquals(2L, BridgeEventCodec.parseCursor(page))
    }

    @Test
    fun `空页与缺 cursor 不抛`() {
        val empty = """{"events":[],"cursor":0}"""
        assertEquals(emptyList(), BridgeEventCodec.parsePage(empty))
        assertEquals(0L, BridgeEventCodec.parseCursor(empty))
        assertEquals(null, BridgeEventCodec.parseCursor("""{"events":[]}"""))
    }

    @Test
    fun `页面结构坏返回 null（调用方按失败重连）不抛`() {
        assertNull(BridgeEventCodec.parsePage("not json at all"))
        assertNull(BridgeEventCodec.parsePage("""{"events":"oops"}"""))
        assertNull(BridgeEventCodec.parsePage("{}"))
        assertNull(BridgeEventCodec.parsePage("[1,2,3]"))
    }

    @Test
    fun `单条坏事件跳过_其余照常（部分降级）`() {
        val mixed = """
            {"events":[
              {"id":1,"status":"working"},
              {"id":2,"sessionId":"ok","status":"mystery"},
              {"id":3,"sessionId":"ok2","status":"waiting","updatedAt":7}
            ],"cursor":3}
        """.trimIndent()
        val events = BridgeEventCodec.parsePage(mixed)!!
        // 解析层：缺 sessionId 的骨架条目直接丢（结构不完整），其余收下——页面不因单条坏而整页 null。
        assertEquals(2, events.size)
        assertEquals(2L, events[0].id)
        // 映射层：未知 status 的事件被跳过
        assertEquals(null, BridgeEventCodec.toSessionState(events[0]))
        val state = BridgeEventCodec.toSessionState(events[1])!!
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, state.status)
        assertEquals(7L, state.updatedAt)
    }

    @Test
    fun `status 归一到镜像事实词表`() {
        fun stateOf(status: String) = BridgeEventCodec.toSessionState(
            BridgeEventCodec.BridgeEvent(1, "s", null, status, null, null, 0),
        )
        assertEquals(AgentStatus.WORKING, stateOf("working")!!.status)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, stateOf("waiting")!!.status)
        assertEquals(AgentStatus.IDLE, stateOf("idle")!!.status)
        assertNull(stateOf("running"))
    }

    @Test
    fun `sessionId 加桥前缀——与 ZCode 源键空间隔离`() {
        val state = BridgeEventCodec.toSessionState(
            BridgeEventCodec.BridgeEvent(1, "abc", null, "idle", null, null, 0),
        )!!
        assertEquals("bridge:abc", state.sessionId)
        assertTrue(state.sessionId.startsWith(BridgeEventCodec.SESSION_PREFIX))
    }
}
