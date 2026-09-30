package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentMembership
import com.rearcue.poc.agent.AgentMembershipReason
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.BridgeEventCodec
import com.rearcue.poc.agent.TaskListParser
import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 0023 验收切片（票 #238）：从各来源边界解析出的 membership 事实，穿过
 * [AgentArchiveTruth] 统一在册缝，落到主屏/背屏同源投影与 core 权威移除。
 *
 * 这是确定性端到端判例，不冒充实机证据：Codex 文件移动、DSH 事件流另有 Node 判例；
 * Claude 只证明显式契约可消费，真机 producer 仍是阻塞缺口。
 */
class AgentArchiveAcceptanceTest {

    private fun task(id: String, archived: Boolean, updatedAt: Long = 1L): String =
        """{"taskId":"$id","displayStatus":"running","updatedAt":$updatedAt,"title":"$id","archived":$archived}"""

    private fun taskPayload(vararg tasks: String): String =
        """{"result":{"tasks":[${tasks.joinToString(",")}]}}"""

    private fun assertMainRearSame(truth: AgentArchiveTruth, expected: List<String>) {
        // 主屏 AppState.agentRoster 与背屏 picker 都取这一条 mirrorRoster 缝。
        val roster = AgentStateLogic.mirrorRoster(truth, v4 = null, indexWaiting = emptySet())
        val mainIds = AgentStateLogic.projectRoster(roster, DashboardEvent.SessionLockMode.Auto)
            .drop(1)
            .map { it.sessionId }
        val rearIds = AgentStateLogic.projectRoster(roster, DashboardEvent.SessionLockMode.Auto)
            .drop(1)
            .map { it.sessionId }
        assertEquals(expected, mainIds, "主屏列表")
        assertEquals(mainIds, rearIds, "背屏列表必须与主屏同源同序")
    }

    @Test
    fun `ZCode任务表membership被消费_ACTIVE到ARCHIVED到ACTIVE走统一缝`() {
        val activeFacts = TaskListParser.parseMembership(
            taskPayload(task("z-1", archived = false, updatedAt = 10L), task("z-2", archived = false, updatedAt = 20L)),
            generation = 1,
        )!!
        var truth = AgentArchiveTruth.Empty.apply(activeFacts)
        assertMainRearSame(truth, listOf("z-2", "z-1"))

        val archivedFacts = TaskListParser.parseMembership(
            taskPayload(task("z-1", archived = true, updatedAt = 30L), task("z-2", archived = false, updatedAt = 40L)),
            generation = 2,
            previouslySeen = setOf("z-1", "z-2"),
        )!!
        truth = truth.apply(archivedFacts)
        assertEquals(setOf("z-2"), truth.currentRosterIds())
        assertMainRearSame(truth, listOf("z-2"))

        val restoredFacts = TaskListParser.parseMembership(
            taskPayload(task("z-1", archived = false, updatedAt = 50L), task("z-2", archived = false, updatedAt = 40L)),
            generation = 3,
        )!!
        truth = truth.apply(restoredFacts)
        assertMainRearSame(truth, listOf("z-1", "z-2"))
    }

    @Test
    fun `Codex桥membership页被消费_墓碑拦迟到活动_取消归档恢复来源状态`() {
        val active = BridgeEventCodec.parseMembershipPage(
            """
            {"events":[{"id":1,"kind":"membership","source":"codex","sourceSessionId":"c-real","membership":"PRESENT","archiveState":"ACTIVE","reason":"membership-contract","generation":1,"revision":1,"status":"working","workspace":"C:/repo"}],"cursor":1}
            """.trimIndent(),
        )!!.single()
        var truth = AgentArchiveTruth.Empty.apply(active)
        assertEquals(setOf("bridge:c-real"), truth.currentRosterIds())

        val archived = BridgeEventCodec.parseMembershipPage(
            """
            {"events":[{"id":2,"kind":"membership","source":"codex","sourceSessionId":"c-real","membership":"ABSENT","archiveState":"ARCHIVED","reason":"archive","generation":2,"revision":2}],"cursor":2}
            """.trimIndent(),
        )!!.single()
        truth = truth.apply(archived)
        assertEquals(emptySet(), truth.currentRosterIds())

        val stale = BridgeEventCodec.parsePage(
            """
            {"events":[{"id":3,"sessionId":"c-real","source":"codex","status":"working"}],"cursor":3}
            """.trimIndent(),
        )!!.single()
        val staleObserved = truth.observe(BridgeEventCodec.toSessionState(stale)!!)
        assertEquals(emptySet(), staleObserved.currentRosterIds(), "归档后的桥活动不得复活")

        val restored = BridgeEventCodec.parseMembershipPage(
            """
            {"events":[{"id":4,"kind":"membership","source":"codex","sourceSessionId":"c-real","membership":"PRESENT","archiveState":"ACTIVE","reason":"unarchive","generation":3,"revision":3,"status":"idle","workspace":"C:/repo"}],"cursor":4}
            """.trimIndent(),
        )!!.single()
        truth = truth.apply(restored)
        assertEquals(setOf("bridge:c-real"), truth.currentRosterIds())
        assertEquals(AgentStatus.IDLE, truth.currentRoster().single().status)
        assertEquals("C:/repo", truth.currentRoster().single().workspace)
    }

