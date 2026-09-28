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

import kotlin.math.ceil
import kotlin.math.sqrt

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

/** Detail 文字块在滚动内容中的首尾留白（px）；短内容的总高度恰好填满视口。 */
data class DetailTextPadding(val before: Int, val after: Int)

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
 *
 * 可读正文（票 #97「排满」）另走 [textHorizontalPadding]：右缘故意越出 [contentRect]
 * 到屏缘 8px，靠**文字垂直位置**判断是否进圆角弧区、进则外扩，与角部不可用区仍不相交。
 */
data class SafeArea(
    val contentRect: PxRect,
    val driftBounds: PxOffset,
    /** 窗口宽（px，票 #97）：文字弧区外扩的横坐标基准（[DisplaySafeArea.resolve] 填入）。 */
    val windowWidth: Int = 0,
    /** 窗口高（px，票 #97）：文字弧区外扩的纵坐标基准。 */
    val windowHeight: Int = 0,
    /** 圆角半径（px，票 #97）：弧区外扩判据——[contentRect] 里它与 cutout 进深已取 max，需单独留档。 */
    val cornerRadius: Int = 0,
    /** cutout 的左右进深（px，不含圆角）：右带在右时文字右缘的避让地板。 */
    val cutoutSides: PxPadding = PxPadding(0, 0),
    /** cutout 的上下进深：Detail 的 8px 留边不得覆盖上/下方开孔。 */
    val cutoutVertical: PxPadding = PxPadding(0, 0),
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
     * 文字水平留白（px，票 #97「排满」）：可读正文（Detail 卡片、Agent 对话）的横跨。
     * 渲染层只报文字块自己的垂直占位，本函数一次性给出左右留白——**同一个纯函数出口**。
     *
     * - [PxPadding.start] 照旧 = max(内容设计留白, [layoutRect].left)：左缘由 cutout 收缩
     *   ＋漂移余量抬高（实机相机带 296 + 8 → 304），0009「铺满含相机带」只授权纯视觉背景，
     *   承载信息的文字不进带（相机模组会把带内内容物理挡住）。左缘落在 x ≥ 圆角半径处，
     *   恒在角部不可用区之外，无需外扩。
     * - [PxPadding.end] = max(屏缘视觉地板 [DisplaySafeArea.TEXT_EDGE_GUTTER_PX]、右侧 cutout
     *   避让（含漂移余量）、圆角弧区外扩量)：文字排多满排多满，只有垂直位置
     *   （[textTopPx]/[textBottomPx]，含漂移到极限位）触到屏幕圆角弧区时才把右缘外扩，
     *   构造性保证文字矩形与四角圆角不可用区不相交（不被圆角切角）。
     *
     * [textTopPx]/[textBottomPx] 是文字块在**窗口坐标系**里的上下缘（渲染层照自己的上下
     * 内边距报入），纵向留白各内容自理。入参须来自 [DisplaySafeArea.resolve] 的产物——
     * 手工拼的 [SafeArea]（窗口尺寸/圆角为 0）不带弧区信息，外扩判据会退化为 0。
     *
     * 漂移的口径：Detail 卡片与 Agent 层都是整屏固定落位、**不在漂移子树里**，故右距按窗口
     * 锚定（实测判据，不平移）；漂移只出现在两处——①左缘/cutout 地板的余量（沿 0011 口径）
     * ②弧区判据的**垂直**最坏位置（文字可能处在的最高/最低点），使外扩判定不依赖漂移相位。
     */
    fun textHorizontalPadding(designGutterPx: Int, textTopPx: Int, textBottomPx: Int): PxPadding {
        val gutter = designGutterPx.coerceAtLeast(0)
        return PxPadding(
            start = maxOf(gutter, layoutRect.left),
            end = maxOf(
                DisplaySafeArea.TEXT_EDGE_GUTTER_PX,
                cutoutSides.end + driftBounds.x,
                arcEndPadding(textTopPx, textBottomPx),
            ),
        )
    }

    /**
     * Detail 的阅读视口：上下最小 8 物理 px，水平先保留直线区的完整阅读宽度。
     * 圆角避让交给 [detailTextPadding] 按实际行宽算首尾留白，不把整个视口的右距推到半径。
     * Agent Mirror 仍使用 [textHorizontalPadding]，其既有留白与对齐不变。
     */
    fun detailTextViewport(designGutterPx: Int): PxRect {
        val left = maxOf(designGutterPx.coerceAtLeast(0), layoutRect.left).coerceIn(0, windowWidth)
        val top = maxOf(DisplaySafeArea.TEXT_EDGE_GUTTER_PX, cutoutVertical.start + driftBounds.y)
            .coerceIn(0, windowHeight)
        return PxRect(
            left = left,
            top = top,
            right = (windowWidth - maxOf(DisplaySafeArea.TEXT_EDGE_GUTTER_PX, cutoutSides.end + driftBounds.x))
                .coerceIn(left, windowWidth),
            bottom = (windowHeight - maxOf(DisplaySafeArea.TEXT_EDGE_GUTTER_PX, cutoutVertical.end + driftBounds.y))
                .coerceIn(top, windowHeight),
        )
    }

    /**
     * [lines] 是排版器在文字块内实际排出的行框，x 相对 [viewport] 左缘、y 相对文字块顶部。
     * 首端按每一行的真实横跨反算圆弧允许的最小 y，尾端同理；只有靠近圆角的行会增加留白。
     * 内容放得下则整个标题+正文居中，放不下则从开头进入且滚到末尾后最后一行完整可读。
     * 滚动中视口边缘允许常规的局部裁剪，每一行都能滚到完整阅读位置；不动态换行/缩字。
     */
    fun detailTextPadding(viewport: PxRect, textHeight: Int, lines: List<PxRect>): DetailTextPadding {
        val height = textHeight.coerceAtLeast(0)
        var earliestTop = viewport.top
        var latestTop = viewport.bottom - height
        for (line in lines) {
            val arcInset = textArcVerticalInset(viewport.left + line.left, viewport.left + line.right)
            earliestTop = maxOf(earliestTop, arcInset - line.top)
            latestTop = minOf(latestTop, windowHeight - arcInset - line.bottom)
        }
        if (earliestTop <= latestTop) {
            val centeredTop = (viewport.top + (viewport.height - height) / 2).coerceIn(earliestTop, latestTop)
            val before = centeredTop - viewport.top
            return DetailTextPadding(before, viewport.height - height - before)
        }
        return DetailTextPadding(
            before = (earliestTop - viewport.top).coerceAtLeast(0),
            after = (viewport.bottom - height - latestTop).coerceAtLeast(0),
        )
    }

    /** 固定 Detail 不漂移；实际行左右缘反算上下圆弧可读边界（向上取整避免亚像素切角）。 */
    private fun textArcVerticalInset(left: Int, right: Int): Int {
        val r = cornerRadius.coerceAtMost(minOf(windowWidth, windowHeight) / 2)
        if (r <= 0) return 0
        val edgeDistance = minOf(left, windowWidth - right).coerceIn(0, r)
        val dx = (r - edgeDistance).toDouble()
        return ceil(r - sqrt(r.toDouble() * r - dx * dx)).toInt()
    }

    /**
     * 圆角弧区外扩量（px）：文字垂直区间（含漂移到极限位）的上/下缘触到圆角弧区时，
     * 右缘须收回到该高度处圆弧的可用边界之内——返回右缘因此要多留的留白；不在弧区、
     * 无圆角或几何未填时返回 0。
     */
    private fun arcEndPadding(textTopPx: Int, textBottomPx: Int): Int {
        val r = cornerRadius
        if (r <= 0 || windowWidth <= 0 || windowHeight <= 0) return 0
        val topWorst = textTopPx - driftBounds.y      // 漂移到上限：文字的最高位置
        val bottomWorst = textBottomPx + driftBounds.y // 漂移到下限：文字的最低位置
        var maxRightX = windowWidth.toDouble()
        // 上排圆角：文字上缘进入弧区（且文字覆盖到屏顶弧区）时收右缘。
        if (topWorst < r && bottomWorst > 0) {
            maxRightX = minOf(maxRightX, arcRightX(topWorst.coerceAtLeast(0), fromTop = true))
        }
        // 下排圆角：文字下缘进入弧区（且文字覆盖到屏底弧区）时收右缘。
        if (bottomWorst > windowHeight - r && topWorst < windowHeight) {
            maxRightX = minOf(maxRightX, arcRightX(bottomWorst.coerceAtMost(windowHeight), fromTop = false))
        }
        // 右缘 = 窗口宽 − 外扩后边界；向上取整（留白是整数 px，宁多勿越界）。
        return ceil(windowWidth - maxRightX).toInt().coerceAtLeast(0)
    }

    /**
     * 高度 y 处圆弧的可用右界（px）：右上角弧圆心 (宽−r, r)、右下角弧圆心 (宽−r, 高−r)，
     * 圆心右侧弧上的 x 即可用与不可用的分界——再往右且在 r×r 角块内就是圆角不可用区。
     * 角块之外（x < 宽−r）本就不在不可用区内，故分界取弧上的点即可。
     */
    private fun arcRightX(y: Int, fromTop: Boolean): Double {
        val r = cornerRadius
        val centerY = if (fromTop) r else windowHeight - r
        val dy = (y - centerY).toDouble()
        val inside = (r * r - dy * dy).toDouble().coerceAtLeast(0.0)
        return (windowWidth - r) + sqrt(inside)
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

    /**
     * 可读正文右距屏缘的视觉地板（px，票 #97）：本机实测 **8px**，按所见钉死、**不做 DPI
     * 换算**（53dp@450dpi 折算是 0011 的旧口径，本值推翻之）；换机型须重新实机量。
     * 它是 [SafeArea.textHorizontalPadding] 右留白的下限，不是上限——文字垂直位置进圆角
     * 弧区、或右侧有 cutout 时自动外扩。
     */
    const val TEXT_EDGE_GUTTER_PX = 8

    fun resolve(geometry: DisplayGeometry): SafeArea {
        val width = geometry.width.coerceAtLeast(0)
        val height = geometry.height.coerceAtLeast(0)
        val radius = geometry.cornerRadius.coerceAtLeast(0)

        var leftInset = radius
        var topInset = radius
        var rightInset = radius
        var bottomInset = radius
        // cutout 自身的左右进深（不含圆角）：文字右缘避带要与圆角分账（圆角由弧区外扩承担）。
        var cutoutLeft = 0
        var cutoutRight = 0
        var cutoutTop = 0
        var cutoutBottom = 0

        for (cutout in geometry.cutouts) {
            // 「显示边 → cutout 远边」的四向进深；不贴边的居中开孔同样被任一最小边的 slab 盖住。
            val fromLeft = cutout.right.coerceAtLeast(0)
            val fromRight = (width - cutout.left).coerceAtLeast(0)
            val fromTop = cutout.bottom.coerceAtLeast(0)
            val fromBottom = (height - cutout.top).coerceAtLeast(0)
            val minDepth = minOf(fromLeft, fromRight, fromTop, fromBottom)
            if (fromLeft == minDepth) {
                leftInset = maxOf(leftInset, minDepth)
                cutoutLeft = maxOf(cutoutLeft, minDepth)
            }
            if (fromRight == minDepth) {
                rightInset = maxOf(rightInset, minDepth)
                cutoutRight = maxOf(cutoutRight, minDepth)
            }
            if (fromTop == minDepth) {
                topInset = maxOf(topInset, minDepth)
                cutoutTop = maxOf(cutoutTop, minDepth)
            }
            if (fromBottom == minDepth) {
                bottomInset = maxOf(bottomInset, minDepth)
                cutoutBottom = maxOf(cutoutBottom, minDepth)
            }
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
        return SafeArea(
            contentRect = contentRect,
            driftBounds = driftBounds,
            windowWidth = width,
            windowHeight = height,
            cornerRadius = radius,
            cutoutSides = PxPadding(start = cutoutLeft, end = cutoutRight),
            cutoutVertical = PxPadding(start = cutoutTop, end = cutoutBottom),
        )
    }
}
