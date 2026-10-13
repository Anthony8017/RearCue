package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentUserQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PriorityNotificationTest {
    private val app = "chat"
    private fun core() = DashboardCore(initialAllowlist = setOf(app), nowMs = { 0L }).apply {
        onEvent(DashboardEvent.ProjectionReady)
        onEvent(DashboardEvent.PriorityApps(setOf(app)))
        onEvent(DashboardEvent.AgentSessionUpdated(session("original")))
    }
    private fun session(id: String, status: AgentStatus = AgentStatus.WORKING) =
        AgentSessionState(id, status = status, updatedAt = 1L)
    private fun post(key: String = "n") = DashboardEvent.NotificationPosted(app, key, "消息", "正文")

    @Test fun `新通知切页后停留 手动切回可取消 下一条再次切页`() {
        val core = core()
        core.onEvent(post())
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        assertFalse(core.agentPicker)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("original")))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.ContentPageToggle)
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)
        core.onEvent(post("next"))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `默认待确认优先 多条合并 处理后到通知页`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
        core.onEvent(post("one"))
        core.onEvent(post("two"))
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertEquals("approval", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval")))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.ContentPageToggle)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("original")))
        assertEquals(ContentPage.AGENT, core.contentPage)
    }

    @Test fun `被挡住的通知全移除就不跳转`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
        core.onEvent(post())
        core.onEvent(DashboardEvent.NotificationRemoved(app, "n"))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval")))
        assertEquals(ContentPage.AGENT, core.contentPage)
        assertTrue(core.agentPicker)
    }

    @Test fun `内容更新 快照 和开启开关不触发`() {
        val core = core()
        core.onEvent(DashboardEvent.PriorityApps(emptySet()))
        core.onEvent(post())
        core.onEvent(DashboardEvent.PriorityApps(setOf(app)))
        core.onEvent(DashboardEvent.NotificationUpdated(app, "n", "更新", "正文"))
        core.onEvent(post("snapshot").copy(fromSnapshot = true))
        assertEquals(ContentPage.AGENT, core.contentPage)
    }

    @Test fun `优先通知排第一可切走待确认 待确认结束不恢复旧页`() {
        val core = core()
        core.onEvent(DashboardEvent.DisplayPriorityChanged(listOf(DisplayPriority.NOTIFICATION)))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
        core.onEvent(post())
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval")))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `优先通知压过播报显示 播报结束仍停通知`() {
        val core = core()
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("original"))
        core.onEvent(post())
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `拖动语音到待确认之前显示播报来源`() {
        val core = core()
        core.onEvent(DashboardEvent.DisplayPriorityChanged(listOf(DisplayPriority.VOICE)))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("original"))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
        assertEquals("original", core.agentState?.sessionId)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertEquals("approval", core.agentState?.sessionId)
    }

    @Test fun `待答排到待确认之前可显示问题`() {
        val core = core()
        core.onEvent(DashboardEvent.DisplayPriorityChanged(listOf(DisplayPriority.QUESTION)))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("question").copy(
            pendingQuestions = listOf(AgentUserQuestion("q", "继续吗？")),
        )))
        assertEquals("question", core.agentState?.sessionId)
    }

    @Test fun `不绕过姿态门 通道恢复仍可显示尚在的通知`() {
        val core = DashboardCore(initialAllowlist = setOf(app), nowMs = { 0L })
        core.onEvent(DashboardEvent.PriorityApps(setOf(app)))
        core.onEvent(DashboardEvent.PostureGateEnabled(true))
        core.onEvent(DashboardEvent.PostureGate(false))
        core.onEvent(DashboardEvent.ProjectionReady)
        assertTrue(core.onEvent(post()).isEmpty())
        assertEquals(null, core.contentPage)
        core.onEvent(DashboardEvent.PostureGate(true))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `手动退出清待跳转 新通知仍可投送`() {
        val core = core()
        core.onEvent(post())
        core.onEvent(DashboardEvent.ManualExit)
        core.onEvent(DashboardEvent.ManualCast)
        assertEquals(ContentPage.AGENT, core.contentPage)
        core.onEvent(post("new"))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `通知显示后待确认插队 处理完回通知页`() {
        val core = core()
        core.onEvent(post())
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
        assertEquals(ContentPage.AGENT, core.contentPage)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval")))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `系统重投之后仍以通知页为插队返回目标`() {
        val core = core()
        core.onEvent(post())
        core.onEvent(DashboardEvent.TakeoverDetected)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval")))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `待跳转部分移除仍保留其他触发 全部移除才取消`() {
        val core = core()
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
        core.onEvent(post("one"))
        core.onEvent(post("two"))
        core.onEvent(DashboardEvent.NotificationRemoved(app, "one"))
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval")))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `撤销开关或移出白名单取消尚未显示的跳转`() {
        for (event in listOf(DashboardEvent.PriorityApps(emptySet()), DashboardEvent.Allowlist(emptySet()))) {
            val core = core()
            core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
            core.onEvent(post())
            core.onEvent(event)
            core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval")))
            assertEquals(ContentPage.AGENT, core.contentPage)
        }
    }

    @Test fun `通道未就绪不投送 恢复时优先通知直接进入通知页`() {
        val core = DashboardCore(initialAllowlist = setOf(app), nowMs = { 0L })
        core.onEvent(DashboardEvent.PriorityApps(setOf(app)))
        assertTrue(core.onEvent(post()).isEmpty())
        assertEquals(null, core.contentPage)
        assertTrue(core.onEvent(DashboardEvent.ProjectionReady).any { it is DashboardEffect.LaunchDashboard })
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `Codex 待答保持被动提示 不压过通知页`() {
        val core = core()
        core.onEvent(post())
        core.onEvent(DashboardEvent.AgentSessionUpdated(session("codex-question").copy(
            source = "codex", pendingQuestions = listOf(AgentUserQuestion("q", "继续吗？")),
        )))
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }

    @Test fun `无效重复排序归一为完整四项`() {
        assertEquals(DisplayPriority.DEFAULT, DisplayPriority.normalize(emptyList()))
        assertEquals(listOf(DisplayPriority.VOICE, DisplayPriority.APPROVAL, DisplayPriority.QUESTION, DisplayPriority.NOTIFICATION),
            DisplayPriority.normalize(listOf(DisplayPriority.VOICE, DisplayPriority.VOICE)))
    }

    @Test fun `四项全部有效时全部二十四种排序都显示首项`() {
        fun permutations(items: List<DisplayPriority>): List<List<DisplayPriority>> =
            if (items.isEmpty()) listOf(emptyList()) else items.flatMap { first ->
                permutations(items - first).map { listOf(first) + it }
            }
        for (order in permutations(DisplayPriority.DEFAULT)) {
            val core = core()
            core.onEvent(DashboardEvent.DisplayPriorityChanged(order))
            core.onEvent(DashboardEvent.VoiceBroadcastStarted("original"))
            core.onEvent(DashboardEvent.AgentSessionUpdated(session("approval", AgentStatus.WAITING_FOR_APPROVAL)))
            core.onEvent(DashboardEvent.AgentSessionUpdated(session("question").copy(
                pendingQuestions = listOf(AgentUserQuestion("q", "继续吗？")),
            )))
            core.onEvent(post())
            val first = order.first()
            if (first == DisplayPriority.NOTIFICATION) {
                assertEquals(ContentPage.NOTIFICATION, core.contentPage, "$order")
            } else {
                assertEquals(ContentPage.AGENT, core.contentPage, "$order")
                assertEquals(when (first) {
                    DisplayPriority.APPROVAL -> "approval"
                    DisplayPriority.QUESTION -> "question"
                    else -> "original"
                }, core.agentState?.sessionId, "$order")
            }
        }
    }

    @Test fun `播报排序更高时通知延后到播报结束`() {
        val core = core()
        core.onEvent(DashboardEvent.DisplayPriorityChanged(listOf(DisplayPriority.VOICE)))
        core.onEvent(DashboardEvent.VoiceBroadcastStarted("original"))
        core.onEvent(post())
        assertEquals(ContentPage.AGENT, core.contentPage)
        core.onEvent(DashboardEvent.VoiceBroadcastFinished)
        assertEquals(ContentPage.NOTIFICATION, core.contentPage)
    }
}
