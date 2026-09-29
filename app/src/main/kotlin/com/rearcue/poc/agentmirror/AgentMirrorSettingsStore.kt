package com.rearcue.poc.agentmirror

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.core.MirrorTextSize
import kotlinx.coroutines.flow.first

private val Context.agentMirrorSettingsDataStore by preferencesDataStore(name = "agent_mirror_settings")

private val KEY_MIRROR_TEXT_SIZE = stringPreferencesKey("mirror_text_size")
private val KEY_ALERT_ENABLED = booleanPreferencesKey("agent_alert_enabled")
private val KEY_ALERT_VIBRATE = booleanPreferencesKey("agent_alert_vibrate")

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
 * 缺键即默认中档（[MirrorTextSize.DEFAULT]）——默认值与 core 初值同源，进程启动首读是一次
 * 幂等对齐；枚举按**名字**存（[MirrorTextSize.fromName] 对未知/空字符串退到默认档，不抛），
 * 所以将来加档或改名都不会把老数据读崩。自己一个 DataStore 文件：镜像区的呈现偏好单独一份键，
 * 不与其他设置耦合（沿 [com.rearcue.poc.charging.ChargingSettingsStore] 的判例）。
 */
object AgentMirrorSettingsStore {

    /** 正文档位默认中档（spec 0017，与 core 初值同源）。 */
    val TEXT_SIZE_DEFAULT = MirrorTextSize.DEFAULT

    /** 提醒总开关默认开（spec 0018-3：开机即用，不需要先理解提醒概念）。 */
    const val ALERT_ENABLED_DEFAULT = true

    /** 震动开关默认开（spec 0018-3；不响铃是既定口径，震动可关）。 */
    const val ALERT_VIBRATE_DEFAULT = true

    suspend fun loadTextSize(context: Context): MirrorTextSize =
        MirrorTextSize.fromName(context.agentMirrorSettingsDataStore.data.first()[KEY_MIRROR_TEXT_SIZE])

    /** 写入档位（设置页 Agent 卡片的写入口）。 */
    suspend fun saveTextSize(context: Context, size: MirrorTextSize) {
        context.agentMirrorSettingsDataStore.edit { prefs ->
            prefs[KEY_MIRROR_TEXT_SIZE] = size.name
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
}
