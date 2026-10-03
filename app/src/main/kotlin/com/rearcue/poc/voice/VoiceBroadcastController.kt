package com.rearcue.poc.voice

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.rearcue.poc.LOG_TAG
import com.rearcue.poc.notify.RearNotificationListener
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SPEECH_TIMEOUT_MS = 60_000L
private const val CHIME_TIMEOUT_MS = 2_000L

/**
 * Voice Broadcast 运行时：队列、提示音、双语音引擎、耳机媒体键与音频焦点。
 * 纯规则拆在 VoiceBroadcastPolicy / Text / Queue；本类只负责系统音频行为。
 */
class VoiceBroadcastController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onRuntimeState: (VoiceBroadcastRuntimeState) -> Unit = {},
) : Closeable {
    private val queue = VoiceBroadcastQueue()
    @Volatile
    private var settings = VoiceBroadcastSettings()
    @Volatile
    private var runtime = VoiceBroadcastRuntimeState()
    private val systemSpeech = SystemSpeechSynthesizer(context) { voices ->
        runtime = runtime.copy(systemVoices = voices)
        onRuntimeState(runtime)
    }
    private val kokoroSpeech = KokoroSpeechSynthesizer(context)
    private var workerJob: Job? = null
    private var downloadJob: Job? = null
    private val generation = AtomicLong()
    private val stopped = AtomicBoolean(false)
    private val skipRequested = AtomicBoolean(false)
    private var chime: Ringtone? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var pausedForInterruption = false
    private var mediaSession: MediaSession? = null
    private val pausedMedia = mutableListOf<MediaController>()

    init {
        mediaSession = MediaSession(context, "RearCueVoiceBroadcast").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Unit
                override fun onPause() = stopAndClear()
                override fun onStop() = stopAndClear()
                override fun onSkipToNext() = skipCurrent()
            })
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
        }
    }

    fun updateSettings(next: VoiceBroadcastSettings) {
        settings = next
    }

    fun setOfflineStatus(status: OfflineVoiceStatus, note: String? = null) {
        runtime = runtime.copy(offlineStatus = status, note = note)
        onRuntimeState(runtime)
    }

    fun enqueue(kind: VoiceBroadcastKind, body: String?, errorReason: String? = null) {
        if (!settings.enabled) return
        val text = VoiceBroadcastText.spokenText(kind, body, errorReason)
        val item = queue.enqueue(kind, text)
        Log.i(LOG_TAG, "voice enqueue kind=${kind.name.lowercase()} id=${item.id} chars=${text.length}")
        ensureWorker()
    }

    /** Waiting-for-Approval 与播放/暂停键的“立即闭嘴”语义。 */
    fun stopAndClear() {
        generation.incrementAndGet()
        stopped.set(true)
        skipRequested.set(false)
        stopSpeech()
        stopChime()
        queue.clear()
        workerJob?.cancel()
        workerJob = null
        endPlayback()
        pausedForInterruption = false
    }

    /** 下一首：只跳过当前整条，后续队列保留。 */
    fun skipCurrent() {
        skipRequested.set(true)
        stopSpeech()
        if (queue.skipCurrent()) {
            ensureWorker()
        }
    }

    fun refreshOfflineStatus() {
        setOfflineStatus(
            if (VoiceModelDownloader.isReady(context)) {
                OfflineVoiceStatus.READY
            } else {
                OfflineVoiceStatus.NOT_READY
            },
        )
    }

    fun prepareOfflineModel() {
        if (downloadJob?.isActive == true) return
        if (VoiceModelDownloader.isReady(context)) {
            setOfflineStatus(OfflineVoiceStatus.READY)
            return
        }
        setOfflineStatus(OfflineVoiceStatus.DOWNLOADING)
        downloadJob = scope.launch {
            try {
                VoiceModelDownloader.download(context)
                setOfflineStatus(OfflineVoiceStatus.READY)
            } catch (_: Throwable) {
                setOfflineStatus(OfflineVoiceStatus.FAILED, "离线语音暂不可用，本次使用系统语音")
            }
        }
    }

    private fun ensureWorker() {
        if (workerJob?.isActive == true) return
        stopped.set(false)
        val token = generation.get()
        workerJob = scope.launch {
            try {
                while (isActive && !stopped.get() && generation.get() == token) {
                    val item = queue.startNext() ?: break
                    skipRequested.set(false)
                    beginPlayback()
                    if (!playChime(token)) {
                        queue.completeCurrent()
                        continue
                    }
                    val sentences = VoiceBroadcastText.sentences(item.text)
                    for (sentence in sentences) {
                        while (pausedForInterruption && isActive && generation.get() == token) {
                            delay(250)
                        }
                        if (stopped.get() || generation.get() != token || skipRequested.get()) break
                        val ok = speak(sentence)
                        if (!ok && !skipRequested.get()) {
                            publishNote("暂无可播报的语音")
                            break
                        }
                    }
                    queue.completeCurrent()
                }
            } finally {
                if (generation.get() == token && queue.pendingCount == 0) {
                    endPlayback()
                }
            }
        }
    }

    private suspend fun speak(sentence: String): Boolean {
        val snapshot = settings
        return when (snapshot.engine) {
            VoiceEngine.OFFLINE -> {
                if (speakWithEngine(kokoroSpeech, "kokoro", sentence, snapshot.kokoroVoiceId, snapshot.clampedSpeed)) {
                    true
                } else {
                    if (VoiceModelDownloader.isReady(context)) {
                        publishNote("离线语音暂不可用，本次使用系统语音")
                    }
                    speakWithEngine(systemSpeech, "system", sentence, snapshot.systemVoiceId, snapshot.clampedSpeed)
                }
            }
            VoiceEngine.SYSTEM -> {
                if (speakWithEngine(systemSpeech, "system", sentence, snapshot.systemVoiceId, snapshot.clampedSpeed)) {
                    true
                } else {
                    if (VoiceModelDownloader.isReady(context)) {
                        publishNote("系统语音暂不可用，本次使用离线语音")
                        speakWithEngine(kokoroSpeech, "kokoro", sentence, snapshot.kokoroVoiceId, snapshot.clampedSpeed)
                    } else {
                        false
                    }
                }
            }
        }
    }

    private suspend fun speakWithEngine(
        engine: SpeechSynthesizer,
        label: String,
        sentence: String,
        voiceId: String,
        speed: Float,
    ): Boolean {
        Log.i(LOG_TAG, "voice speak engine=$label chars=${sentence.length}")
        val ok = VoiceSpeechDeadline.speak(
            engine = engine,
            text = sentence,
            voiceId = voiceId,
            speed = speed,
            timeoutMs = SPEECH_TIMEOUT_MS,
        )
        Log.i(LOG_TAG, "voice speak engine=$label ok=$ok")
        return ok
    }

    private suspend fun playChime(token: Long): Boolean {
        if (stopped.get() || generation.get() != token) return false
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return true
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return true
        chime = ringtone
        runCatching { ringtone.play() }
        val played = withTimeoutOrNull(CHIME_TIMEOUT_MS) {
            while (ringtone.isPlaying && !stopped.get() && generation.get() == token) {
                delay(50)
            }
            !stopped.get() && generation.get() == token
        } ?: false
        stopChime()
        return played
    }

    private fun stopChime() {
        chime?.let { ringtone -> runCatching { ringtone.stop() } }
        chime = null
    }

    private fun stopSpeech() {
        systemSpeech.stop()
        kokoroSpeech.stop()
    }

    private fun beginPlayback() {
        if (audioFocusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    -> pausedForInterruption = true
                    AudioManager.AUDIOFOCUS_GAIN -> pausedForInterruption = false
                    else -> Unit
                }
            }
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
        pauseOtherMedia()
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT,
                )
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build(),
        )
        mediaSession?.setActive(true)
    }

    private fun endPlayback() {
        stopSpeech()
        stopChime()
        resumeOtherMedia()
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_STOPPED, 0L, 1f)
                .build(),
        )
        mediaSession?.setActive(false)
    }

    private fun pauseOtherMedia() {
        pausedMedia.clear()
        runCatching {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(
                ComponentName(context, RearNotificationListener::class.java),
            )
            sessions.asSequence()
                .filter { it.packageName != context.packageName }
                .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .forEach { controller ->
                    runCatching { controller.transportControls.pause() }
                    pausedMedia += controller
                }
        }
    }

    private fun resumeOtherMedia() {
        pausedMedia.forEach { controller ->
            runCatching { controller.transportControls.play() }
        }
        pausedMedia.clear()
    }

    private fun publishNote(note: String) {
        runtime = runtime.copy(note = note)
        onRuntimeState(runtime)
    }

    override fun close() {
        stopAndClear()
        systemSpeech.release()
        kokoroSpeech.release()
        mediaSession?.release()
        mediaSession = null
    }
}
