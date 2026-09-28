package com.rearcue.poc.rear

import com.rearcue.poc.rear.MirrorScrollPolicy.Follow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 实时滚动跟随状态机测试（grilling #115）：上滑打断回看、滚到底/按钮恢复、
 * 新输出仅在跟随态滚底（回看不打断）。只断言纯函数的事件 → 状态转移。
 */
class MirrorScrollPolicyTest {

    @Test
    fun `上滑（滚动值减小）打断跟随进入回看`() {
        assertEquals(Follow.PAUSED, MirrorScrollPolicy.onValueChange(Follow.FOLLOWING, prev = 100, next = 60, maxValue = 500))
        assertEquals(Follow.PAUSED, MirrorScrollPolicy.onValueChange(Follow.PAUSED, prev = 60, next = 20, maxValue = 500))
    }

    @Test
    fun `手动滚到底（增大且触底）恢复跟随`() {
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onValueChange(Follow.PAUSED, prev = 420, next = 500, maxValue = 500))
    }

    @Test
    fun `向前滚动未触底保持回看`() {
        assertEquals(Follow.PAUSED, MirrorScrollPolicy.onValueChange(Follow.PAUSED, prev = 20, next = 80, maxValue = 500))
    }

    @Test
    fun `程序化滚底（跟随态增大触底）不改变状态`() {
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onValueChange(Follow.FOLLOWING, prev = 400, next = 500, maxValue = 500))
    }

    @Test
    fun `无滚动空间（max=0）时不因零值变化打断跟随`() {
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onValueChange(Follow.FOLLOWING, prev = 0, next = 0, maxValue = 0))
    }

    @Test
    fun `恢复按钮点按回跟随`() {
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onResumeTap())
    }

    @Test
    fun `新输出仅跟随态滚底_回看态不打断`() {
        assertTrue(MirrorScrollPolicy.shouldFollowNewOutput(Follow.FOLLOWING))
        assertFalse(MirrorScrollPolicy.shouldFollowNewOutput(Follow.PAUSED))
    }
}