    @Test
    fun `DSH实时出册从membership事件页到core清锁清场_无需重连快照`() {
        val active = BridgeEventCodec.parseMembershipPage(
            """
            {"events":[{"id":1,"kind":"membership","source":"dsh","sourceSessionId":"d-live","membership":"PRESENT","archiveState":"ACTIVE","reason":"source-created","generation":1,"revision":1,"status":"waiting","workspace":"C:/dsh"}],"cursor":1}
            """.trimIndent(),
        )!!.single()
        var truth = AgentArchiveTruth.Empty.apply(active)
        val state = assertNotNull(active.state)
        val core = DashboardCore(nowMs = { 0L }, log = {})
        core.onEvent(DashboardEvent.AgentSessionUpdated(state))
        core.onEvent(DashboardEvent.SessionLock(DashboardEvent.SessionLockMode.Locked(state.sessionId)))
        assertEquals(state.sessionId, core.agentState?.sessionId)

        val before = truth.currentRosterIds()
        val removal = BridgeEventCodec.parseMembershipPage(
            """
            {"events":[{"id":2,"kind":"membership","source":"dsh","sourceSessionId":"d-live","membership":"ABSENT","archiveState":"UNKNOWN","reason":"source-removed","generation":1,"revision":2}],"cursor":2}
            """.trimIndent(),
        )!!.single()
        assertEquals(AgentMembershipReason.SOURCE_REMOVED, removal.reason)
        truth = truth.apply(removal)
        val removed = before - truth.currentRosterIds()
        core.onEvent(DashboardEvent.AgentSessionsRemoved(removed))

        assertEquals(emptySet(), truth.currentRosterIds())
        assertNull(core.agentState, "实时移除必须撤下镜像状态")
        assertEquals(DashboardEvent.SessionLockMode.Auto, core.sessionLock, "实时移除必须清锁回自动")
    }

    @Test
    fun `Claude显式membership契约可消费_但本判例不是真机归档证据`() {
        val source = AgentSources.CLAUDE
        fun fact(id: String, membership: String, archiveState: String, reason: String, generation: Long) =
            BridgeEventCodec.parseMembershipPage(
                """
                {"events":[{"id":$generation,"kind":"membership","source":"$source","sourceSessionId":"$id","membership":"$membership","archiveState":"$archiveState","reason":"$reason","generation":$generation,"revision":$generation,"status":"idle"}],"cursor":$generation}
                """.trimIndent(),
            )!!.single()

        val active = fact("cl-real", "PRESENT", "ACTIVE", "membership-contract", 1L)
        val archived = fact("cl-real", "ABSENT", "ARCHIVED", "archive", 2L)
        val restored = fact("cl-real", "PRESENT", "ACTIVE", "unarchive", 3L)
        val truth = AgentArchiveTruth.Empty
            .apply(active)
            .apply(archived)
            .apply(restored)
        assertEquals(setOf("bridge:cl-real"), truth.currentRosterIds())
        assertTrue(restored.newerThan(archived.generation, archived.revision))
        assertEquals(AgentMembership.PRESENT, restored.membership)
        // 真机缺口保持显式：Claude app 有 isArchived/archive API，但桥还没有稳定 producer。
    }
}
