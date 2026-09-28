package com.rearcue.poc.rear

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 背屏多通知**纯图标网格**的排布逻辑（issue #101）：行/列/整组居中/+N 计数全部在这里以
 * 纯函数算完，渲染层只照单摆放（与 [SafeArea.fitScale] 同款接缝——几何决策不进组合树）。
 *
 * 语义（issue #101 定案）：
 * - **单/多切换**：Icon Set 内 Active Notification 总数（[isGridMode]）≥2 → 网格；
 *   恰 1 条维持现状（一枚图标 + 点开 Detail 正文卡片）。
 * - **网格**：每格一枚 96dp 图标（[com.rearcue.poc.design.RearCueIconSize.iconSetRearDisplay]，
 *   恒定不随条数缩放——超框收口是 fitScale 的既有兜底）+ 右上未读数角标（数字＝该 App 的
 *   Active Notification 条数）；每行至多 3 个、整组水平居中；最多 2 行 6 个，溢出在网格
 *   下方居中一枚「+N」徽标（N = 应用数 − 6）。
 * - **顺序**：[entries] 由状态机按时间倒序给出（[com.rearcue.poc.core.DashboardCore.iconSet]），
 *   本函数只照序切行，不重排。
 *
 * 纯 Kotlin + dp/sp 令牌声明（不引 Density/Compose 运行时）：输入输出全为 px，与
 * [DisplaySafeArea] 的口径一致；单测见 `IconGridTest`。
 */
object IconGrid {

    /** 每行列数（issue #101：每行恰 3 个）。 */
    const val COLUMNS = 3

    /** 网格最多摆放的图标数（2 行 × 3 列），超出折叠进「+N」徽标。 */
    const val MAX_ICONS = 6

    /** 单/多切换判据：Icon Set 内 Active Notification 总数 ≥2 → 纯图标网格（恰 1 条维持现状）。 */
    fun isGridMode(unreadCounts: Map<String, Int>): Boolean = unreadCounts.values.sum() >= 2

    /** 溢出应用数：超出 [MAX_ICONS] 的部分（0 = 不出「+N」徽标；7→+1、9→+3）。 */
    fun overflowCount(entryCount: Int): Int = (entryCount - MAX_ICONS).coerceAtLeast(0)

    /** 未读数角标直径（网格每格右上；文本不足时也保底成圆）。 */
    val badgeSize: Dp = 18.dp

    /** 未读数角标在格内的边距（右/上各留这么多，角标整体收在格内——不与图标/边缘打架）。 */
    val badgeInset: Dp = 4.dp

    /** 未读数角标字号。 */
    val badgeFontSize = 11.sp

    /** 「+N」溢出徽标宽（固定档：容纳「+99」；更大的溢出在 POC 场景不成立）。 */
    val chipWidth: Dp = 36.dp

    /** 「+N」溢出徽标高。 */
    val chipHeight: Dp = 20.dp

    /** 「+N」溢出徽标字号。 */
    val chipFontSize = 12.sp
}

/** 网格一格的输入：应用包名 + 未读角标数字（顺序＝时间倒序，由状态机给出）。 */
data class IconGridEntry(val app: String, val unread: Int)

/**
 * 网格一格的排布结果：[x]/[y] 是格左上角在**组内**的坐标（px，组＝整个网格的包围盒，
 * 由 [iconGridLayout] 算出并交给渲染层整体居中摆放）。[row]/[col] 是行/列下标（0 起），
 * 供单测断言切行用。
 */
data class IconGridCell(
    val app: String,
    val unread: Int,
    val row: Int,
    val col: Int,
    val x: Int,
    val y: Int,
)

/**
 * 网格整体排布（px，组内坐标）：
 * - [cells]：实际摆放的格（至多 [IconGrid.MAX_ICONS] 枚，按输入序切行）；
 * - [overflow]：溢出应用数（0 = 无「+N」徽标）；
 * - [chipX]/[chipY]：「+N」徽标左上角（网格末行下方再隔一档竖距、水平居中；[overflow]=0 时为 0）；
 * - [width]/[height]：组包围盒——渲染层按它度量，外层 [dashboardPlacement] 把整组居中。
 */
data class IconGridLayout(
    val cells: List<IconGridCell>,
    val overflow: Int,
    val chipX: Int,
    val chipY: Int,
    val width: Int,
    val height: Int,
)

/**
 * 纯图标网格排布（纯函数，issue #101 验收面）：
 *
 * - 每行至多 [IconGrid.COLUMNS] 个、最多 2 行（[IconGrid.MAX_ICONS]），超出计入 [IconGridLayout.overflow]；
 * - **整组与每行都水平居中**：组宽取满行宽，不满一行的行在组内居中（1/2/4/5 个时不偏坠）；
 * - 每格 pitch = [cellPx] + [gapXPx] 恒定——图标 96dp 不随条数缩放（fitScale 只做整组兜底）；
 * - 溢出「+N」徽标在末行下方（竖距同 [gapYPx]）水平居中，尺寸由调用方给（文字量的 dp 档在
 *   [IconGrid]，px 换算在渲染层）。
 *
 * 输入退化：[entries] 为空 → 空布局（0×0，无徽标）；[cellPx] ≤0 同样返回空布局（病态不崩）。
 */
fun iconGridLayout(
    entries: List<IconGridEntry>,
    cellPx: Int,
    gapXPx: Int,
    gapYPx: Int,
    chipWidthPx: Int,
    chipHeightPx: Int,
): IconGridLayout {
    if (entries.isEmpty() || cellPx <= 0) return IconGridLayout(emptyList(), 0, 0, 0, 0, 0)
    val gapX = gapXPx.coerceAtLeast(0)
    val gapY = gapYPx.coerceAtLeast(0)
    val shown = entries.take(IconGrid.MAX_ICONS)
    val overflow = IconGrid.overflowCount(entries.size)
    val rows = shown.chunked(IconGrid.COLUMNS)
    val groupWidth = rows.maxOf { row -> row.size * cellPx + (row.size - 1) * gapX }
    val rowsHeight = rows.size * cellPx + (rows.size - 1) * gapY

    val cells = buildList {
        rows.forEachIndexed { r, row ->
            val rowWidth = row.size * cellPx + (row.size - 1) * gapX
            val rowLeft = (groupWidth - rowWidth) / 2 // 居中；奇数余量落左侧（亚 px 级，不偏坠）
            row.forEachIndexed { c, entry ->
                add(
                    IconGridCell(
                        app = entry.app,
                        unread = entry.unread,
                        row = r,
                        col = c,
                        x = rowLeft + c * (cellPx + gapX),
                        y = r * (cellPx + gapY),
                    ),
                )
            }
        }
    }

    if (overflow == 0) {
        return IconGridLayout(cells, 0, 0, 0, groupWidth, rowsHeight)
    }
    val chipW = chipWidthPx.coerceAtLeast(0)
    val chipH = chipHeightPx.coerceAtLeast(0)
    return IconGridLayout(
        cells = cells,
        overflow = overflow,
        chipX = ((groupWidth - chipW) / 2).coerceAtLeast(0),
        chipY = rowsHeight + gapY,
        width = maxOf(groupWidth, chipW),
        height = rowsHeight + gapY + chipH,
    )
}
