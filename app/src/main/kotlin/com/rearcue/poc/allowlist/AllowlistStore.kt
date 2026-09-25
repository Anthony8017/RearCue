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
 * 首读为空（新装，或从无持久层的 POC 版升级）时写入 POC 五枚种子并返回——与升级前行为
 * 完全一致，零迁移；此后增删均由调用方即时写盘。种子规则只有这一处（存储层直写，
 * 不为它立新测试 seam，spec 0005 的 Implementation Decisions）。
 */
object AllowlistStore {

    suspend fun load(context: Context): Set<String> {
        val stored = context.allowlistDataStore.data.first()[KEY_APPS]
        if (!stored.isNullOrEmpty()) return stored
        save(context, PocAllowlist.APPS)
        return PocAllowlist.APPS
    }

    suspend fun save(context: Context, apps: Set<String>) {
        context.allowlistDataStore.edit { prefs -> prefs[KEY_APPS] = apps }
    }
}
