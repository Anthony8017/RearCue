package com.rearcue.poc.agent

import java.net.URI
import java.net.URLDecoder

/**
 * ZCode 桌面端「远程控制」二维码链接（一次性配对凭据，spec 0010 定案：机主粘贴一次）。
 *
 * 链接形态（E1 固化，zcode.cjs TXi）：
 * `https://zcode.z.ai/remote/v4?sid=<deviceSid>&hash=<passHash>&t=<签发ms>&mid=<deviceMid>&name=<设备名>&app_version=<v>`
 *
 * 安全：链接即凭据——泄露等于交出控制权（ADR 0005）；本类只解析，调用方负责不回显、不落日志。
 */
data class PairingLink(
    val deviceSid: String,
    val passHash: String,
    val issuedAtMs: Long?,
    val deviceMid: String?,
    val deviceName: String?,
    val appVersion: String?,
    val raw: String,
) {
    companion object {
        /** 解析失败（缺 sid/hash 或非 http(s) 链接）抛 [IllegalArgumentException]。 */
        fun parse(raw: String): PairingLink {
            val trimmed = raw.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: throw IllegalArgumentException("不是有效的链接")
            if (uri.scheme?.lowercase() !in listOf("http", "https")) {
                throw IllegalArgumentException("链接必须是 http(s) 地址")
            }
            val query = uri.rawQuery ?: throw IllegalArgumentException("链接缺少配对参数")
            val params = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                val key = URLDecoder.decode(if (idx >= 0) pair.substring(0, idx) else pair, Charsets.UTF_8)
                val value = if (idx >= 0) URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8) else ""
                params[key] = value
            }
            val sid = params["sid"]?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("链接缺少 sid（桌面端会话标识）")
            val hash = params["hash"]?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("链接缺少 hash（配对凭据）")
            return PairingLink(
                deviceSid = sid,
                passHash = hash,
                issuedAtMs = params["t"]?.toLongOrNull(),
                deviceMid = params["mid"]?.takeIf { it.isNotEmpty() },
                deviceName = params["name"]?.takeIf { it.isNotEmpty() },
                appVersion = params["app_version"]?.takeIf { it.isNotEmpty() },
                raw = trimmed,
            )
        }
    }
}
