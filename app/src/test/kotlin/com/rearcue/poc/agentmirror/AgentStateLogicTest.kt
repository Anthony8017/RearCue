package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 统一桥会话投影的外部行为（#234）：四来源同一名册、ZCode 标题优先、来源/目录显示不变、
 * 等待置顶与锁定空窗缓存名。只断言用户看到的行与名字，不钉内部合并步骤。
 */
class AgentStateLogicTest {

    private fun session(
        id: String,
        source: String?,
        title: String? = null,
        workspace: String? = null,
        status: AgentStatus = AgentStatus.IDLE,
        updatedAt: Long = 0L,
    ) = AgentSessionState(
        sessionId = id,
        title = title,
        workspace = workspace,
        status = status,
        updatedAt = updatedAt,
        source = source,
    )

    @Test
    fun `四来源经桥进入同一列表_ZCode标题优先且来源标签不变`() {
        val roster = listOf(
            session("bridge:z-1234", AgentSources.ZCODE, title = "修复断链恢复", workspace = "C:\\work\\RearCue"),
            session("bridge:c-2345", AgentSources.CODEX, workspace = "C:/work/RearCue"),
            session("bridge:cl-3456", AgentSources.CLAUDE, workspace = "D:/work/Claude"),
            session("bridge:d-4567", AgentSources.DSH, workspace = "E:/dsh/AgentX"),
        )

        val rows = AgentStateLogic.projectRoster(roster, SessionLockMode.Auto).drop(1)

        assertEquals(listOf("修复断链恢复", "RearCue", "Claude", "AgentX"), rows.map { it.title })
        assertEquals(listOf("ZCode", "Codex", "Claude", "DSH"), rows.map { it.sourceLabel })
        assertEquals(roster.map { it.sessionId }, rows.map { it.sessionId })
    }

    @Test
    fun `标题回退顺序_真实标题_工作区目录名_尾4位`() {
        val titled = session("bridge:s1", AgentSources.ZCODE, title = "真标题", workspace = "C:/work/Fallback")
        val workspaceOnly = session("bridge:s2", AgentSources.CODEX, workspace = "C:/work/Fallback")
        val tailOnly = session("bridge:prefix-3456", AgentSources.CLAUDE)

        assertEquals("真标题", AgentStateLogic.sessionName(titled))
        assertEquals("Fallback", AgentStateLogic.sessionName(workspaceOnly))
        assertEquals("3456", AgentStateLogic.sessionName(tailOnly))
    }

    @Test
    fun `重复标题消歧仍附尾4位_不泄露完整sessionId`() {
        val roster = listOf(
            session("bridge:aaaa1111", AgentSources.ZCODE, title = "同一标题"),
            session("bridge:bbbb2222", AgentSources.ZCODE, title = "同一标题"),
        )

        val rows = AgentStateLogic.projectRoster(roster, SessionLockMode.Auto).drop(1)

        assertEquals(listOf("同一标题 · 1111", "同一标题 · 2222"), rows.map { it.title })
        assertFalse(rows.any { it.title.contains("bridge:") })
    }

    @Test
    fun `等待确认置顶_组内最近活跃降序_平局保到达序`() {
        val roster = listOf(
            session("bridge:a", AgentSources.ZCODE, status = AgentStatus.WORKING, updatedAt = 100L),
            session("bridge:b", AgentSources.CODEX, status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 50L),
            session("bridge:c", AgentSources.CLAUDE, status = AgentStatus.WORKING, updatedAt = 300L),
            session("bridge:d", AgentSources.DSH, status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 500L),
            session("bridge:e", AgentSources.ZCODE, status = AgentStatus.WORKING, updatedAt = 100L),
        )

        val rows = AgentStateLogic.projectRoster(roster, SessionLockMode.Auto)

        assertEquals(listOf("bridge:d", "bridge:b", "bridge:c", "bridge:a", "bridge:e"), rows.drop(1).map { it.sessionId })
    }

    @Test
    fun `锁定会话短暂离册_沿缓存标题显示_没有缓存也只显示尾4位`() {
        val locked = SessionLockMode.Locked("bridge:z-9876")
        val retained = session("bridge:z-9876", AgentSources.ZCODE, title = "缓存标题")

        assertEquals("缓存标题", AgentStateLogic.lockTargetName(locked, emptyList(), retained))
        assertEquals("9876", AgentStateLogic.lockTargetName(locked, emptyList()))
        assertFalse(AgentStateLogic.lockTargetName(locked, emptyList()).orEmpty().contains("bridge:"))
    }

    @Test
    fun `自动档无锁定名_锁定选中态跟档位`() {
        assertNull(AgentStateLogic.lockTargetName(SessionLockMode.Auto, emptyList()))
        assertNull(AgentStateLogic.selectedSessionId(SessionLockMode.Auto))
        assertEquals("bridge:z-1", AgentStateLogic.selectedSessionId(SessionLockMode.Locked("bridge:z-1")))

        val rows = AgentStateLogic.projectRoster(
            listOf(session("bridge:z-1", AgentSources.ZCODE)),
            SessionLockMode.Locked("bridge:z-1"),
        )
        assertTrue(rows.first().auto)
        assertFalse(rows.first().selected)
        assertTrue(rows.first { it.sessionId == "bridge:z-1" }.selected)
    }
}
