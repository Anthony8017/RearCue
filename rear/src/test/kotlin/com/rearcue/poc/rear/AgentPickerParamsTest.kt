package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 会话选择器几何判例（票 #160）：列表上限、底部关闭带、以及行高下限——把「3 行 + 带」的
 * 口径钉死在纯函数上（沿 [AgentMirrorParamsTest] / [IconGridTest] 惯例）。
 */
class AgentPickerParamsTest {

    /** 本机背屏折算：48dp 行高 @density 2.8125 ≈ 135px，行距 4dp ≈ 11px。 */
    private val rowPx = 135
    private val gapPx = 11

    @Test
    fun `列表上限是3行加行距`() {
        assertEquals(3 * 135 + 2 * 11, AgentPickerParams.listMaxHeightPx(rowPx, gapPx))
        assertEquals(3, AgentPickerParams.VISIBLE_ROWS)
    }

    @Test
    fun `单行与空列表不比上限高`() {
        assertEquals(135, AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows = 1))
        assertEquals(0, AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows = 0))
        // 病态输入不抛：负数行数按 0 收口
        assertEquals(0, AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows = -3))
    }

    @Test
    fun `底部关闭带等于可用高度减列表上限_不为负`() {
        // 本机阅读视口 ≈ 476px：3 行上限 427px ⇒ 带 ≈ 49px（可见、可点）
        assertEquals(49, AgentPickerParams.dismissStripPx(availableHeightPx = 476, listMaxHeightPx = 427))
        assertTrue(AgentPickerParams.dismissStripPx(476, 427) > 0)
        // 屏太矮/行太高：带为 0，不出现负值（列表照旧滚动，关闭仍可由「再点标识行」完成）
        assertEquals(0, AgentPickerParams.dismissStripPx(availableHeightPx = 300, listMaxHeightPx = 427))
    }
}
