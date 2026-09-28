package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.AgentConnectionChanged
import com.rearcue.poc.core.DashboardEvent.AgentRoster
import com.rearcue.poc.core.DashboardEvent.AgentSessionUpdated
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.NotificationRemoved
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.SessionLock
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import com.rearcue.poc.core.DashboardEffect.ExitDashboard
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session Lock 仲裁（票 #103，CONTEXT.md「Session Lock」）：锁定只定「显示谁」不改接管门槛、
 * 等确认永远插队、锁定会话不锁屏（空闲回落常规内容）、锁定会话从任务表消失自动清锁退回自动。
 * 只断言「事件序列 → 效果序列 + 只读投影」（[AgentArbitrationTest] 的判例口径）。
 */
class SessionLockTest {

    private val wechat = "com.tencent.mm"
    private val logs = mutableListOf<String>()

    private fun core() = DashboardCore(setOf(wechat), { 0L }, { logs += it })

    private fun working(sessionId: String = "s1", updatedAt: Long = 100L) =
        AgentSessionUpdated(
            AgentSessionState(sessionId, workspace = "ws", status = AgentStatus.WORKING, updatedAt = updatedAt),
        )

    private fun waiting(sessionId: String = "s1", updatedAt: Long = 100L) =
        AgentSessionUpdated(
            AgentSessionState(
                sessionId,
                workspace = "ws",
                status = AgentStatus.WAITING_FOR_APPROVAL,
                updatedAt = updatedAt,
            ),
        )

    private fun idle(sessionId: String = "s1") =
        AgentSessionUpdated(AgentSessionState(sessionId, status = AgentStatus.IDLE, updatedAt = 999L))

    private fun lock(sessionId: String?) = SessionLock(
        sessionId?.let { SessionLockMode.Locked(it) } ?: SessionLockMode.Auto,
    )

    // ---------- 自动档无回归 ----------

    @Test
    fun `自动档为默认_换档事件幂等无效果`() {
        val core = core()
        assertEquals(SessionLockMode.Auto, core.sessionLock)
        assertEquals(emptyList(), core.onEvent(lock(null)))
        assertEquals(SessionLockMode.Auto, core.sessionLock)
    }

