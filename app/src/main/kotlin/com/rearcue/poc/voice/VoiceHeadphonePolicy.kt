package com.rearcue.poc.voice

internal data class ExternalPlayback(
    val key: String,
    val playing: Boolean,
)

/** 播报仍占着声音时，后开始的其他媒体要被压住，避免和语音叠在一起。 */
internal fun externalPlaybackKeysToPause(
    holdingBroadcast: Boolean,
    sessions: List<ExternalPlayback>,
    alreadyHeld: Set<String>,
): Set<String> {
    if (!holdingBroadcast) return emptySet()
    return sessions
        .filter { it.playing && it.key !in alreadyHeld }
        .map { it.key }
        .toSet()
}

/**
 * 单击暂停后其他媒体会恢复出声，并夺走耳机键。
 * 其他媒体真正出声后再占回一次，再次单击才能继续播报。
 */
internal fun shouldReclaimHeadphoneButton(
    userPaused: Boolean,
    broadcastStopped: Boolean,
    otherMediaPlaying: Boolean,
    alreadyReclaimed: Boolean,
): Boolean = userPaused && !broadcastStopped && otherMediaPlaying && !alreadyReclaimed
