package com.rearcue.poc.rear

import com.rearcue.poc.agent.SessionActionKind

/**
 * 背屏批准二次确认浮层的几何与生效判定（spec 0018-5 / ADR 0009，纯函数——JVM 判例沿
 * [AgentPickerParams] / [AgentMirrorParams] 惯例，几何数值锁死，渲染层零决策照单执行）。
 *
 * 口径（票面 AC1/AC2/AC4）：
 * - **二次确认防误触**：第一下点按（Agent 页空白/正文区）弹层，弹层里再点按钮才生效；
 *   按钮以外任意处（含会话标识行区域）＝取消。不设超时自动关——不替机主做决定。
 * - 确认类＝「同意/拒绝」两个大按钮；提问类＝选项行点选（同一二次确认语义：先弹层、再点生效）。
 * - 与通知栏/主屏同一动作语义：出口恰好三类（APPROVE/REJECT/SELECT），
 *   **没有自由文字入口**（ADR 0009 红线）。
 * - 只有「等确认」那一屏响应（入口显隐在 app 层批准判定，本层只看投影有无）。
 */
object AgentApproveParams {

    /** 「同意/拒绝」按钮高度（dp）：背屏大按钮、抬手可点。 */
    const val BUTTON_HEIGHT_DP = 64

    /** 两个按钮的水平间距（dp）。 */
    const val BUTTON_GAP_DP = 16

    /** 提问类选项行高（dp）：48dp 触控目标（不随票 #204 picker 的 28dp 密排行高变）。 */
    const val OPTION_ROW_HEIGHT_DP = 48

    /** 提问类选项行距（dp）。 */
    const val OPTION_ROW_GAP_DP = 8

    /** 选项行高压缩地板（dp）：选项太多挤不下时等分视口高度，但不低于此值。 */
    const val OPTION_ROW_MIN_HEIGHT_DP = 32

    /** 浮层上的可点对象（窗口坐标，左闭右开，与 [PxRect] 同口径）。 */
    sealed interface Target {
        /** 确认类的「同意」按钮。 */
        data object Yes : Target

        /** 确认类的「拒绝」按钮。 */
        data object No : Target

        /** 提问类的某个选项行。 */
        data class Option(val id: String) : Target

        /** 按钮/选项以外的一切（含会话标识行区域）——弹层状态下的语义是「取消」。 */
        data object Outside : Target
    }

    /** 点按判定的出口：恰好弹层/收层/三类动作/不归本浮层管。 */
    sealed interface Decision {
        /** 第一下点按：弹出二次确认浮层。 */
        data object Arm : Decision

        /** 取消（点按钮外）：收层，不发动作。 */
        data object Disarm : Decision

        /** 不归本浮层管（无批准入口的屏/会话）：调用方走既有语义。 */
        data object Ignore : Decision

        /** 生效：发会话动作（恰好三类，无自由文字）。 */
        data class Fire(val kind: SessionActionKind, val optionId: String?) : Decision
    }

    /** 一个可点矩形（窗口坐标，px，左闭右开）。 */
    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int) {

        fun contains(x: Int, y: Int): Boolean =
            x >= left && x < left + width && y >= top && y < top + height
    }

    /**
     * 浮层布局（窗口坐标）：确认类只有 [yes]/[no]（[options] 空），提问类只有 [options]
     * （yes/no 为零矩形）——两形态互斥，[hit] 按此分流。
     */
    data class Layout(
        val yes: Rect,
        val no: Rect,
        val options: List<Pair<String, Rect>>,
    ) {

        /** 坐标命中判定（唯一出口）：按钮/选项以外一律 [Target.Outside]。 */
        fun hit(x: Int, y: Int): Target = if (options.isNotEmpty()) {
            options.firstOrNull { it.second.contains(x, y) }
                ?.let { Target.Option(it.first) }
                ?: Target.Outside
        } else when {
            yes.contains(x, y) -> Target.Yes
            no.contains(x, y) -> Target.No
            else -> Target.Outside
        }
    }

    /**
     * 确认类布局：「同意/拒绝」两个大按钮**水平二等分**（间距 [buttonGapPx]）、
     * **视口垂直居中**（与阅读版式「整段优先垂直居中」同语言）。
     */
    fun confirmLayout(
        viewportLeft: Int,
        viewportTop: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        buttonHeightPx: Int,
        buttonGapPx: Int,
    ): Layout {
        val vw = viewportWidth.coerceAtLeast(0)
        val vh = viewportHeight.coerceAtLeast(0)
        val bh = buttonHeightPx.coerceAtLeast(0)
        val gap = buttonGapPx.coerceAtLeast(0)
        val buttonWidth = ((vw - gap) / 2).coerceAtLeast(0)
        val top = viewportTop + ((vh - bh).coerceAtLeast(0) / 2)
        val yes = Rect(left = viewportLeft, top = top, width = buttonWidth, height = bh)
        val no = Rect(
            left = viewportLeft + buttonWidth + gap,
            top = top,
            width = (vw - buttonWidth - gap).coerceAtLeast(0),
            height = bh,
        )
        return Layout(yes = yes, no = no, options = emptyList())
    }

    /**
     * 提问类布局：选项行**等宽铺满视口**、行块**视口垂直居中**；行高 [rowHeightPx]，
     * 挤不下时等分视口高度收缩（地板 [minRowHeightPx]）。
     */
    fun optionLayout(
        viewportLeft: Int,
        viewportTop: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        optionIds: List<String>,
        rowHeightPx: Int,
        rowGapPx: Int,
        minRowHeightPx: Int,
    ): Layout {
        val vw = viewportWidth.coerceAtLeast(0)
        val vh = viewportHeight.coerceAtLeast(0)
        val count = optionIds.size
        val gap = rowGapPx.coerceAtLeast(0)
        val wanted = rowHeightPx.coerceAtLeast(0)
        val floor = minRowHeightPx.coerceAtLeast(0)
        val empty = Layout(yes = Rect(0, 0, 0, 0), no = Rect(0, 0, 0, 0), options = emptyList())
        if (count <= 0) return empty
        val gaps = gap * (count - 1)
        val rowHeight = minOf(wanted, maxOf(floor, (vh - gaps) / count))
        val blockHeight = rowHeight * count + gaps
        var y = viewportTop + ((vh - blockHeight).coerceAtLeast(0) / 2)
        val options = optionIds.map { id ->
            val rect = Rect(left = viewportLeft, top = y, width = vw, height = rowHeight)
            y += rowHeight + gap
            id to rect
        }
        return Layout(yes = Rect(0, 0, 0, 0), no = Rect(0, 0, 0, 0), options = options)
    }

    /**
     * 弹层前的第一下点按（Agent 页空白/正文区）：有批准入口才弹层；没有就**不归本浮层管**
     * （调用方走既有切页语义）。
     */
    fun onBodyTap(hasApproveEntry: Boolean): Decision =
        if (hasApproveEntry) Decision.Arm else Decision.Ignore

    /**
     * 弹层后的点按生效判定（二次确认，唯一出口）：点按钮才生效，按钮外一律取消。
     * 出口恰好三类动作——APPROVE/REJECT/SELECT，没有自由文字入口（ADR 0009）。
     */
    fun onOverlayTap(target: Target): Decision = when (target) {
        Target.Yes -> Decision.Fire(SessionActionKind.APPROVE, null)
        Target.No -> Decision.Fire(SessionActionKind.REJECT, null)
        is Target.Option -> Decision.Fire(SessionActionKind.SELECT, target.id)
        Target.Outside -> Decision.Disarm
    }
}
