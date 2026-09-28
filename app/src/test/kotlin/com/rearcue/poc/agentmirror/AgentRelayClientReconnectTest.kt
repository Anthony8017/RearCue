package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.PairingLink
import com.rearcue.poc.agent.RelayTransport
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 重连生命周期判例（#106 验收遗留：验收末段中继进入持续 `RECONNECTING` 循环）。
 *
 * 假中继固化两条真中继实证规则（`docs/poc-logs/20260927-86-agent-mirror-acceptance.md`）：
 * - **一机限制、后到踢先到**：同会话第二条终端连接静默踢掉第一条（无 close 帧，传输故障）；
 * - 首连认证可长期不落地（`pair_status=waiting`，45s 等待窗后超时）。
 *
 * 被测不变量：
 * 1. 客户端任意时刻至多一条活连接（认证超时后重连不得把旧连接留成僵尸）；
 * 2. 在「后到踢先到」规则下重连能收敛到稳定 `CONNECTED`，不得持续 churn。
 */
class AgentRelayClientReconnectTest {

    /** OkHttpRelayTransport 的同构假体：单实例可多次 [connect]（不自动关旧 socket），事件投给**当前** listener。 */
    private class FakeTransport(private val server: FakeRelayServer) : RelayTransport {
        @Volatile
        private var listener: RelayTransport.Listener? = null

        @Volatile
        private var current: FakeSocket? = null

        override fun connect(endpoint: String, headers: Map<String, String>) {
            current = server.newSocket(this)
            listener?.onOpen()
        }

        override fun send(text: String) {
            val socket = current ?: return
            server.onClientText(socket, text)
        }

        override fun close(code: Int, reason: String) {
            current?.clientClosed(code, reason)
        }

        override fun setListener(listener: RelayTransport.Listener?) {
            this.listener = listener
        }

        fun deliverText(text: String) {
            listener?.onText(text)
        }

        fun deliverClosed(code: Int, reason: String) {
            listener?.onClosed(code, reason)
        }

        fun deliverFailure(error: IOException) {
            listener?.onFailure(error)
        }
    }

    /** 服务端视角的一条 socket。 */
    private class FakeSocket(
        val transport: FakeTransport,
        val answerAuth: Boolean,
    ) {
        @Volatile
        var open = false

        fun peerKicked() {
            if (!open) return
            open = false
            transport.deliverFailure(IOException("kicked by newer connection (no close frame)"))
        }

        fun clientClosed(code: Int, reason: String) {
            if (!open) return
            open = false
            transport.deliverClosed(code, reason)
        }

        fun deliver(text: String) {
            transport.deliverText(text)
        }
    }

    /** 假中继：可配置「后到踢先到」与首连认证静默；统计并发存活连接数。 */
    private class FakeRelayServer(
        private val silentAuthOnFirstSocket: Boolean,
        private val kickOlderOnNew: Boolean,
    ) {
        val connectCount = AtomicInteger()
        val maxConcurrentlyLive = AtomicInteger()
        private val live = CopyOnWriteArrayList<FakeSocket>()

        fun newSocket(transport: FakeTransport): FakeSocket {
            val index = connectCount.incrementAndGet()
            if (kickOlderOnNew) live.filter { it.open }.forEach { it.peerKicked() }
            val socket = FakeSocket(transport, answerAuth = !(silentAuthOnFirstSocket && index == 1))
            live += socket
            socket.open = true
            val concurrent = live.count { it.open }
            maxConcurrentlyLive.updateAndGet { cur -> maxOf(cur, concurrent) }
            return socket
        }

        fun onClientText(socket: FakeSocket, text: String) {
            if (!text.contains("auth_init")) return
            if (!socket.answerAuth) return // fresh-waiting：不回 matched，认证只能等到超时
            socket.deliver("""{"type":"auth_ack","pair_status":"matched","terminal_sid":"term-1"}""")
        }
    }

    private val link = PairingLink.parse("https://zcode.z.ai/remote/v4?sid=sid-1&hash=hash-1")

    private fun awaitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        assertTrue(cond(), "超时未达成：$what")
    }

    @Test
    fun `认证超时后重连不留僵尸连接（任意时刻至多一条活连接）`() {
        val server = FakeRelayServer(silentAuthOnFirstSocket = true, kickOlderOnNew = false)
        val statuses = CopyOnWriteArrayList<AgentLinkStatus>()
        val client = AgentRelayClient(
            sleep = { },
            transportFactory = { FakeTransport(server) },
            authTimeoutMs = 50,
        ).apply { onStatusChanged = { statuses += it } }

        client.start(link)
        awaitUntil(3_000, "首连认证超时 → 重连后 CONNECTED") {
            statuses.contains(AgentLinkStatus.CONNECTED)
        }
        Thread.sleep(150) // 留出窗口暴露僵尸连接
        assertTrue(
            server.maxConcurrentlyLive.get() <= 1,
            "同时存活的连接数=${server.maxConcurrentlyLive.get()}（>1 即旧连接未随重连销毁）",
        )
        client.stop()
    }

    @Test
    fun `后到踢先到规则下重连应收敛到稳定 CONNECTED`() {
        val server = FakeRelayServer(silentAuthOnFirstSocket = true, kickOlderOnNew = true)
        val statuses = CopyOnWriteArrayList<AgentLinkStatus>()
        val client = AgentRelayClient(
            sleep = { },
            transportFactory = { FakeTransport(server) },
            authTimeoutMs = 50,
        ).apply { onStatusChanged = { statuses += it } }

        client.start(link)
        awaitUntil(3_000, "重连后 CONNECTED") {
            statuses.contains(AgentLinkStatus.CONNECTED) && server.connectCount.get() >= 2
        }
        Thread.sleep(200) // 收敛窗：稳定后不得再建连
        val settled = server.connectCount.get()
        Thread.sleep(300)
        assertTrue(
            server.connectCount.get() == settled,
            "连接数仍在增长：$settled → ${server.connectCount.get()}（持续 RECONNECTING 循环）",
        )
        assertTrue(statuses.last() == AgentLinkStatus.CONNECTED, "末态=${statuses.last()}")
        client.stop()
    }

    @Test
    fun `退避等待中再次 start 不得多连一条（世代闸）`() {
        val server = FakeRelayServer(silentAuthOnFirstSocket = true, kickOlderOnNew = false)
        val statuses = CopyOnWriteArrayList<AgentLinkStatus>()
        val gate = java.util.concurrent.CountDownLatch(1)
        val client = AgentRelayClient(
            sleep = { gate.await() },
            transportFactory = { FakeTransport(server) },
            authTimeoutMs = 50,
        ).apply { onStatusChanged = { statuses += it } }

        client.start(link) // 首连认证静默 → 50ms 超时 → 退避线程卡在 gate 上
        awaitUntil(3_000, "首连认证超时 → RECONNECTING") {
            statuses.contains(AgentLinkStatus.RECONNECTING)
        }
        client.start(link) // 重入：立即另起一条（旧退避线程尚未醒来）
        awaitUntil(3_000, "重入 start 后 CONNECTED") {
            statuses.contains(AgentLinkStatus.CONNECTED)
        }
        gate.countDown() // 放行退避线程
        Thread.sleep(200)
        assertTrue(
            server.maxConcurrentlyLive.get() <= 1,
            "同时存活的连接数=${server.maxConcurrentlyLive.get()}（退避线程醒来后又连了一条）",
        )
        assertTrue(server.connectCount.get() == 2, "建连次数=${server.connectCount.get()}（应为 2）")
        client.stop()
    }
}
