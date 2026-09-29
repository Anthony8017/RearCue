package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ConversationProjectorTest {

    private fun projector() = ConversationProjector(sessionId = "conv-1")

    private fun row(
        rowId: Long,
        type: String,
        role: String? = null,
        text: String? = null,
        tool: String? = null,
        status: String? = null,
        input: String? = null,
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("rowId", rowId)
        put("type", type)
        role?.let { put("role", it) }
        text?.let { put("text", it) }
        tool?.let {
            put("tool", it)
            putJsonObject("state") {
                status?.let { s -> put("status", s) }
                input?.let { i -> put("input", i) }
            }
        }
    }

    @Test
    fun `空会话为 Idle`() {
        assertEquals(AgentStatus.IDLE, projector().project(100L).status)
    }

    @Test
    fun `运行中工具为 Working 且产出当前动作摘要`() {
        val p = projector()
        p.apply(row(1, "step-start"))
        p.apply(row(2, "tool", tool = "edit", status = "running", input = "src/App.kt\n  new content"))
        val state = p.project(200L)
        assertEquals(AgentStatus.WORKING, state.status)
        assertEquals("edit src/App.kt new content", state.currentAction)
    }

    @Test
    fun `pendingApproval 插为等待确认`() {
        val p = projector()
        p.apply(row(1, "step-start"))
        p.apply(row(2, "tool", tool = "bash", status = "pendingApproval", input = "rm -rf build"))
        val state = p.project(300L)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, state.status)
    }

    @Test
    fun `回合关闭且无运行工具为 Idle_并保留最新回复`() {
        val p = projector()
        p.apply(row(1, "step-start"))
        p.apply(row(2, "text", role = "assistant", text = "完成了重构"))
        p.apply(row(3, "step-finish"))
        val state = p.project(400L)
        assertEquals(AgentStatus.IDLE, state.status)
        assertEquals("完成了重构", state.latestReply)
        assertEquals("conv-1", state.sessionId)
        assertEquals(AgentSources.ZCODE, state.source)
    }

    @Test
    fun `最新回复取最后一条助手文本_忽略 reasoning`() {
        val p = projector()
        p.apply(row(1, "text", role = "assistant", text = "第一版"))
        p.apply(row(2, "reasoning", role = "assistant", text = "thinking..."))
        p.apply(row(3, "text", role = "assistant", text = "最终结论"))
        assertEquals("最终结论", p.project(500L).latestReply)
    }

    @Test
    fun `快照全量替换`() {
        val p = projector()
        p.apply(row(1, "text", role = "assistant", text = "旧回复"))
        p.reset(
            buildJsonObject {
                put("rows", kotlinx.serialization.json.JsonArray(listOf(row(9, "text", role = "assistant", text = "快照回复"))))
            },
        )
        val state = p.project(600L)
        assertEquals("快照回复", state.latestReply)
        assertEquals(setOf(9L), p.rowIds())
    }

    @Test
    fun `remove 删行生效`() {
        val p = projector()
        p.apply(row(1, "text", role = "assistant", text = "将被删除"))
        p.remove(1L)
        assertNull(p.project(700L).latestReply)
    }

    @Test
    fun `超长当前动作截断`() {
        val p = projector()
        p.apply(row(1, "tool", tool = "bash", status = "running", input = "x".repeat(300)))
        val action = p.project(800L).currentAction!!
        assertEquals(ACTION_SUMMARY_MAX_CHARS, action.length)
        assertTrue(action.endsWith("…"))
    }

    @Test
    fun `未知形态行容错跳过`() {
        val p = projector()
        p.apply(Json.parseToJsonElement("""{"rowId":1,"type":"unknown-future-type","payload":{"x":1}}"""))
        p.apply(Json.parseToJsonElement("""{"nonsense":true}"""))
        assertEquals(AgentStatus.IDLE, p.project(900L).status)
    }

    @Test
    fun `损坏行安全重打包`() {
        // buildJsonObject 路径未覆盖的工具状态缺省：tool 行无 state
        val p = projector()
        p.apply(
            buildJsonObject {
                put("rowId", 1)
                put("type", "tool")
                put("tool", "edit")
            },
        )
        assertEquals(AgentStatus.IDLE, p.project(1000L).status) // 无 status 不算 running
    }
}
