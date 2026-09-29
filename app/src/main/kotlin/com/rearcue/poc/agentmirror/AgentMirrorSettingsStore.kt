package com.rearcue.poc.agentmirror

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.core.MirrorTextSize
import kotlinx.coroutines.flow.first

private val Context.agentMirrorSettingsDataStore by preferencesDataStore(name = "agent_mirror_settings")

private val KEY_MIRROR_TEXT_SIZE = stringPreferencesKey("mirror_text_size")

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

    suspend fun loadTextSize(context: Context): MirrorTextSize =
        MirrorTextSize.fromName(context.agentMirrorSettingsDataStore.data.first()[KEY_MIRROR_TEXT_SIZE])

    /** 写入档位（设置页 Agent 卡片的写入口）。 */
    suspend fun saveTextSize(context: Context, size: MirrorTextSize) {
        context.agentMirrorSettingsDataStore.edit { prefs ->
            prefs[KEY_MIRROR_TEXT_SIZE] = size.name
        }
    }
}
