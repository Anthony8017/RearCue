package com.rearcue.poc.voice

import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentVoiceEvent
import com.rearcue.poc.agent.AgentInputRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceBroadcastPolicyTest {

    @Test
    fun `状态变化停止和历史回放不播报`() {
        val tracker = VoiceBroadcastTracker()
        assertEquals(emptyList(), tracker.onSessionState(AgentSessionState("s", status = AgentStatus.WORKING)))
        assertEquals(emptyList(), tracker.onSessionState(AgentSessionState("s", status = AgentStatus.IDLE)))
        assertEquals(emptyList(), tracker.onSessionState(AgentSessionState("s", status = AgentStatus.ERROR)))
        val historical = AgentVoiceEvent("old", "done", "旧结果", 1L, replay = true)
        assertEquals(emptyList(), tracker.onSessionState(AgentSessionState("s", voiceEvent = historical)))
        assertEquals(emptyList(), tracker.onSessionState(AgentSessionState("s", voiceEligible = false,
            pendingRequests = listOf(AgentInputRequest("old", "question", "已解决的旧问题")))))
    }

    @Test
    fun `真正完成只播一次并且不查询上一轮回答`() {
        val tracker = VoiceBroadcastTracker()
        val state = AgentSessionState("s", latestReply = "上一轮", voiceEvent = AgentVoiceEvent("turn", "done", "本轮", 1L))
        assertEquals("本轮", tracker.onSessionState(state).single().text)
        assertEquals(emptyList(), tracker.onSessionState(state))
    }

    @Test
    fun `待答期间继续工作不重复叫人且不妨碍其他结果`() {
        val tracker = VoiceBroadcastTracker()
        val state = AgentSessionState("s", status = AgentStatus.WORKING,
            pendingRequests = listOf(AgentInputRequest("ask", "question", "问题和选项")))
        assertEquals(VoiceBroadcastKind.NEEDS_INPUT, tracker.onSessionState(state).single().kind)
        assertEquals(emptyList(), tracker.onSessionState(state.copy(status = AgentStatus.IDLE)))
        assertEquals(VoiceBroadcastKind.DONE, tracker.onSessionState(AgentSessionState("other",
            voiceEvent = AgentVoiceEvent("done", "done", "另一结果", 2L))).single().kind)
    }

    @Test
    fun `开关关闭不消耗尚未播过的当前问题身份`() {
        val tracker = VoiceBroadcastTracker()
        val state = AgentSessionState("s", pendingRequests = listOf(AgentInputRequest("live", "question", "待答")))
        assertEquals(emptyList(), tracker.onSessionState(state, inputsEnabled = false))
        assertEquals(VoiceBroadcastKind.NEEDS_INPUT, tracker.onSessionState(state, inputsEnabled = true).single().kind)
    }

    @Test
    fun `语音设置默认关并固定小爱一倍速`() {
        val settings = VoiceBroadcastSettings()
        assertEquals(false, settings.enabled)
        assertEquals(VoiceEngine.SYSTEM, settings.engine)
        assertEquals(1.0f, settings.speed)
        assertEquals(VoiceCatalog.SYSTEM_DEFAULT.id, settings.systemVoiceId)
        assertEquals(VoiceEngine.SYSTEM, VoiceEngine.fromName("offline"))
    }
    @Test
    fun `音调默认一倍且与语速独立夹紧`() {
        val settings = VoiceBroadcastSettings(pitch = 9f)

        assertEquals(1.0f, VoiceBroadcastSettings().pitch)
        assertEquals(2.0f, settings.clampedPitch)
        assertEquals(0.5f, VoiceBroadcastSettings(pitch = 0f).clampedPitch)
    }
}
