package com.rearcue.poc.voice

import com.rearcue.poc.agent.AgentSessionState

enum class VoiceBroadcastKind {
    DONE,
    ERROR,
    NEEDS_INPUT,
}

data class VoiceBroadcastDelivery(val kind: VoiceBroadcastKind, val text: String,
    val sessionId: String, val eventId: String, val requestId: String? = null)

/** Explicit completion and pending requests; status changes cannot finish a task. */
class VoiceBroadcastTracker {
    private val seen = mutableMapOf<String, MutableSet<String>>()

    fun onSessionState(state: AgentSessionState, inputsEnabled: Boolean = true): List<VoiceBroadcastDelivery> {
        if (!state.voiceEligible) return emptyList()
        val recorded = seen.getOrPut(state.sessionId) { mutableSetOf() }
        val deliveries = mutableListOf<VoiceBroadcastDelivery>()
        if (inputsEnabled) state.pendingRequests.forEach { request ->
            if (recorded.add("input:${request.id}")) deliveries += VoiceBroadcastDelivery(
                VoiceBroadcastKind.NEEDS_INPUT, request.text, state.sessionId, "input:${request.id}", request.id)
        }
        state.voiceEvent?.let { event ->
            if (!event.replay && recorded.add("result:${event.id}")) {
                val kind = when (event.kind) { "done" -> VoiceBroadcastKind.DONE; "error" -> VoiceBroadcastKind.ERROR; else -> null }
                if (kind != null) deliveries += VoiceBroadcastDelivery(kind, event.text, state.sessionId, "result:${event.id}")
            }
        }
        return deliveries
    }

    fun retain(sessionIds: Set<String>) {
        seen.keys.retainAll(sessionIds)
    }
}
