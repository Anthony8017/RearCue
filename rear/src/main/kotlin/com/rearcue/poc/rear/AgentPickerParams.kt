package com.rearcue.poc.rear

/**
 * 会话选择器的渲染参数（票 #160，纯函数——JVM 判例沿 [AgentMirrorParams] / [IconGrid] 惯例）：
 * 「列表最多完整展示几行、底部关闭带留多高」是几何决策，收口在这里，渲染层照单执行。
 *
 * 由来：spec 0016 原判「一屏约 4 行、点列表外关闭」，实机验收发现 ≥4 条时列表铺满整块可点区域
 * ——「点列表外」只剩左侧被相机模组挡住、肉眼不可见的那条空白（能点中但靠猜），「再点标识行」
 * 又被浮层盖住。机主 2026-09-29 定夺：**3 行 + 可见的底部空白关闭带**。
 *
 * 行高口径：渲染侧每行用 `heightIn(min = RearCueTouch.minTarget)` 且内容只有一行 15sp 文本，
 * 实际高度就是 [com.rearcue.poc.design.RearCueTouch.minTarget]——本对象按该值计算（票 #162 评审
 * 记录该不变量；行内容将来变高需同步这里）。
 */
object AgentPickerParams {

    /** 列表最多完整展示的行数（其余在列表内滚动）。 */
    const val VISIBLE_ROWS = 3

    /**
     * 底部关闭带的最小高度（dp）：**可见**即可（不是 48dp 触控目标——带本身是整块浮层背景的一段，
     * 本机 3 行时实测约 46dp）。不够就让出一行，避免出现 0 高的死区（票 #162 评审修复）。
     */
    const val MIN_STRIP_DP = 32

    /**
     * 列表区域的高度上限（px）：[rows] 行 × 行高 + 行距。
     * 行数 ≤ [rows] 时列表自然更矮——**带的余量更大，不是把列表居中**。
     */
    fun listMaxHeightPx(rowHeightPx: Int, gapPx: Int, rows: Int = VISIBLE_ROWS): Int {
        val safeRows = rows.coerceAtLeast(0)
        val height = rowHeightPx.coerceAtLeast(0) * safeRows
        val gaps = gapPx.coerceAtLeast(0) * (safeRows - 1).coerceAtLeast(0)
        return height + gaps
    }

    /**
     * 实际展示的行数：尽量用 [wanted] 行，但列表上限必须给底部关闭带留出 [minStripPx]
     * （屏太矮/行太高时逐行退让，最少 1 行）。返回 [rows] → [listMaxHeightPx] 是该行数的上限。
     */
    fun visibleRows(
        availableHeightPx: Int,
        rowHeightPx: Int,
        gapPx: Int,
        minStripPx: Int,
        wanted: Int = VISIBLE_ROWS,
    ): Int {
        var rows = wanted.coerceAtLeast(1)
        val available = availableHeightPx.coerceAtLeast(0)
        val minStrip = minStripPx.coerceAtLeast(0)
        while (rows > 1 && available - listMaxHeightPx(rowHeightPx, gapPx, rows) < minStrip) {
            rows--
        }
        return rows
    }

    /** 底部关闭带高度（px）：可用高度扣掉该行数下的列表上限，至少 0（不为负）。 */
    fun dismissStripPx(availableHeightPx: Int, listMaxHeightPx: Int): Int =
        (availableHeightPx - listMaxHeightPx).coerceAtLeast(0)
}
