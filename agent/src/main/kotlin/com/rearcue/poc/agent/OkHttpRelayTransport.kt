package com.rearcue.poc.agent

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * [RelayTransport] 的 OkHttp WebSocket 实现（薄胶水，沿仓库惯例不写 JVM 测试；
 * 协议行为全部在纯层测）。
 *
 * 服务端每 ~10s 发 WS 层 ping，OkHttp 自动回 pong（E1 实测无需应用层干预）。
 */
class OkHttpRelayTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build(),
) : RelayTransport {

    private var webSocket: WebSocket? = null
    private var listener: RelayTransport.Listener? = null

    override fun connect(endpoint: String, headers: Map<String, String>) {
        val builder = Request.Builder().url(endpoint)
        for ((name, value) in headers) builder.header(name, value)
        webSocket = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                listener?.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener?.onText(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener?.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                listener?.onFailure(t)
            }
        })
    }

    override fun send(text: String) {
        webSocket?.send(text)
    }

    override fun close(code: Int, reason: String) {
        webSocket?.close(code, reason)
    }

    override fun setListener(listener: RelayTransport.Listener?) {
        this.listener = listener
    }
}
