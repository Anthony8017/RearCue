package com.rearcue.poc.rear

/**
 * 会话选择器的渲染参数（2026-10-02 收口）：同一屏恒定 5 个完整条目，不允许半截；
 * 角部避让开/关都不改变条数，只把每条压到当屏五等分高度。超过 5 条时按单条边界自由滚动吸附。
 *
 * 条目为两行式，主行标题＋副行「来源 · 目录」；左侧为会话状态点，右侧选中圆点删除，
 * 选中行改中性描边。
 */
object AgentPickerParams {

    /** 同屏完整条目数：默认与角部避让档一致。 */
    const val PAGE_ROWS = 5

    /** 行高按可用视口五等分；整数像素除法向下取整，余数留在列表背景，永不截条目。 */
    fun rowHeightPx(viewportHeightPx: Int): Int =
        viewportHeightPx.coerceAtLeast(0) / PAGE_ROWS

    /** 同屏五条实际占高＝完整行高 × 条目数。 */
    fun visibleHeightPx(viewportHeightPx: Int): Int =
        rowHeightPx(viewportHeightPx) * PAGE_ROWS

    /** 状态点直径（dp）：与状态光带同色，但保持静止。 */
    const val STATUS_DOT_DP = 8

    /** 选中行中性描边宽度（dp）：避免与五档状态色混淆。 */
    const val SELECTED_BORDER_DP = 1
}
