package com.rearcue.poc.agent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeRelayClientOptionsTest {
    @Test
    fun `options query identifies the selected project and actual model without losing provider prefixes`() {
        val query = AtomicReference<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/codex/options" -> {
                    query.set(exchange.requestURI.rawQuery.split("&").associate {
                        val pair = it.split("=", limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
                    })
                    """{"ok":true,"projects":[],"models":[]}"""
                }
                "/events" -> """{"events":[],"cursor":0}"""
                else -> """{"sessions":[]}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = BridgeRelayClient(sleep = { Thread.sleep(10) })
        try {
            client.start("http://127.0.0.1:${server.address.port}")
            val done = CountDownLatch(1)
            client.fetchCodexOptions("project +&一", "provider/model +&") { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS), "options callback missing")
            assertEquals(mapOf("projectId" to "project +&一", "model" to "provider/model +&"), query.get())
        } finally {
            client.stop()
            server.stop(0)
        }
    }
}
