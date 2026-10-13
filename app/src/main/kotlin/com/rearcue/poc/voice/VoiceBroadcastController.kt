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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.rearcue.poc.LOG_TAG
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
    private var cueEpoch = 0L
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
    private val buttonAnchor = VoiceButtonAnchor(scope)
    private var holdingBroadcastAudio = false
    private var headphoneReclaimedWhilePaused = false
    private var sessionWatcher: MediaSessionManager.OnActiveSessionsChangedListener? = null

    fun updateSettings(next: VoiceBroadcastSettings) {
        if (settings.cueEnabled != next.cueEnabled) cueEpoch++
        settings = next
        if (!next.cueEnabled) stopChime()
    }

    fun enqueue(
        kind: VoiceBroadcastKind,
        body: String?,
        errorReason: String? = null,
        source: VoiceBroadcastSource = VoiceBroadcastSource(),
        sessionId: String? = source.sessionId,
        eventId: String? = null,
        requestId: String? = null,
        cueBeforeSpeech: Boolean = false,
    ) {
        if (!settings.enabled) return
        val text = VoiceBroadcastText.spokenText(kind, body, errorReason)
        val sentences = if (kind != VoiceBroadcastKind.ERROR && !body.isNullOrBlank()) {
            VoiceBroadcastText.spokenSentences(body)
        } else {
            VoiceBroadcastText.spokenSentences(text)
        }
        val item = queue.enqueue(
            kind = kind,
            text = text,
            sessionId = sessionId,
            eventId = eventId,
            requestId = requestId,
            source = source,
            sentences = sentences,
            cueBeforeSpeech = cueBeforeSpeech,
            cueEpoch = cueEpoch,
        )
        Log.i(LOG_TAG, "voice enqueue kind=${kind.name.lowercase()} id=${item.id} chars=${text.length}")
        ensureWorker()
    }

    fun retainRequests(sessionId: String, requestIds: Set<String>) {
        if (queue.retainRequests(sessionId, requestIds)) skipCurrent()
        publishQueueState()
    }

    fun retainSessions(sessionIds: Set<String>) {
        if (queue.retainSessions(sessionIds)) skipCurrent()
        publishQueueState()
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
        releasePlayback(userPaused = true)
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
                    publishFollow(item, startIndex)
                    beginPlayback()
                    if (item.shouldPlayCue(settings, cueEpoch) &&
                        !chimeCompletedForCurrent && !playChime(token)) {
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
        val follow = item?.projectFollow(sentenceIndex)
        runtime = runtime.copy(follow = follow, batchActive = queue.hasContent)
        Log.i(
            LOG_TAG,
            follow?.let {
                "voice follow item=${it.itemId} session=${it.sessionId} " +
                    "sentence=${it.sentenceIndex}/${it.sentenceCount} visual=${it.visualAnchor}"
            } ?: "voice follow finished",
        )
        onRuntimeState(runtime)
    }

    private fun publishQueueState() {
        val active = queue.hasContent
        if (runtime.batchActive == active) return
        runtime = runtime.copy(batchActive = active, follow = runtime.follow.takeIf { active })
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
        if (!canPlayAgentCue(context)) return true
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return true
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return true
        chime = ringtone
        runCatching { ringtone.play() }
        val played = withTimeoutOrNull(CHIME_TIMEOUT_MS) {
            while (ringtone.isPlaying && !paused.get() && !stopped.get() && generation.get() == token) {
                delay(50)
            }
            !paused.get() && !stopped.get() && generation.get() == token
        } ?: !paused.get() && !stopped.get() && generation.get() == token
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
            holdingBroadcastAudio = true
            headphoneReclaimedWhilePaused = false
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change ->
                    onMain {
                        val next = interruptedAfterAudioFocusChange(change, pausedForInterruption)
                        val becameInterrupted = next && !pausedForInterruption
                        val interruptionEnded = pausedForInterruption && !next
                        pausedForInterruption = next
                        if (becameInterrupted) buttonAnchor.stop()
                        if (interruptionEnded && holdingBroadcastAudio) buttonAnchor.start()
                    }
                }
                .build()
            pauseOtherMedia()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
            watchOtherMedia()
        }
        ensureSession()
        publishPlaybackState(PlaybackState.STATE_PLAYING)
        buttonAnchor.start()
    }

    private fun releasePlayback(userPaused: Boolean) {
        stopSpeech()
        stopChime()
        val keepButton = userPaused && queue.hasContent
        holdingBroadcastAudio = false
        headphoneReclaimedWhilePaused = false
        if (!keepButton) unwatchOtherMedia()
        buttonAnchor.stop()
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
        resumeOtherMedia()
        if (keepButton) {
            ensureSession()
            publishPlaybackState(PlaybackState.STATE_PAUSED)
            buttonAnchor.start()
        } else {
            publishPlaybackState(PlaybackState.STATE_STOPPED)
            closeSession()
        }
    }

    private fun endPlayback() {
        releasePlayback(userPaused = false)
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
            suppressOtherMedia(activeSessions())
        }
    }

    private fun watchOtherMedia() {
        if (sessionWatcher != null) return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onOtherMediaChanged(controllers.orEmpty())
        }
        val registered = runCatching {
            sessionManager().addOnActiveSessionsChangedListener(
                listener,
                ComponentName(context, RearNotificationListener::class.java),
                Handler(Looper.getMainLooper()),
            )
        }.isSuccess
        if (registered) sessionWatcher = listener
    }

    private fun unwatchOtherMedia() {
        val listener = sessionWatcher ?: return
        sessionWatcher = null
        runCatching { sessionManager().removeOnActiveSessionsChangedListener(listener) }
    }

    private fun onOtherMediaChanged(sessions: List<MediaController>) {
        if (holdingBroadcastAudio) {
            if (suppressOtherMedia(sessions)) buttonAnchor.restart()
            return
        }
        val othersPlaying = sessions.any {
            it.packageName != context.packageName &&
                it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (shouldReclaimHeadphoneButton(
                userPaused = paused.get(),
                broadcastStopped = stopped.get(),
                otherMediaPlaying = othersPlaying,
                alreadyReclaimed = headphoneReclaimedWhilePaused,
            )
        ) {
            headphoneReclaimedWhilePaused = true
            Log.i(LOG_TAG, "voice headphone reclaim")
            buttonAnchor.restart()
        } else if (!othersPlaying) {
            headphoneReclaimedWhilePaused = false
        }
    }

    private fun suppressOtherMedia(sessions: List<MediaController>): Boolean {
        val keys = externalPlaybackKeysToPause(
            holdingBroadcast = holdingBroadcastAudio,
            sessions = sessions
                .filter { it.packageName != context.packageName }
                .map {
                    ExternalPlayback(
                        it.sessionToken.toString(),
                        it.playbackState?.state == PlaybackState.STATE_PLAYING,
                    )
                },
            alreadyHeld = pausedMedia.map { it.sessionToken.toString() }.toSet(),
        )
        if (keys.isEmpty()) return false
        Log.i(LOG_TAG, "voice headphone suppress=${keys.size}")
        sessions.filter { it.sessionToken.toString() in keys }.forEach { controller ->
            runCatching { controller.transportControls.pause() }
            pausedMedia += controller
        }
        return true
    }

    private fun activeSessions(): List<MediaController> =
        sessionManager().getActiveSessions(ComponentName(context, RearNotificationListener::class.java))

    private fun sessionManager() = context.getSystemService(MediaSessionManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun ensureSession() {
        if (mediaSession != null) return
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
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "语音播报")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "RearCue")
                    .build(),
            )
        }
    }

    private fun closeSession() {
        unwatchOtherMedia()
        mediaSession?.release()
        mediaSession = null
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
        buttonAnchor.stop()
        closeSession()
    }
}
