package com.rearcue.poc.rear

import kotlin.math.roundToInt

/**
 * 显示几何约束（票 #26）：cutout 矩形 + 圆角半径 + 漂移幅度 → 内容安全矩形 + 漂移边界。
 *
 * 纯 Kotlin（同 [RearDisplayLocator] 判例）：Android 层只负责从系统 DisplayCutout /
 * RoundedCorner 采集输入、照单执行输出，不自算几何、不硬编码机型数字。
 *
 * 票 #102 追加：充电电量数字的本体落位与缺口占位（[chargingNumberRect] /
 * [chargingNumberPlaceholder]）+ Icon Set 图标块的让位落位（[SafeArea.placeIconBlock]），
 * 构造性保证图标不与数字占位区相交。
 */

/** 像素矩形，左闭右开（[right]/[bottom] 不含）。 */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = (right - left).coerceAtLeast(0)

    val height: Int get() = (bottom - top).coerceAtLeast(0)

    /** 与另一矩形是否相交（开区间口径：贴边算不相交）。 */
    fun overlaps(other: PxRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/** 像素偏移。 */
data class PxOffset(val x: Int, val y: Int)

/** 像素水平留白（[start] 左、[end] 右）。 */
data class PxPadding(val start: Int, val end: Int)

/**
 * 充电电量数字的本体落位（纯函数，票 #102）：整屏右下角锚定、圆角感知内缩
 * ＝ 圆角半径 × 0.35 ＋ 屏缘留白 [extraInsetPx]（设计 md 的 px 值，调用方按密度折算）。
 * 与渲染落位 `RearDashboardActivity.chargingNumberPlacement` 同式同参——数字实测尺寸
 * （[numberWidth]/[numberHeight]）由渲染侧传入，渲染与图标避让共用这一个出口。
 */
fun chargingNumberRect(
    windowWidth: Int,
    windowHeight: Int,
    cornerRadiusPx: Int,
    extraInsetPx: Int,
    numberWidth: Int,
    numberHeight: Int,
): PxRect {
    val inset = (cornerRadiusPx * 0.35f).toInt() + extraInsetPx.coerceAtLeast(0)
    return PxRect(
        left = windowWidth - numberWidth - inset,
        top = windowHeight - numberHeight - inset,
        right = windowWidth - inset,
        bottom = windowHeight - inset,
    )
}

/**
 * 数字占位区（纯函数，票 #102 的缺口几何）：本体 [numberRect] 四向外扩 [gapPx]。
 * Icon Set 图标布局避让的是**这个区**（[SafeArea.placeIconBlock] 拿它当让位输入），
 * 缺口宽度由设计令牌 sm 折算传入；数字本体渲染仍用 [numberRect]，缺口只外扩不挪数字。
 */
fun chargingNumberPlaceholder(numberRect: PxRect, gapPx: Int): PxRect {
    val gap = gapPx.coerceAtLeast(0)
    return PxRect(
        left = numberRect.left - gap,
        top = numberRect.top - gap,
        right = numberRect.right + gap,
        bottom = numberRect.bottom + gap,
    )
}

/**
 * 图标块落位结果（票 #102）：[x]/[y] 是缩放后块左上角的窗口像素（含漂移），[scale] 是
 * 等比缩放系数；[width]/[height] 是缩放后占位的收口尺寸（向上取整——测试按保守外接矩形
 * 断言「不相交」，比实际墨迹只大 1px 以内，结论更稳）。
 */
data class BlockPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scale: Double,
) {
    val rect: PxRect get() = PxRect(x, y, x + width, y + height)
}

/**
 * 显示几何输入（像素）：
 * - [cutouts]：`DisplayCutout.getBoundingRects()` 原样传入（开孔在左 / 在上 / 贴角 / 居中都行）；
 * - [cornerRadius]：四角 `RoundedCorner.radius` 的最大值；
 * - [driftAmplitude]：防烧屏漂移幅度（±x、±y，非负）。
 */
data class DisplayGeometry(
    val width: Int,
    val height: Int,
    val cutouts: List<PxRect>,
    val cornerRadius: Int,
    val driftAmplitude: PxOffset,
)

/**
 * 显示几何约束输出：
 * - [contentRect] **内容安全矩形**：时间 / Icon Set 在漂移的任何时刻都不得出界；
 * - [driftBounds] **漂移边界**：漂移偏移的合法范围 ±([PxOffset.x], [PxOffset.y])，
 *   幅度超出安全矩形可承受范围时按安全矩形收紧。
 *
 * 不变量（[DisplaySafeAreaTest] 钉死）：内容摆进 [layoutRect]、再按 [driftBounds] 内任意
 * 偏移漂移，全程落在 [contentRect] 内；[contentRect] 与任何 cutout 不相交，且其边界到
 * 圆角切掉的角部不可用区的距离 ≥ 圆角半径（实机 97px，恰压线达标）。
 */
