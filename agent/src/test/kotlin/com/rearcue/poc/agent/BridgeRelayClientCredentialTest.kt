package com.rearcue.poc.agent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeRelayClientCredentialTest {
    @Test
    fun `same bridge address accepts refreshed credentials for conversation writes`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/events") { exchange ->
            val body = """{"events":[],"cursor":0}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/snapshot") { exchange ->
            val body = """{"sessions":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val authorization = AtomicReference<String>()
        server.createContext("/codex/conversations") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = """{"ok":true,"receipt":"accepted","threadId":"new-thread"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = BridgeRelayClient(sleep = { Thread.sleep(10) })
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            client.start(base)
            client.start("$base#token=refreshed")
            val completed = CountDownLatch(1)
            client.startCodexConversation(CodexRemoteRequest("r1", "test prompt", projectId = "p1")) {
                completed.countDown()
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS), "creation request did not complete")
            assertEquals("Bearer refreshed", authorization.get())
        } finally {
            client.stop()
            server.stop(0)
        }
    }
}
