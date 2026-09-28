package com.rearcue.poc.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShadeVisibilityProbeSchedulerTest {

    @Test
    fun `防抖期间到来的请求合并为一次尾随补跑`() = runTest {
        val scheduler = ShadeVisibilityProbeScheduler(debounceMs = 200L, timeoutMs = 1_000L)
        val events = mutableListOf<String>()
        val first = scheduler.request("first")!!
        launch {
            scheduler.run(
                initialReason = first,
                snapshot = { setOf("a") },
                probe = { reason, _ ->
                    events += "probe:$reason"
                    setOf("a")
                },
                apply = { reason, _, _ -> events += "apply:$reason" },
            )
        }

        advanceTimeBy(100L)
        assertNull(scheduler.request("second"))
        advanceUntilIdle()

        assertEquals(
            listOf("probe:first", "apply:first", "probe:queued", "apply:queued"),
            events,
        )
        assertEquals("next", scheduler.request("next"))
    }

    @Test
    fun `探测超时按未知返回并允许后续请求继续`() = runTest {
        val scheduler = ShadeVisibilityProbeScheduler(debounceMs = 10L, timeoutMs = 100L)
        val applied = mutableListOf<Set<String>?>()
        var calls = 0
        val first = scheduler.request("first")!!
        launch {
            scheduler.run(
                initialReason = first,
                snapshot = { setOf("raw") },
                probe = { _, _ ->
                    calls++
                    delay(10_000L)
                    setOf("late")
                },
                apply = { _, _, visible -> applied += visible },
            )
        }

        advanceTimeBy(110L)
        advanceUntilIdle()
        assertEquals(listOf<Set<String>?>(null), applied)
        assertEquals(1, calls)

        val second = scheduler.request("second")
        assertEquals("second", second)
        launch {
            scheduler.run(
                initialReason = second!!,
                snapshot = { setOf("raw") },
                probe = { _, _ ->
                    calls++
                    setOf("raw")
                },
                apply = { _, _, visible -> applied += visible },
            )
        }
        advanceUntilIdle()

        assertEquals(listOf<Set<String>?>(null, setOf("raw")), applied)
        assertEquals(2, calls)
    }

    @Test
    fun `超时期间到达的请求在超时后补跑`() = runTest {
        val scheduler = ShadeVisibilityProbeScheduler(debounceMs = 10L, timeoutMs = 100L)
        val appliedReasons = mutableListOf<String>()
        var calls = 0
        val first = scheduler.request("first")!!
        launch {
            scheduler.run(
                initialReason = first,
                snapshot = { setOf("raw") },
                probe = { reason, _ ->
                    calls++
                    if (reason == "first") delay(10_000L)
                    setOf("raw")
                },
                apply = { reason, _, _ -> appliedReasons += reason },
            )
        }

        advanceTimeBy(11L)
        assertNull(scheduler.request("second"))
        advanceTimeBy(100L)
        advanceUntilIdle()

        assertEquals(listOf("first", "queued"), appliedReasons)
        assertEquals(2, calls)
    }

    @Test
    fun `探测异常退出后后续请求仍能启动`() = runTest {
        val scheduler = ShadeVisibilityProbeScheduler(debounceMs = 1L, timeoutMs = 1_000L)
        val first = scheduler.request("boom")!!

        val result = runCatching {
            scheduler.run(
                initialReason = first,
                snapshot = { emptySet() },
                probe = { _, _ -> error("boom") },
                apply = { _, _, _ -> },
            )
        }

        assertTrue(result.isFailure)
        assertEquals("next", scheduler.request("next"))
    }
}
