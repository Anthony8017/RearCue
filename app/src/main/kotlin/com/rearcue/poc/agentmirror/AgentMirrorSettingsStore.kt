package com.rearcue.poc.agentmirror

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.GlowBrightness
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.voice.VoiceBroadcastSettings
import com.rearcue.poc.voice.VoiceCatalog
import com.rearcue.poc.voice.VoiceEngine
import kotlinx.coroutines.flow.first

private val Context.agentMirrorSettingsDataStore by preferencesDataStore(name = "agent_mirror_settings")

private val KEY_MIRROR_ENABLED = booleanPreferencesKey("mirror_enabled")
private val KEY_MIRROR_TEXT_SIZE = stringPreferencesKey("mirror_text_size")
private val KEY_ALERT_ENABLED = booleanPreferencesKey("agent_alert_enabled")
private val KEY_ALERT_VIBRATE = booleanPreferencesKey("agent_alert_vibrate")
private val KEY_APPROVE_ENABLED = booleanPreferencesKey("remote_approve_enabled")
private val KEY_GLOW_BRIGHTNESS = floatPreferencesKey("glow_brightness")
private val KEY_VOICE_ENABLED = booleanPreferencesKey("voice_broadcast_enabled")
private val KEY_CUE_ENABLED = booleanPreferencesKey("agent_cue_enabled")
private val KEY_VOICE_ENGINE = stringPreferencesKey("voice_broadcast_engine")
private val KEY_VOICE_SPEED = floatPreferencesKey("voice_broadcast_speed")
private val KEY_VOICE_PITCH = floatPreferencesKey("voice_broadcast_pitch")
private val KEY_VOICE_KOKORO = stringPreferencesKey("voice_broadcast_kokoro_voice")
private val KEY_VOICE_SYSTEM = stringPreferencesKey("voice_broadcast_system_voice")

/**
 * Agent 提醒两开关的持久化值（spec 0018-3 / 票 #173）：缺键即默认（双默认开，
 * 与 [AgentMirrorSettingsStore.ALERT_ENABLED_DEFAULT] / [AgentMirrorSettingsStore.ALERT_VIBRATE_DEFAULT] 同源）。
 */
data class AgentAlertSettings(
    val enabled: Boolean = AgentMirrorSettingsStore.ALERT_ENABLED_DEFAULT,
    val vibrate: Boolean = AgentMirrorSettingsStore.ALERT_VIBRATE_DEFAULT,
)

/**
 * Agent 页呈现偏好持久化（spec 0017 / 票 #169）：DataStore Preferences。
 *
 * 字号选项已删除（2026-10-11），缺键或旧中/大值都统一为默认小档，并把非小档旧值回写归一。
 * 自己一个 DataStore 文件：镜像区的呈现偏好单独一份键，不与其他设置耦合
 * （沿 [com.rearcue.poc.charging.ChargingSettingsStore] 的判例）。
 */
object AgentMirrorSettingsStore {

    /** Agent Mirror 总开关缺省档（默认开，与 core 初值同源）。 */
    const val MIRROR_ENABLED_DEFAULT = true

    /** 唯一用户可见字号档：小（2026-10-11）。 */
    val TEXT_SIZE_DEFAULT = MirrorTextSize.DEFAULT

    /** 提醒总开关默认开（spec 0018-3：开机即用，不需要先理解提醒概念）。 */
    const val ALERT_ENABLED_DEFAULT = true

    /** 震动开关默认开（spec 0018-3；不响铃是既定口径，震动可关）。 */
    const val ALERT_VIBRATE_DEFAULT = true

    /** 远程批准开关默认开（spec 0018 §五「免解锁批准……提供开关，随时可关」，review 2026-09-30 补遗）。 */
    const val APPROVE_ENABLED_DEFAULT = true

    /** 光带亮度倍率默认 1×（spec 0021 修订 / 票 #214，与 core [GlowBrightness.DEFAULT] 同源）。 */
    val GLOW_BRIGHTNESS_DEFAULT = GlowBrightness.DEFAULT

    suspend fun loadMirrorEnabled(context: Context): Boolean =
        context.agentMirrorSettingsDataStore.data.first()[KEY_MIRROR_ENABLED] ?: MIRROR_ENABLED_DEFAULT

    suspend fun saveMirrorEnabled(context: Context, enabled: Boolean) {
        context.agentMirrorSettingsDataStore.edit { prefs -> prefs[KEY_MIRROR_ENABLED] = enabled }
    }

    suspend fun loadTextSize(context: Context): MirrorTextSize {
        val prefs = context.agentMirrorSettingsDataStore.data.first()
        val stored = prefs[KEY_MIRROR_TEXT_SIZE]
        val normalized = MirrorTextSize.fromName(stored)
        if (stored != null && stored != normalized.name) {
            context.agentMirrorSettingsDataStore.edit { it[KEY_MIRROR_TEXT_SIZE] = normalized.name }
        }
        return normalized
    }

