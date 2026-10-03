package com.rearcue.poc.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.PlaybackParams
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class KokoroTtsCallback(
    private val onSamples: (FloatArray) -> Int,
) : OfflineTtsCallback {
    override fun invoke(samples: FloatArray): java.lang.Integer =
        java.lang.Integer.valueOf(onSamples(samples)) as java.lang.Integer
}

interface SpeechSynthesizer {
    suspend fun speak(text: String, voiceId: String, speed: Float, pitch: Float): Boolean
    fun stop()
    fun release() = Unit
}

/** 手机系统自带 TTS：零下载选项与离线引擎故障回退。 */
class SystemSpeechSynthesizer(
    context: Context,
    onVoices: (List<VoiceOption>) -> Unit,
) : SpeechSynthesizer {
    private val ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null
    private val stopped = AtomicBoolean(false)

    init {
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready.complete(false)
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            engine.language = Locale.SIMPLIFIED_CHINESE
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            onVoices(chineseVoices(engine.voices))
            ready.complete(true)
        }
    }

    override suspend fun speak(text: String, voiceId: String, speed: Float, pitch: Float): Boolean {
        if (!ready.await()) return false
        val engine = tts ?: return false
        stopped.set(false)
        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            val utteranceId = "rearcue-${System.nanoTime()}"
            fun complete(value: Boolean) {
                if (resumed.compareAndSet(false, true)) continuation.resume(value)
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    if (id == null || id == utteranceId) complete(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == null || id == utteranceId) complete(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?, errorCode: Int) {
                    if (id == null || id == utteranceId) complete(false)
                }
            })
            engine.setSpeechRate(speed.coerceIn(VoiceCatalog.MIN_SPEED, VoiceCatalog.MAX_SPEED))
            engine.setPitch(pitch.coerceIn(VoiceCatalog.MIN_PITCH, VoiceCatalog.MAX_PITCH))
            findVoice(engine.voices, voiceId)?.let(engine::setVoice)
            stopped.set(false)
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) complete(false)
            continuation.invokeOnCancellation {
                engine.stop()
                complete(false)
            }
        }
    }

    override fun stop() {
        stopped.set(true)
        tts?.stop()
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun chineseVoices(voices: Set<Voice>?): List<VoiceOption> {
        val options = voices
            .orEmpty()
            .filter { it.locale?.language == Locale.CHINESE.language || it.name.contains("zh", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { VoiceOption(it.name, it.name) }
        return listOf(VoiceCatalog.SYSTEM_DEFAULT) + options
    }

    private fun findVoice(voices: Set<Voice>?, id: String): Voice? =
        if (id.isEmpty()) null else voices?.firstOrNull { it.name == id }
}

/** Sherpa-ONNX + Kokoro 离线引擎；模型由 [VoiceModelDownloader] 在启用时准备。 */
class KokoroSpeechSynthesizer(
    private val context: Context,
) : SpeechSynthesizer {
    private var tts: OfflineTts? = null
    private val stopped = AtomicBoolean(false)
    private var activeTrack: AudioTrack? = null

    override suspend fun speak(text: String, voiceId: String, speed: Float, pitch: Float): Boolean =
        withContext(Dispatchers.Default) {
            if (!ensureReady()) return@withContext false
            val engine = tts ?: return@withContext false
            stopped.set(false)
            val track = createTrack(engine.sampleRate())
            activeTrack = track
            try {
                runCatching {
                    track.playbackParams = PlaybackParams()
                        .setPitch(pitch.coerceIn(VoiceCatalog.MIN_PITCH, VoiceCatalog.MAX_PITCH))
                        .setSpeed(1f)
                }
                track.play()
                val config = GenerationConfig(
                    speed = speed.coerceIn(VoiceCatalog.MIN_SPEED, VoiceCatalog.MAX_SPEED),
                    sid = VoiceCatalog.KOKORO_SPEAKER_IDS[voiceId]
                        ?: VoiceCatalog.KOKORO_SPEAKER_IDS.getValue(VoiceCatalog.KOKORO_DEFAULT.id),
                )
                val callback = KokoroTtsCallback { samples ->
                    if (stopped.get()) {
                        0
                    } else {
                        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        1
                    }
                }
                engine.generateWithConfigAndCallback(text, config, callback)
                !stopped.get()
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { track.stop() }
                track.release()
                activeTrack = null
            }
        }

    override fun stop() {
        stopped.set(true)
        activeTrack?.stop()
    }

    override fun release() {
        stop()
        tts?.release()
        tts = null
    }

    private fun ensureReady(): Boolean {
        if (!VoiceModelDownloader.isReady(context)) return false
        if (tts != null) return true
        val root = VoiceModelDownloader.modelDir(context)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(root, "model.onnx").absolutePath,
                    voices = File(root, "voices.bin").absolutePath,
                    tokens = File(root, "tokens.txt").absolutePath,
                    dataDir = File(root, "espeak-ng-data").absolutePath,
                    lexicon = listOf(
                        File(root, "lexicon-us-en.txt").absolutePath,
                        File(root, "lexicon-zh.txt").absolutePath,
                    ).joinToString(","),
                ),
                numThreads = 2,
            ),
            ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst")
                .joinToString(",") { File(root, it).absolutePath },
        )
        return try {
            tts = OfflineTts(config = config)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        return AudioTrack(
            attributes,
            format,
            minBuffer.coerceAtLeast(4096),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }
}