data class SafeArea(
    val contentRect: PxRect,
    val driftBounds: PxOffset,
) {

    /** 静止布局框 = [contentRect] 四边收缩漂移幅度；内容摆这里，漂移后不出 [contentRect]。 */
    val layoutRect: PxRect = PxRect(
        left = contentRect.left + driftBounds.x,
        top = contentRect.top + driftBounds.y,
        right = contentRect.right - driftBounds.x,
        bottom = contentRect.bottom - driftBounds.y,
    )

    /**
     * 防烧屏漂移调度（纯函数）：按分钟在漂移矩形的四角轮流驻留，相邻分钟都是极限位
     * （±幅度），任一时刻都不越界。相位 0..3 = （−,−）→（+,−）→（+,+）→（−,+）。
     */
    fun driftFor(minute: Int): PxOffset {
        val phase = Math.floorMod(minute, DRIFT_PHASES)
        val x = if (phase == PHASE_TOP_RIGHT || phase == PHASE_BOTTOM_RIGHT) driftBounds.x else -driftBounds.x
        val y = if (phase == PHASE_BOTTOM_RIGHT || phase == PHASE_BOTTOM_LEFT) driftBounds.y else -driftBounds.y
        return PxOffset(x, y)
    }

    /**
     * 内容等比缩放系数：装得下不缩（[NO_SCALE]），装不下按长宽较紧的一边缩到恰好进 [layoutRect]。
     * 非法尺寸（≤0）返回 [NO_SCALE]；安全矩形退化为空时返回 [DEGENERATE_SCALE]（几何输入病态，
     * 内容不该上屏）。
     */
    fun fitScale(contentWidth: Int, contentHeight: Int): Double = fitScale(layoutRect, contentWidth, contentHeight)

    /**
     * 任意框上的等比缩放（票 #102 让位分支的兜底口径，与 [fitScale] 同式）：
     * 装得下不缩、装不下按较紧的一边缩到恰好进 [box]；非法尺寸返回 [NO_SCALE]，
     * 退化框（≤0）返回 [DEGENERATE_SCALE]。
     */
    fun fitScale(box: PxRect, contentWidth: Int, contentHeight: Int): Double = when {
        contentWidth <= 0 || contentHeight <= 0 -> NO_SCALE
        box.width <= 0 || box.height <= 0 -> DEGENERATE_SCALE
        else -> minOf(
            NO_SCALE,
            box.width.toDouble() / contentWidth,
            box.height.toDouble() / contentHeight,
        )
    }

    /**
     * Icon Set 图标块落位（纯函数，票 #102；[RearDashboardActivity.dashboardPlacement] 照单执行）。
     *
     * 原位 = [layoutRect] 内居中 + [drift]（与既有落位式逐位一致，无数字时完全不回退）。
     * 充电时若与数字占位区 [numberPlaceholder]（已含缺口）相交则让位——**只在相交时让**，
     * 逐分支构造性保证不相交（[DisplaySafeAreaTest] 按任意图标数钉住）：
     * 1. 上移：块底边收到占位顶边之上——保住原缩放与**水平居中**（每行居中排布不破）；
     * 2. 上移越出 [layoutRect] 顶边时左移：块右边收到占位左边之上——保住原缩放；
     * 3. 两条路线都放不下时按「上方净空框 / 左侧净空框」取缩放更大的一条收缩，并在该框内
     *    居中（fitScale 兜底；两框都退化 = 占位吃满布局框的病态几何，内容不上屏）。
     *
     * 漂移对数字与图标是同量平移（相对关系不变），所以避让判定在无漂移坐标系里做、
     * 最后再整体平移——任一漂移相位下「不相交」与「不出安全矩形」同时成立。
     */
    fun placeIconBlock(
        blockWidth: Int,
        blockHeight: Int,
        numberPlaceholder: PxRect? = null,
        drift: PxOffset = PxOffset(0, 0),
    ): BlockPlacement {
        fun centeredIn(box: PxRect, scale: Double): BlockPlacement {
            val scaledWidth = blockWidth * scale
            val scaledHeight = blockHeight * scale
            val x = (box.left + (box.width - scaledWidth) / 2).roundToInt()
            val y = (box.top + (box.height - scaledHeight) / 2).roundToInt()
            return BlockPlacement(
                x = x,
                y = y,
                // 收口尺寸沿用既有落位的 placedRight 口径（最近整数）：与渲染层像素口径一致，
                // 恰满框时的浮点噪声（495.0000…06）也不会被 ceil 放大成越界 1px；
                // 亚像素误差落在数字占位的缺口缓冲（sm）之内。
                width = (x + scaledWidth).roundToInt() - x,
                height = (y + scaledHeight).roundToInt() - y,
                scale = scale,
            )
        }

        var placed = centeredIn(layoutRect, fitScale(blockWidth, blockHeight))
        val slot = numberPlaceholder
        if (slot != null && placed.rect.overlaps(slot)) {
            val upY = slot.top - placed.height
            val leftX = slot.left - placed.width
            placed = when {
                upY >= layoutRect.top -> placed.copy(y = upY)
                leftX >= layoutRect.left -> placed.copy(x = leftX)
                else -> {
                    val upBox = PxRect(layoutRect.left, layoutRect.top, layoutRect.right, minOf(layoutRect.bottom, slot.top))
                    val leftBox = PxRect(layoutRect.left, layoutRect.top, minOf(layoutRect.right, slot.left), layoutRect.bottom)
                    val upScale = fitScale(upBox, blockWidth, blockHeight)
                    val leftScale = fitScale(leftBox, blockWidth, blockHeight)
                    if (upScale >= leftScale) centeredIn(upBox, upScale) else centeredIn(leftBox, leftScale)
                }
            }
        }
        return placed.copy(x = placed.x + drift.x, y = placed.y + drift.y)
    }

    /**
     * 文字水平留白（px）：可读文字的横跨收进 [layoutRect] 水平区间——不进相机带（cutout
     * 收缩 + 漂移余量都在内）、不出圆角弧深的水平投影，cutout 在左/在右同样成立；两侧再
     * 不小于内容自身的设计留白 [designGutterPx]。0009「铺满含相机带」只授权纯视觉背景，
     * 承载信息的文字不进带（相机模组会把带内内容物理挡住）。纵向留白各内容自理。
     */
    fun textHorizontalPadding(windowWidth: Int, designGutterPx: Int): PxPadding {
        val gutter = designGutterPx.coerceAtLeast(0)
        return PxPadding(
            start = maxOf(gutter, layoutRect.left),
            end = maxOf(gutter, windowWidth - layoutRect.right),
        )
    }

    private companion object {

        /** 漂移相位数：四角轮流，周期 4 分钟。 */
        const val DRIFT_PHASES = 4

        /** 相位下标（0..3 = （−,−）→（+,−）→（+,+）→（−,+））。 */
        const val PHASE_TOP_RIGHT = 1
        const val PHASE_BOTTOM_RIGHT = 2
        const val PHASE_BOTTOM_LEFT = 3

        /** 装得下 = 原尺寸。 */
        const val NO_SCALE = 1.0

        /** 几何病态（安全矩形退化为空）= 零缩放，内容不上屏。 */
        const val DEGENERATE_SCALE = 0.0
    }
}

