package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 会话选择器几何判例（票 #160；数字口径按票 #162 评审修正为**本机实测**）：行数上限、底部关闭带
 * 与「屏太矮时退行」的降级——沿 [AgentMirrorParamsTest] / [IconGridTest] 的纯函数判例惯例。
 *
 * 本机基准（小米 17 Pro 背屏 904×572 px、450dpi → density 2.8125）：
 * 阅读视口 y 8..564（上下各 8px 边缘留白）= 556px 可用；行高 48dp = 135px、行距 4dp = 11px。
 */
class AgentPickerParamsTest {

    private val rowPx = 135
    private val gapPx = 11

    /** 关闭带最小高度（票 #160 / #162）：32dp = 90px。 */
    private val minStripPx = 90

    /** 本机浮层可用高度（阅读视口高）。 */
    private val availablePx = 556

    @Test
    fun `列表上限是3行加行距`() {
        assertEquals(3, AgentPickerParams.VISIBLE_ROWS)
        assertEquals(3 * 135 + 2 * 11, AgentPickerParams.listMaxHeightPx(rowPx, gapPx))
        assertEquals(135, AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows = 1))
        assertEquals(0, AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows = 0))
        // 病态输入不抛：负数行数按 0 收口
        assertEquals(0, AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows = -3))
    }

    @Test
    fun `本机展示3行_关闭带约46dp可见`() {
        val rows = AgentPickerParams.visibleRows(availablePx, rowPx, gapPx, minStripPx)
        assertEquals(3, rows)
        val strip = availablePx - AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows)
        // 实测约 129px ≈ 45.9dp：可见、可点（浮层背景整段都是关闭区），但**不到 48dp 触控下限**——
        // 这正是关闭带最小高度取 32dp 而不是 48dp 的原因（写进断言，不再口头夸大）。
        assertEquals(129, strip)
        assertTrue(strip >= minStripPx, "strip=$strip")
        assertTrue(strip < 135, "48dp=135px：本机带达不到触控下限")
        assertEquals(129, AgentPickerParams.dismissStripPx(availablePx, 427))
    }

    @Test
    fun `屏高不够时逐行退让_关闭带不让成0`() {
        // 556px：3 行够用（带 129 ≥ 90）
        assertEquals(3, AgentPickerParams.visibleRows(556, rowPx, gapPx, minStripPx))
        // 500px：3 行只剩 73 < 90 → 退到 2 行（带 217）
        assertEquals(2, AgentPickerParams.visibleRows(500, rowPx, gapPx, minStripPx))
        // 400px：2 行 117 ≥ 90 → 保持 2 行；320px：2 行 37 < 90 → 退到 1 行
        assertEquals(2, AgentPickerParams.visibleRows(400, rowPx, gapPx, minStripPx))
        assertEquals(1, AgentPickerParams.visibleRows(320, rowPx, gapPx, minStripPx))
        // 极矮屏：最少 1 行，不出现 0 行
        assertEquals(1, AgentPickerParams.visibleRows(10, rowPx, gapPx, minStripPx))
    }

    @Test
    fun `关闭带永不为负`() {
        assertEquals(0, AgentPickerParams.dismissStripPx(availableHeightPx = 300, listMaxHeightPx = 427))
        assertEquals(129, AgentPickerParams.dismissStripPx(availableHeightPx = 556, listMaxHeightPx = 427))
    }
}
