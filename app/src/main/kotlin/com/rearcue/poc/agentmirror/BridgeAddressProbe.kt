package com.rearcue.poc.agentmirror

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 手填桥地址的当场探测结果（票 #171）：保存前先探一次，把"打错了"和"存下来了但连不上"分开。 */
sealed interface BridgeAddressProbe {
    /** 还没探过（初值）：地址说明行退回来源文案。 */
    data object Idle : BridgeAddressProbe

    /** 正在探（探测中：按钮置灰，避免连点）。 */
    data object Probing : BridgeAddressProbe

    /** 地址被桥认领（/health 200）。 */
    data object Reachable : BridgeAddressProbe

    /** 地址形状就不对（空、没有 scheme、不是 http/https）。 */
    data object BadFormat : BridgeAddressProbe

    /** 地址形状对，但把 HTTP 错误码回上来了（网关 530/404 之类，多半是隧道换了域名）。 */
    data class HttpStatus(val code: Int) : BridgeAddressProbe

    /** 压根没连上（DNS 失败 / 超时 / 网络不可达）。 */
    data class Unreachable(val reason: String) : BridgeAddressProbe
}

/**
 * 桥地址当场探测（票 #171）：手填后立刻打一次 `GET <url>/health`——
 * 手打一长串随机域名很容易错一位，而链路失败信号要等好几秒才在状态点上显形，排查成本高。
 *
 * 判定与桥的其它接口同口径：HTTP 200 = 桥在；非 200 说明走到了隧道但桥没应（域名过期/隧道掉）。
 * 只发一次、超时 5 秒，不做重试——它是"保存前的一次确认"，不是链路探活（那是客户端的事）。
 */
object BridgeAddressProbeClient {

    private const val TIMEOUT_MS = 5_000

    /** 归一化用户输入：补 scheme、去空白与结尾斜杠；形状不对返回 null。 */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // 无 scheme 的裸域名/host:port：按 https 补（隧道地址全是 https；本机调试可显式写 http://）
            else -> "https://$trimmed"
        }
        val host = withScheme.substringAfter("://").substringBefore('/')
        return if (host.isBlank() || host.contains(' ')) null else withScheme
    }

    /** 探一次；[normalized] 必须是 [normalize] 的产物。 */
    suspend fun probe(normalized: String): BridgeAddressProbe = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$normalized/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                BridgeAddressProbe.Reachable
            } else {
                BridgeAddressProbe.HttpStatus(code)
            }
        } catch (e: IOException) {
            BridgeAddressProbe.Unreachable(e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            BridgeAddressProbe.BadFormat
        } finally {
            connection?.disconnect()
        }
    }
}
