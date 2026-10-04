package com.rearcue.poc.agent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceReplayBoundaryTest {
    @Test
    fun `replayed old requests remain silent while a result after snapshot is live`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val latest = AtomicInteger(1)
        val seen = CopyOnWriteArrayList<AgentSessionState>()
        val snapshot = CountDownLatch(1)
        val live = CountDownLatch(1)
        server.createContext("/events") { exchange ->
            val since = exchange.requestURI.query.split('&').first { it.startsWith("since=") }.substringAfter('=').toInt()
            val id = latest.get()
            val event = if (since < id) {
                if (id == 1) """{"id":1,"sessionId":"s","status":"working","pendingRequests":[{"id":"old","kind":"question","text":"已回答的旧问题"}]}"""
                else """{"id":2,"sessionId":"s","status":"idle","voiceEvent":{"id":"new","kind":"done","text":"新结果","createdAt":2}}"""
            } else ""
            val bytes = """{"events":[$event],"cursor":$id}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/snapshot") { exchange ->
            val bytes = """{"cursor":1,"sessions":[{"sessionId":"s","status":"idle","pendingRequests":[]}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = BridgeRelayClient(sleep = { Thread.sleep(it.coerceAtMost(5L)) }, snapshotSettleMs = 0L).apply {
            onSession = { state -> seen += state; if (state.voiceEvent?.id == "new") live.countDown() }
            onSnapshot = { snapshot.countDown() }
        }
        try {
            client.start("http://127.0.0.1:${server.address.port}")
            assertTrue(snapshot.await(3, TimeUnit.SECONDS), "authoritative speech boundary not fetched")
            assertFalse(seen.first { it.pendingRequests.isNotEmpty() }.voiceEligible, "historical question was eligible for speech")
            latest.set(2)
            assertTrue(live.await(3, TimeUnit.SECONDS), "fresh result not delivered")
            assertTrue(seen.first { it.voiceEvent?.id == "new" }.voiceEligible)
        } finally { client.stop(); server.stop(0) }
    }
}
