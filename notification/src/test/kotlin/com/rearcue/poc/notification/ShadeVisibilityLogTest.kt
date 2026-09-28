package com.rearcue.poc.notification

import kotlin.test.Test
import kotlin.test.assertEquals

class ShadeVisibilityLogTest {

    @Test
    fun `日志锚词形冻结`() {
        assertEquals(
            "shade-visible probe reason=<reason> systemUiVisible=<n|unknown> raw=<n> shown=<n>; " +
                "shade-visible probe shell failed reason=<reason>; " +
                "shade-visible probe fallback reason=<reason>; " +
                "shade-visible probe discarded reason=<reason> source-changed",
            ShadeVisibilityLog.LOG_CONTRACT,
        )
        assertEquals(
            "shade-visible probe reason=posted systemUiVisible=2 raw=3 shown=2",
            ShadeVisibilityLog.probe(reason = "posted", systemUiVisible = 2, raw = 3, shown = 2),
        )
        assertEquals(
            "shade-visible probe reason=posted systemUiVisible=unknown raw=3 shown=3",
            ShadeVisibilityLog.probe(reason = "posted", systemUiVisible = null, raw = 3, shown = 3),
        )
        assertEquals(
            "shade-visible probe shell failed reason=posted",
            ShadeVisibilityLog.shellFailed("posted"),
        )
        assertEquals(
            "shade-visible probe fallback reason=shizuku-down",
            ShadeVisibilityLog.fallback("shizuku-down"),
        )
        assertEquals(
            "shade-visible probe discarded reason=stale source-changed",
            ShadeVisibilityLog.discarded("stale"),
        )
    }
}
