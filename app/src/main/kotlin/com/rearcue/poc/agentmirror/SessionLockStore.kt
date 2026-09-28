package com.rearcue.poc.agentmirror

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import kotlinx.coroutines.flow.first

private val Context.sessionLockDataStore by preferencesDataStore(name = "session_lock")

private val KEY_LOCKED_SESSION_ID = stringPreferencesKey("locked_session_id")

/**
 * Session Lock 偏好持久化（票 #103，照 [com.rearcue.poc.charging.ChargingSettingsStore] 惯例）：
 * DataStore Preferences、独立文件、缺键即默认「自动」（[SessionLockMode.Auto]，与 core 初值同源）。
 *
 * 锁定跨 App 重启保留（CONTEXT.md「Session Lock」）；切回自动（含锁定会话从任务表消失被
 * core 自动清锁后的写盘跟随）即删键，回到「缺键 = 自动」的零态——布尔没有的「空值 ≠ 缺键」
 * 歧义这里靠删键消除。自己一份 `session_lock` 文件，与充电/Agent 开关键互不耦合。
 */
object SessionLockStore {

    /** 首读：缺键/空值即 [SessionLockMode.Auto]（进程启动首读是一次幂等对齐）。 */
    suspend fun load(context: Context): SessionLockMode =
        context.sessionLockDataStore.data.first()[KEY_LOCKED_SESSION_ID]
            ?.takeIf { it.isNotBlank() }
            ?.let { SessionLockMode.Locked(it) }
            ?: SessionLockMode.Auto

    /** 写入档位（写入口与 core 自动清锁的跟随落点）：自动档删键、锁定档落会话键。 */
    suspend fun save(context: Context, mode: SessionLockMode) {
        context.sessionLockDataStore.edit { prefs ->
            when (mode) {
                SessionLockMode.Auto -> prefs.remove(KEY_LOCKED_SESSION_ID)
                is SessionLockMode.Locked -> prefs[KEY_LOCKED_SESSION_ID] = mode.sessionId
            }
        }
    }
}