    @Test
    fun `自动档仲裁与现状逐字一致_等确认插队取最近活跃`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))
        core.onEvent(waiting(sessionId = "b", updatedAt = 50L))
        assertEquals("b", core.agentState?.sessionId)
        core.onEvent(idle("b"))
        assertEquals("a", core.agentState?.sessionId)
    }

    @Test
    fun `自动档任务表对账不动锁`() {
        val core = core()
        assertEquals(emptyList(), core.onEvent(AgentRoster(setOf("x"))))
        assertEquals(SessionLockMode.Auto, core.sessionLock)
    }

    // ---------- 锁定档显示面 ----------

    @Test
    fun `锁定显示锁定会话_不随最近活跃漂移`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))
        core.onEvent(working(sessionId = "b", updatedAt = 50L))
        assertEquals("a", core.agentState?.sessionId) // 自动档：a 最近活跃

        // 锁到较旧的 b：显示面即刻改判；理由仍在身（不重投不退出，效果为空）
        val effects = core.onEvent(lock("b"))
        assertEquals(emptyList(), effects)
        assertEquals("b", core.agentState?.sessionId)
        assertEquals(CastSource.AGENT, core.castSource)
        assertTrue(core.agentOnScreen)
    }

    @Test
    fun `锁定会话空闲即回落常规内容_锁会话不锁屏`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        core.onEvent(ProjectionReady)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))
        core.onEvent(idle("b"))
        assertEquals(CastSource.AGENT, core.castSource)

        // 锁到空闲会话 b：理由消失（a 再忙也不顶班）→ 交还 auto 留屏、显示面回落（null）
        val effects = core.onEvent(lock("b"))
        assertEquals(emptyList(), effects)
        assertEquals(CastSource.AUTO, core.castSource)
        assertFalse(core.agentOnScreen)
        assertNull(core.agentState)

        // 常规内容也清空 → 判退（锁定不把屏钉住）
        assertEquals(listOf(ExitDashboard), core.onEvent(NotificationRemoved(wechat, "k1")))
        assertNull(core.castSource)
    }

    @Test
    fun `锁定会话忙碌时别的会话空闲不改显示`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))
        core.onEvent(working(sessionId = "b", updatedAt = 900L))
        core.onEvent(lock("a"))
        assertEquals("a", core.agentState?.sessionId)

        core.onEvent(idle("b"))
        assertEquals("a", core.agentState?.sessionId)
        assertEquals(CastSource.AGENT, core.castSource)
    }

    // ---------- 等确认插队 ----------

    @Test
    fun `他会话等确认临时插队_处理完回锁定会话`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))
        core.onEvent(lock("a"))

        core.onEvent(waiting(sessionId = "b", updatedAt = 50L))
        assertEquals("b", core.agentState?.sessionId)
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, core.agentState?.status)

        core.onEvent(idle("b"))
        assertEquals("a", core.agentState?.sessionId)
        assertEquals(CastSource.AGENT, core.castSource)
    }

    @Test
    fun `锁定会话空闲时他会话等确认照样插队接管_处理完回落`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(lock("a")) // a 不在册（视同空闲）

        // 等确认在背屏内容选择中永远优先：临时插队显示 b、理由在身即接管
        val effects = core.onEvent(waiting(sessionId = "b", updatedAt = 50L))
        assertTrue(effects.contains(LaunchDashboard(emptySet())), "effects=$effects")
        assertEquals("b", core.agentState?.sessionId)

        // 处理完：等待消失 → 锁会话仍空闲 ⇒ 无理由回落（无人在屏即判退）
        assertEquals(listOf(ExitDashboard), core.onEvent(idle("b")))
        assertNull(core.agentState)
        assertNull(core.castSource)
    }

    // ---------- 任务表在册对账 ----------

    @Test
    fun `锁定会话从任务表消失_自动清锁退回自动`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))
        core.onEvent(waiting(sessionId = "b", updatedAt = 50L))
        core.onEvent(lock("a"))
        assertEquals(SessionLockMode.Locked("a"), core.sessionLock)

        // a 不在册（电脑端消失）→ core 清锁 + 打词形锚；显示面回自动仲裁（b 等确认仍插队）
        val effects = core.onEvent(AgentRoster(setOf("b")))
        assertEquals(emptyList(), effects)
        assertEquals(SessionLockMode.Auto, core.sessionLock)
        assertTrue(logs.contains("session lock cleared a"), "logs=$logs")
        assertEquals("b", core.agentState?.sessionId)
        assertEquals(CastSource.AGENT, core.castSource)
    }

    @Test
    fun `锁定会话仍在册_对账不清锁`() {
        val core = core()
        core.onEvent(lock("a"))
        assertEquals(emptyList(), core.onEvent(AgentRoster(setOf("a", "b"))))
        assertEquals(SessionLockMode.Locked("a"), core.sessionLock)

        // 在册为空（任务表清空）同样视为「都不在册」→ 清锁
        core.onEvent(AgentRoster(emptySet()))
        assertEquals(SessionLockMode.Auto, core.sessionLock)
    }

    @Test
    fun `清锁后锁定会话不复存在_自动档回落不虚构显示`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))
        core.onEvent(lock("ghost")) // 锁到不在册会话：显示面立刻回落、理由消失判退
        assertNull(core.agentState)
        assertNull(core.castSource)

        // 任务表对账确认 ghost 不在册 → 清锁（词形锚），自动档回到 a
        core.onEvent(AgentRoster(setOf("a")))
        assertEquals(SessionLockMode.Auto, core.sessionLock)
        assertEquals("a", core.agentState?.sessionId)
        assertTrue(logs.contains("session lock cleared ghost"), "logs=$logs")
    }

    // ---------- 断连语义不被锁定改写 ----------

    @Test
    fun `断连仍整体回落_锁定档也不例外`() {
        val core = core()
        core.onEvent(NotificationPosted(wechat, "k1", "标题", "正文"))
        core.onEvent(ProjectionReady)
        core.onEvent(working(sessionId = "a", updatedAt = 100L))
        core.onEvent(lock("a"))
        assertEquals(CastSource.AGENT, core.castSource)

        val effects = core.onEvent(AgentConnectionChanged(connected = false))
        assertEquals(emptyList(), effects)
        assertEquals(CastSource.AUTO, core.castSource)
        assertFalse(core.agentOnScreen)
    }
}
