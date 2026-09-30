package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ZCode 侧问答流判例（spec 0017 / 票 #169）：中继会话的行集里本来就有 `userInput` 行
 * （`role="user"` 在 [ConversationProjector.rowFrom] 里早就分好了），spec 0010 时代在投影
 * 那一步被整类丢掉——背屏只看得到无头无尾的回答。本组把「提问也进流、顺序按行号、
 * 同角色同文去重、尾部窗口裁剪」钉死。
 */
class AgentTurnsTest {

    private fun row(rowId: Long, kind: String, role: String?, text: String?): JsonObject = buildJsonObject {
        put("rowId", rowId)
        put("kind", kind)
        role?.let { put("role", it) }
        text?.let { put("text", it) }
    }

    private fun projectorWith(vararg rows: JsonObject) = ConversationProjector("c1").apply {
        rows.forEach { upsert(it) }
    }

    @Test
    fun `提问与回答按行号顺序进流`() {
        val projector = projectorWith(
            row(1, "userInput", "user", "帮我把背屏文字靠左对齐"),
            row(2, "assistantText", "assistant", "好，先改版心。"),
            row(3, "userInput", "user", "字号也做成选项"),
            row(4, "assistantText", "assistant", "字号三档已经接好。"),
        )
        val turns = projector.project(100L).turns
        assertEquals(4, turns.size)
        assertEquals(
            listOf(
                AgentTurnRole.USER to "帮我把背屏文字靠左对齐",
                AgentTurnRole.AGENT to "好，先改版心。",
                AgentTurnRole.USER to "字号也做成选项",
                AgentTurnRole.AGENT to "字号三档已经接好。",
            ),
            turns.map { it.role to it.text },
        )
    }

    @Test
    fun `非文本行（思考_工具_步骤）不进流`() {
        val projector = projectorWith(
            row(1, "userInput", "user", "问一句"),
            row(2, "reasoning", "assistant", "这是思考过程，不该上屏"),
            row(3, "assistantText", "assistant", "答一句"),
        )
        assertEquals(
            listOf("问一句", "答一句"),
            projector.project(100L).turns.map { it.text },
        )
    }

    @Test
    fun `空文与同角色同文复读不成条`() {
        val projector = projectorWith(
            row(1, "assistantText", "assistant", "同一段"),
            row(2, "assistantText", "assistant", "同一段"),
            row(3, "assistantText", "assistant", "   "),
        )
        assertEquals(listOf("同一段"), projector.project(100L).turns.map { it.text })
    }

    @Test
    fun `latestReply 仍是末尾助手输出（旧字段口径不变）`() {
        val projector = projectorWith(
            row(1, "assistantText", "assistant", "第一段"),
            row(2, "userInput", "user", "又问一句"),
        )
        val state = projector.project(100L)
        assertEquals("第一段", state.latestReply, "末尾是提问时，旧字段仍指向最后一条回答")
        assertEquals(2, state.turns.size)
    }

    @Test
    fun `尾部窗口按条数裁剪，从最旧丢`() {
        val rows = (1..(AgentTurns.MAX_ENTRIES + 3)).map { i ->
            row(i.toLong(), "assistantText", "assistant", "第 $i 段")
        }
        val turns = projectorWith(*rows.toTypedArray()).project(100L).turns
        assertEquals(AgentTurns.MAX_ENTRIES, turns.size)
        assertEquals("第 4 段", turns.first().text, "最旧的三段被丢掉")
        assertEquals("第 ${AgentTurns.MAX_ENTRIES + 3} 段", turns.last().text)
    }

    @Test
    fun `尾部窗口按总字符裁剪，且至少留一条`() {
        val long = "x".repeat(AgentTurns.MAX_CHARS)
        val turns = AgentTurns.window(
            listOf(
                AgentTurn(AgentTurnRole.AGENT, "早先的一大段"),
                AgentTurn(AgentTurnRole.USER, "中间的一句"),
                AgentTurn(AgentTurnRole.AGENT, long),
            ),
        )
        // 最后一条自己就吃满窗口：前面的全被挤掉，但**至少留一条**（屏上不会突然全空）。
        assertEquals(1, turns.size)
        assertEquals(long, turns.single().text)

        // 窗口内还放得下两条时不该多丢。
        val roomy = AgentTurns.window(
            listOf(
                AgentTurn(AgentTurnRole.USER, "短问"),
                AgentTurn(AgentTurnRole.AGENT, "短答"),
            ),
        )
        assertEquals(2, roomy.size)
    }

