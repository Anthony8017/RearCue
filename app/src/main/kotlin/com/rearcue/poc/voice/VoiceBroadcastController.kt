package com.rearcue.poc.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.rearcue.poc.LOG_TAG
import com.rearcue.poc.core.VoiceBroadcastFollow
import com.rearcue.poc.notify.RearNotificationListener
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

private const val SPEECH_TIMEOUT_MS = 60_000L
private const val CHIME_TIMEOUT_MS = 2_000L

/**
 * Voice Broadcast 运行时：队列、提示音、小爱语音引擎、耳机媒体键与音频焦点。
 * 纯规则拆在 VoiceBroadcastPolicy / Text / Queue；本类只负责系统音频行为。
 */
class VoiceBroadcastController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
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
    private var workerJob: Job? = null
    private val generation = AtomicLong()
    private val stopped = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val skipRequested = AtomicBoolean(false)
    @Volatile
    private var resumeSentenceIndex = 0
    @Volatile
    private var chimeCompletedForCurrent = false
    private var chime: Ringtone? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var pausedForInterruption = false
    private var mediaSession: MediaSession? = null
    private val pausedMedia = mutableListOf<MediaController>()
    private val mediaButtonGesture = VoiceMediaButtonGestureResolver {
        SystemClock.elapsedRealtime()
    }

    init {
        mediaSession = MediaSession(context, "RearCueVoiceBroadcast").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumeBroadcast()
                override fun onPause() = pauseBroadcast()
                override fun onStop() = stopAndClear()
                override fun onSkipToNext() = skipCurrent()

                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val event = mediaButtonEvent.getParcelableExtra(
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent::class.java,
                    )
                    if (event == null) return super.onMediaButtonEvent(mediaButtonEvent)
                    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
                    return when (mediaButtonGesture.fromKeyCode(event.keyCode)) {
                        VoiceMediaButtonAction.TOGGLE_PAUSE_RESUME -> {
                            toggleBroadcast()
                            true
                        }
                        VoiceMediaButtonAction.STOP_AND_CLEAR -> {
                            stopAndClear()
                            true
                        }
                        VoiceMediaButtonAction.SKIP_CURRENT -> {
                            skipCurrent()
                            true
                        }
                        VoiceMediaButtonAction.IGNORE ->
                            super.onMediaButtonEvent(mediaButtonEvent)
                    }
                }
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

    fun enqueue(
        kind: VoiceBroadcastKind,
        body: String?,
        errorReason: String? = null,
        source: VoiceBroadcastSource = VoiceBroadcastSource(),
        sessionId: String? = source.sessionId,
        eventId: String? = null,
        requestId: String? = null,
    ) {
        if (!settings.enabled) return
        val text = VoiceBroadcastText.spokenText(kind, body, errorReason)
        val sentences = if (kind == VoiceBroadcastKind.DONE && !body.isNullOrBlank()) {
            VoiceBroadcastText.spokenSentences(body)
        } else {
            VoiceBroadcastText.spokenSentences(text)
        }
        val item = queue.enqueue(kind, text, sessionId, eventId, requestId, source, sentences)
        Log.i(LOG_TAG, "voice enqueue kind=${kind.name.lowercase()} id=${item.id} chars=${text.length}")
        ensureWorker()
    }

    fun retainRequests(sessionId: String, requestIds: Set<String>) {
        if (queue.retainRequests(sessionId, requestIds)) skipCurrent()
    }

    fun retainSessions(sessionIds: Set<String>) {
        if (queue.retainSessions(sessionIds)) skipCurrent()
    }

    /** 媒体键双击的“立即闭嘴并丢弃整批”语义。 */
    fun stopAndClear() {
        generation.incrementAndGet()
        stopped.set(true)
        paused.set(false)
        skipRequested.set(false)
        resumeSentenceIndex = 0
        chimeCompletedForCurrent = false
        stopSpeech()
        stopChime()
        queue.clear()
        publishFollow(null)
        workerJob?.cancel()
        workerJob = null
        endPlayback()
        pausedForInterruption = false
    }

    /** 下一首：只跳过当前整条，后续队列保留。 */
    fun skipCurrent() {
        workerJob?.cancel()
        workerJob = null
        skipRequested.set(true)
        stopSpeech()
        stopChime()
        val skipped = queue.skipCurrent()
        resumeSentenceIndex = 0
        chimeCompletedForCurrent = false
        if (skipped) publishFollow(null)
        if (skipped && !paused.get()) ensureWorker()
    }

    private fun toggleBroadcast() {
        if (paused.get()) {
            resumeBroadcast()
        } else if (queue.hasContent) {
            pauseBroadcast()
        }
    }

    /** 单击暂停：保留当前句和队列，让其他媒体恢复。 */
    private fun pauseBroadcast() {
        if (!queue.hasContent || !paused.compareAndSet(false, true)) return
        workerJob?.cancel()
        workerJob = null
        skipRequested.set(false)
        stopSpeech()
        stopChime()
        chimeCompletedForCurrent = true
        pausedForInterruption = false
        releasePlayback(paused = true)
    }

    /** 再次单击：从当前句开头继续；提示音不重播。 */
    private fun resumeBroadcast() {
        if (!paused.compareAndSet(true, false)) return
        stopped.set(false)
        skipRequested.set(false)
        ensureWorker()
    }

    private fun ensureWorker() {
        if (paused.get() || workerJob?.isActive == true) return
        stopped.set(false)
        val token = generation.get()
        val nextWorker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive && !stopped.get() && generation.get() == token) {
                    val item = queue.startNext() ?: break
                    skipRequested.set(false)
                    val sentences = item.sentences
                    val startIndex = resumeSentenceIndex.coerceIn(0, sentences.size)
                    beginPlayback()
                    if (!chimeCompletedForCurrent && !playChime(token)) {
                        if (paused.get()) break
                        queue.completeCurrent()
                        resumeSentenceIndex = 0
                        chimeCompletedForCurrent = false
                        publishFollow(null)
                        continue
                    }
                    chimeCompletedForCurrent = true
                    for (sentenceIndex in startIndex until sentences.size) {
                        while (pausedForInterruption && isActive && generation.get() == token) {
                            delay(250)
                        }
                        if (paused.get() || stopped.get() || generation.get() != token || skipRequested.get()) break
                        resumeSentenceIndex = sentenceIndex
                        publishFollow(item, sentenceIndex)
                        val ok = speak(sentences[sentenceIndex].text)
                        if (paused.get()) break
                        if (!ok && !skipRequested.get()) {
                            publishNote("暂无可播报的语音")
                            break
                        }
                        if (!skipRequested.get()) resumeSentenceIndex = sentenceIndex + 1
                    }
                    if (paused.get()) break
                    queue.completeCurrent()
                    resumeSentenceIndex = 0
                    chimeCompletedForCurrent = false
                    publishFollow(null)
                }
            } finally {
                if (workerJob === coroutineContext[Job]) {
                    workerJob = null
                    if (!paused.get() && generation.get() == token) {
                        if (queue.hasContent) ensureWorker() else endPlayback()
                    }
                }
            }
        }
        workerJob = nextWorker
        nextWorker.start()
    }

    private fun publishFollow(item: VoiceBroadcastItem?, sentenceIndex: Int = 0) {
        val follow = item?.takeUnless { it.kind == VoiceBroadcastKind.NEEDS_INPUT }?.let {
            val spoken = it.sentences.getOrNull(sentenceIndex) ?: return@let null
            VoiceBroadcastFollow(
                itemId = it.id,
                sessionId = it.source.sessionId.orEmpty(),
                turnEntryId = it.source.turnEntryId,
                sourceBody = it.source.body,
                sentenceText = spoken.text,
                sentenceIndex = sentenceIndex,
                sentenceCount = it.sentences.size,
                visualAnchor = spoken.visualAnchor,
            )
        }
        runtime = runtime.copy(follow = follow)
        Log.i(
            LOG_TAG,
            follow?.let {
                "voice follow item=${it.itemId} session=${it.sessionId} " +
                    "sentence=${it.sentenceIndex}/${it.sentenceCount} visual=${it.visualAnchor}"
            } ?: "voice follow finished",
        )
        onRuntimeState(runtime)
    }

    private suspend fun speak(sentence: String): Boolean {
        val snapshot = settings
        val ok = speakWithEngine(
            systemSpeech,
            "xiaomi",
            sentence,
            snapshot.systemVoiceId,
            snapshot.clampedSpeed,
            snapshot.clampedPitch,
        )
        if (!ok && !paused.get()) publishNote("小爱语音暂不可用")
        return ok
    }

    private suspend fun speakWithEngine(
        engine: SpeechSynthesizer,
        label: String,
        sentence: String,
        voiceId: String,
        speed: Float,
        pitch: Float,
    ): Boolean {
        Log.i(LOG_TAG, "voice speak engine=$label chars=${sentence.length}")
        val ok = VoiceSpeechDeadline.speak(
            engine = engine,
            text = sentence,
            voiceId = voiceId,
            speed = speed,
            pitch = pitch,
            timeoutMs = SPEECH_TIMEOUT_MS,
        )
        Log.i(LOG_TAG, "voice speak engine=$label ok=$ok")
        return ok
    }

    private suspend fun playChime(token: Long): Boolean {
        if (paused.get() || stopped.get() || generation.get() != token) return false
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return true
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return true
        chime = ringtone
        runCatching { ringtone.play() }
        val played = withTimeoutOrNull(CHIME_TIMEOUT_MS) {
            while (ringtone.isPlaying && !paused.get() && !stopped.get() && generation.get() == token) {
                delay(50)
            }
            !paused.get() && !stopped.get() && generation.get() == token
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
    }

    private fun beginPlayback() {
        if (audioFocusRequest == null) {
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
        }
        publishPlaybackState(PlaybackState.STATE_PLAYING)
    }

    private fun releasePlayback(paused: Boolean) {
        stopSpeech()
        stopChime()
        resumeOtherMedia()
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
        publishPlaybackState(
            if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_STOPPED,
        )
    }

    private fun endPlayback() {
        releasePlayback(paused = false)
    }

    private fun publishPlaybackState(state: Int) {
        val actions = if (state == PlaybackState.STATE_STOPPED) {
            PlaybackState.ACTION_STOP
        } else {
            PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT
        }
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, 0L, 1f)
                .build(),
        )
        mediaSession?.setActive(state != PlaybackState.STATE_STOPPED)
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
        mediaSession?.release()
        mediaSession = null
    }
}
