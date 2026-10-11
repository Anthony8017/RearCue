package com.rearcue.poc.agent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeRelayClientCredentialTest {
    @Test
    fun `concurrent address refresh cannot mix old base with new token`() {
        val makeServer = { token: String ->
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val body = when {
                        path == "/events" -> """{"events":[],"cursor":0}"""
                        path == "/snapshot" -> """{"sessions":[]}"""
                        path.startsWith("/codex/conversations") -> {
                            val auth = exchange.requestHeaders.getFirst("Authorization")
                            if (auth == "Bearer $token") {
                                """{"ok":true,"receipt":"accepted","threadId":"t"}"""
                            } else {
                                exchange.sendResponseHeaders(401, 0)
                                exchange.responseBody.use { it.write("""{"ok":false,"error":"unauthorized"}""".toByteArray()) }
                                return@createContext
                            }
                        }
                        else -> """{"ok":true}"""
                    }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        }
        val serverA = makeServer("token-a")
        val serverB = makeServer("token-b")
        val baseA = "http://127.0.0.1:${serverA.address.port}"
        val baseB = "http://127.0.0.1:${serverB.address.port}"
        val client = BridgeRelayClient(sleep = { Thread.sleep(1) })
        val credentialFailures = AtomicInteger()
        val completed = CountDownLatch(400)
        try {
            client.start("$baseA#token=token-a")
            repeat(400) { index ->
                client.sendCodexMessage(
                    "bridge:codex:t",
                    CodexRemoteRequest("race-$index", "diagnostic"),
                ) { result ->
                    if (result is CodexRemoteResult.Failed && result.message.contains("凭据")) {
                        credentialFailures.incrementAndGet()
                    }
                    completed.countDown()
                }
                client.start(if (index % 2 == 0) "$baseB#token=token-b" else "$baseA#token=token-a")
            }
            assertTrue(completed.await(20, TimeUnit.SECONDS), "concurrent requests did not complete")
            assertEquals(0, credentialFailures.get(), "old base was paired with a refreshed token")
        } finally {
            client.stop()
            serverA.stop(0)
            serverB.stop(0)
        }
    }

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