    @Test
    fun `窗口不掐掉超过窗口单条自身的正文内容`() {
        val text = "y".repeat(AgentTurns.MAX_CHARS + 5000)
        val turns = AgentTurns.window(listOf(AgentTurn(AgentTurnRole.AGENT, text)))
        assertEquals(text, turns.single().text, "窗口只丢整条，不截断单条正文")
    }

    @Test
    fun `正在增长的那条不因窗口被丢——裁剪只动头部`() {
        // 塞满一窗旧条目，末尾放一条 open 的：它必须在（裁剪只从最旧开始丢）。
        val old = (1..AgentTurns.MAX_ENTRIES).map { i ->
            AgentTurn(AgentTurnRole.AGENT, "第 $i 段旧内容")
        }
        val growing = AgentTurn(AgentTurnRole.AGENT, "正在长的那条", open = true)
        val turns = AgentTurns.window(old + growing)
        assertEquals(growing, turns.last(), "末尾的增长条必须留在窗内")
        assertTrue(turns.last().open)
    }

    @Test
    fun `单条超窗口时那条不被截断也不被丢（至少留一条）`() {
        val huge = AgentTurn(AgentTurnRole.AGENT, "w".repeat(AgentTurns.MAX_CHARS + 1000))
        val turns = AgentTurns.window(listOf(AgentTurn(AgentTurnRole.USER, "被挤掉的提问"), huge))
        assertEquals(listOf(huge), turns, "窗口只丢整条、不截断，也不把最后一条也丢掉")
    }

    // ---------- 完整历史合并（spec 0018-7 / 票 #177）：窗口是视图，更早条目接在前面 ----------

    @Test
    fun `全量历史里比实时窗口更早的条目接到窗口前面`() {
        val older = listOf(
            AgentTurn(AgentTurnRole.USER, "第一问", ts = 1),
            AgentTurn(AgentTurnRole.AGENT, "第一答", ts = 2),
        )
        val live = listOf(
            AgentTurn(AgentTurnRole.USER, "第二问", ts = 3),
            AgentTurn(AgentTurnRole.AGENT, "第二答", ts = 4),
        )
        assertEquals(older + live, AgentTurns.withHistoryPrefix(older + live, live))
    }

    @Test
    fun `同 ts 以实时为准——开放条原地增长同一段不出现两次`() {
        val full = listOf(AgentTurn(AgentTurnRole.AGENT, "前半句", ts = 1, open = true))
        val live = listOf(AgentTurn(AgentTurnRole.AGENT, "前半句，后半句", ts = 1))
        assertEquals(live, AgentTurns.withHistoryPrefix(full, live), "边界按 ts 收口，全量副本不重复接回")
    }

    @Test
    fun `任一为空返回另一份——空桥历史不抹现有内容`() {
        val live = listOf(AgentTurn(AgentTurnRole.AGENT, "现有", ts = 5))
        assertEquals(live, AgentTurns.withHistoryPrefix(emptyList(), live))
        val full = listOf(AgentTurn(AgentTurnRole.AGENT, "更早", ts = 1))
        assertEquals(full, AgentTurns.withHistoryPrefix(full, emptyList()))
        assertEquals(emptyList<AgentTurn>(), AgentTurns.withHistoryPrefix(emptyList(), emptyList()))
    }

    @Test
    fun `只有桥来源取全量——ZCode 翻到快照边界为止`() {
        assertTrue(AgentTurns.shouldFetchFullHistory(BridgeEventCodec.SESSION_PREFIX + "codex-1"))
        assertFalse(AgentTurns.shouldFetchFullHistory("zcode-task-1"))
        assertFalse(AgentTurns.shouldFetchFullHistory(""))
    }
}