    /** 兼容写入口：无论旧值来自哪里，落盘都归一为小档。 */
    suspend fun saveTextSize(context: Context, size: MirrorTextSize) {
        val normalized = MirrorTextSize.fromName(size.name)
        context.agentMirrorSettingsDataStore.edit { prefs ->
            prefs[KEY_MIRROR_TEXT_SIZE] = normalized.name
        }
    }

    /** 读提醒两开关（spec 0018-3）：缺键即默认（双默认开），首读是幂等对齐。 */
    suspend fun loadAlerts(context: Context): AgentAlertSettings {
        val prefs = context.agentMirrorSettingsDataStore.data.first()
        return AgentAlertSettings(
            enabled = prefs[KEY_ALERT_ENABLED] ?: ALERT_ENABLED_DEFAULT,
            vibrate = prefs[KEY_ALERT_VIBRATE] ?: ALERT_VIBRATE_DEFAULT,
        )
    }

    /** 写提醒总开关（设置页 Agent 区的写入口）。 */
    suspend fun saveAlertEnabled(context: Context, enabled: Boolean) {
        context.agentMirrorSettingsDataStore.edit { prefs ->
            prefs[KEY_ALERT_ENABLED] = enabled
        }
    }

    /** 写震动开关（设置页 Agent 区的写入口）。 */
    suspend fun saveAlertVibrate(context: Context, vibrate: Boolean) {
        context.agentMirrorSettingsDataStore.edit { prefs ->
            prefs[KEY_ALERT_VIBRATE] = vibrate
        }
    }

    /** 读远程批准开关（review 2026-09-30）：缺键即默认开，首读幂等对齐。 */
    suspend fun loadApprovalEnabled(context: Context): Boolean =
        context.agentMirrorSettingsDataStore.data.first()[KEY_APPROVE_ENABLED] ?: APPROVE_ENABLED_DEFAULT

    /** 写远程批准开关（设置页 Agent 区的写入口）：关＝三处批准入口全部不出现。 */
    suspend fun saveApprovalEnabled(context: Context, enabled: Boolean) {
        context.agentMirrorSettingsDataStore.edit { prefs ->
            prefs[KEY_APPROVE_ENABLED] = enabled
        }
    }

    /** 读光带亮度倍率（spec 0021 修订 / 票 #214）：缺键即 1×，越界钳回范围（老数据兜底）。 */
    suspend fun loadGlowBrightness(context: Context): Float =
        GlowBrightness.coerce(
            context.agentMirrorSettingsDataStore.data.first()[KEY_GLOW_BRIGHTNESS] ?: GLOW_BRIGHTNESS_DEFAULT,
        )

    /** 写光带亮度倍率（主屏 Agent 区滑动条的写入口）。 */
    suspend fun saveGlowBrightness(context: Context, brightness: Float) {
        context.agentMirrorSettingsDataStore.edit { prefs ->
            prefs[KEY_GLOW_BRIGHTNESS] = GlowBrightness.coerce(brightness)
        }
    }
    /** Agent 声音设置（spec 0032）：提示音新键缺席时沿用旧朗读开关；固定小爱、1.0x。 */
    suspend fun loadVoiceBroadcast(context: Context): VoiceBroadcastSettings {
        val prefs = context.agentMirrorSettingsDataStore.data.first()
        val toggles = VoiceBroadcastSettings.fromStoredToggles(prefs[KEY_VOICE_ENABLED], prefs[KEY_CUE_ENABLED])
        return toggles.copy(
            engine = VoiceEngine.fromName(prefs[KEY_VOICE_ENGINE]),
            speed = (prefs[KEY_VOICE_SPEED] ?: VoiceCatalog.DEFAULT_SPEED)
                .coerceIn(VoiceCatalog.MIN_SPEED, VoiceCatalog.MAX_SPEED),
            pitch = (prefs[KEY_VOICE_PITCH] ?: VoiceCatalog.DEFAULT_PITCH)
                .coerceIn(VoiceCatalog.MIN_PITCH, VoiceCatalog.MAX_PITCH),
            kokoroVoiceId = prefs[KEY_VOICE_KOKORO] ?: VoiceCatalog.KOKORO_DEFAULT.id,
            systemVoiceId = prefs[KEY_VOICE_SYSTEM] ?: VoiceCatalog.SYSTEM_DEFAULT.id,
        )
    }

    suspend fun saveVoiceBroadcast(context: Context, settings: VoiceBroadcastSettings) {
        context.agentMirrorSettingsDataStore.edit { prefs ->
            prefs[KEY_VOICE_ENABLED] = settings.enabled
            prefs[KEY_CUE_ENABLED] = settings.cueEnabled
            prefs[KEY_VOICE_ENGINE] = settings.engine.wireName
            prefs[KEY_VOICE_SPEED] = settings.clampedSpeed
            prefs[KEY_VOICE_PITCH] = settings.clampedPitch
            prefs[KEY_VOICE_KOKORO] = settings.kokoroVoiceId
            prefs[KEY_VOICE_SYSTEM] = settings.systemVoiceId
        }
    }
}
