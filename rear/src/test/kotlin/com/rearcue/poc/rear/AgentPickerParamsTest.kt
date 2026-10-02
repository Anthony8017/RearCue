package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 会话选择器几何判例（2026-10-02 收口）：同屏 5 个完整条目、无半截、角部避让不改变条数。
 * 超出同屏容量后按单条边界滚动吸附。
 */
class AgentPickerParamsTest {

    @Test
    fun `同屏五个完整条目_两种避让档都不截半行`() {
        assertEquals(5, AgentPickerParams.PAGE_ROWS)
        assertEquals(570, AgentPickerParams.visibleHeightPx(572))
        assertEquals(380, AgentPickerParams.visibleHeightPx(380))
        assertEquals(114, AgentPickerParams.rowHeightPx(572))
        assertEquals(76, AgentPickerParams.rowHeightPx(380))
    }

    @Test
    fun `状态点静止八dp_选中描边一dp`() {
        assertEquals(8, AgentPickerParams.STATUS_DOT_DP)
        assertEquals(1, AgentPickerParams.SELECTED_BORDER_DP)
    }
}
