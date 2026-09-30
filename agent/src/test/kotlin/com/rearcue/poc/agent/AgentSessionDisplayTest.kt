package com.rearcue.poc.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSessionDisplayTest {
    private fun session(
        source: String?,
        title: String? = null,
        workspace: String? = null,
        id: String = "sess-abcd",
    ) = AgentSessionState(sessionId = id, source = source, title = title, workspace = workspace)

    @Test
    fun `ZCode 优先真实标题，其他来源优先 workspace`() {
        assertEquals(
            "ZCode 索引标题",
            AgentSessionDisplay.title(
                session(AgentSources.ZCODE, title = "ZCode 索引标题", workspace = "C:/work/RearCue"),
            ),
        )
        for (source in listOf(AgentSources.CODEX, AgentSources.CLAUDE, AgentSources.DSH)) {
            assertEquals(
                "RearCue",
                AgentSessionDisplay.title(
                    session(source, title = "桥透传标题", workspace = "C:/work/RearCue"),
                ),
            )
        }
    }

    @Test
    fun `非 ZCode 标题只做 workspace 后备，最后仍兜底 sessionId`() {
        assertEquals(
            "桥透传标题",
            AgentSessionDisplay.title(session(AgentSources.CODEX, title = "桥透传标题")),
        )
        assertEquals("abcd", AgentSessionDisplay.title(session(AgentSources.CLAUDE)))
    }
}
