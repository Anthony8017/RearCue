package com.rearcue.poc.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * PC 桥长轮询客户端（ADR 0006 / 票 #116）：对着桥的 `GET /events?since=<cursor>` 做
 * 长轮询，页面解码走 [BridgeEventCodec]，会话事实经 [onSession] 出口给接线层
 * （→ DashboardEvent.AgentSessionUpdated，与 ZCode 源同一事实模型）。
 *
 * 生命周期与 [com.rearcue.poc.agentmirror.AgentRelayClient] 同形：
 * [start] 幂等（同 URL 在跑直接忽略）、[stop] 断开不再重连；连接成功 → [onLinkUp]
 * （仅上升沿一次），请求失败 → [onLinkDown]（零打扰回落）+ [ReconnectPolicy] 指数退避
 * 重连，成功即归零。
 *
 * 线程模型：独立 daemon 轮询线程，全部状态收口在 synchronized 面；回调在轮询线程触发，
 * 调用方自行切线程。HTTP 只读 GET，无凭据（隧道 URL 即地址面；桥无鉴权属已知取舍，
 * 见 ADR 0006——后续可加 token 查询参）。
 */
class BridgeRelayClient(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 长轮询：桥持有 ~25s，读超时留裕量（超时=空页语义之外的失败路径，走重连）。
        .readTimeout(35, TimeUnit.SECONDS)
        .build(),
) {

    private val policy = ReconnectPolicy()

    @Volatile
    private var enabled = false

    @Volatile
    private var baseUrl: String? = null

    @Volatile
    private var running = false

    private var cursor = 0L
    private var linkUpNotified = false

    @Volatile
    var onLinkUp: (() -> Unit)? = null

    @Volatile
    var onLinkDown: (() -> Unit)? = null

    @Volatile
    var onSession: ((AgentSessionState) -> Unit)? = null

    /**
     * 开始维护桥链路：立即起轮询线程。**幂等**——同 URL 且线程在跑直接忽略
     * （重复 start 不另起线程，防轮询风暴；换 URL 先 stop 再 start）。
     */
    fun start(url: String) {
        synchronized(this) {
            if (enabled && baseUrl == url && running) return
            enabled = true
            baseUrl = url.trimEnd('/')
            cursor = 0L
            linkUpNotified = false
            policy.reset()
            if (!running) {
                running = true
                Thread(::pollLoop, "bridge-poll").apply { isDaemon = true }.start()
            }
            log("bridge start url=${baseUrl}")
        }
    }

    /** 停止（开关关/清 URL）：断开、不再重连、报一次失联（若曾上线）。 */
    fun stop() {
        synchronized(this) {
            enabled = false
            baseUrl = null
            val wasUp = linkUpNotified
            linkUpNotified = false
            if (wasUp) onLinkDown?.invoke()
            log("bridge stop")
        }
    }

    /** 桥链路是否在维护（调试页/日志观测面）。 */
    val isActive: Boolean
        get() = enabled

    private fun pollLoop() {
        while (true) {
            // 退出判定与 start 的起线程判定同锁：stop 后立刻 start 的竞态下，
            // 要么本线程看到 enabled=true 继续跑（不另起），要么先置 running=false
            // （start 随后看到 false 再起）——两条路都恰有一条轮询线程。
            synchronized(this) {
                if (!enabled) {
                    running = false
                    return
                }
            }
            val url = baseUrl ?: continue
            val ok = pollOnce(url)
            if (!ok) {
                // 失联收口：先报失联（core 零打扰回落），再按退避排下一轮。
                val wasUp = synchronized(this) { linkUpNotified.also { linkUpNotified = false } }
                if (wasUp) onLinkDown?.invoke()
                if (!enabled) continue
                statusLog("bridge down，退避重连")
                sleep(policy.nextDelayMs())
            } else {
                policy.reset()
            }
        }
    }

    /** 一次长轮询：true = 成功（含空页）；false = 请求/解码失败。 */
    private fun pollOnce(base: String): Boolean {
        val request = Request.Builder().url("$base/events?since=$cursor").get().build()
        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    statusLog("bridge http ${response.code}")
                    return false
                }
                response.body?.string() ?: return false
            }
        } catch (e: Exception) {
            statusLog("bridge 请求失败 ${e.javaClass.simpleName}")
            return false
        }
        val events = BridgeEventCodec.parsePage(body) ?: run {
            statusLog("bridge 页面解析失败（版本漂移？）")
            return false
        }
        // 先报上线再发事实：接线层依赖「连接在线」语义（AgentSessionUpdated 也会自证连接）。
        val notifyUp = synchronized(this) { !linkUpNotified.also { linkUpNotified = true } }
        if (notifyUp) onLinkUp?.invoke()
        events.forEach { event ->
            BridgeEventCodec.toSessionState(event)?.let { state -> onSession?.invoke(state) }
        }
        BridgeEventCodec.parseCursor(body)?.let { cursor = it }
        return true
    }

    private fun statusLog(message: String) {
        log("$message cursor=$cursor")
    }
}
