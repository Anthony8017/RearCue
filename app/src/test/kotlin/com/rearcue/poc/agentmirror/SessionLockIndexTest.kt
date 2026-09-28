package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.RelayEnvelope
import com.rearcue.poc.agent.SessionIndexFeed
import com.rearcue.poc.agent.TaskListParser
import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEffect
import com.rearcue.poc.core.DashboardEvent.AgentSessionUpdated
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.SessionLock
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 锁档插队的**真实链路**判例（票 #103 P0）：任务表 wire → [TaskListParser]、sessions-index
 * wire → [SessionIndexFeed]、两者经 [AgentStateLogic.dispatchBatch] 合成，唯一入口是
 * [AgentSessionUpdated] 事件本身——**不手工注入「等待」事实**（[SessionLockTest] 的
 * `waiting()` 那类手造状态不算本判例；Debug Bypass 注入更不算）。
 *
 * 覆盖 P0 断言：锁定 A 时 B 进等待 → core 显示面插队 B → 处理完（索引清除等待）→ 回锁 A。
 * 没有这条链，插队分支在真实双会话下是死代码（任务表无等待语义、v4 帧只覆盖单订阅会话）。
 */
class SessionLockIndexTest {

    private val logs = mutableListOf<String>()
    private fun core() = DashboardCore(nowMs = { 0L }, log = { logs += it })

    private val lockTarget = "sess_lock_a"
    private val other = "sess_other_b"
    private val archived = "sess_archived_x"

    /** 任务表 wire（真实形态：displayStatus 只有 running/completed，无等待语义）。 */
    private val workspaceList = """
        {"zcode_type":"workspace-list-response","requestId":"poll","result":{
          "activeWorkspaceKey":"C:\\ws","activeTaskId":"$lockTarget",
          "tasks":[
            {"taskId":"$lockTarget","displayStatus":"running","updatedAt":2000,
             "workspaceLabel":"RearCue","workspacePath":"C:\\ws","title":"A 在跑"},
            {"taskId":"$other","displayStatus":"completed","updatedAt":1000,
             "workspaceLabel":"RearCue","workspacePath":"C:\\ws","title":"B 跑完了"}
          ]}}
    """.trimIndent()

    /**
     * sessions-index 快照 wire（真实形态，schema 见 [SessionIndexFeed]）：
     * [waitingFor] 会话带 `pendingInteraction`（等批准），其余不带。
     */
    private fun indexSnapshot(waitingFor: String?): String {
        val sessions = listOf(lockTarget, other, archived).joinToString(",") { id ->
            val pending = if (id == waitingFor) {
                """"pendingInteraction":{"interactionId":"perm_1","kind":"permission","toolName":"Write"},
                   "pendingInteractionSummary":{"permissionCount":1,"userInputCount":0},"""
            } else {
                """"pendingInteractionSummary":{"permissionCount":0,"userInputCount":0},"""
            }
            """{"sessionId":"$id","workspaceId":"ws-1","title":"t-$id","phase":"running",
                "sessionEnded":false,$pending"lastActivityAt":1790573810000,"createdAt":1790570000000}"""
        }
        return """
            {"wireVersion":3,"kind":"complete","deliveryKind":"online",
             "topic":"sessions-index/C:\\ws","subscriptionId":"sub-idx-1",
             "frame":{"topic":"sessions-index/C:\\ws","subscriptionId":"sub-idx-1",
               "fromSeq":0,"toSeq":1,"sentAt":1790573810726,
               "payload":{"kind":"snapshot","snapshot":{"protocolVersion":1,
                 "workspaceId":"ws-1","logEpoch":"e1","sessions":[$sessions]}}}}
        """.trimIndent()
    }

    /** 接线层同一套：解析 wire → dispatchBatch → 唯一入口 AgentSessionUpdated。 */
    private inner class Rig {
        val core = core()
        val roster: List<AgentSessionState> = TaskListParser.parseAll(workspaceList)!!
        private val feed = SessionIndexFeed("C:\\ws")
        private var dispatched: Set<String> = emptySet()

        /** 一帧索引 wire → 按当前派发基线算批次并进 core；返回（效果，送入的状态）。 */
        fun indexFrame(wire: String): Pair<List<DashboardEffect>, List<AgentSessionState>> {
            feed.applyFrame(RelayEnvelope.json.parseToJsonElement(wire))
            val batch = AgentStateLogic.dispatchBatch(
                roster = roster,
                task = TaskListParser.latest(roster),
                v4 = null,
                indexEntries = feed.entries(),
                dispatchedWaiting = dispatched,
            )
            dispatched = batch.waitingDispatched
            val effects = batch.states.flatMap { core.onEvent(AgentSessionUpdated(it)) }
            return effects to batch.states
        }
    }

