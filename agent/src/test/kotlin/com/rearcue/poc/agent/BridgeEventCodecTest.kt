package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // 会话级出错（spec 0018-3）：词表第四词——「出错」提醒的来源语义
        assertEquals(AgentStatus.ERROR, stateOf("error")!!.status)
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
    fun `第四来源dsh透传_摘要字段取出_缺省退化null`() {
        val page = """
            {"events":[
              {"id":1,"sessionId":"d-1","status":"working","source":"dsh",
               "summary":"想修改 xx 文件","updatedAt":1758000000000},
              {"id":2,"sessionId":"d-2","status":"idle","source":"dsh","summary":null,"updatedAt":1758000001000}
            ],"cursor":2}
        """.trimIndent()
        val events = BridgeEventCodec.parsePage(page)!!
        assertEquals(AgentSources.DSH, events[0].source)
        assertEquals("想修改 xx 文件", events[0].summary)
        assertEquals(null, events[1].summary) // JSON null 与缺键同义（不产 "null" 字符串）

        val withSummary = BridgeEventCodec.toSessionState(events[0])!!
        assertEquals(AgentSources.DSH, withSummary.source)
        assertEquals("想修改 xx 文件", withSummary.summary)
        // 缺省退化：没发摘要的来源/旧桥照常工作（summary=null，功能不崩）。
        assertNull(BridgeEventCodec.toSessionState(events[1])!!.summary)
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

    // ---- 问答流 turns（spec 0017 / 票 #169） ----

    @Test
    fun `事件里的 turns 解成提问与输出两角色`() {
        val body = """
            {"events":[{"id":7,"sessionId":"q","status":"working","turns":[
              {"role":"user","text":"把背屏改成靠左对齐","ts":1},
              {"role":"assistant","text":"好，先改版心。","ts":2},
              {"role":"assistant","text":"正在读文件…","ts":3,"open":true}
            ]}],"cursor":7}
        """.trimIndent()
        val event = BridgeEventCodec.parsePage(body)!!.single()
        assertEquals(3, event.turns.size)
        assertEquals(AgentTurnRole.USER, event.turns[0].role)
        assertEquals("把背屏改成靠左对齐", event.turns[0].text)
        assertEquals(1L, event.turns[0].ts)
        assertEquals(AgentTurnRole.AGENT, event.turns[1].role)
        assertEquals(false, event.turns[1].open)
        assertTrue(event.turns[2].open, "未收口的条目要标成 open（渲染层据此做追加语义）")

        val state = BridgeEventCodec.toSessionState(event)!!
        assertEquals(3, state.turns.size)
        assertEquals("bridge:q", state.sessionId)
    }

    @Test
    fun `turns 单条坏跳过_整块坏当没有（回落旧字段）`() {
        val mixed = """
            {"events":[{"id":8,"sessionId":"q2","status":"idle","latestReply":"旧字段正文","turns":[
              {"role":"system","text":"不认识的角色"},
              {"role":"user","text":""},
              {"role":"user","text":"留下的提问"},
              "不是对象"
            ]}],"cursor":8}
        """.trimIndent()
        val event = BridgeEventCodec.parsePage(mixed)!!.single()
        assertEquals(1, event.turns.size)
        assertEquals("留下的提问", event.turns[0].text)

        val broken = """
            {"events":[{"id":9,"sessionId":"q3","status":"idle","latestReply":"旧字段正文","turns":"oops"}],"cursor":9}
        """.trimIndent()
        val fallback = BridgeEventCodec.parsePage(broken)!!.single()
        assertEquals(emptyList(), fallback.turns, "整块不是数组就当没有")
        assertEquals("旧字段正文", BridgeEventCodec.toSessionState(fallback)!!.latestReply)
    }

    @Test
    fun `缺 turns 的旧事件照常解码为空列表`() {
        val events = BridgeEventCodec.parsePage(page)!!
        assertEquals(emptyList(), events[0].turns)
        assertEquals(emptyList(), events[1].turns)
    }

    // ---------- 来源能力表（spec 0018-2 / 票 #172） ----------

    @Test
    fun `来源能力表——桥声明叠加内置默认_waiting 可读_approve 缺省不可`() {
        val caps = BridgeEventCodec.parseCapabilities(
            """{"sessions":[],"capabilities":{"codex":["waiting"],"dsh":["waiting","approve"]}}""",
        )
        assertEquals(true, caps.can("dsh", SourceCapabilities.WAITING))
        assertEquals(true, caps.can("dsh", SourceCapabilities.APPROVE), "桥声明了 approve 才可批")
        assertEquals(true, caps.can("zcode", SourceCapabilities.WAITING), "ZCode 不经桥，内置默认在")
        assertEquals(false, caps.can("codex", SourceCapabilities.APPROVE), "没声明的能力＝不可用（缺省保守）")
        assertEquals(false, caps.can("claude", SourceCapabilities.APPROVE))
    }

    @Test
    fun `来源能力表——旧桥没发_整块坏_单来源坏_照常退默认不崩`() {
        // 旧桥快照没有 capabilities 键：四来源等待语义照常可读（能力表是增量声明）。
        val legacy = BridgeEventCodec.parseCapabilities("""{"sessions":[]}""")
        assertEquals(true, legacy.can("dsh", SourceCapabilities.WAITING))
        assertEquals(false, legacy.can("dsh", SourceCapabilities.APPROVE))

        // 整块不是对象 → 默认表；单来源坏（词不是字符串）→ 跳过该来源，其余照常。
        val broken = BridgeEventCodec.parseCapabilities("""{"sessions":[],"capabilities":"oops"}""")
        assertEquals(SourceCapabilities.DEFAULTS, broken)

        val partial = BridgeEventCodec.parseCapabilities(
            """{"sessions":[],"capabilities":{"dsh":["waiting"],"codex":[1,2],"claude":[]}}""",
        )
        assertEquals(true, partial.can("dsh", SourceCapabilities.WAITING))
        assertEquals(true, partial.can("codex", SourceCapabilities.WAITING), "坏来源跳过声明、保留默认")

        // 非法 JSON 整页 → 默认表（能力表永不挡镜像）。
        assertEquals(SourceCapabilities.DEFAULTS, BridgeEventCodec.parseCapabilities("{oops"))
    }

    @Test
    fun `来源能力表——键大小写归一_不认识的来源 false`() {
        val caps = BridgeEventCodec.parseCapabilities("""{"sessions":[],"capabilities":{"DSH":["Waiting"]}}""")
        assertEquals(true, caps.can(" DSH ", SourceCapabilities.WAITING))
        assertEquals(false, caps.can("watson", SourceCapabilities.WAITING))
        assertEquals(false, caps.can(null, SourceCapabilities.WAITING))
    }

    // ---------- 会话动作应答与选项（spec 0018-4 / 票 #174） ----------

    @Test
    fun `会话动作应答——词表归一_认不出一律 MALFORMED 不悬挂`() {
        assertEquals(ActionReceipt.ACCEPTED, BridgeEventCodec.parseActionReceipt("""{"ok":true,"receipt":"accepted","requestId":"r1"}"""))
        assertEquals(ActionReceipt.UNKNOWN_SESSION, BridgeEventCodec.parseActionReceipt("""{"ok":false,"receipt":"unknown-session"}"""))
        assertEquals(ActionReceipt.UNSUPPORTED, BridgeEventCodec.parseActionReceipt("""{"ok":false,"receipt":"unsupported"}"""))
        assertEquals(ActionReceipt.BAD_REQUEST, BridgeEventCodec.parseActionReceipt("""{"ok":false,"receipt":"bad-request"}"""))
        // 容错同族：坏 JSON / 缺键 / 未知词 → MALFORMED（当失败处理，回执恒定不悬挂）
        assertEquals(ActionReceipt.MALFORMED, BridgeEventCodec.parseActionReceipt("{oops"))
        assertEquals(ActionReceipt.MALFORMED, BridgeEventCodec.parseActionReceipt("""{"ok":true}"""))
        assertEquals(ActionReceipt.MALFORMED, BridgeEventCodec.parseActionReceipt("""{"receipt":"what"}"""))
    }

    @Test
    fun `事件带 pendingOptions——选择题选项进模型_坏条目跳过`() {
        val body = """{"events":[{"id":1,"sessionId":"q","status":"waiting","pendingOptions":[{"id":"a","label":"方案 A"},{"id":"b","label":"方案 B"},{"label":"缺 id"},{"id":"c"}]}],"cursor":1}"""
        val event = BridgeEventCodec.parsePage(body)!!.single()
        assertEquals(
            listOf(AgentPendingOption("a", "方案 A"), AgentPendingOption("b", "方案 B")),
            event.pendingOptions,
        )
        val state = BridgeEventCodec.toSessionState(event)!!
        assertEquals(event.pendingOptions, state.pendingOptions)
        // 旧桥没发该字段：空列表（确认类等待的正常形态），不破坏兼容
        val legacy = BridgeEventCodec.parsePage("""{"events":[{"id":2,"sessionId":"q","status":"waiting"}],"cursor":2}""")!!.single()
        assertEquals(emptyList(), legacy.pendingOptions)
    }

    // ---------- 完整历史应答（spec 0018-7 / 票 #177） ----------

    @Test
    fun `完整历史应答解析——全量条目与容错_缺键与坏页走 null`() {
        val ok = BridgeEventCodec.parseHistory(
            """{"sessionId":"h","turns":[{"role":"user","text":"第 1 问","ts":1},{"role":"assistant","text":"第 1 答","ts":2}]}""",
        )
        assertEquals(2, ok!!.size)
        assertEquals("第 1 问", ok.first().text)
        // 空列表是合法答复（桥没有更多/会话刚下册）——与解析失败的 null 不是一回事。
        assertEquals(emptyList(), BridgeEventCodec.parseHistory("""{"sessionId":"h","turns":[]}"""))
        // 缺 turns 键 / 整页坏 → null（调用方保现状不抹内容）。
        assertEquals(null, BridgeEventCodec.parseHistory("""{"sessionId":"h"}"""))
        assertEquals(null, BridgeEventCodec.parseHistory("not json"))
    }

    @Test
    fun `actionExpired 终态标记只在带标记的事件上为真_不粘连`() {
        // review 2026-09-30 / spec 0018-4 AC3 补遗：桥侧对「已受理之后无人取走」的动作判死，
        // 手机按此标记走失败提示链；普通事件（含同会话后续事件）恒为 false。
        val page = BridgeEventCodec.parsePage(
            """{"events":[
                {"id":1,"sessionId":"s","status":"waiting","actionExpired":true},
                {"id":2,"sessionId":"s","status":"waiting"},
                {"id":3,"sessionId":"s","status":"idle","actionExpired":false}
            ],"cursor":3}""",
        )!!
        assertTrue(page[0].actionExpired)
        assertFalse(page[1].actionExpired)
        assertFalse(page[2].actionExpired)
    }
}
