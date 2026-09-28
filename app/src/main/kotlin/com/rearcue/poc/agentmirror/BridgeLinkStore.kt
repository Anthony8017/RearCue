package com.rearcue.poc.agentmirror

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.bridgeLinkDataStore by preferencesDataStore(name = "bridge_link")

private val KEY_BRIDGE_URL = stringPreferencesKey("bridge_url")

/**
 * PC 桥地址持久化（ADR 0006 / 票 #116）：桥（tools/bridge）经 tunwg 暴露的 HTTPS 隧道
 * URL——手机侧唯一的接入配置（无凭据；隧道域名由桥侧 WireGuard key 派生、重启不变，
 * 见 tunwg README）。缺键 = 未配置（桥客户端不启动）。
 *
 * 与 [AgentLinkStore]（ZCode 配对凭据）分库分键：两条通道各自独立，
 * 桥的开关仍随 Agent Mirror 总开关（agentEnabled）统一管。
 */
object BridgeLinkStore {

    suspend fun load(context: Context): String? =
        context.bridgeLinkDataStore.data.first()[KEY_BRIDGE_URL]?.takeIf { it.isNotBlank() }

    /** 保存/清除（null 或空串 = 清除）。 */
    suspend fun save(context: Context, url: String?) {
        context.bridgeLinkDataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(KEY_BRIDGE_URL) else prefs[KEY_BRIDGE_URL] = url
        }
    }
}
