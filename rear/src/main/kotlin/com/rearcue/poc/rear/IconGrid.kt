package com.rearcue.poc.rear

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.design.RearCueNotificationIcons
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Icon Set 的容量、计数及最终屏上视觉规格；几何计算不依赖 Compose 运行时。 */
object IconGrid {
    const val COLUMNS = 3
    const val MAX_ICONS = 6

    fun isGridMode(unreadCounts: Map<String, Int>): Boolean = unreadCounts.values.sumOf { it.toLong() } >= 2
    fun overflowCount(entryCount: Int): Int = (entryCount - MAX_ICONS).coerceAtLeast(0)
    val badgeSize = RearCueNotificationIcons.badgeSize
    val badgeFontSize = RearCueNotificationIcons.badgeFontSize
    val chipWidth = 36.dp
    val chipHeight = 20.dp
    val chipFontSize = 12.sp
}

/** 角标完整计数的底片与文字几何；只在数字确实过宽时缩字。 */data class IconBadgeLayout(
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

/** 输入順序由状态机给出，本层不重排。 */
data class IconGridEntry(val app: String, val unread: Int)

data class IconGridCell(
    val app: String,
    val unread: Int,
    val row: Int,
    val col: Int,
    val x: Int,
    val y: Int,
)

/** 实际图标及角标外探的组内包围盒；+N 独立落位，不进入整组尺寸。 */
data class IconGridLayout(
    val cells: List<IconGridCell>,
    val overflow: Int,
    val width: Int,
    val height: Int,
)

/** 每行至多三格，不满一行时居中。尺寸稳定来自固定 [cellPx]，不再依赖虚构的六格空盒。 */
fun iconGridLayout(
    entries: List<IconGridEntry>,
    cellPx: Int,
    gapXPx: Int,
    gapYPx: Int,
    badgeOverhangPx: Int,
): IconGridLayout {
    if (entries.isEmpty() || cellPx <= 0) return IconGridLayout(emptyList(), 0, 0, 0)
    val overhang = badgeOverhangPx.coerceAtLeast(0)
    // 角标占据间距的一部分，但不得伸进相邻图标。
    val gapX = maxOf(gapXPx, overhang)
    val gapY = maxOf(gapYPx, overhang)
    val rows = entries.take(IconGrid.MAX_ICONS).chunked(IconGrid.COLUMNS)
    val columns = minOf(entries.size, IconGrid.COLUMNS)
    val width = columns * cellPx + (columns - 1) * gapX + overhang
    val height = rows.size * cellPx + (rows.size - 1) * gapY + overhang
    val cells = buildList {
        rows.forEachIndexed { r, row ->
            val rowWidth = row.size * cellPx + (row.size - 1) * gapX + overhang
            val rowLeft = (width - rowWidth) / 2
            row.forEachIndexed { c, entry ->
                add(IconGridCell(entry.app, entry.unread, r, c, rowLeft + c * (cellPx + gapX), overhang + r * (cellPx + gapY)))
            }
        }
    }
    return IconGridLayout(cells, IconGrid.overflowCount(entries.size), width, height)
}

/** 实际像素尺寸及绝对落位。图标与角标不再依赖外层 graphicsLayer 的缩放补偿。 */
data class NotificationIconPlacement(
    val grid: IconGridLayout,
    val block: BlockPlacement,
    val iconSizePx: Int,
    val badgeOverhangPx: Int,
    val chip: PxRect?,
)

/**
 * 先按桌面参照尺寸尝试真实可见区，只有设备确实放不下时才逐像素收口。单条、网格、充电
 * 共用此出口；单条只隐藏数字。六格优先，+N 位于其下方，充电时在电量左侧可用空间居中。
 */
fun notificationIconPlacement(
    entries: List<IconGridEntry>,
    safeArea: SafeArea,
    targetIconSizePx: Int,
    gapXPx: Int,
    gapYPx: Int,
    chipWidthPx: Int,
    chipHeightPx: Int,
    showBadges: Boolean,
    numberPlaceholder: PxRect? = null,
    drift: PxOffset = PxOffset(0, 0),
): NotificationIconPlacement? {
    if (entries.isEmpty()) return null
    for (size in targetIconSizePx downTo 1) {
        val overhang = if (showBadges) (size * RearCueNotificationIcons.badgeOverhangRatio).roundToInt() else 0
        val grid = iconGridLayout(entries, size, gapXPx, gapYPx, overhang)
        val block = safeArea.placeNotificationBlock(grid.width, grid.height, numberPlaceholder) ?: continue
        val chip = if (grid.overflow > 0) {
            safeArea.placeNotificationBlock(
                chipWidthPx, chipHeightPx, numberPlaceholder,
                minimumTop = block.y + block.height + gapYPx.coerceAtLeast(0), preferTop = true,
            )?.rect ?: continue
        } else null
        return NotificationIconPlacement(
            grid, block.copy(x = block.x + drift.x, y = block.y + drift.y), size, overhang,
            chip?.let { PxRect(it.left + drift.x, it.top + drift.y, it.right + drift.x, it.bottom + drift.y) },
        )
    }
    return null
}