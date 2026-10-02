package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 会话选择器几何判例（2026-10-02 收口）：每页 5 个完整条目、无半截、角部避让不改变条数。
 * 行高随可用视口五等分；状态点/选中描边尺寸由渲染常量钉住。
 */
class AgentPickerParamsTest {

    @Test
    fun `每页五个完整条目_两种避让档都不截半行`() {
        assertEquals(5, AgentPickerParams.PAGE_ROWS)
        assertEquals(570, AgentPickerParams.pageHeightPx(572))
        assertEquals(380, AgentPickerParams.pageHeightPx(380))
        assertEquals(114, AgentPickerParams.rowHeightPx(572))
        assertEquals(76, AgentPickerParams.rowHeightPx(380))
    }

    @Test
    fun `超过五条按整页翻_末页不足五条仍完整`() {
        assertEquals(1, AgentPickerParams.pageCount(0))
        assertEquals(1, AgentPickerParams.pageCount(5))
        assertEquals(2, AgentPickerParams.pageCount(6))
        assertEquals(3, AgentPickerParams.pageCount(11))
    }

    @Test
    fun `状态点静止八dp_选中描边一dp`() {
        assertEquals(8, AgentPickerParams.STATUS_DOT_DP)
        assertEquals(1, AgentPickerParams.SELECTED_BORDER_DP)
    }
}
