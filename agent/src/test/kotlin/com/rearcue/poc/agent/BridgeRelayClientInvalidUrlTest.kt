package com.rearcue.poc.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * 坏桥地址不得打死轮询线程（真机 2026-10-02 崩溃）：
 * `"[https:"` 曾穿过手填入口校验，随后 OkHttp 在 pollOnce 拼 URL 时抛
 * IllegalArgumentException，daemon 线程未捕获异常直接杀掉 App。
 */
class BridgeRelayClientInvalidUrlTest {

    @Test
    fun `坏地址起链路不触发未捕获线程异常`() {
        val crashed = AtomicReference<Throwable>()
        val crashSeen = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            crashed.compareAndSet(null, throwable)
            crashSeen.countDown()
        }
        try {
            val client = BridgeRelayClient(sleep = { Thread.sleep(20) })
            client.start("[https:")
            assertFalse(
                crashSeen.await(300, TimeUnit.MILLISECONDS),
                "BridgeRelayClient polling thread crashed with ${crashed.get()}",
            )
            client.stop()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}

