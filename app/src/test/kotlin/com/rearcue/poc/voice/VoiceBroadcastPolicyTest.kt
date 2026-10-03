package com.rearcue.poc.voice

import com.rearcue.poc.agent.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceBroadcastPolicyTest {

    @Test
    fun `任务成功和最终报错进入队列`() {
        assertEquals(
            VoiceBroadcastSignal.ENQUEUE_DONE,
            VoiceBroadcastPolicy.signalFor(AgentStatus.WORKING, AgentStatus.IDLE),
        )
        assertEquals(
            VoiceBroadcastSignal.ENQUEUE_ERROR,
            VoiceBroadcastPolicy.signalFor(AgentStatus.WORKING, AgentStatus.ERROR),
        )
        assertEquals(
            VoiceBroadcastSignal.ENQUEUE_ERROR,
            VoiceBroadcastPolicy.signalFor(AgentStatus.IDLE, AgentStatus.ERROR),
        )
        assertEquals(
            VoiceBroadcastSignal.ENQUEUE_ERROR,
            VoiceBroadcastPolicy.signalFor(null, AgentStatus.ERROR),
        )
    }

    @Test
    fun `进入等待确认清空队列`() {
        assertEquals(
            VoiceBroadcastSignal.CLEAR,
            VoiceBroadcastPolicy.signalFor(AgentStatus.WORKING, AgentStatus.WAITING_FOR_APPROVAL),
        )
        assertEquals(
            VoiceBroadcastSignal.CLEAR,
            VoiceBroadcastPolicy.signalFor(AgentStatus.IDLE, AgentStatus.WAITING_FOR_APPROVAL),
        )
    }

    @Test
    fun `实时输出空闲停留和等待停留不触发`() {
        assertEquals(VoiceBroadcastSignal.NONE, VoiceBroadcastPolicy.signalFor(null, AgentStatus.WORKING))
        assertEquals(VoiceBroadcastSignal.NONE, VoiceBroadcastPolicy.signalFor(AgentStatus.WORKING, AgentStatus.WORKING))
        assertEquals(VoiceBroadcastSignal.NONE, VoiceBroadcastPolicy.signalFor(null, AgentStatus.IDLE))
        assertEquals(VoiceBroadcastSignal.NONE, VoiceBroadcastPolicy.signalFor(AgentStatus.IDLE, AgentStatus.IDLE))
        assertEquals(
            VoiceBroadcastSignal.NONE,
            VoiceBroadcastPolicy.signalFor(AgentStatus.WAITING_FOR_APPROVAL, AgentStatus.WAITING_FOR_APPROVAL),
        )
    }

    @Test
    fun `播报判定不使用提醒冷却但处理完等待不播报`() {
        val tracker = VoiceBroadcastTracker()
        assertEquals(VoiceBroadcastSignal.NONE, tracker.onSessionState("s", AgentStatus.WORKING))
        assertEquals(VoiceBroadcastSignal.ENQUEUE_DONE, tracker.onSessionState("s", AgentStatus.IDLE))
        assertEquals(VoiceBroadcastSignal.NONE, tracker.onSessionState("s", AgentStatus.WORKING))
        assertEquals(VoiceBroadcastSignal.ENQUEUE_DONE, tracker.onSessionState("s", AgentStatus.IDLE))
        assertEquals(VoiceBroadcastSignal.CLEAR, tracker.onSessionState("s", AgentStatus.WAITING_FOR_APPROVAL))
        assertEquals(VoiceBroadcastSignal.NONE, tracker.onSessionState("s", AgentStatus.IDLE))
    }
    @Test
    fun `语音设置默认关离线推荐一倍速`() {
        val settings = VoiceBroadcastSettings()
        assertEquals(false, settings.enabled)
        assertEquals(VoiceEngine.OFFLINE, settings.engine)
        assertEquals(1.0f, settings.speed)
        assertEquals(VoiceCatalog.KOKORO_DEFAULT.id, settings.kokoroVoiceId)
        assertEquals(VoiceCatalog.SYSTEM_DEFAULT.id, settings.systemVoiceId)
    }
    @Test
    fun `音调默认一倍且与语速独立夹紧`() {
        val settings = VoiceBroadcastSettings(pitch = 9f)

        assertEquals(1.0f, VoiceBroadcastSettings().pitch)
        assertEquals(2.0f, settings.clampedPitch)
        assertEquals(0.5f, VoiceBroadcastSettings(pitch = 0f).clampedPitch)
    }
}
