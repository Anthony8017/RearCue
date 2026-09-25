package com.rearcue.poc.allowlist

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.core.PocAllowlist
import kotlinx.coroutines.flow.first

private val Context.allowlistDataStore by preferencesDataStore(name = "allowlist")

private val KEY_APPS = stringSetPreferencesKey("apps")

/**
 * Allowlist 持久化（spec 0005）：DataStore Preferences，只存包名集合。
 *
 * 首读判据是 **键是否存在**（`apps == null`），不是集合是否为空——机主合法清空后存的就是
 * 空集，重启必须仍是空（#48 链路 D 实测踩过：空集被误判首装、重置回种子）。首次写入
 * POC 五枚种子（新装或从无持久层的版本升级），与升级前行为一致，零迁移。
 */
object AllowlistStore {

    suspend fun load(context: Context): Set<String> {
        val stored = context.allowlistDataStore.data.first()[KEY_APPS]
        if (stored != null) return stored
        save(context, PocAllowlist.APPS)
        return PocAllowlist.APPS
    }

    suspend fun save(context: Context, apps: Set<String>) {
        context.allowlistDataStore.edit { prefs -> prefs[KEY_APPS] = apps }
    }
}
