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
           "currentAction":"edit A.kt","latestReply":"正在改……","updatedAt":1758000000000,
           "source":"codex","futureField":"ignored"},
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
        assertEquals("codex", events[0].source) // 未知字段被忽略，source 正常取出
        // 第二条：可选字段缺省（含旧事件无 source）
        assertEquals(null, events[1].workspace)
        assertEquals(null, events[1].source)
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
        assertEquals(null, state.source)
        // 到达时间戳（手机时钟），非桥侧 PC 时间——跨源仲裁不受时钟偏差扭曲（评审修复）。
        assertTrue(state.updatedAt in 1_700_000_000_000L..4_000_000_000_000L)
        assertTrue(state.updatedAt != 7L)
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
        assertEquals(null, state.source)
        assertTrue(state.sessionId.startsWith(BridgeEventCodec.SESSION_PREFIX))
    }

    @Test
    fun `显式 JSON null 与缺键同义——不变成字符串 null`() {
        // 票 #156 实机验收发现：桥侧可选字段显式发 null 时，JsonNull 也是 JsonPrimitive，
        // 取 content 会得到字符串 "null"，列表上就出现一行标题「null」。
        val page = """
            {"events":[{"id":1,"sessionId":"s1","status":"working",
              "workspace":null,"currentAction":null,"latestReply":null,"source":null}],"cursor":1}
        """.trimIndent()
        val event = BridgeEventCodec.parsePage(page)!!.single()
        assertEquals(null, event.workspace)
        assertEquals(null, event.currentAction)
        assertEquals(null, event.latestReply)
        assertEquals(null, event.source)
        val state = BridgeEventCodec.toSessionState(event)!!
        assertEquals(null, state.workspace)
        assertEquals(null, state.source)

        val snapshot = """
            {"sessions":[{"sessionId":"s2","source":null,"workspace":null,"status":"idle","updatedAt":null}]}
        """.trimIndent()
        val session = BridgeEventCodec.parseSnapshot(snapshot)!!.single()
        assertEquals(null, session.workspace)
        assertEquals(null, session.source)
    }

    // ---------- 在册快照（spec 0016 / 票 #155） ----------

    private val snapshot = """
        {"sessions":[
          {"sessionId":"c-1","source":"codex","workspace":"C:/work/repo","status":"working","updatedAt":1758000000000},
          {"sessionId":"d-2","source":"claude","workspace":"C:/work/other","status":"waiting","updatedAt":1758000001000},
          {"sessionId":"e-3","status":"idle","updatedAt":1758000002000}
        ]}
    """.trimIndent()

    @Test
    fun `快照解码——键加前缀_字段与状态归一`() {
        val sessions = BridgeEventCodec.parseSnapshot(snapshot)!!
        assertEquals(3, sessions.size)
        assertEquals("bridge:c-1", sessions[0].sessionId)
        assertEquals("C:/work/repo", sessions[0].workspace)
        assertEquals(AgentStatus.WORKING, sessions[0].status)
        assertEquals("codex", sessions[0].source)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, sessions[1].status)
        assertEquals("claude", sessions[1].source)
        assertEquals(AgentStatus.IDLE, sessions[2].status)
        assertEquals(null, sessions[2].source) // 旧事件/无来源照常
        assertTrue(AgentSessionKeys.isBridge(sessions[0].sessionId))
    }

    @Test
    fun `空快照是合法答复——空列表而非解析失败`() {
        assertEquals(emptyList(), BridgeEventCodec.parseSnapshot("""{"sessions":[]}"""))
    }

    @Test
    fun `快照页面坏返回 null（调用方保锁等下轮）不抛`() {
        assertNull(BridgeEventCodec.parseSnapshot("not json at all"))
        assertNull(BridgeEventCodec.parseSnapshot("""{"sessions":"oops"}"""))
        assertNull(BridgeEventCodec.parseSnapshot("{}"))
        assertNull(BridgeEventCodec.parseSnapshot("""{"events":[]}"""))
    }

    @Test
    fun `快照单条坏跳过_其余照常（部分降级）`() {
        val mixed = """
            {"sessions":[
              {"source":"codex","status":"working"},
              {"sessionId":"ok","status":"mystery"},
              {"sessionId":"ok2","status":"idle"}
            ]}
        """.trimIndent()
        val sessions = BridgeEventCodec.parseSnapshot(mixed)!!
        assertEquals(1, sessions.size) // 缺 sessionId 与未知 status 各丢一条
        assertEquals("bridge:ok2", sessions[0].sessionId)
    }
}
