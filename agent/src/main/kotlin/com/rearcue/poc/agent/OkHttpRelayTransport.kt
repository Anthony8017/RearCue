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
 * 每条连接一个实例（会话级），**共享一个 [OkHttpClient]**——OkHttp 自带连接池与
 * 调度线程，按连接新建 client 会在长期重连下堆线程；实例只持有自己的 WebSocket。
 *
 * 服务端每 ~10s 发 WS 层 ping，OkHttp 自动回 pong（E1 实测无需应用层干预）。
 */
class OkHttpRelayTransport(
    private val client: OkHttpClient = sharedClient,
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
        val ws = webSocket ?: return
        // 握手未完成时 close() 不生效（返回 false）——不取消就会连上去成僵尸连接。
        if (!ws.close(code, reason)) ws.cancel()
    }

    override fun setListener(listener: RelayTransport.Listener?) {
        this.listener = listener
    }

    companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
