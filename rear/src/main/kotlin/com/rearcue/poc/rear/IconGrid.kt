package com.rearcue.poc.rear

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.design.RearCueNotificationIcons
import kotlin.math.ceil

/**
 * 背屏多通知**纯图标网格**的排布逻辑（issue #101）：行/列/整组居中/+N 计数全部在这里以
 * 纯函数算完，渲染层只照单摆放（与 [SafeArea.fitScale] 同款接缝——几何决策不进组合树）。
 *
 * 语义（issue #101 定案）：
 * - **单/多切换**：Icon Set 内 Active Notification 总数（[isGridMode]）≥2 → 网格；
 *   恰 1 条维持现状（一枚图标 + 点开 Detail 正文卡片）。
 * - **网格**：每格一枚 96dp 图标（[com.rearcue.poc.design.RearCueIconSize.iconSetRearDisplay]，
 *   恒定不随条数缩放）+ 右上未读数角标（数字＝该 App 的 Active Notification 条数）；
 *   每行至多 3 个、整组水平居中；最多 2 行 6 个，溢出在网格下方居中一枚「+N」徽标
 *   （N = 应用数 − 6）。
 * - **顺序**：[entries] 由状态机按时间倒序给出（[com.rearcue.poc.core.DashboardCore.iconSet]），
 *   本函数只照序切行，不重排。
 *
 * **AC 不变量（issue #101，[IconGridTest] 钉死）**：组包围盒（[IconGridLayout.width]/
 * [IconGridLayout.height]）只由常量档（列数、行数、间距、徽标尺寸）决定，**与格数无关**——
 * 三列自然宽度大于布局框时，渲染层的 [SafeArea.fitScale] 对所有网格数量取**同一收口系数**，
 * 2 条 / 3 条 / 6 条的图标渲染尺寸恒等（收口后小于 96dp 是
 * 超框的既有兜底，不随条数变才是 AC）。
 *
 * 纯 Kotlin + dp/sp 令牌声明（**只引 `androidx.compose.ui.unit` 的 Dp/sp 单位类型，不引
 * Compose 运行时/`@Composable`**）：输入输出全为 px，与 [DisplaySafeArea] 的口径一致；
 * 单测见 `IconGridTest`。
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

    /** 角标最终屏上直径/字号；[iconBadgeLayout] 补偿外层收口，而非跟着整组变小。 */
    val badgeSize: Dp = RearCueNotificationIcons.badgeSize
    val badgeFontSize = RearCueNotificationIcons.badgeFontSize

    /** 「+N」溢出徽标宽（固定档：容纳「+99」；更大的溢出在 POC 场景不成立）。 */
    val chipWidth: Dp = 36.dp

    /** 「+N」溢出徽标高。 */
    val chipHeight: Dp = 20.dp

    /** 「+N」溢出徽标字号。 */
    val chipFontSize = 12.sp
    // 「+N」保留 issue #101 的档位，图标与角标视觉令牌集中在 DesignTokens。
}

/**
 * 角标在未缩放图标格内的尺寸与文字落位。文字按最终屏上字号度量，[textScale] 补偿外层
 * 网格缩放；只有完整数字超过该格可用宽度时才缩字，不截断计数，也不越到相邻图标。
 */
data class IconBadgeLayout(
    val width: Int,
    val height: Int,
    val textX: Int,
    val textY: Int,
    val textScale: Float,
)

/**
 * [cellPx] 是自然图标格，其他尺寸输入是希望最终显示的像素。最终角标独立于网格缩放，
 * 在三列容量与充电避让需要缩小整组时仍保留数字字号；退化小格才按完整文字整体收口。
 */
