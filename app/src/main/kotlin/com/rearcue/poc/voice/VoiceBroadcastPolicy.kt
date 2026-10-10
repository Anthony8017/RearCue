package com.rearcue.poc.voice

import com.rearcue.poc.agent.AgentSessionState

enum class VoiceBroadcastKind {
    DONE,
    ERROR,
    NEEDS_INPUT,
}

data class VoiceBroadcastDelivery(val kind: VoiceBroadcastKind, val text: String,
    val sessionId: String, val eventId: String, val requestId: String? = null)

/** 同一到达事件在四档声音设置下的输出；开关变化不重放旧事件。 */
data class AgentAudioPlan(val cueNow: Boolean, val readAloud: Boolean, val cueBeforeSpeech: Boolean)

fun VoiceBroadcastSettings.planForArrival(): AgentAudioPlan = AgentAudioPlan(
    cueNow = cueEnabled && !enabled,
    readAloud = enabled,
    cueBeforeSpeech = cueEnabled && enabled,
)

/** 已排队项目只在入队时和当前都允许提示音的同一开关周期内响。 */
fun VoiceBroadcastItem.shouldPlayCue(settings: VoiceBroadcastSettings, currentCueEpoch: Long): Boolean =
    cueBeforeSpeech && settings.cueEnabled && cueEpoch == currentCueEpoch

/** Explicit completion and pending requests; status changes cannot finish a task. */
class VoiceBroadcastTracker {
    private val seen = mutableMapOf<String, MutableSet<String>>()

    fun onSessionState(state: AgentSessionState): List<VoiceBroadcastDelivery> {
        if (!state.voiceEligible) return emptyList()
        val recorded = seen.getOrPut(state.sessionId) { mutableSetOf() }
        val deliveries = mutableListOf<VoiceBroadcastDelivery>()
        state.pendingRequests.forEach { request ->
            if (recorded.add("input:${request.id}")) deliveries += VoiceBroadcastDelivery(
                VoiceBroadcastKind.NEEDS_INPUT, request.text, state.sessionId, "input:${request.id}", request.id)
        }
        state.voiceEvent?.let { event ->
            val kind = when (event.kind) { "done" -> VoiceBroadcastKind.DONE; "error" -> VoiceBroadcastKind.ERROR; else -> null }
            if (!event.replay && kind != null && recorded.add("result:${event.id}")) {
                deliveries += VoiceBroadcastDelivery(kind, event.text, state.sessionId, "result:${event.id}")
            }
        }
        return deliveries
    }

    fun retain(sessionIds: Set<String>) {
        seen.keys.retainAll(sessionIds)
    }
}
