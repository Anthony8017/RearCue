package com.rearcue.poc.rear

import com.rearcue.poc.core.MirrorTextSize

/**
 * 会话选择器的渲染参数（2026-10-02 收口）：同一屏恒定 6 个完整条目，不允许半截；
 * 角部避让开/关都不改变条数，只把每条压到当屏六等分高度。超过 6 条时按单条边界自由滚动吸附。
 *
 * 条目为两行式，主行标题＋副行「来源 · 目录」；左侧为会话状态标识；列表不表达当前锁定/选中项。
 */
object AgentPickerParams {

    /** 会话选择器两行文字档；与 Agent 会话页标题/正文角色对换解耦。 */
    data class Typography(
        val titleSp: Float,
        val subtitleSp: Float,
    )

    /** 会话选择器主行保持既有 14/16/20sp，副行保持 10/11/13sp。 */
    fun typography(size: MirrorTextSize): Typography = when (size) {
        MirrorTextSize.SMALL -> Typography(titleSp = 14f, subtitleSp = 10f)
        MirrorTextSize.MEDIUM -> Typography(titleSp = 16f, subtitleSp = 11f)
        MirrorTextSize.LARGE -> Typography(titleSp = 20f, subtitleSp = 13f)
    }

    /** 同屏完整条目数：默认与角部避让档一致。 */
    const val PAGE_ROWS = 6

    /** 行高按可用视口六等分；整数像素除法向下取整，余数留在列表背景，永不截条目。 */
    fun rowHeightPx(viewportHeightPx: Int): Int =
        viewportHeightPx.coerceAtLeast(0) / PAGE_ROWS

    /** 同屏六条实际占高＝完整行高 × 条目数。 */
    fun visibleHeightPx(viewportHeightPx: Int): Int =
        rowHeightPx(viewportHeightPx) * PAGE_ROWS

    /** 会话状态标识直径（dp）：与正文标题行同槽位尺寸；工作中为 Spinner，其余按状态显示圆点。 */
    const val STATUS_INDICATOR_DP = 8
}
