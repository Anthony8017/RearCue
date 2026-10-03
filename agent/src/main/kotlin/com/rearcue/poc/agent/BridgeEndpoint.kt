package com.rearcue.poc.agent

import java.net.URI
import java.net.URLDecoder

/**
 * Bridge URL 的接入拆分（spec 0024）：base URL 走 HTTP，访问凭据放 fragment 的
 * `token=...`。fragment 不会发给隧道服务端；日志与界面仍只显示 base URL。
 */
data class BridgeEndpoint(
    val baseUrl: String,
    val accessToken: String? = null,
) {
    fun authorize(builder: okhttp3.Request.Builder): okhttp3.Request.Builder {
        val token = accessToken?.takeIf { it.isNotBlank() } ?: return builder
        return builder.header("Authorization", "Bearer $token")
    }

    companion object {
        fun parse(raw: String): BridgeEndpoint {
            val trimmed = raw.trim().trimEnd('/')
            val uri = URI(trimmed)
            require(uri.scheme?.lowercase() in listOf("http", "https")) { "桥地址必须是 http(s)" }
            val token = uri.rawFragment
                ?.split('&')
                ?.firstNotNullOfOrNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) return@firstNotNullOfOrNull null
                    val key = URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8)
                    if (!key.equals("token", ignoreCase = true)) return@firstNotNullOfOrNull null
                    URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8).takeIf { it.isNotBlank() }
                }
            val withoutFragment = URI(
                uri.scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                null,
            ).toString().trimEnd('/')
            return BridgeEndpoint(baseUrl = withoutFragment, accessToken = token)
        }
    }
}
