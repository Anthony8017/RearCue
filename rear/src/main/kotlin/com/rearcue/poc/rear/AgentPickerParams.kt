package com.rearcue.poc.rear

/**
 * 会话选择器的渲染参数（票 #160，纯函数——JVM 判例沿 [AgentMirrorParams] / [IconGrid] 惯例）：
 * 「列表最多完整展示几行、底部留多高的关闭带」是几何决策，收口在这里，渲染层照单执行。
 *
 * 由来：spec 0016 原判「一屏约 4 行、点列表外关闭」，实机验收发现 ≥4 条时列表铺满整块可点区域
 * ——「点列表外」只剩左侧被相机模组挡住、肉眼不可见的那条空白（能点中但靠猜），「再点标识行」
 * 又被浮层盖住。机主 2026-09-29 定夺：**3 行 + 可见的底部空白关闭带**。
 */
object AgentPickerParams {

    /** 列表最多完整展示的行数（其余在列表内滚动）。 */
    const val VISIBLE_ROWS = 3

    /**
     * 列表区域的高度上限（px）：[rows] 行 × 行高 + 行距，行高取触控目标下限（≥48dp 的既有约束）。
     * 行数 ≤ [VISIBLE_ROWS] 时列表自然更矮——**带的余量更大，不是把列表居中**。
     */
    fun listMaxHeightPx(rowHeightPx: Int, gapPx: Int, rows: Int = VISIBLE_ROWS): Int {
        val safeRows = rows.coerceAtLeast(0)
        val height = rowHeightPx.coerceAtLeast(0) * safeRows
        val gaps = gapPx.coerceAtLeast(0) * (safeRows - 1).coerceAtLeast(0)
        return height + gaps
    }

    /**
     * 底部关闭带高度（px）：浮层可用高度扣掉列表上限后剩下的空白。
     * 不成立（屏太矮/行太高，结果为 0）时列表照旧滚动，关闭仍可经「再点标识行」或选中条目完成。
     */
    fun dismissStripPx(availableHeightPx: Int, listMaxHeightPx: Int): Int =
        (availableHeightPx - listMaxHeightPx).coerceAtLeast(0)
}
