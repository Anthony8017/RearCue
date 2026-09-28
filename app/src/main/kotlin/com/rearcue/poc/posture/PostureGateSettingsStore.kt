package com.rearcue.poc.posture

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.core.DashboardCore
import kotlinx.coroutines.flow.first

private val Context.postureGateDataStore by preferencesDataStore(name = "posture_gate_settings")

private val KEY_POSTURE_GATE_ENABLED = booleanPreferencesKey("posture_gate_enabled")

/**
 * 姿态门控开关持久化（票 #100）：DataStore Preferences。
 *
 * 缺键即默认（**默认关**——门控旁路，出厂与升级后同档）——默认值与 [DashboardCore] 初值同源，
 * 进程启动首读是一次幂等对齐，布尔没有「空值 ≠ 缺键」的歧义、不需要播种。自己一个
 * DataStore 文件：设置页姿态区单独一份键，互不耦合（沿 [com.rearcue.poc.charging.ChargingSettingsStore]
 * 的判例）。
 */
object PostureGateSettingsStore {

    /** 姿态门控开关默认关（票 #100，与 core 初值同源）。 */
    const val POSTURE_GATE_DEFAULT = DashboardCore.POSTURE_GATE_DEFAULT

    suspend fun load(context: Context): Boolean =
        context.postureGateDataStore.data.first()[KEY_POSTURE_GATE_ENABLED]
            ?: POSTURE_GATE_DEFAULT

    /** 写入开关档位（设置页姿态区的写入口；缺键即 [POSTURE_GATE_DEFAULT]）。 */
    suspend fun savePostureGateEnabled(context: Context, enabled: Boolean) {
        context.postureGateDataStore.edit { prefs -> prefs[KEY_POSTURE_GATE_ENABLED] = enabled }
    }
}
