package com.rearcue.poc.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShadeVisibilityProbeResultTest {

    @Test
    fun `未知保持未知`() {
        assertNull(ShadeVisibilityProbeResult.normalize(setOf("a"), null))
    }

    @Test
    fun `空可见集合是合法结果`() {
        assertEquals(
            emptySet<String>(),
            ShadeVisibilityProbeResult.normalize(setOf("a"), emptySet()),
        )
    }

    @Test
    fun `完全不相交按未知处理`() {
        assertNull(
            ShadeVisibilityProbeResult.normalize(
                probedKeys = setOf("a", "b"),
                visibleKeys = setOf("x", "y"),
            ),
        )
    }

    @Test
    fun `有交集保留可见集合`() {
        assertEquals(
            setOf("a"),
            ShadeVisibilityProbeResult.normalize(
                probedKeys = setOf("a", "b"),
                visibleKeys = setOf("a"),
            ),
        )
    }

    @Test
    fun `探测时无在册 key 时不误判`() {
        assertEquals(
            setOf("x"),
            ShadeVisibilityProbeResult.normalize(
                probedKeys = emptySet(),
                visibleKeys = setOf("x"),
            ),
        )
    }
}
