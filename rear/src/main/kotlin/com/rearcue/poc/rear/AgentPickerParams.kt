package com.rearcue.poc.rear

/**
 * 会话选择器的渲染参数（spec 0020 / 票 #204，纯函数——JVM 判例沿 [AgentMirrorParams] / [IconGrid] 惯例）：
 * 「列表最多完整展示几行」是几何决策，收口在这里，渲染层照单执行。
 *
 * 由来：spec 0016 原判「一屏约 4 行、点列表外关闭」，票 #160 发现 ≥4 条时「点列表外」只剩看不见的
 * 空白，改判「3 行 + 可见的底部空白关闭带」（票 #162 评审锁定带的最小高度）；**票 #160 的可见
 * 关闭带由机主 2026-09-30 推翻**（spec 0020）：行高降为 28dp 密排、上限 5 行，可用高度能容纳几行
 * 就几行——列表外整块浮层背景都是关闭区，不再为带保底让行。
 *
 * 行高口径：28dp 是 picker 专属行高，**不挂** [com.rearcue.poc.design.RearCueTouch] 的 48dp 触控
 * 目标——提问确认浮层（[AgentApproveParams]）与主屏列表仍按触控口径，不随此值变。
 */
object AgentPickerParams {

    /** 列表最多完整展示的行数（其余在列表内滚动）。 */
    const val VISIBLE_ROWS = 5

    /** 单行高度（dp）：picker 专属密排行高，不是触控目标（见对象 KDoc）。 */
    const val ROW_HEIGHT_DP = 28

    /**
     * 列表区域的高度上限（px）：[rows] 行 × 行高 + 行距。
     * 行数不足 [rows] 时列表自然更矮——顶对齐，不居中。
     */
    fun listMaxHeightPx(rowHeightPx: Int, gapPx: Int, rows: Int = VISIBLE_ROWS): Int {
        val safeRows = rows.coerceAtLeast(0)
        val height = rowHeightPx.coerceAtLeast(0) * safeRows
        val gaps = gapPx.coerceAtLeast(0) * (safeRows - 1).coerceAtLeast(0)
        return height + gaps
    }

    /**
     * 实际展示的行数：可用高度能容纳几行就几行（第 n 行要连同行距一起放下），夹在
     * 1..[VISIBLE_ROWS]——矮屏逐行退让，最少 1 行；没有「为关闭带让行」的分支（spec 0020）。
     * 返回 [rows] → [listMaxHeightPx] 是该行数的上限。
     */
    fun visibleRows(
        availableHeightPx: Int,
        rowHeightPx: Int,
        gapPx: Int,
        wanted: Int = VISIBLE_ROWS,
    ): Int {
        val wantedRows = wanted.coerceIn(1, VISIBLE_ROWS)
        val available = availableHeightPx.coerceAtLeast(0)
        val row = rowHeightPx.coerceAtLeast(0)
        val gap = gapPx.coerceAtLeast(0)
        val unit = row + gap
        if (unit == 0) return wantedRows
        // n 行占 row + (n-1)×unit ≤ available；整数除法向零截断，放不下一行时落到下限 1。
        val fit = (available - row) / unit + 1
        return fit.coerceIn(1, wantedRows)
    }
}
