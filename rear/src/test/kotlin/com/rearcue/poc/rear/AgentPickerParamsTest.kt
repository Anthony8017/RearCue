package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 会话选择器几何判例（spec 0020 二次修订 / 票 #211）：机主推翻「最多完整展示 5 行」上限改**平铺**
 * ——列表高度上限＝可用视口高（渲染层直接取用，见 [AgentPickerLayer]）、行数无上限，末行可被
 * 屏缘截半行；`VISIBLE_ROWS` / `visibleRows()` / `listMaxHeightPx()` 随定夺删除（判例沿革见
 * git 历史）。issue #213 两行式只改条目内容高度，平铺/截半行/点外关闭不变；本判例钉住新的两行高。
 */
class AgentPickerParamsTest {

    @Test
    fun `两行条目高44dp——平铺几何不随行内容改写`() {
        assertEquals(44, AgentPickerParams.ROW_HEIGHT_DP)
    }
}
