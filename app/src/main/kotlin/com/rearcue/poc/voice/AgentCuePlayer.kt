package com.rearcue.poc.voice

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import java.io.Closeable
import java.util.ArrayDeque

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

internal fun canPlayAgentCue(context: Context): Boolean {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
    val notifications = context.getSystemService(NotificationManager::class.java)
    val filter = notifications?.currentInterruptionFilter
    return filter == null || filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
        filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
}

private data class QueuedCue(val sessionId: String, val requestId: String?)

/** 仅提示音档：逐件播放，不占用语音队列、媒体键或背屏跟随状态。 */
class AgentCuePlayer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : Closeable {
    private val pending = ArrayDeque<QueuedCue>()
    private var current: QueuedCue? = null
    private var worker: Job? = null
    private var ringtone: Ringtone? = null
    private var enabled = false

    fun setEnabled(next: Boolean) {
        enabled = next
        if (!next) stopAndClear()
    }

    fun enqueue(sessionId: String, requestId: String?) {
        if (!enabled) return
        pending.addLast(QueuedCue(sessionId, requestId))
        ensureWorker()
    }

    fun retainRequests(sessionId: String, requestIds: Set<String>) {
        pending.removeIf { it.sessionId == sessionId && it.requestId != null && it.requestId !in requestIds }
        if (current?.let { it.sessionId == sessionId && it.requestId != null && it.requestId !in requestIds } == true) {
            ringtone?.let { runCatching { it.stop() } }
        }
    }

    fun retainSessions(sessionIds: Set<String>) {
        pending.removeIf { it.sessionId !in sessionIds }
        if (current?.sessionId?.let { it !in sessionIds } == true) {
            ringtone?.let { runCatching { it.stop() } }
        }
    }

    fun stopAndClear() {
        worker?.cancel()
        worker = null
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
        current = null
        pending.clear()
    }

    private fun ensureWorker() {
        if (worker?.isActive == true || pending.isEmpty()) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive && enabled && pending.isNotEmpty()) {
                    current = pending.removeFirst()
                    try {
                        playOne()
                    } finally {
                        current = null
                    }
                }
            } finally {
                if (worker === coroutineContext[Job]) {
                    worker = null
                    if (pending.isNotEmpty() && enabled) ensureWorker()
                }
            }
        }
        worker = next
        next.start()
    }

    private suspend fun playOne() {
        // 不把提示音当成媒体音频申请焦点，也不突破手机的静音和勿扰。
        if (!canPlayAgentCue(context)) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        val sound = RingtoneManager.getRingtone(context, uri) ?: return
        ringtone = sound
        try {
            if (runCatching { sound.play() }.isFailure) return
            withTimeoutOrNull(2_000L) {
                while (sound.isPlaying) delay(50)
            }
        } finally {
            runCatching { sound.stop() }
            if (ringtone === sound) ringtone = null
        }
    }

    override fun close() = stopAndClear()
}
