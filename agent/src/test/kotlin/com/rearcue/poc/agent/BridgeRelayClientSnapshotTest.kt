package com.rearcue.poc.agent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PC 桥客户端的在册快照对账判例（spec 0016 / 票 #155）：跑真 HTTP 环回（JDK 内置
 * `com.sun.net.httpserver`，不引依赖）——链路（重）连后取一次 `/snapshot`，成功才交出口，
 * 取不到（HTTP 错/页面坏）不交（接线层据此保锁）。
 *
 * 只断言外部行为：请求次数与 [BridgeRelayClient.onSnapshot] 的出口内容。
 */
class BridgeRelayClientSnapshotTest {

    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop(0) }
    }

    /** 起一个环回桥：`/events` 空页即时返回，`/snapshot` 按 [snapshotBody]（null = 500）。 */
    private fun bridge(snapshotBody: String?): Pair<String, () -> Int> {
        val snapshotHits = java.util.concurrent.atomic.AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/events") { exchange ->
            val body = """{"events":[],"cursor":0}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/snapshot") { exchange ->
            snapshotHits.incrementAndGet()
            if (snapshotBody == null) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                return@createContext
            }
            val body = snapshotBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}" to { snapshotHits.get() }
    }

    @Test
    fun `链路状态按真实生命周期上报（票 #165）`() {
        val (url, _) = bridge("""{"sessions":[]}""")
        val seen = CopyOnWriteArrayList<BridgeLinkStatus>()
        val connected = CountDownLatch(1)
        val client = BridgeRelayClient(snapshotSettleMs = 0L).apply {
            onStatusChanged = { status ->
                seen += status
                if (status == BridgeLinkStatus.CONNECTED) connected.countDown()
            }
        }
        client.start(url)
        try {
            assertEquals(BridgeLinkStatus.CONNECTING, seen.firstOrNull())
            assertTrue(connected.await(10, TimeUnit.SECONDS), "未上报已连接：$seen")
        } finally {
            client.stop()
        }
        // 次序：连接中 → 已连接 → 停用（同值不重复上报）
        assertEquals(
            listOf(BridgeLinkStatus.CONNECTING, BridgeLinkStatus.CONNECTED, BridgeLinkStatus.DISABLED),
            seen.toList(),
        )
    }

    @Test
    fun `请求失败上报重连中（票 #165）`() {
        // 保留端口：请求必被拒 ⇒ 重连退避中（sleep 注入成短睡，不真等退避）
        val seen = CopyOnWriteArrayList<BridgeLinkStatus>()
        val retrying = CountDownLatch(1)
        val client = BridgeRelayClient(sleep = { Thread.sleep(20) }, snapshotSettleMs = 0L).apply {
            onStatusChanged = { status ->
                seen += status
                if (status == BridgeLinkStatus.RETRYING) retrying.countDown()
            }
        }
        client.start("http://127.0.0.1:1")
        try {
            assertTrue(retrying.await(10, TimeUnit.SECONDS), "未上报重连中：$seen")
            assertEquals(BridgeLinkStatus.CONNECTING, seen.first())
        } finally {
            client.stop()
        }
    }

    @Test
    fun `重连失败累计跨窗显示降级停用_恢复后自动翻回已连接（票 #187）`() {
        // 环回桥先拒后收：前段 /events 全 500（制造连续失败累计），翻转后空页即时返回（网络恢复）。
        val serving = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/events") { exchange ->
            if (!serving.get()) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                return@createContext
            }
            val body = """{"events":[],"cursor":0}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/snapshot") { exchange ->
            val body = """{"sessions":[]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        servers += server
        val url = "http://127.0.0.1:${server.address.port}"

        val seen = CopyOnWriteArrayList<BridgeLinkStatus>()
        val downgraded = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val client = BridgeRelayClient(
            // 退避快进（每轮失败间隔 ~20ms）＋注入小判停窗（产品节奏 150s，判例加速）
            sleep = { Thread.sleep(20) },
            retryGiveUpMs = 200L,
            snapshotSettleMs = 0L,
        ).apply {
            onStatusChanged = { status ->
                seen += status
                if (status == BridgeLinkStatus.DISABLED) downgraded.countDown()
                if (status == BridgeLinkStatus.CONNECTED) recovered.countDown()
            }
        }
        client.start(url)
        try {
            assertTrue(downgraded.await(10, TimeUnit.SECONDS), "判停窗走完后未降级停用：$seen")
            // RETRYING→DISABLED 边沿：降级发生在重连循环里，且此前确实报过重连中
            assertTrue(
                seen.indexOf(BridgeLinkStatus.RETRYING) in 0 until seen.indexOf(BridgeLinkStatus.DISABLED),
                "降级前未见重连中：$seen",
            )
            // 显示判停不停底层重连：桥恢复应答后自动翻回已连接（US9，无需人工干预）
            serving.set(true)
            assertTrue(recovered.await(10, TimeUnit.SECONDS), "恢复后未自动翻回已连接：$seen")
        } finally {
            client.stop()
        }
        // 全程序列（同值不重复上报保持）：连接中 → 重连中 → 停用（超时判停）→ 已连接（恢复）→ 停用（stop）
        assertEquals(
            listOf(
                BridgeLinkStatus.CONNECTING,
                BridgeLinkStatus.RETRYING,
                BridgeLinkStatus.DISABLED,
                BridgeLinkStatus.CONNECTED,
                BridgeLinkStatus.DISABLED,
            ),
            seen.toList(),
        )
    }

    @Test
    fun `重连中收到停用立即降级_不等判停窗（票 #187）`() {
        // 临终通知走既有「清除桥地址」链（广播 → setBridgeAddress(null) → reconcileBridge → stop()），
        // 本判例钉 agent 侧外部行为：重连中 stop 立即上报 DISABLED——默认判停窗 150s，要等窗就不是「立即」。
        val seen = CopyOnWriteArrayList<BridgeLinkStatus>()
        val retrying = CountDownLatch(1)
        val disabled = CountDownLatch(1)
        val client = BridgeRelayClient(sleep = { Thread.sleep(20) }, snapshotSettleMs = 0L).apply {
            onStatusChanged = { status ->
                seen += status
                if (status == BridgeLinkStatus.RETRYING) retrying.countDown()
                if (status == BridgeLinkStatus.DISABLED) disabled.countDown()
            }
        }
        client.start("http://127.0.0.1:1")
        try {
            assertTrue(retrying.await(10, TimeUnit.SECONDS), "未上报重连中：$seen")
            client.stop()
            assertTrue(disabled.await(2, TimeUnit.SECONDS), "停用未立即上报（不应等判停窗）：$seen")
        } finally {
            client.stop()
        }
        // 停用即终态：DISABLED 之后不再回摆（连接中 → 重连中 → 停用）
        assertEquals(
            listOf(BridgeLinkStatus.CONNECTING, BridgeLinkStatus.RETRYING, BridgeLinkStatus.DISABLED),
            seen.toList(),
        )
    }

    @Test
    fun `链路上线后取一次快照_交出带前缀的在册会话`() {
        val (url, hits) = bridge(
            """{"sessions":[{"sessionId":"codex-1","source":"codex","title":"Bridge title","workspace":"C:/work/repo","status":"working"}]}""",
        )
        val got = CopyOnWriteArrayList<List<AgentSessionState>>()
        val latch = CountDownLatch(1)
        val client = BridgeRelayClient(snapshotSettleMs = 0L).apply {
            onSnapshot = { sessions ->
                got += sessions
                latch.countDown()
            }
        }
        client.start(url)
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "快照未在 10s 内交出口")
            assertEquals(1, got.size)
            assertEquals("bridge:codex-1", got[0][0].sessionId)
            assertEquals(AgentStatus.WORKING, got[0][0].status)
            assertEquals("codex", got[0][0].source)
            assertEquals("Bridge title", got[0][0].title)
            // 空页轮询持续进行，但快照只取一次（每条链路一次对账）
            Thread.sleep(300)
            assertEquals(1, hits())
        } finally {
            client.stop()
        }
    }

    @Test
    fun `快照取不到（HTTP 错）不交出口_下一轮重试`() {
        val (url, hits) = bridge(snapshotBody = null)
        val got = CopyOnWriteArrayList<List<AgentSessionState>>()
        val client = BridgeRelayClient(snapshotSettleMs = 0L).apply { onSnapshot = { got += it } }
        client.start(url)
        try {
            // 轮询很快（空页即时返回）：给重试几次的时间窗，期间一次都不该交出口
            Thread.sleep(500)
            assertTrue(hits() >= 1, "未尝试过快照")
            assertEquals(0, got.size)
        } finally {
            client.stop()
        }
    }
}
