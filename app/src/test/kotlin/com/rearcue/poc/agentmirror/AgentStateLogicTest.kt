package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.SessionIndexEntry
import com.rearcue.poc.agent.TaskListParser
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        source: String? = null,
        turns: List<AgentTurn> = emptyList(),
    ) = AgentSessionState(
        sessionId = id,
        workspace = workspace,
        status = status,
        updatedAt = updatedAt,
        source = source,
        turns = turns,
    )

    // ---- 问答流进投影（spec 0017 / 票 #169 明确要求的回归） ----

    @Test
    fun `归一后仍带问答流——任务表与 v4 同键时不许把 turns 丢掉`() {
        // 现场踩过：ZCode 的任务表与 v4 帧同 sessionId，归一走 else 分支重建状态对象，
        // 若那里不搬运 turns，问答流就被整类丢掉——背屏只看得到单条最新回复。
        val v4 = session("s1", status = AgentStatus.WORKING).copy(
            latestReply = "它的回答",
            turns = listOf(
                AgentTurn(AgentTurnRole.USER, "我的提问"),
                AgentTurn(AgentTurnRole.AGENT, "它的回答"),
            ),
        )
        val task = session("s1", status = AgentStatus.IDLE, source = "zcode")

        val merged = AgentStateLogic.merge(task, v4)!!
        assertEquals(
            listOf("我的提问", "它的回答"),
            merged.turns.map { it.text },
            "归一必须搬运问答流（v4 是唯一有行集的一侧）",
        )
        assertEquals("它的回答", merged.latestReply)
    }

    @Test
    fun `只有任务表一侧时用它的问答流（通常为空）`() {
        val task = session("s1", turns = listOf(AgentTurn(AgentTurnRole.USER, "只有任务表")))
        assertEquals(1, AgentStateLogic.merge(task, null)!!.turns.size)
        val v4Only = session("s2", turns = listOf(AgentTurn(AgentTurnRole.AGENT, "只有 v4")))
        assertEquals(1, AgentStateLogic.merge(null, v4Only)!!.turns.size)
    }

    @Test
    fun `会话键不一致时用任务表（不把上一个会话的问答流带过来）`() {
        val task = session("s1", turns = listOf(AgentTurn(AgentTurnRole.USER, "任务表的提问")))
        val v4 = session("s2", turns = listOf(AgentTurn(AgentTurnRole.USER, "旧会话的提问")))
        assertEquals(listOf("任务表的提问"), AgentStateLogic.merge(task, v4)!!.turns.map { it.text })
    }

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

    // ---------- 合并在册集与统一列表投影（spec 0016 / 票 #154） ----------

    @Test
    fun `mergeRoster_三来源合并去重_后值覆盖且保到达序`() {
        val zcode = session("z-1", workspace = "C:\\work\\RearCue", status = AgentStatus.WORKING, updatedAt = 10L, source = "zcode")
        val codex = session("bridge:c-1", workspace = "C:/work/RearCue", status = AgentStatus.IDLE, updatedAt = 20L, source = "codex")
        val claude = session("bridge:cl-1", workspace = "C:/work/Claude", status = AgentStatus.IDLE, updatedAt = 30L, source = "claude")
        val codexNew = codex.copy(status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 40L)

        val merged = AgentStateLogic.mergeRoster(listOf(zcode, codex), listOf(claude), listOf(codexNew))

        assertEquals(listOf("z-1", "bridge:c-1", "bridge:cl-1"), merged.map { it.sessionId })
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, merged[1].status)
        assertEquals(40L, merged[1].updatedAt)
        assertEquals("codex", merged[1].source)
        assertEquals(3, merged.size)
    }

    @Test
    fun `projectRoster_自动行置顶_选中态跟当前档`() {
        val roster = listOf(
            session("a", workspace = "甲"),
            session("b", workspace = "乙"),
        )

        val auto = AgentStateLogic.projectRoster(roster, SessionLockMode.Auto)
        assertTrue(auto.first().auto)
        assertTrue(auto.first().selected)
        assertFalse(auto.drop(1).any { it.selected })

        val locked = AgentStateLogic.projectRoster(roster, SessionLockMode.Locked("b"))
        assertTrue(locked.first().auto)
        assertFalse(locked.first().selected)
        assertTrue(locked.first { it.sessionId == "b" }.selected)
    }

    @Test
    fun `projectRoster_等待确认置顶_组内最近活跃降序_平局保到达序`() {
        val roster = listOf(
            session("a", workspace = "甲", status = AgentStatus.WORKING, updatedAt = 100L),
            session("b", workspace = "乙", status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 50L),
            session("c", workspace = "丙", status = AgentStatus.WORKING, updatedAt = 300L),
            session("d", workspace = "丁", status = AgentStatus.WAITING_FOR_APPROVAL, updatedAt = 500L),
            session("e", workspace = "戊", status = AgentStatus.WORKING, updatedAt = 100L),
        )

        val rows = AgentStateLogic.projectRoster(roster, SessionLockMode.Auto)

        assertEquals(listOf("d", "b", "c", "a", "e"), rows.drop(1).map { it.sessionId })
    }

    @Test
    fun `projectRoster_标题目录名_空尾4位_重名附尾号_来源标记`() {
        val roster = listOf(
            session("sess-a123", workspace = "C:\\Users\\me\\RearCue", source = "zcode"),
            session("bridge-c456", workspace = "C:/work/RearCue", source = "codex"),
            session("task-9876", workspace = "   ", source = null),
            session("task-1111", workspace = "D:\\work\\Ant_Nest", source = "claude"),
        )

        val rows = AgentStateLogic.projectRoster(roster, SessionLockMode.Auto).drop(1)

        assertEquals("RearCue · a123", rows.first { it.sessionId == "sess-a123" }.title)
        assertEquals("RearCue · c456", rows.first { it.sessionId == "bridge-c456" }.title)
        assertEquals("9876", rows.first { it.sessionId == "task-9876" }.title)
        assertEquals("Ant_Nest", rows.first { it.sessionId == "task-1111" }.title)
        assertEquals("ZCode", rows.first { it.sessionId == "sess-a123" }.sourceLabel)
        assertEquals("Codex", rows.first { it.sessionId == "bridge-c456" }.sourceLabel)
        assertEquals("Claude", rows.first { it.sessionId == "task-1111" }.sourceLabel)
        assertNull(rows.first { it.sessionId == "task-9876" }.sourceLabel)
    }

    // ---------- sessions-index 等待视图（票 #103 P0） ----------

    private fun entry(id: String, waiting: Boolean) = SessionIndexEntry(id, waiting, 1_000L)

    @Test
    fun `索引等待集_只取在册交集_索引含归档也不越界`() {
        val roster = listOf(session("a", workspace = "甲"), session("b", workspace = "乙"))
        val entries = listOf(entry("a", waiting = false), entry("b", waiting = true), entry("archived", waiting = true))
        assertEquals(setOf("b"), AgentStateLogic.indexWaitingIds(entries, roster))
        assertEquals(emptySet(), AgentStateLogic.indexWaitingIds(entries, emptyList()))
        assertEquals(emptySet(), AgentStateLogic.indexWaitingIds(emptyList(), roster))
    }

    @Test
    fun `等确认升级_只上调状态位_已是等确认不重复改写`() {
        val working = session("a", workspace = "甲", status = AgentStatus.WORKING, updatedAt = 7L)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, AgentStateLogic.withIndexWaiting(working, waiting = true).status)
        assertEquals("甲", AgentStateLogic.withIndexWaiting(working, waiting = true).workspace)
        assertEquals(7L, AgentStateLogic.withIndexWaiting(working, waiting = true).updatedAt)
        // 不判等则原样（含已是等确认的——v4 口径不被索引降级）
        assertEquals(working, AgentStateLogic.withIndexWaiting(working, waiting = false))
        val waiting = session("a", status = AgentStatus.WAITING_FOR_APPROVAL)
        assertEquals(waiting, AgentStateLogic.withIndexWaiting(waiting, waiting = false))
    }

    @Test
    fun `派发批次_单条口径不变_索引判等上调该条`() {
        val roster = listOf(
            session("a", workspace = "甲", status = AgentStatus.WORKING, updatedAt = 2_000L),
            session("b", workspace = "乙", status = AgentStatus.IDLE, updatedAt = 1_000L),
        )
        // 任务表最新＝a；索引不判等 ⇒ 逐字单条（无补发、无改写）
        val plain = AgentStateLogic.dispatchBatch(roster, TaskListParser.latest(roster), null, emptyList(), emptySet())
        assertEquals(listOf("a"), plain.states.map { it.sessionId })
        assertEquals(AgentStatus.WORKING, plain.states.single().status)
        assertEquals(emptySet(), plain.waitingDispatched)

        // 索引判等 b ⇒ 单条仍 a（最新没变），b 以等确认补发；已发集跟上
        val entries = listOf(entry("a", waiting = false), entry("b", waiting = true))
        val enter = AgentStateLogic.dispatchBatch(roster, TaskListParser.latest(roster), null, entries, emptySet())
        assertEquals(listOf("a", "b"), enter.states.map { it.sessionId })
        assertEquals(AgentStatus.WORKING, enter.states[0].status)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, enter.states[1].status)
        assertEquals(setOf("b"), enter.waitingDispatched)

        // 索引撤等 ⇒ 无新进、b 出集回任务表基态（空闲）
        val exit = AgentStateLogic.dispatchBatch(
            roster,
            TaskListParser.latest(roster),
            null,
            listOf(entry("a", waiting = false), entry("b", waiting = false)),
            enter.waitingDispatched,
        )
        assertEquals(listOf("a", "b"), exit.states.map { it.sessionId })
        assertEquals(AgentStatus.IDLE, exit.states[1].status)
        assertEquals(emptySet(), exit.waitingDispatched)
    }

    @Test
    fun `派发批次_会话从名册消失_补空闲不让等确认滞留`() {
        val roster = listOf(session("a", workspace = "甲", status = AgentStatus.WORKING, updatedAt = 2_000L))
        val batch = AgentStateLogic.dispatchBatch(
            roster = roster,
            task = TaskListParser.latest(roster),
            v4 = null,
            indexEntries = listOf(entry("gone", waiting = false)), // 已从索引/名册移除
            dispatchedWaiting = setOf("gone"),
        )
        val idle = batch.states.firstOrNull { it.sessionId == "gone" }
        assertEquals(AgentStatus.IDLE, idle?.status)
        assertEquals(emptySet(), batch.waitingDispatched)
    }

    @Test
    fun `列表三态_索引判等升级任一在册行_其余照任务表`() {
        val roster = listOf(
            session("a", workspace = "甲", status = AgentStatus.WORKING),
            session("b", workspace = "乙", status = AgentStatus.IDLE),
        )
        val normalized = AgentStateLogic.normalizeRoster(roster, null, indexWaiting = setOf("b"))
        assertEquals(listOf("a", "b"), normalized.map { it.sessionId })
        assertEquals(AgentStatus.WORKING, normalized[0].status)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, normalized[1].status)
        // 缺索引读数时与既有口径逐字一致（两态来自任务表，不伪造等待）
        assertEquals(roster, AgentStateLogic.normalizeRoster(roster, null))
    }
}
