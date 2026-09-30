package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 会话选择器几何判例（spec 0020 二次修订 / 票 #211）：机主推翻「最多完整展示 5 行」上限改**平铺**
 * ——列表高度上限＝可用视口高（渲染层直接取用，见 [AgentPickerLayer]）、行数无上限，末行可被
 * 屏缘截半行；`VISIBLE_ROWS` / `visibleRows()` / `listMaxHeightPx()` 随定夺删除（判例沿革见
 * git 历史）。二次修订明示**不动**的口径只剩密排行高——本判例把它钉住：改 28dp 须带着本条一起改。
 */
class AgentPickerParamsTest {

    @Test
    fun `密排行高28dp——二次修订只翻行数上限`() {
        assertEquals(28, AgentPickerParams.ROW_HEIGHT_DP)
    }
}
