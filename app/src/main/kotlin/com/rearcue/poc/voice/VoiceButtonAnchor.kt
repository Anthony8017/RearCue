package com.rearcue.poc.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.rearcue.poc.LOG_TAG
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 小爱语音在独立进程出声，系统把耳机键交给最后在本进程出声且拥有媒体会话的应用。
 * 播报期间持续输出听不见的静音，耳机播放键才会落到播报，而不是把音乐一起打开。
 */
internal class VoiceButtonAnchor(
    private val scope: CoroutineScope,
) {
    private var track: AudioTrack? = null
    private var writer: Job? = null
    private var releaseTrack: (() -> Unit)? = null

    fun start() {
        if (writer?.isActive == true && track?.playState == AudioTrack.PLAYSTATE_PLAYING) return
        stop()
        val sampleRate = 48_000
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            Log.w(LOG_TAG, "voice button anchor unavailable buffer=$minBytes")
            return
        }
        val active = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (active.state != AudioTrack.STATE_INITIALIZED) {
            active.release()
            Log.w(LOG_TAG, "voice button anchor init failed")
            return
        }
        val once = AtomicBoolean(false)
        val release = {
            if (once.compareAndSet(false, true)) {
                if (track === active) track = null
                runCatching { active.pause() }
                runCatching { active.flush() }
                runCatching { active.release() }
            }
        }
        track = active
        releaseTrack = release
        runCatching { active.play() }.onFailure {
            Log.w(LOG_TAG, "voice button anchor play failed", it)
            release()
            releaseTrack = null
            return
        }
        if (active.playState != AudioTrack.PLAYSTATE_PLAYING) {
            Log.w(LOG_TAG, "voice button anchor not playing")
            release()
            releaseTrack = null
            return
        }
        val silence = ByteArray(minBytes)
        writer = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val wrote = runCatching { active.write(silence, 0, silence.size) }.getOrDefault(-1)
                    if (wrote < 0) break
                }
            } finally {
                release()
                releaseTrack = null
            }
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        val job = writer
        val release = releaseTrack
        writer = null
        track = null
        releaseTrack = null
        release?.invoke()
        job?.cancel()
    }
}
