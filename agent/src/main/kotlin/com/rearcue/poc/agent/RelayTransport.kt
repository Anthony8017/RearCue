package com.rearcue.poc.agent

/**
 * WebSocket 传输抽象：协议层（握手/帧编解码）不绑定 OkHttp，测试用假传输。
 * 传输失败重试是 [ReconnectPolicy] 的决策；本接口只搬字节。
 */
interface RelayTransport {
    fun connect(endpoint: String, headers: Map<String, String>)
    fun send(text: String)
    fun close(code: Int, reason: String)
    fun setListener(listener: Listener?)

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: Throwable)
    }
}

/** 连接相关常量（E1 固化）。 */
object RelayConstants {
    /** 生产中继；备用 chatglm.site 端点本机网络不可达（DNS 污染），不配置。 */
    const val DEFAULT_ENDPOINT = "wss://zcode.z.ai/ws"

    /** 应用层角色白名单实测只有 device / terminal；RearCue 走官方手机端同款 terminal 路径。 */
    const val ROLE_TERMINAL = "terminal"
}
