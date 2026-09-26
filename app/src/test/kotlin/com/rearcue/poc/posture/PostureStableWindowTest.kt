package com.rearcue.poc.posture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 姿态稳定窗行为测试（spec 0006 / 票 #53）：投与撤两个方向对称防抖，
 * 时间戳由测试注入，不碰任何 Android 框架。
 */
class PostureStableWindowTest {

    private val window = PostureStableWindow(stableMs = 800)

    @Test
    fun `首读需持续过窗才提交`() {
        assertNull(window.onReading(near = true, nowMs = 0))
        assertNull(window.onReading(near = true, nowMs = 799))
        assertEquals(true, window.onReading(near = true, nowMs = 800))
    }

    @Test
    fun `窗内抖动不提交`() {
        assertNull(window.onReading(near = true, nowMs = 0))
        assertNull(window.onReading(near = false, nowMs = 200))
        assertNull(window.onReading(near = true, nowMs = 400))
        assertNull(window.onReading(near = false, nowMs = 600))
        assertNull(window.onReading(near = true, nowMs = 800)) // 距上次同向起点 400ms，仍在窗内
    }

    @Test
    fun `提交后反向需再过窗（撤也防抖）`() {
        window.onReading(near = true, nowMs = 0)
        assertEquals(true, window.onReading(near = true, nowMs = 800))
        assertNull(window.onReading(near = false, nowMs = 900))
        assertNull(window.onReading(near = false, nowMs = 1699))
        assertEquals(false, window.onReading(near = false, nowMs = 1700))
    }

    @Test
    fun `反向窗内抖回不提交（倒扣中拿起晃动不撤）`() {
        window.onReading(near = true, nowMs = 0)
        assertEquals(true, window.onReading(near = true, nowMs = 800))
        assertNull(window.onReading(near = false, nowMs = 1000)) // 短暂拿远
        assertNull(window.onReading(near = true, nowMs = 1300)) // 抖回倒扣
        assertNull(window.onReading(near = true, nowMs = 2100)) // 提交值仍是倒扣，无新事件
    }

    @Test
    fun `同值重复读无事件`() {
        window.onReading(near = false, nowMs = 0)
        assertEquals(false, window.onReading(near = false, nowMs = 800))
        assertNull(window.onReading(near = false, nowMs = 900))
        assertNull(window.onReading(near = false, nowMs = 5000))
    }

    @Test
    fun `持续反向最终会提交（真翻正不丢）`() {
        window.onReading(near = true, nowMs = 0)
        assertEquals(true, window.onReading(near = true, nowMs = 800))
        // 一次 400ms 的拿起抖动后回到倒扣，随后真正翻正并保持
        assertNull(window.onReading(near = false, nowMs = 1000))
        assertNull(window.onReading(near = true, nowMs = 1300))
        assertNull(window.onReading(near = false, nowMs = 2000))
        assertEquals(false, window.onReading(near = false, nowMs = 2800))
    }
}
