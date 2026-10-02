package com.rearcue.poc.rear

import kotlin.math.ceil

/**
 * 会话选择器的渲染参数（spec 0020 / 票 #204 定密排；票 #211 曾废行数上限；2026-10-02 机主收口
 * 改回完整分页）：沿 [AgentMirrorParams] / [IconGrid] 的参数对象惯例。
 *
 * 最新口径：每页恒定 5 个完整条目，不允许半截；角部避让开/关都不改变条数，只把每条压到
 * 当页五等分高度。条目为两行式，主行标题＋副行「来源 · 目录」；等待/选中圆点判例由本次
 * 改版替代——左侧为会话状态点，右侧选中圆点删除，选中行改中性描边。
 */
object AgentPickerParams {

    /** 每页完整条目数：默认与角部避让档一致。 */
    const val PAGE_ROWS = 5

    /** 页高/行高按可用视口五等分；整数像素除法故意向下取整，余数留在页间背景，永不截条目。 */
    fun rowHeightPx(viewportHeightPx: Int): Int =
        viewportHeightPx.coerceAtLeast(0) / PAGE_ROWS

    /** 一页实际占高＝完整行高 × 每页行数。 */
    fun pageHeightPx(viewportHeightPx: Int): Int =
        rowHeightPx(viewportHeightPx) * PAGE_ROWS

    /** 条目总数（含首行“自动”）→ 页数；0 条也保留一页空位。 */
    fun pageCount(rowCount: Int): Int =
        ceil(rowCount.coerceAtLeast(0) / PAGE_ROWS.toDouble()).toInt().coerceAtLeast(1)

    /** 状态点直径（dp）：与状态光带同色，但保持静止。 */
    const val STATUS_DOT_DP = 8

    /** 选中行中性描边宽度（dp）：避免与五档状态色混淆。 */
    const val SELECTED_BORDER_DP = 1
}