/**
 * 显示几何约束（纯函数）：
 * - cutout 收缩：对每个 cutout 取「显示边到它远边」的四向进深，**最小进深所在的边**
 *   承担收缩（同进深的边都收）。任选一边的整条 slab 都盖住该 cutout，所以内容安全矩形
 *   与 cutout 不交是构造性成立的；选最小进深是为了少丢可用区（本机背屏左带 296px
 *   落在左边、主屏挖孔 150px 落在上边，与平台 safeInset 口径一致）；
 * - 圆角收缩：每边再收圆角半径（与 cutout 收缩取 max）——四角被圆角切掉的角部不可用区
 *   到内容安全矩形的距离 ≥ 半径本身（实机 97px，见 `docs/poc-findings.md` 票 #26）；
 * - 漂移边界：±幅度，按安全矩形可承受范围收紧（不超过半宽/半高）。
 */
object DisplaySafeArea {

    fun resolve(geometry: DisplayGeometry): SafeArea {
        val width = geometry.width.coerceAtLeast(0)
        val height = geometry.height.coerceAtLeast(0)
        val radius = geometry.cornerRadius.coerceAtLeast(0)

        var leftInset = radius
        var topInset = radius
        var rightInset = radius
        var bottomInset = radius

        for (cutout in geometry.cutouts) {
            // 「显示边 → cutout 远边」的四向进深；不贴边的居中开孔同样被任一最小边的 slab 盖住。
            val fromLeft = cutout.right.coerceAtLeast(0)
            val fromRight = (width - cutout.left).coerceAtLeast(0)
            val fromTop = cutout.bottom.coerceAtLeast(0)
            val fromBottom = (height - cutout.top).coerceAtLeast(0)
            val minDepth = minOf(fromLeft, fromRight, fromTop, fromBottom)
            if (fromLeft == minDepth) leftInset = maxOf(leftInset, minDepth)
            if (fromRight == minDepth) rightInset = maxOf(rightInset, minDepth)
            if (fromTop == minDepth) topInset = maxOf(topInset, minDepth)
            if (fromBottom == minDepth) bottomInset = maxOf(bottomInset, minDepth)
        }

        val left = leftInset.coerceIn(0, width)
        val top = topInset.coerceIn(0, height)
        val right = (width - rightInset).coerceIn(left, width)
        val bottom = (height - bottomInset).coerceIn(top, height)
        val contentRect = PxRect(left, top, right, bottom)

        val driftBounds = PxOffset(
            x = geometry.driftAmplitude.x.coerceAtLeast(0).coerceAtMost(contentRect.width / 2),
            y = geometry.driftAmplitude.y.coerceAtLeast(0).coerceAtMost(contentRect.height / 2),
        )
        return SafeArea(contentRect, driftBounds)
    }
}
