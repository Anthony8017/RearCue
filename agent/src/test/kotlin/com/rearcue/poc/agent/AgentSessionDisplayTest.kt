package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionDisplayTest {

    @Test
    fun `主行真标题优先_目录名与尾4位依次兜底`() {
        val titled = session("sess-a123", title = "修标题链", workspace = "C:\\work\\RearCue")
        val directory = session("sess-b456", workspace = "C:/work/Ant_Nest")
        val tail = session("sess-c789", workspace = "   ")

        assertEquals("修标题链", AgentSessionDisplay.forState(titled).title)
        assertEquals("Ant_Nest", AgentSessionDisplay.forState(directory).title)
        assertEquals("c789", AgentSessionDisplay.forState(tail).title)
    }

    @Test
    fun `DSH_summary不冒充标题_无真标题按目录或尾号兜底`() {
        val summaryOnly = session(
            "bridge:dsh-2468",
            workspace = null,
            source = AgentSources.DSH,
            summary = "想修改 xx 文件",
        )

        assertEquals("2468", AgentSessionDisplay.forState(summaryOnly).title)
    }

    @Test
    fun `同列主行撞名_全部附sessionId尾4位`() {
        val rows = AgentSessionDisplay.forRoster(
            listOf(
                session("sess-a123", title = "同一标题"),
                session("sess-b456", title = "同一标题"),
                session("sess-c789", title = "另一标题"),
            ),
        )

        assertEquals("同一标题 · a123", rows.getValue("sess-a123").title)
        assertEquals("同一标题 · b456", rows.getValue("sess-b456").title)
        assertEquals("另一标题", rows.getValue("sess-c789").title)
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
        val blankTitle = session("sess-a123", title = "   ", workspace = "C:/work/RearCue", source = "zcode")
        val noSubtitle = AgentSessionDisplay(title = "会话列表", subtitle = "   ")

        assertEquals("RearCue", AgentSessionDisplay.forState(blankTitle).title)
        assertEquals("会话列表", noSubtitle.inline)
    }

    private fun session(
        id: String,
        title: String? = null,
        workspace: String? = null,
        source: String? = null,
        summary: String? = null,
    ) = AgentSessionState(
        sessionId = id,
        workspace = workspace,
        source = source,
        summary = summary,
        title = title,
    )
}
