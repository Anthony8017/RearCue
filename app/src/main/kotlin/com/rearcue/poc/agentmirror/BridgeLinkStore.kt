package com.rearcue.poc.agentmirror

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.bridgeLinkDataStore by preferencesDataStore(name = "bridge_link")

private val KEY_BRIDGE_URL = stringPreferencesKey("bridge_url")
private val KEY_BRIDGE_URL_SOURCE = stringPreferencesKey("bridge_url_source")
private val KEY_BRIDGE_PUSHED_AT = longPreferencesKey("bridge_pushed_at")

/** 桥地址从哪来（ADR 0006 补记）：电脑推送 / 手机手填 / 调试旁路。 */
enum class BridgeAddressSource {
    /** 电脑侧 adb 推送（常态；隧道换域名后自动覆盖）。 */
    PUSHED,

    /** 手机 Agent 设置页手填（兜底：电脑不在身边 / 无线调试没连上）。 */
    MANUAL,

    /**
     * Debug Bypass 广播（`DebugCommandReceiver.BRIDGE_URL`）：与 [PUSHED] 是同一条广播动作，
     * 但由人手敲 adb 触发，不是自动路径——界面文案得说得出这个差别（CONTEXT.md「Debug Bypass」）。
     */
    DEBUG_BYPASS,
    ;

    companion object {
        fun fromName(value: String?): BridgeAddressSource? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** 桥地址一条记录：地址 + 来源 + 电脑最后一次推送的时刻（仅 [BridgeAddressSource.PUSHED] 有意义）。 */
data class BridgeAddress(
    val url: String,
    val source: BridgeAddressSource,
    val pushedAt: Long?,
)

/**
 * PC 桥地址持久化（ADR 0006 / 票 #116）:手机连桥用的隧道地址（Bridge URL），
 * 手机侧唯一的接入配置。写面访问凭据藏在 URL fragment，[BridgeEndpoint] 解析后不回显；缺键 = 未配置。
 *
 * 旧 ZCode 配对凭据分库分键；#234 后本 store 是唯一传输配置面，
 * 桥的开关仍随 Agent Mirror 总开关（agentEnabled）统一管。
 *
 * 票 #171 加两栏：地址**来源**（电脑推送 / 手填 / 调试入口）与**推送时刻**。
 * 前者让界面说得出「当前这行是电脑推来的还是你手填的」，后者是"地址是不是被换过"的事后判据
 * （人不在电脑边时，托盘气泡看不见，只能回来看这一行）。
 */
object BridgeLinkStore {

    /** 首读（不含来源判定之外的加工）：未配置返回 null。 */
    suspend fun loadAddress(context: Context): BridgeAddress? {
        val prefs = context.bridgeLinkDataStore.data.first()
        val url = prefs[KEY_BRIDGE_URL]?.takeIf { it.isNotBlank() } ?: return null
        return BridgeAddress(
            url = url,
            source = BridgeAddressSource.fromName(prefs[KEY_BRIDGE_URL_SOURCE])
                ?: BridgeAddressSource.MANUAL,
            pushedAt = prefs[KEY_BRIDGE_PUSHED_AT],
        )
    }

    suspend fun load(context: Context): String? = loadAddress(context)?.url

    /** 保存（含来源与推送时刻；null 或空串 = 清除，两栏一并清）。 */
    suspend fun save(context: Context, address: BridgeAddress?) {
        context.bridgeLinkDataStore.edit { prefs ->
            if (address == null || address.url.isBlank()) {
                prefs.remove(KEY_BRIDGE_URL)
                prefs.remove(KEY_BRIDGE_URL_SOURCE)
                prefs.remove(KEY_BRIDGE_PUSHED_AT)
            } else {
                prefs[KEY_BRIDGE_URL] = address.url
                prefs[KEY_BRIDGE_URL_SOURCE] = address.source.name
                if (address.pushedAt != null) {
                    prefs[KEY_BRIDGE_PUSHED_AT] = address.pushedAt
                } else {
                    prefs.remove(KEY_BRIDGE_PUSHED_AT)
                }
            }
        }
    }

    /** 兼容旧调用（只给地址 = 当手填）：来源与时刻按手填入库。 */
    suspend fun save(context: Context, url: String?) {
        save(context, url?.takeIf { it.isNotBlank() }?.let { BridgeAddress(it, BridgeAddressSource.MANUAL, null) })
    }
}
