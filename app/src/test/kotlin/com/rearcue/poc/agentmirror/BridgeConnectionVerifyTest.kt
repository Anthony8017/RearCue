package com.rearcue.poc.agentmirror

import java.net.ServerSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeConnectionVerifyTest {
    @Test fun `verification sends credential in header and requires identity contract`() = runBlocking {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val replies = LinkedBlockingQueue<Pair<Int, String>>()
        val requestTarget = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        requestTarget.set(reader.readLine().split(" ")[1])
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            if (line.startsWith("Authorization:", true)) authorization.set(line.substringAfter(':').trim())
                            line = reader.readLine()
                        }
                        val (status, body) = replies.take()
                        val bytes = body.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status Response\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (_: SocketException) { }
        }
        try {
            val address = "http://127.0.0.1:${server.localPort}/#token=secret"
            replies.put(200 to """{"ok":true,"product":"rearcue-bridge","protocol":1}""")
            assertEquals(BridgeAddressProbe.Reachable, BridgeAddressProbeClient.verifyConnection(address))
            assertEquals("/connection/verify", requestTarget.get())
            assertEquals("Bearer secret", authorization.get())
            for (invalid in listOf("ok", "<html>generic site</html>", """{"ok":true,"product":"another-app","protocol":1}""", "x".repeat(2050))) {
                replies.put(200 to invalid)
                assertTrue(BridgeAddressProbeClient.verifyConnection(address) is BridgeAddressProbe.Unreachable)
            }
            replies.put(401 to "unauthorized")
            assertEquals(BridgeAddressProbe.HttpStatus(401), BridgeAddressProbeClient.verifyConnection(address))
            assertEquals(BridgeAddressProbe.BadFormat, BridgeAddressProbeClient.verifyConnection("http://127.0.0.1:${server.localPort}"))
        } finally { server.close(); worker.join(1000) }
    }
}