fun iconBadgeLayout(
    cellPx: Int,
    displayScale: Double,
    minimumSizePx: Int,
    horizontalPaddingPx: Int,
    textWidthPx: Int,
    textHeightPx: Int,
): IconBadgeLayout {
    if (cellPx <= 0 || displayScale <= 0.0 || !displayScale.isFinite()) {
        return IconBadgeLayout(0, 0, 0, 0, 0f)
    }
    val available = cellPx * displayScale
    val padding = horizontalPaddingPx.coerceAtLeast(0).toDouble().coerceAtMost(available / 4)
    val textWidth = textWidthPx.coerceAtLeast(0)
    val textHeight = textHeightPx.coerceAtLeast(0)
    val fit = minOf(
        1.0,
        if (textWidth > 0) (available - 2 * padding) / textWidth else 1.0,
        if (textHeight > 0) available / textHeight else 1.0,
    )
    val minimum = minimumSizePx.coerceAtLeast(0).toDouble().coerceAtMost(available)
    val width = ceil(maxOf(minimum, textWidth * fit + 2 * padding) / displayScale).toInt().coerceAtMost(cellPx)
    val height = ceil(maxOf(minimum, textHeight * fit) / displayScale).toInt().coerceAtMost(cellPx)
    val textScale = (fit / displayScale).toFloat()
    return IconBadgeLayout(
        width = width,
        height = height,
        textX = ((width - textWidth * textScale) / 2).toInt().coerceAtLeast(0),
        textY = ((height - textHeight * textScale) / 2).toInt().coerceAtLeast(0),
        textScale = textScale,
    )
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
 * - [cells]：实际摆放的格（至多 [IconGrid.MAX_ICONS] 枚，按输入序切行，在组内垂直居中）；
 * - [overflow]：溢出应用数（0 = 无「+N」徽标）；
 * - [chipX]/[chipY]：「+N」徽标左上角（内容下方再隔一档竖距、水平居中；[overflow]=0 时为 0）；
 * - [width]/[height]：**恒定组包围盒**——3 列满行宽 ×（2 行 + 徽标带）的高度，只由常量档
 *   决定、**与格数无关**（issue #101 AC：渲染层 [SafeArea.fitScale] 对所有条数取同一系数，
 *   图标渲染尺寸不随条数变）。渲染层按它度量，外层 [dashboardPlacement] 把整组居中。
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
 * - **整组与每行都水平居中**：组宽恒取满行宽，不满一行的行在组内居中（1/2/4/5 个时不偏坠）；
 * - 每格 pitch = [cellPx] + [gapXPx] 恒定——图标 96dp 不随条数缩放；
 * - **组包围盒与格数无关**（宽恒 3 列、高恒 2 行 + 徽标带）：内容不足时在盒内居中、
 *   有「+N」时内容顶格并把徽标收在盒底，因此条数只改内容坐标，不改 [IconGridLayout.width]/
 *   [IconGridLayout.height]，渲染层的 fitScale 因子因而恒等（超框整组收口是它的既有兜底）；
 * - 溢出「+N」徽标在内容下方（竖距同 [gapYPx]）水平居中，尺寸由调用方给（文字量的 dp 档在
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
    val chipW = chipWidthPx.coerceAtLeast(0)
    val chipH = chipHeightPx.coerceAtLeast(0)
    val shown = entries.take(IconGrid.MAX_ICONS)
    val overflow = IconGrid.overflowCount(entries.size)
    val rows = shown.chunked(IconGrid.COLUMNS)

    // 恒定组包围盒（AC 不变量）：宽 = 3 列满行宽；高 = 2 行 + 一档竖距 + 徽标带。
    val boxWidth = IconGrid.COLUMNS * cellPx + (IconGrid.COLUMNS - 1) * gapX
    val boxHeight = 2 * cellPx + gapY + gapY + chipH
    val rowsHeight = rows.size * cellPx + (rows.size - 1) * gapY
    // 有徽标时内容顶格、徽标收在盒底（内容区恰＝2 行）；无徽标时内容在整盒内居中。
    val contentAreaHeight = if (overflow > 0) boxHeight - gapY - chipH else boxHeight
    val contentTop = (contentAreaHeight - rowsHeight) / 2 // 亚 px 级余量落上侧（不偏坠）

    val cells = buildList {
        rows.forEachIndexed { r, row ->
            val rowWidth = row.size * cellPx + (row.size - 1) * gapX
            val rowLeft = (boxWidth - rowWidth) / 2 // 居中；奇数余量落左侧（亚 px 级，不偏坠）
            row.forEachIndexed { c, entry ->
                add(
                    IconGridCell(
                        app = entry.app,
                        unread = entry.unread,
                        row = r,
                        col = c,
                        x = rowLeft + c * (cellPx + gapX),
                        y = contentTop + r * (cellPx + gapY),
                    ),
                )
            }
        }
    }

    if (overflow == 0) {
        return IconGridLayout(cells, 0, 0, 0, boxWidth, boxHeight)
    }
    return IconGridLayout(
        cells = cells,
        overflow = overflow,
        chipX = ((boxWidth - chipW) / 2).coerceAtLeast(0),
        chipY = contentTop + rowsHeight + gapY,
        width = boxWidth,
        height = boxHeight,
    )
}
