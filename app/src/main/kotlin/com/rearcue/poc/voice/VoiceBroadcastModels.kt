package com.rearcue.poc.voice

/** 语音播报引擎：离线推荐为默认，系统 TTS 是零下载选项与故障回退。 */
enum class VoiceEngine(val wireName: String) {
    OFFLINE("offline"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromName(raw: String?): VoiceEngine =
            entries.firstOrNull { it.wireName == raw } ?: OFFLINE
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
    val engine: VoiceEngine = VoiceEngine.OFFLINE,
    val speed: Float = 1.0f,
    val kokoroVoiceId: String = VoiceCatalog.KOKORO_DEFAULT.id,
    val systemVoiceId: String = "",
) {
    val clampedSpeed: Float get() = speed.coerceIn(VoiceCatalog.MIN_SPEED, VoiceCatalog.MAX_SPEED)
}

data class VoiceBroadcastRuntimeState(
    val offlineStatus: OfflineVoiceStatus = OfflineVoiceStatus.NOT_READY,
    val note: String? = null,
    val systemVoices: List<VoiceOption> = listOf(VoiceCatalog.SYSTEM_DEFAULT),
)

object VoiceCatalog {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2.0f
    const val DEFAULT_SPEED = 1.0f

    val SYSTEM_DEFAULT = VoiceOption("", "系统默认")

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
