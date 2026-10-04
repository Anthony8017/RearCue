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
 * 桥链路分页取件判例（issue #309：手机 bridge-poll OOM 崩进程）：
 * 1. 满页＝还有积压 → 立刻续取，直到取不满才算追平（游标一路推进，不漏事件）；
 * 2. 单页超大响应体 → 按一次失败回落（不把手机内存交给对面决定），链路仍会重试。
 *
 * 跑真 HTTP 环回（JDK 内置 server，与 [BridgeRelayClientSnapshotTest] 同规矩），只断言外部行为。
 */
class BridgeRelayClientPagingTest {

    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop(0) }
    }

    private fun start(server: HttpServer): String {
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** 第 1、2 页各 300 条（满页 = 有积压）→ 第 3 页 1 条（取不满 = 追平）。 */
    private fun pagedBridge(): Pair<String, List<Ip>> {
        val calls = CopyOnWriteArrayList<Ip>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/events") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            calls += Ip(sinceParam(query) ?: -1L, limitParam(query) ?: -1)
            if (calls.size <= FULL_PAGES) {
                val events = (1..PAGE).joinToString(",") { i ->
                    val id = (calls.size - 1) * PAGE + i
                    """{"id":$id,"sessionId":"s-$id","status":"working","updatedAt":$id}"""
                }
                respond(exchange, """{"events":[$events],"cursor":${calls.size * PAGE}}""")
            } else {
                val id = FULL_PAGES * PAGE + 1
                respond(
                    exchange,
                    """{"events":[{"id":$id,"sessionId":"tail","status":"idle","updatedAt":1}],"cursor":$id}""",
                )
            }
        }
        server.createContext("/snapshot") { exchange -> respond(exchange, """{"sessions":[]}""") }
        return start(server) to calls
    }

    @Test
    fun `满页立刻续取直到追平_游标推进不漏事件（issue #309）`() {
        val (url, calls) = pagedBridge()
        val sessions = CopyOnWriteArrayList<String>()
        val tail = CountDownLatch(1)
        val client = BridgeRelayClient(snapshotSettleMs = 0L).apply {
            onSession = { state ->
                sessions += state.sessionId
                if (state.sessionId.contains("tail")) tail.countDown()
            }
        }
        client.start(url)
        try {
            assertTrue(tail.await(15, TimeUnit.SECONDS), "tail 页未到达：收到 ${sessions.size} 条，请求 ${calls.size} 次")
        } finally {
            client.stop()
        }
        // 三页事件一条不漏：两页满页（300×2）＋一页尾页（1），次序即游标次序。
        assertEquals(FULL_PAGES * PAGE + 1, sessions.size)
        assertTrue(sessions.last().contains("tail"), "尾页应最后到：${sessions.last()}")
        assertTrue(sessions.first().contains("s-1"))
        // 每轮按「上一页游标 + 固定 limit」续取——不是复用 since=0，也不是不带 limit 的老口径。
        assertTrue(calls.size >= FULL_PAGES + 1, "满页必须再取一页：$calls")
        assertEquals(0L, calls[0].since)
        assertEquals(PAGE.toLong(), calls[1].since, "第二页从第一页游标续：$calls")
        assertEquals((2 * PAGE).toLong(), calls[2].since, "第三页从第二页游标续：$calls")
        assertTrue(calls.all { it.limit == PAGE }, "每页都带同一 limit：$calls")
    }

    @Test
    fun `单页超大响应体按失败回落_不把手机内存交给对面（issue #309）`() {
        val huge = 13_000_000
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, huge.toLong())
            exchange.responseBody.use { body ->
                val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
                var written = 0
                while (written < huge) {
                    body.write(chunk, 0, minOf(chunk.size, huge - written))
                    written += chunk.size
                }
            }
        }
        server.createContext("/snapshot") { exchange -> respond(exchange, """{"sessions":[]}""") }
        val url = start(server)

        val seen = CopyOnWriteArrayList<BridgeLinkStatus>()
        val retrying = CountDownLatch(1)
        val client = BridgeRelayClient(sleep = { Thread.sleep(20) }, snapshotSettleMs = 0L).apply {
            onStatusChanged = { status ->
                seen += status
                if (status == BridgeLinkStatus.RETRYING) retrying.countDown()
            }
        }
        client.start(url)
        try {
            assertTrue(retrying.await(15, TimeUnit.SECONDS), "超大页未按失败回落：$seen")
        } finally {
            client.stop()
        }
    }

    private data class Ip(val since: Long, val limit: Int)

    private companion object {
        const val PAGE = 300

        /** 满页页数：两页之后才是取不满的尾页（满页＝还有积压，必须立刻续取）。 */
        const val FULL_PAGES = 2

        fun sinceParam(query: String): Long? =
            query.split("&").firstOrNull { it.startsWith("since=") }?.removePrefix("since=")?.toLongOrNull()

        fun limitParam(query: String): Int? =
            query.split("&").firstOrNull { it.startsWith("limit=") }?.removePrefix("limit=")?.toIntOrNull()
    }
}
