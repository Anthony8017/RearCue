package com.rearcue.poc.voice

import com.rearcue.poc.core.VoiceBroadcastFollow
import com.rearcue.poc.core.DashboardEvent

/** 语音播报引擎：产品只使用小爱语音；旧 wireName 仅用于兼容既有设置。 */
enum class VoiceEngine(val wireName: String) {
    OFFLINE("offline"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromName(raw: String?): VoiceEngine = SYSTEM
    }
}

data class VoiceOption(
    val id: String,
    val label: String,
)

enum class OfflineVoiceStatus {
    NOT_READY,
    DOWNLOADING,
    READY,
    FAILED,
}

data class VoiceBroadcastSettings(
    val enabled: Boolean = false,
    val engine: VoiceEngine = VoiceEngine.SYSTEM,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val kokoroVoiceId: String = VoiceCatalog.KOKORO_DEFAULT.id,
    val systemVoiceId: String = "",
) {
    val clampedSpeed: Float get() = speed.coerceIn(VoiceCatalog.MIN_SPEED, VoiceCatalog.MAX_SPEED)
    val clampedPitch: Float get() = pitch.coerceIn(VoiceCatalog.MIN_PITCH, VoiceCatalog.MAX_PITCH)
}

/** 播报内容的来源事实；无法定位会话时只播声音，不做视觉跳转。 */
data class VoiceBroadcastSource(
    val sessionId: String? = null,
    val turnEntryId: String? = null,
    val body: String? = null,
)

data class VoiceBroadcastRuntimeState(
    val offlineStatus: OfflineVoiceStatus = OfflineVoiceStatus.NOT_READY,
    val note: String? = null,
    val systemVoices: List<VoiceOption> = listOf(VoiceCatalog.SYSTEM_DEFAULT),
    val follow: VoiceBroadcastFollow? = null,
    /** 队列尚未耗尽；条目间和暂停期间仍为 true。 */
    val batchActive: Boolean = false,
)

/** 同一条的句子更新不重投，单条结束不冒充整组结束。 */
fun VoiceBroadcastRuntimeState.dashboardEventsSince(previous: VoiceBroadcastRuntimeState): List<DashboardEvent> =
    buildList {
        follow?.takeIf { previous.follow?.itemId != it.itemId }?.let {
            add(DashboardEvent.VoiceBroadcastStarted(it.sessionId, it.turnEntryId))
        }
        if (previous.batchActive && !batchActive) add(DashboardEvent.VoiceBroadcastFinished)
    }

object VoiceCatalog {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2.0f
    const val DEFAULT_SPEED = 1.0f
    const val MIN_PITCH = 0.5f
    const val MAX_PITCH = 2.0f
    const val DEFAULT_PITCH = 1.0f

    val SYSTEM_DEFAULT = VoiceOption("", "小爱默认")

    val KOKORO_DEFAULT = VoiceOption("zf_xiaoxiao", "小晓（女）")

    /** kokoro-multi-lang-v1_0 的中文说话人（官方 speaker id 45–52）。 */
    val KOKORO = listOf(
        VoiceOption("zf_xiaobei", "小贝（女）"),
        VoiceOption("zf_xiaoni", "小妮（女）"),
        VoiceOption("zf_xiaoxiao", "小晓（女）"),
        VoiceOption("zf_xiaoyi", "小艺（女）"),
        VoiceOption("zm_yunjian", "云健（男）"),
        VoiceOption("zm_yunxi", "云希（男）"),
        VoiceOption("zm_yunxia", "云夏（男）"),
        VoiceOption("zm_yunyang", "云扬（男）"),
    )

    val KOKORO_SPEAKER_IDS = mapOf(
        "zf_xiaobei" to 45,
        "zf_xiaoni" to 46,
        "zf_xiaoxiao" to 47,
        "zf_xiaoyi" to 48,
        "zm_yunjian" to 49,
        "zm_yunxi" to 50,
        "zm_yunxia" to 51,
        "zm_yunyang" to 52,
    )
}
