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
    fun `首次见到的待答在开关关闭时也记账且重新开启不补响`() {
        val tracker = VoiceBroadcastTracker()
        val state = AgentSessionState("s", pendingRequests = listOf(AgentInputRequest("live", "question", "待答")))
        val first = tracker.onSessionState(state)
        assertEquals(VoiceBroadcastKind.NEEDS_INPUT, first.single().kind)
        assertEquals(AgentAudioPlan(false, false, false), VoiceBroadcastSettings().planForArrival())
        assertEquals(emptyList(), tracker.onSessionState(state))
    }

    @Test
    fun `四种开关组合各自决定响铃和朗读`() {
        assertEquals(AgentAudioPlan(false, false, false), VoiceBroadcastSettings().planForArrival())
        assertEquals(AgentAudioPlan(true, false, false), VoiceBroadcastSettings(cueEnabled = true).planForArrival())
        assertEquals(AgentAudioPlan(false, true, false), VoiceBroadcastSettings(enabled = true).planForArrival())
        assertEquals(AgentAudioPlan(false, true, true), VoiceBroadcastSettings(enabled = true, cueEnabled = true).planForArrival())
    }

    @Test
    fun `开关关闭再开启不会为已排队语音补响提示音`() {
        val queue = VoiceBroadcastQueue()
        val settings = VoiceBroadcastSettings(enabled = true, cueEnabled = true)
        val item = queue.enqueue(VoiceBroadcastKind.DONE, "结果", cueBeforeSpeech = true, cueEpoch = 1L)
        assertEquals(true, item.shouldPlayCue(settings, 1L))
        assertEquals(false, item.shouldPlayCue(settings.copy(cueEnabled = false), 2L))
        assertEquals(false, item.shouldPlayCue(settings, 3L))
        assertEquals(false, queue.enqueue(VoiceBroadcastKind.DONE, "仅朗读").shouldPlayCue(settings, 1L))
    }

    @Test
    fun `升级沿用旧总开关但新键可独立覆盖`() {
        assertEquals(VoiceBroadcastSettings(), VoiceBroadcastSettings.fromStoredToggles(null, null))
        assertEquals(VoiceBroadcastSettings(enabled = true, cueEnabled = true),
            VoiceBroadcastSettings.fromStoredToggles(true, null))
        assertEquals(VoiceBroadcastSettings(), VoiceBroadcastSettings.fromStoredToggles(false, null))
        assertEquals(VoiceBroadcastSettings(enabled = true, cueEnabled = false),
            VoiceBroadcastSettings.fromStoredToggles(true, false))
        assertEquals(VoiceBroadcastSettings(enabled = false, cueEnabled = true),
            VoiceBroadcastSettings.fromStoredToggles(false, true))
    }

    @Test
    fun `完成和失败重复送达与历史回放不补响`() {
        val tracker = VoiceBroadcastTracker()
        val done = AgentSessionState("s", voiceEvent = AgentVoiceEvent("turn", "done", "结果", 1L))
        assertEquals(VoiceBroadcastKind.DONE, tracker.onSessionState(done).single().kind)
        assertEquals(emptyList(), tracker.onSessionState(done))
        assertEquals(emptyList(), tracker.onSessionState(done.copy(voiceEvent = done.voiceEvent?.copy(replay = true))))
        val error = done.copy(voiceEvent = AgentVoiceEvent("failure", "error", "原因", 2L))
        assertEquals(VoiceBroadcastKind.ERROR, tracker.onSessionState(error).single().kind)
        assertEquals(emptyList(), tracker.onSessionState(error))
    }

    @Test
    fun `语音设置默认关并固定小爱一倍速`() {
        val settings = VoiceBroadcastSettings()
        assertEquals(false, settings.enabled)
        assertEquals(false, settings.cueEnabled)
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
