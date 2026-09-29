package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Icon Set 身份对账判例（spec 0015 / 票 #147）：入场/退场只看包名，顺序变化不是出入场；
 * 冷启动首帧不播入场，但空集是有效上一帧。
 */
class IconSetMotionTest {

    @Test
    fun `入场按包名对账且不受顺序影响`() {
        assertEquals(
            setOf("c"),
            IconSetMotion.enteringApps(previous = listOf("a", "b"), current = listOf("c", "b", "a")),
        )
        assertEquals(
            emptySet(),
            IconSetMotion.enteringApps(previous = listOf("a", "b"), current = listOf("b", "a")),
        )
    }

    @Test
    fun `冷启动首帧不播入场 空集后的新图标仍入场`() {
        assertEquals(
            emptySet(),
            IconSetMotion.enteringApps(previous = null, current = listOf("a")),
        )
        assertEquals(
            setOf("a"),
            IconSetMotion.enteringApps(previous = emptyList(), current = listOf("a")),
        )
    }

    @Test
    fun `退场按包名对账`() {
        assertEquals(
            setOf("b"),
            IconSetMotion.exitingApps(previous = listOf("a", "b"), current = listOf("a", "c")),
        )
        assertEquals(
            emptySet(),
            IconSetMotion.exitingApps(previous = null, current = emptyList()),
        )
    }
}
