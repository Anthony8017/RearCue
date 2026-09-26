package com.rearcue.poc.feed

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.core.DashboardCore
import kotlinx.coroutines.flow.first

private val Context.feedSettingsDataStore by preferencesDataStore(name = "feed_settings")

private val KEY_PRIVACY_MODE = booleanPreferencesKey("privacy_mode")
private val KEY_AUTO_DISMISS_MS = longPreferencesKey("auto_dismiss_ms")

/** Notification Feed 设置（spec 0007）：Privacy Mode 档位 + Auto-dismiss 时限。 */
data class FeedSettings(
    val privacyMode: Boolean,
    val autoDismissMs: Long,
)

/**
 * Notification Feed 设置持久化（spec 0007 / 票 #55）：DataStore Preferences。
 *
 * 缺键即默认（Privacy Mode 开、Auto-dismiss 10 秒）——默认值与 [DashboardCore] 初值同源，
 * 进程启动首读是一次幂等对齐。布尔/时长没有「空值 ≠ 缺键」的歧义，
 * 不需要 [com.rearcue.poc.allowlist.AllowlistStore] 那种首读播种。
 *
 * 本层只管存取，没有设置页（Privacy Mode 开关与时长调节归票 #56）。
 */
object FeedSettingsStore {

    /** Privacy Mode 默认开（spec 0007 story 2，与 core 初值同源）。 */
    const val PRIVACY_MODE_DEFAULT = DashboardCore.PRIVACY_MODE_DEFAULT

    /** Auto-dismiss 默认 10 秒（spec 0007 story 5，与 core 初值同源）。 */
    const val AUTO_DISMISS_DEFAULT_MS = DashboardCore.AUTO_DISMISS_DEFAULT_MS

    suspend fun load(context: Context): FeedSettings {
        val prefs = context.feedSettingsDataStore.data.first()
        return FeedSettings(
            privacyMode = prefs[KEY_PRIVACY_MODE] ?: PRIVACY_MODE_DEFAULT,
            autoDismissMs = prefs[KEY_AUTO_DISMISS_MS] ?: AUTO_DISMISS_DEFAULT_MS,
        )
    }

    /** 写入 Privacy Mode 档位（票 #56 设置页的写入口；默认档缺键即 [PRIVACY_MODE_DEFAULT]）。 */
    suspend fun savePrivacyMode(context: Context, enabled: Boolean) {
        context.feedSettingsDataStore.edit { prefs -> prefs[KEY_PRIVACY_MODE] = enabled }
    }

    /**
     * 写入 Auto-dismiss 时限（票 #56 设置页的写入口）。
     * 取值域 5 秒～无上限由设置页收口，存储层原样照记。
     */
    suspend fun saveAutoDismissMs(context: Context, durationMs: Long) {
        context.feedSettingsDataStore.edit { prefs -> prefs[KEY_AUTO_DISMISS_MS] = durationMs }
    }
}
