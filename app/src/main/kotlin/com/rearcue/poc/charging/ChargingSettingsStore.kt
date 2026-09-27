package com.rearcue.poc.charging

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.core.DashboardCore
import kotlinx.coroutines.flow.first

private val Context.chargingSettingsDataStore by preferencesDataStore(name = "charging_settings")

private val KEY_CHARGING_ANIMATION = booleanPreferencesKey("charging_animation_enabled")

/**
 * 充电动画总开关持久化（spec 0007 story 11 / 票 #57）：DataStore Preferences。
 *
 * 缺键即默认（默认开）——默认值与 [DashboardCore] 初值同源，进程启动首读是一次幂等对齐，
 * 布尔没有「空值 ≠ 缺键」的歧义、不需要播种。自己一个 DataStore 文件：设置页充电区单独
 * 一份键，互不耦合。（原横幅区的 FeedSettingsStore 随横幅退役删除，spec 0008；
 * 设备上残留的 `feed_settings` 文件废弃容忍——无读者、无害，卸载即清。）
 */
object ChargingSettingsStore {

    /** 充电动画总开关默认开（spec 0007 story 11，与 core 初值同源）。 */
    const val CHARGING_ANIMATION_DEFAULT = DashboardCore.CHARGING_ANIMATION_DEFAULT

    suspend fun load(context: Context): Boolean =
        context.chargingSettingsDataStore.data.first()[KEY_CHARGING_ANIMATION]
            ?: CHARGING_ANIMATION_DEFAULT

    /** 写入总开关（设置页充电区的写入口；缺键即 [CHARGING_ANIMATION_DEFAULT]）。 */
    suspend fun saveChargingAnimationEnabled(context: Context, enabled: Boolean) {
        context.chargingSettingsDataStore.edit { prefs -> prefs[KEY_CHARGING_ANIMATION] = enabled }
    }
}
