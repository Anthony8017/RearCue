package com.rearcue.poc.rear

/**
 * 显示几何约束（票 #26）：cutout 矩形 + 圆角半径 + 漂移幅度 → 内容安全矩形 + 漂移边界。
 *
 * 纯 Kotlin（同 [RearDisplayLocator] 判例）：Android 层只负责从系统 DisplayCutout /
 * RoundedCorner 采集输入、照单执行输出，不自算几何、不硬编码机型数字。
 */

/** 像素矩形，左闭右开（[right]/[bottom] 不含）。 */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = (right - left).coerceAtLeast(0)

    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** 像素偏移。 */
data class PxOffset(val x: Int, val y: Int)

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
    fun fitScale(contentWidth: Int, contentHeight: Int): Double = when {
        contentWidth <= 0 || contentHeight <= 0 -> NO_SCALE
        layoutRect.width <= 0 || layoutRect.height <= 0 -> DEGENERATE_SCALE
        else -> minOf(
            NO_SCALE,
            layoutRect.width.toDouble() / contentWidth,
            layoutRect.height.toDouble() / contentHeight,
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
