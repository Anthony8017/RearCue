package com.rearcue.poc.agentmirror

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.agent.PairingLink
import kotlinx.coroutines.flow.first

private val Context.agentLinkDataStore by preferencesDataStore(name = "agent_link")

private val KEY_DEVICE_SID = stringPreferencesKey("device_sid")
private val KEY_PASS_HASH = stringPreferencesKey("pass_hash")
private val KEY_DEVICE_MID = stringPreferencesKey("device_mid")
private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
private val KEY_APP_VERSION = stringPreferencesKey("app_version")
private val KEY_ISSUED_AT = stringPreferencesKey("issued_at_ms")
private val KEY_ENABLED = booleanPreferencesKey("mirror_enabled")

/**
 * Agent Mirror 配对凭据持久化（spec 0010 / 票 #81）：DataStore Preferences，应用私有存储。
 *
 * 安全红线（spec 0010 story 20 / ADR 0005）：QR 链接即凭据（hash 泄露 = 交出桌面控制权）——
 * 本 store 是凭据的唯一落盘处；界面不回显完整链接/hash，任何日志路径不得打印键值
 * （[describe] 只报「有/无」与设备名，设备名是桌面自己起的别名，非机密）。
 * 总开关缺键即默认开（与充电开关同款「布尔无歧义、不播种」判例）。
 */
object AgentLinkStore {

    const val ENABLED_DEFAULT = true

    suspend fun load(context: Context): PairingLink? {
        val prefs = context.agentLinkDataStore.data.first()
        val sid = prefs[KEY_DEVICE_SID] ?: return null
        val hash = prefs[KEY_PASS_HASH] ?: return null
        return PairingLink(
            deviceSid = sid,
            passHash = hash,
            issuedAtMs = prefs[KEY_ISSUED_AT]?.toLongOrNull(),
            deviceMid = prefs[KEY_DEVICE_MID],
            deviceName = prefs[KEY_DEVICE_NAME],
            appVersion = prefs[KEY_APP_VERSION],
            raw = "", // 原始链接不落盘（凭据拆开存，raw 无读者）
        )
    }

    suspend fun save(context: Context, link: PairingLink) {
        context.agentLinkDataStore.edit { prefs ->
            prefs[KEY_DEVICE_SID] = link.deviceSid
            prefs[KEY_PASS_HASH] = link.passHash
            link.deviceMid?.let { prefs[KEY_DEVICE_MID] = it } ?: prefs.remove(KEY_DEVICE_MID)
            link.deviceName?.let { prefs[KEY_DEVICE_NAME] = it } ?: prefs.remove(KEY_DEVICE_NAME)
            link.appVersion?.let { prefs[KEY_APP_VERSION] = it } ?: prefs.remove(KEY_APP_VERSION)
            link.issuedAtMs?.let { prefs[KEY_ISSUED_AT] = it.toString() } ?: prefs.remove(KEY_ISSUED_AT)
        }
    }

    /** 解除配对（spec 0010 story 4）：凭据整体清除，状态回未配对。 */
    suspend fun clear(context: Context) {
        context.agentLinkDataStore.edit { prefs ->
            prefs.remove(KEY_DEVICE_SID)
            prefs.remove(KEY_PASS_HASH)
            prefs.remove(KEY_DEVICE_MID)
            prefs.remove(KEY_DEVICE_NAME)
            prefs.remove(KEY_APP_VERSION)
            prefs.remove(KEY_ISSUED_AT)
        }
    }

    suspend fun loadEnabled(context: Context): Boolean =
        context.agentLinkDataStore.data.first()[KEY_ENABLED] ?: ENABLED_DEFAULT

    suspend fun saveEnabled(context: Context, enabled: Boolean) {
        context.agentLinkDataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    /** 日志安全描述：绝不包含凭据内容。 */
    fun describe(link: PairingLink?): String =
        if (link == null) "unpaired" else "paired device=${link.deviceName ?: "unknown"}"
}
