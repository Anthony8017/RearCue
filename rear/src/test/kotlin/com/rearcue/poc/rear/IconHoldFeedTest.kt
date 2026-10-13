package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IconHoldFeedTest {
    @Test
    fun `全部回执到齐才放开这枚图标`() {
        IconHoldFeed.reset()
        IconHoldFeed.arm("wechat", setOf("k1", "k2"))
        assertTrue(IconHoldFeed.pending("wechat"))
        IconHoldFeed.resolve("k1")
        assertEquals(setOf("wechat"), IconHoldFeed.holding.value)
        IconHoldFeed.resolve("k2")
        assertFalse(IconHoldFeed.pending("wechat"))
        assertEquals(emptySet(), IconHoldFeed.holding.value)
        IconHoldFeed.reset()
    }
}
