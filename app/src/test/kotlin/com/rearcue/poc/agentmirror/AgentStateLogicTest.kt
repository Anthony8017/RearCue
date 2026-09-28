package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 会话列表与当前档的纯逻辑测试（票 #104）：状态归一（任务表 × v4 帧、列表叠等确认）
 * 与当前档派生（状态行名字、列表选中键）——两块都是 JVM 可测的无 Android 依赖逻辑。
 */
class AgentStateLogicTest {

    private fun session(
        id: String,
        workspace: String? = null,
        status: AgentStatus = AgentStatus.IDLE,
        updatedAt: Long = 0L,
    ) = AgentSessionState(sessionId = id, workspace = workspace, status = status, updatedAt = updatedAt)

    // ---------- 单条状态归一（merge） ----------

    @Test
    fun `merge 同会话_等确认优先于工作中`() {
        val merged = AgentStateLogic.merge(
            task = session("a", status = AgentStatus.WORKING),
            v4 = session("a", status = AgentStatus.WAITING_FOR_APPROVAL),
        )
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, merged?.status)
    }

    @Test
    fun `merge 同会话_任一来源工作中即工作_空闲最次`() {
        assertEquals(
            AgentStatus.WORKING,
            AgentStateLogic.merge(session("a"), session("a", status = AgentStatus.WORKING))?.status,
        )
        assertEquals(
            AgentStatus.WORKING,
            AgentStateLogic.merge(session("a", status = AgentStatus.WORKING), session("a"))?.status,
        )
        assertEquals(
            AgentStatus.IDLE,
            AgentStateLogic.merge(session("a"), session("a"))?.status,
        )
    }

    @Test
    fun `merge 同会话_回复取v4_动作取任务表_时间取新`() {
        val merged = AgentStateLogic.merge(
            task = AgentSessionState(
                sessionId = "a",
                workspace = "工作区A",
                status = AgentStatus.WORKING,
                currentAction = "edit Main.kt",
                latestReply = "旧回复",
                updatedAt = 10L,
            ),
            v4 = AgentSessionState(
                sessionId = "a",
                workspace = "工作区A(v4)",
                status = AgentStatus.IDLE,
                currentAction = "v4 动作",
                latestReply = "新回复",
                updatedAt = 99L,
            ),
        )
        assertEquals("工作区A", merged?.workspace)
        assertEquals("edit Main.kt", merged?.currentAction)
        assertEquals("新回复", merged?.latestReply)
        assertEquals(99L, merged?.updatedAt)
    }

    @Test
    fun `merge 会话键不一致_先用任务表_单边缺失取另一边`() {
        // 任务刚切换、v4 尚未重订阅：任务表说了算（既有 dispatchAgentMerged 判例口径）。
        val task = session("task-1", status = AgentStatus.WORKING)
        assertEquals(task, AgentStateLogic.merge(task, session("v4-1", status = AgentStatus.IDLE)))
        assertEquals(task, AgentStateLogic.merge(task, null))
        val v4 = session("v4-1", status = AgentStatus.WAITING_FOR_APPROVAL)
        assertEquals(v4, AgentStateLogic.merge(null, v4))
        assertNull(AgentStateLogic.merge(null, null))
    }

    // ---------- 列表状态归一（normalizeRoster） ----------

    @Test
    fun `normalizeRoster_v4只升级同会话_其余原样且保序`() {
        val roster = listOf(
            session("a", workspace = "甲", status = AgentStatus.IDLE),
            session("b", workspace = "乙", status = AgentStatus.WORKING),
            session("c", workspace = "丙", status = AgentStatus.IDLE),
        )
        val normalized = AgentStateLogic.normalizeRoster(
            roster,
            session("b", status = AgentStatus.WAITING_FOR_APPROVAL),
        )
        assertEquals(listOf("a", "b", "c"), normalized.map { it.sessionId })
        assertEquals(AgentStatus.IDLE, normalized[0].status)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, normalized[1].status)
        assertEquals(AgentStatus.IDLE, normalized[2].status)
    }

    @Test
    fun `normalizeRoster_无v4读数_任务表原样`() {
        val roster = listOf(session("a", workspace = "甲", status = AgentStatus.WORKING))
        assertEquals(roster, AgentStateLogic.normalizeRoster(roster, null))
    }

    @Test
    fun `normalizeRoster_v4会话不在册_不新增行只留任务表`() {
        val roster = listOf(session("a", workspace = "甲"))
        val normalized = AgentStateLogic.normalizeRoster(roster, session("ghost", status = AgentStatus.WAITING_FOR_APPROVAL))
        assertEquals(listOf("a"), normalized.map { it.sessionId })
        assertEquals(AgentStatus.IDLE, normalized[0].status)
    }

    // ---------- 当前档派生（状态行 / 列表选中） ----------

    @Test
    fun `当前档派生_自动档无选中键也无会话名`() {
        val roster = listOf(session("a", workspace = "甲"))
        assertNull(AgentStateLogic.selectedSessionId(SessionLockMode.Auto))
        assertNull(AgentStateLogic.lockTargetName(SessionLockMode.Auto, roster))
    }

    @Test
    fun `当前档派生_锁定在册_取工作区名`() {
        val roster = listOf(
            session("a", workspace = "甲"),
            session("b", workspace = null),
        )
        val locked = SessionLockMode.Locked("a")
        assertEquals("a", AgentStateLogic.selectedSessionId(locked))
        assertEquals("甲", AgentStateLogic.lockTargetName(locked, roster))
        // 工作区名缺失的会话回退会话键，行永远有名字可读。
        assertEquals("b", AgentStateLogic.lockTargetName(SessionLockMode.Locked("b"), roster))
    }

    @Test
    fun `当前档派生_锁定不在册_回退会话键不落空`() {
        // 存储首读到在册对账清锁之间的空窗：状态行仍显示锁了谁，不显示半截空值。
        assertEquals(
            "ghost",
            AgentStateLogic.lockTargetName(SessionLockMode.Locked("ghost"), listOf(session("a", workspace = "甲"))),
        )
    }

    @Test
    fun `会话名_工作区名优先_空串也回退会话键`() {
        assertEquals("甲", AgentStateLogic.sessionName(session("a", workspace = "甲")))
        assertEquals("a", AgentStateLogic.sessionName(session("a", workspace = "   ")))
        assertEquals("a", AgentStateLogic.sessionName(session("a", workspace = null)))
    }
}
