package com.rearcue.poc.tile

import com.rearcue.poc.rear.Presence
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Quick Tile Entry 决策测试（spec 0006 / 票 #54）：tile 状态与点击语义同源——
 * Presence 不在 Absent 即「激活态 + 点击退出」，否则「停用态 + 点击投送」。
 */
class TilePolicyTest {

    @Test
    fun `Absent 时点击是投送语义（非退出）`() {
        assertFalse(TilePolicy.tapExits(Presence.ABSENT))
    }

    @Test
    fun `LaunchPending 时点击是退出语义（在途也算有界面在屏）`() {
        assertTrue(TilePolicy.tapExits(Presence.LAUNCH_PENDING))
    }

    @Test
    fun `OnScreen 时点击是退出语义`() {
        assertTrue(TilePolicy.tapExits(Presence.ON_SCREEN))
    }
}
