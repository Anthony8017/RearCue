package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionDisplayTest {

    @Test
    fun `主行真标题优先_首条提问首行_目录名_未命名依次兜底`() {
        val titled = session(
            "sess-a123",
            title = "修标题链",
            workspace = "C:\\work\\RearCue",
            turns = listOf(user("不该覆盖真标题")),
        )
        val question = session(
            "sess-b456",
            workspace = "C:/work/Ant_Nest",
            turns = listOf(
                assistant("先说话也不算提问"),
                user("  帮我修标题  \n第二行不显示  "),
            ),
        )
        val directory = session("sess-c789", workspace = "D:/work/RearCue")
        val unnamed = session("sess-d012", workspace = "   ")

        assertEquals("修标题链", AgentSessionDisplay.forState(titled).title)
        assertEquals("帮我修标题", AgentSessionDisplay.forState(question).title)
        assertEquals("RearCue", AgentSessionDisplay.forState(directory).title)
        assertEquals("未命名会话", AgentSessionDisplay.forState(unnamed).title)
    }

    @Test
    fun `DSH_summary不冒充标题_无提问按目录或未命名兜底`() {
        val summaryOnly = session(
            "bridge:dsh-2468",
            workspace = null,
            source = AgentSources.DSH,
            summary = "想修改 xx 文件",
        )

        assertEquals("未命名会话", AgentSessionDisplay.forState(summaryOnly).title)
    }

    @Test
    fun `同列主行撞名_原样重复_不加编号或sessionId尾码`() {
        val rows = AgentSessionDisplay.forRoster(
            listOf(
                session("sess-a123", title = "同一标题"),
                session("sess-b456", title = "同一标题"),
                session("sess-c789", title = "另一标题"),
            ),
        )

        assertEquals("同一标题", rows.getValue("sess-a123").title)
        assertEquals("同一标题", rows.getValue("sess-b456").title)
        assertEquals("另一标题", rows.getValue("sess-c789").title)
    }

    @Test
    fun `无在册缓存兜底不显示sessionId全串或尾码`() {
        assertEquals("未命名会话", AgentSessionDisplay.fallback("0123456789abcdef").title)
    }

    @Test
    fun `副行来源与目录拼装_无目录只剩来源_两者皆无为空`() {
        val full = AgentSessionDisplay.forState(
            session("sess-a123", title = "真标题", workspace = "C:\\work\\RearCue", source = "zcode"),
        )
        val sourceOnly = AgentSessionDisplay.forState(
            session("bridge:c456", workspace = null, source = "codex"),
        )
        val directoryOnly = AgentSessionDisplay.forState(
            session("sess-d789", workspace = "D:/work/RearCue", source = null),
        )
        val neither = AgentSessionDisplay.forState(
            session("sess-e012", workspace = null, source = null),
        )

        assertEquals("ZCode · RearCue", full.subtitle)
        assertEquals("Codex", sourceOnly.subtitle)
        assertEquals("RearCue", directoryOnly.subtitle)
        assertNull(neither.subtitle)
    }

    @Test
    fun `状态行内联含标题来源目录_通知只取主行`() {
        val display = AgentSessionDisplay.forState(
            session("sess-a123", title = "修标题链", workspace = "C:/work/RearCue", source = "zcode"),
        )

        assertEquals("修标题链 · ZCode · RearCue", display.inline)
        assertEquals("修标题链", display.notificationTitle())
    }

    @Test
    fun `空标题按缺失处理_空白副行不显示`() {
        val blankTitle = session(
            "sess-a123",
            title = "   ",
            workspace = "C:/work/RearCue",
            source = "zcode",
            turns = listOf(user("首条提问\n第二行")),
        )
        val noSubtitle = AgentSessionDisplay(title = "会话列表", subtitle = "   ")

        assertEquals("首条提问", AgentSessionDisplay.forState(blankTitle).title)
        assertEquals("会话列表", noSubtitle.inline)
    }

    private fun session(
        id: String,
        title: String? = null,
        workspace: String? = null,
        source: String? = null,
        summary: String? = null,
        turns: List<AgentTurn> = emptyList(),
    ) = AgentSessionState(
        sessionId = id,
        workspace = workspace,
        source = source,
        summary = summary,
        turns = turns,
        title = title,
    )

    private fun user(text: String) = AgentTurn(AgentTurnRole.USER, text)
    private fun assistant(text: String) = AgentTurn(AgentTurnRole.AGENT, text)
}
