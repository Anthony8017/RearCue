package com.rearcue.poc.voice

import com.rearcue.poc.agent.AgentStatus

enum class VoiceBroadcastKind {
    DONE,
    ERROR,
}

enum class VoiceBroadcastSignal {
    NONE,
    INTERRUPT_VISUAL,
    ENQUEUE_DONE,
    ENQUEUE_ERROR,
}

/**
 * Voice Broadcast 触发判定（spec 0022）：成功干完与最终报错进队列；进入等待确认只暂停视觉跟随，语音继续播完。
 * 与 Agent Alert 的 60 秒冷却完全分开——多条 Task Completion 必须逐条排队播报。
 */
object VoiceBroadcastPolicy {
    fun signalFor(previous: AgentStatus?, next: AgentStatus): VoiceBroadcastSignal = when {
        next == AgentStatus.WAITING_FOR_APPROVAL && previous != AgentStatus.WAITING_FOR_APPROVAL ->
            VoiceBroadcastSignal.INTERRUPT_VISUAL
        previous == AgentStatus.WORKING && next == AgentStatus.IDLE ->
            VoiceBroadcastSignal.ENQUEUE_DONE
        next == AgentStatus.ERROR && previous != AgentStatus.ERROR ->
            VoiceBroadcastSignal.ENQUEUE_ERROR
        else -> VoiceBroadcastSignal.NONE
    }
}

/** 无冷却的 Task Completion 序列簿记：桥事件、debug 注入共用。 */
class VoiceBroadcastTracker {
    private val lastStatus = mutableMapOf<String, AgentStatus>()

    fun onSessionState(sessionId: String, status: AgentStatus): VoiceBroadcastSignal {
        val previous = lastStatus.put(sessionId, status)
        return VoiceBroadcastPolicy.signalFor(previous, status)
    }

    fun forget(sessionId: String) {
        lastStatus.remove(sessionId)
    }

    fun retain(sessionIds: Set<String>) {
        lastStatus.keys.retainAll(sessionIds)
    }
}