    @Test
    fun `锁档下他会话索引判等_插队显示等待会话`() {
        val rig = Rig()
        rig.core.onEvent(ProjectionReady)
        rig.core.onEvent(SessionLock(SessionLockMode.Locked(lockTarget)))
        // 任务表口径先入 core：A 在跑 ⇒ 锁定显示 A（任务表给不出任何人的等待）
        rig.indexFrame(indexSnapshot(waitingFor = null))
        assertEquals(lockTarget, rig.core.agentState?.sessionId)

        // B 进等待（索引 pendingInteraction，锁档把 v4 订阅钉在 A 上、B 的帧到不了）：
        // 进出补发把 B 以等确认送进 core ⇒ 显示面插队 B——真实链路在此闭合。
        val (effects, states) = rig.indexFrame(indexSnapshot(waitingFor = other))
        assertTrue(states.any { it.sessionId == other && it.status.name == "WAITING_FOR_APPROVAL" }, "states=$states")
        assertEquals(other, rig.core.agentState?.sessionId)
        assertEquals("WAITING_FOR_APPROVAL", rig.core.agentState?.status?.name)
        assertEquals("RearCue", rig.core.agentState?.workspace)
        assertTrue(rig.core.agentOnScreen, "插队者在屏 effects=$effects")
    }

    @Test
    fun `等待处理完_索引清除等待_回锁显示锁定会话`() {
        val rig = Rig()
        rig.core.onEvent(ProjectionReady)
        rig.core.onEvent(SessionLock(SessionLockMode.Locked(lockTarget)))
        rig.indexFrame(indexSnapshot(waitingFor = null))
        rig.indexFrame(indexSnapshot(waitingFor = other))
        assertEquals(other, rig.core.agentState?.sessionId)

        // 处理完：索引快照不再判等 ⇒ 出集补发 B 的任务表基态（跑完＝空闲），
        // core 里 B 的等确认撤下、显示回锁定会话 A（锁档不动、屏不撤、无投撤效果）。
        val (effects, states) = rig.indexFrame(indexSnapshot(waitingFor = null))
        assertTrue(states.none { it.status.name == "WAITING_FOR_APPROVAL" }, "states=$states")
        assertEquals(lockTarget, rig.core.agentState?.sessionId)
        assertEquals(SessionLockMode.Locked(lockTarget), rig.core.sessionLock)
        assertEquals(emptyList<DashboardEffect>(), effects, "effects=$effects")
        assertTrue(rig.core.agentOnScreen)
    }

    @Test
    fun `索引等待只认任务表在册_归档会话不插队`() {
        val rig = Rig()
        rig.core.onEvent(ProjectionReady)
        rig.core.onEvent(SessionLock(SessionLockMode.Locked(lockTarget)))
        rig.indexFrame(indexSnapshot(waitingFor = null))

        // 索引含已归档会话（wire 事实），但任务表在册才可能上屏/被锁定——交集裁掉它。
        val (_, states) = rig.indexFrame(indexSnapshot(waitingFor = archived))
        assertTrue(states.none { it.sessionId == archived }, "states=$states")
        assertEquals(lockTarget, rig.core.agentState?.sessionId)
    }

    @Test
    fun `多次索引帧_进出集幂等_不重复补发`() {
        val rig = Rig()
        rig.core.onEvent(ProjectionReady)
        rig.core.onEvent(SessionLock(SessionLockMode.Locked(lockTarget)))
        rig.indexFrame(indexSnapshot(waitingFor = null))
        rig.indexFrame(indexSnapshot(waitingFor = other))
        // 同一等待态再来一帧：进出集无变化 ⇒ 零状态补发（单条口径那条仍照发）
        val (_, states) = rig.indexFrame(indexSnapshot(waitingFor = other))
        assertTrue(states.none { it.sessionId == other }, "states=$states")
        assertEquals(other, rig.core.agentState?.sessionId)
    }
}
