package com.rearcue.poc.rear

/**
 * 显示几何约束（票 #26）：cutout 矩形 + 圆角半径 + 漂移幅度 → 内容安全矩形 + 漂移边界。
 *
 * 纯 Kotlin（同 [RearDisplayLocator] 判例）：Android 层只负责从系统 DisplayCutout /
 * RoundedCorner 采集输入、照单执行输出，不自算几何、不硬编码机型数字。
 */

import kotlin.math.ceil
import kotlin.math.sqrt

/** 像素矩形，左闭右开（[right]/[bottom] 不含）。 */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = (right - left).coerceAtLeast(0)

    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** 像素偏移。 */
data class PxOffset(val x: Int, val y: Int)

/** 像素水平留白（[start] 左、[end] 右）。 */
data class PxPadding(val start: Int, val end: Int)

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
    fun fitScale(contentWidth: Int, contentHeight: Int): Double = when {
        contentWidth <= 0 || contentHeight <= 0 -> NO_SCALE
        layoutRect.width <= 0 || layoutRect.height <= 0 -> DEGENERATE_SCALE
        else -> minOf(
            NO_SCALE,
            layoutRect.width.toDouble() / contentWidth,
            layoutRect.height.toDouble() / contentHeight,
        )
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
        return SafeArea(
            contentRect = contentRect,
            driftBounds = driftBounds,
            windowWidth = width,
            windowHeight = height,
            cornerRadius = radius,
            cutoutSides = PxPadding(start = cutoutLeft, end = cutoutRight),
        )
    }
}
