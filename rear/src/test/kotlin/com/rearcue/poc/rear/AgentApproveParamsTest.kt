package com.rearcue.poc.rear

import com.rearcue.poc.agent.SessionActionKind
import com.rearcue.poc.rear.AgentApproveParams.Decision
import com.rearcue.poc.rear.AgentApproveParams.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 背屏批准二次确认浮层判例（spec 0018-5 / 票 #175 AC2）：几何数值锁死＋生效判定纯函数，
 * 沿 [AgentPickerParamsTest] / [AgentMirrorParamsTest] 惯例——只测外部行为（坐标进→矩形/判定出）。
 */
class AgentApproveParamsTest {

    // ---------- 确认类几何：两个大按钮水平二等分、视口垂直居中 ----------

    @Test
    fun `确认类两按钮二等分且垂直居中_几何数值锁死`() {
        val layout = AgentApproveParams.confirmLayout(
            viewportLeft = 304, viewportTop = 80,
            viewportWidth = 500, viewportHeight = 400,
            buttonHeightPx = 128, buttonGapPx = 32,
        )
        assertEquals(AgentApproveParams.Rect(304, 216, 234, 128), layout.yes)
        assertEquals(AgentApproveParams.Rect(570, 216, 234, 128), layout.no)
        assertTrue(layout.options.isEmpty())
    }

    @Test
    fun `确认类按钮不越视口`() {
        val layout = AgentApproveParams.confirmLayout(
            viewportLeft = 0, viewportTop = 0,
            viewportWidth = 500, viewportHeight = 400,
            buttonHeightPx = 128, buttonGapPx = 32,
        )
        assertEquals(layout.no.left + layout.no.width, 500)
        assertTrue(layout.yes.top >= 0)
        assertTrue(layout.yes.top + layout.yes.height <= 400)
    }

    @Test
    fun `确认类退化输入_不炸且零尺寸`() {
        val layout = AgentApproveParams.confirmLayout(
            viewportLeft = 10, viewportTop = 10,
            viewportWidth = 0, viewportHeight = 0,
            buttonHeightPx = 128, buttonGapPx = 32,
        )
        assertEquals(0, layout.yes.width)
        assertEquals(0, layout.no.width)
        assertEquals(Target.Outside, layout.hit(10, 10))
    }

    // ---------- 提问类几何：选项行等宽、行块垂直居中、挤不下收缩 ----------

    @Test
    fun `提问类三选项_行块垂直居中_几何数值锁死`() {
        val layout = AgentApproveParams.optionLayout(
            viewportLeft = 304, viewportTop = 80,
            viewportWidth = 500, viewportHeight = 400,
            optionIds = listOf("a", "b", "c"),
            rowHeightPx = 96, rowGapPx = 16, minRowHeightPx = 32,
        )
        assertEquals(
            listOf(
                "a" to AgentApproveParams.Rect(304, 120, 500, 96),
                "b" to AgentApproveParams.Rect(304, 232, 500, 96),
                "c" to AgentApproveParams.Rect(304, 344, 500, 96),
            ),
            layout.options,
        )
    }

    @Test
    fun `提问类选项太多_等分收缩到地板之上`() {
        val layout = AgentApproveParams.optionLayout(
            viewportLeft = 0, viewportTop = 0,
            viewportWidth = 500, viewportHeight = 200,
            optionIds = listOf("1", "2", "3", "4", "5", "6"),
            rowHeightPx = 48, rowGapPx = 8, minRowHeightPx = 32,
        )
        // gaps=40，(200-40)/6=26 < 地板 32 → 行高取地板。
        assertTrue(layout.options.all { it.second.height == 32 })
        assertEquals(6, layout.options.size)
    }

    @Test
    fun `提问类空选项_退化零布局`() {
        val layout = AgentApproveParams.optionLayout(
            viewportLeft = 0, viewportTop = 0,
            viewportWidth = 500, viewportHeight = 400,
            optionIds = emptyList(),
            rowHeightPx = 96, rowGapPx = 16, minRowHeightPx = 32,
        )
        assertTrue(layout.options.isEmpty())
        assertEquals(Target.Outside, layout.hit(100, 100))
    }

    // ---------- 命中判定：左闭右开，按钮/选项外一律 Outside ----------

    @Test
    fun `命中判定_按钮内与按钮外`() {
        val layout = AgentApproveParams.confirmLayout(
            viewportLeft = 304, viewportTop = 80,
            viewportWidth = 500, viewportHeight = 400,
            buttonHeightPx = 128, buttonGapPx = 32,
        )
        assertEquals(Target.Yes, layout.hit(304, 216)) // 左上角（含）
        assertEquals(Target.Yes, layout.hit(537, 343)) // 右下角（不含）前一格
        assertEquals(Target.Outside, layout.hit(538, 344)) // 右下角（开区间外）
        assertEquals(Target.No, layout.hit(570, 216))
        assertEquals(Target.No, layout.hit(803, 343))
        assertEquals(Target.Outside, layout.hit(552, 280)) // 按钮间空档
        assertEquals(Target.Outside, layout.hit(304, 200)) // 按钮上方
    }

    @Test
    fun `命中判定_选项行按 id 分流`() {
        val layout = AgentApproveParams.optionLayout(
            viewportLeft = 304, viewportTop = 80,
            viewportWidth = 500, viewportHeight = 400,
            optionIds = listOf("a", "b", "c"),
            rowHeightPx = 96, rowGapPx = 16, minRowHeightPx = 32,
        )
        assertEquals(Target.Option("a"), layout.hit(400, 150))
        assertEquals(Target.Option("b"), layout.hit(400, 260))
        assertEquals(Target.Option("c"), layout.hit(400, 370))
        assertEquals(Target.Outside, layout.hit(400, 226)) // 行间空档
    }

    // ---------- 生效判定：恰好三类动作，按钮外取消 ----------

    @Test
    fun `弹层点按_同意拒绝选项各自生效_按钮外取消`() {
        assertEquals(Decision.Fire(SessionActionKind.APPROVE, null), AgentApproveParams.onOverlayTap(Target.Yes))
        assertEquals(Decision.Fire(SessionActionKind.REJECT, null), AgentApproveParams.onOverlayTap(Target.No))
        assertEquals(
            Decision.Fire(SessionActionKind.SELECT, "opt-1"),
            AgentApproveParams.onOverlayTap(Target.Option("opt-1")),
        )
        assertEquals(Decision.Disarm, AgentApproveParams.onOverlayTap(Target.Outside))
    }

    @Test
    fun `第一下点按_有批准入口才弹层`() {
        assertEquals(Decision.Arm, AgentApproveParams.onBodyTap(hasApproveEntry = true))
        assertEquals(Decision.Ignore, AgentApproveParams.onBodyTap(hasApproveEntry = false))
    }
}
