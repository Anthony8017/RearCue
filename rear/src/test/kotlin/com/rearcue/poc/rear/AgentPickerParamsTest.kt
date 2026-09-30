package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 会话选择器几何判例（spec 0020 / 票 #204）：28dp 密排行高、最多 5 行、矮屏逐行退让到 1 行下限
 * ——沿 [AgentMirrorParamsTest] / [IconGridTest] 的纯函数判例惯例。
 *
 * 本机基准（小米 17 Pro 背屏 904×572 px、450dpi → density 2.8125）：
 * 可用高度由调用方喂**贴缘视口**（spec 0019：`flushReadingViewport`）——贴满档整高 572px
 * （角部避让开档上下各让 97 → 378px）；行高 28dp = 79px（78.75 取整）、行距 4dp = 11px。
 * 本类的 556px 是旧 8px 边距口径的纯函数判例输入，函数语义与其无关、保留不动。
 */
class AgentPickerParamsTest {

    private val rowPx = 79
    private val gapPx = 11

    /** 本机浮层可用高度（阅读视口高）。 */
    private val availablePx = 556

    @Test
    fun `列表上限是5行加行距`() {
        assertEquals(5, AgentPickerParams.VISIBLE_ROWS)
        assertEquals(28, AgentPickerParams.ROW_HEIGHT_DP)
        assertEquals(5 * 79 + 4 * 11, AgentPickerParams.listMaxHeightPx(rowPx, gapPx))
        assertEquals(79, AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows = 1))
        assertEquals(0, AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows = 0))
        // 病态输入不抛：负数行数按 0 收口
        assertEquals(0, AgentPickerParams.listMaxHeightPx(rowPx, gapPx, rows = -3))
    }

    @Test
    fun `常规屏高容纳5行`() {
        // 572px（贴满档整高）：5 行 439px 放得下
        assertEquals(5, AgentPickerParams.visibleRows(572, rowPx, gapPx))
        // 556px（旧判例输入）：同样容 5 行——旧口径 3 行 + 关闭带 129px，现在不再为带让行
        assertEquals(5, AgentPickerParams.visibleRows(availablePx, rowPx, gapPx))
        // 正好整除边界：439px 恰好装满 5 行（79 + 4×90）
        assertEquals(5, AgentPickerParams.visibleRows(439, rowPx, gapPx))
        // 差 1px 就退一行
        assertEquals(4, AgentPickerParams.visibleRows(438, rowPx, gapPx))
    }

    @Test
    fun `矮屏逐行退让_最少1行`() {
        // 378px（角部避让档）：4 行 349px 放得下、5 行 439px 放不下
        assertEquals(4, AgentPickerParams.visibleRows(378, rowPx, gapPx))
        assertEquals(3, AgentPickerParams.visibleRows(300, rowPx, gapPx))
        // 169px 恰好装下 2 行（79 + 90）；再矮只剩 1 行
        assertEquals(2, AgentPickerParams.visibleRows(169, rowPx, gapPx))
        assertEquals(1, AgentPickerParams.visibleRows(168, rowPx, gapPx))
        // 极矮屏：最少 1 行，不出现 0 行；0 高、负值同样兜到 1
        assertEquals(1, AgentPickerParams.visibleRows(10, rowPx, gapPx))
        assertEquals(1, AgentPickerParams.visibleRows(0, rowPx, gapPx))
        assertEquals(1, AgentPickerParams.visibleRows(-572, rowPx, gapPx))
    }

    @Test
    fun `wanted夹在1到5`() {
        assertEquals(5, AgentPickerParams.visibleRows(572, rowPx, gapPx, wanted = 99))
        assertEquals(2, AgentPickerParams.visibleRows(572, rowPx, gapPx, wanted = 2))
        assertEquals(1, AgentPickerParams.visibleRows(572, rowPx, gapPx, wanted = 0))
        assertEquals(1, AgentPickerParams.visibleRows(572, rowPx, gapPx, wanted = -3))
    }

    @Test
    fun `病态输入容错`() {
        // 行高 + 行距为 0：任意行数都放得下，按 wanted 收口（负值夹回下限 1）
        assertEquals(5, AgentPickerParams.visibleRows(0, rowHeightPx = 0, gapPx = 0))
        assertEquals(1, AgentPickerParams.visibleRows(0, rowHeightPx = 0, gapPx = 0, wanted = 0))
        // 负行高/负行距按 0 收口后同上
        assertEquals(5, AgentPickerParams.visibleRows(572, rowHeightPx = -79, gapPx = -11))
        // 行高 0、行距正常：行数只受 wanted 上限约束
        assertEquals(5, AgentPickerParams.visibleRows(556, rowHeightPx = 0, gapPx = gapPx))
    }
}
