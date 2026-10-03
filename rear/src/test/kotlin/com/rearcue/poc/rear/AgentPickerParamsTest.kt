package com.rearcue.poc.rear

import com.rearcue.poc.core.MirrorTextSize
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
    fun `会话选择器字号不受会话页标题正文角色对换影响`() {
        assertEquals(14f, AgentPickerParams.typography(MirrorTextSize.SMALL).titleSp)
        assertEquals(10f, AgentPickerParams.typography(MirrorTextSize.SMALL).subtitleSp)
        assertEquals(16f, AgentPickerParams.typography(MirrorTextSize.MEDIUM).titleSp)
        assertEquals(11f, AgentPickerParams.typography(MirrorTextSize.MEDIUM).subtitleSp)
        assertEquals(20f, AgentPickerParams.typography(MirrorTextSize.LARGE).titleSp)
        assertEquals(13f, AgentPickerParams.typography(MirrorTextSize.LARGE).subtitleSp)
    }

    @Test
    fun `状态标识槽位八dp`() {
        assertEquals(8, AgentPickerParams.STATUS_INDICATOR_DP)
    }
}
