package com.rearcue.poc.rear

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 显示几何约束的行为测试：只断言「几何输入 → 内容安全矩形 + 漂移边界」与「漂移后不出界」。
 *
 * 数值判例同 [RearDisplayLocatorTest]：本机实测数字（背屏 904×572、cutout 左带 296px、
 * 圆角 97、可用区 608×572；主屏 1220×2656、挖孔 150px、圆角 190）直接进断言，
 * 换机型只换输入（docs/poc-findings.md）。
 */
class DisplaySafeAreaTest {

    // ---- 本机基准（2026-09-22 实机探针，验收数字） ----

    private val rearWidth = 904
    private val rearHeight = 572
    private val rearCutoutLeftBand = PxRect(left = 0, top = 0, right = 296, bottom = 572)
    private val rearCornerRadius = 97
    private val rearDrift3dpAt450dpi = PxOffset(8, 8) // 3dp @ 450dpi = 8.4px → roundToPx 8

    private val mainWidth = 1220
    private val mainHeight = 2656
    private val mainCutoutPunch = PxRect(left = 573, top = 0, right = 647, bottom = 150)
    private val mainCornerRadius = 190

    private fun rearGeometry(drift: PxOffset = rearDrift3dpAt450dpi) = DisplayGeometry(
        width = rearWidth,
        height = rearHeight,
        cutouts = listOf(rearCutoutLeftBand),
        cornerRadius = rearCornerRadius,
        driftAmplitude = drift,
    )

    @Test
    fun `本机背屏实测：开孔在左时内容安全矩形避开相机带与圆角`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        assertEquals(PxRect(296, 97, 807, 475), safe.contentRect)
    }

    @Test
    fun `本机背屏实测：可用区恰为 608x572 且内容安全矩形落在其中`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val usableBand = PxRect(296, 0, 904, 572)

        // 验收基准：可用区 608×572（= 显示 904×572 减左侧相机带 296）。
        assertEquals(608, usableBand.width)
        assertEquals(572, usableBand.height)
        assertTrue(
            usableBand.contains(safe.contentRect),
            "内容安全矩形 ${safe.contentRect} 必须落在可用区 $usableBand 内",
        )
    }

    @Test
    fun `本机背屏实测：内容安全矩形离圆角不可用区距离 ≥ 97（恰压线达标）`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        // 四角逐一验证：内容安全矩形到「圆角切掉的角部不可用区」的最小距离 ≥ 圆角半径。
        for (corner in listOf("TL", "TR", "BL", "BR")) {
            val distance = cornerLensDistance(safe.contentRect, rearWidth, rearHeight, rearCornerRadius, corner)
            assertTrue(
                distance >= rearCornerRadius - 0.01,
                "$corner 角离圆角距离 $distance < 97",
            )
        }
    }

    @Test
    fun `本机主屏实测：开孔在上时内容安全矩形避挖孔与四角圆角`() {
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(mainWidth, mainHeight, listOf(mainCutoutPunch), mainCornerRadius, PxOffset(0, 0)),
        )

        assertEquals(PxRect(190, 190, 1030, 2466), safe.contentRect)
    }

    // ---- cutout 位置组合 ----

    @Test
    fun `圆角为 0 时开孔在左的可用区就是 608x572 本体`() {
        val safe = DisplaySafeArea.resolve(rearGeometry().copy(cornerRadius = 0))

        assertEquals(PxRect(296, 0, 904, 572), safe.contentRect)
    }

    @Test
    fun `圆角为 0 时开孔在上只收 safe top 150`() {
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(mainWidth, mainHeight, listOf(mainCutoutPunch), 0, PxOffset(0, 0)),
        )

        assertEquals(PxRect(0, 150, 1220, 2656), safe.contentRect)
    }

    @Test
    fun `无 cutout 时只按圆角收四边`() {
        val safe = DisplaySafeArea.resolve(DisplayGeometry(904, 572, emptyList(), 97, PxOffset(0, 0)))

        assertEquals(PxRect(97, 97, 807, 475), safe.contentRect)
    }

    @Test
    fun `cutout 在右在下与在左在上按远边进深对称收缩`() {
        val rightBand = DisplayGeometry(
            width = 904,
            height = 572,
            cutouts = listOf(PxRect(left = 608, top = 0, right = 904, bottom = 572)),
            cornerRadius = 0,
            driftAmplitude = PxOffset(0, 0),
        )
        val bottomBand = DisplayGeometry(
            width = 904,
            height = 572,
            cutouts = listOf(PxRect(left = 0, top = 472, right = 904, bottom = 572)),
            cornerRadius = 0,
            driftAmplitude = PxOffset(0, 0),
        )

        assertEquals(PxRect(0, 0, 608, 572), DisplaySafeArea.resolve(rightBand).contentRect)
        assertEquals(PxRect(0, 0, 904, 472), DisplaySafeArea.resolve(bottomBand).contentRect)
    }

    @Test
    fun `cutout 进深与圆角半径取 max 不叠加`() {
        // 左带 296 + r=97：左边收 296（不是 393），其余边收 97。
        val safe = DisplaySafeArea.resolve(rearGeometry().copy(driftAmplitude = PxOffset(0, 0)))

        assertEquals(PxRect(296, 97, 807, 475), safe.contentRect)
    }

    @Test
    fun `部分高度的左带仍从左边收，不误收上下边`() {
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(
                width = 904,
                height = 572,
                cutouts = listOf(PxRect(left = 0, top = 0, right = 296, bottom = 500)),
                cornerRadius = 0,
                driftAmplitude = PxOffset(0, 0),
            ),
        )

        assertEquals(PxRect(296, 0, 904, 572), safe.contentRect)
    }

    @Test
    fun `居中开孔从四向进深最小的边收`() {
        // 进深：左 500 / 右 404 / 上 400 / 下 272 ⇒ 从下边收 272，内容整体避开开孔。
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(
                width = 904,
                height = 572,
                cutouts = listOf(PxRect(left = 400, top = 300, right = 500, bottom = 400)),
                cornerRadius = 0,
                driftAmplitude = PxOffset(0, 0),
            ),
        )

        assertEquals(PxRect(0, 0, 904, 300), safe.contentRect)
    }

    @Test
    fun `内容安全矩形与任意 cutout 组合都不相交（构造性保证回归）`() {
        val cutouts = listOf(
            rearCutoutLeftBand,
            mainCutoutPunch,
            PxRect(0, 0, 296, 500),   // 部分高度左带
            PxRect(400, 300, 500, 400), // 居中开孔
            PxRect(0, 0, 100, 100),   // 贴角小块
        )
        for (cutout in cutouts) {
            val safe = DisplaySafeArea.resolve(
                DisplayGeometry(904, 572, listOf(cutout), 97, PxOffset(0, 0)),
            )
            val overlap = safe.contentRect.overlaps(cutout)
            assertTrue(!overlap, "cutout=$cutout 与内容安全矩形 ${safe.contentRect} 相交")
        }
    }

    // ---- 漂移幅度组合 ----

    @Test
    fun `漂移幅度为 0 时布局框即内容安全矩形`() {
        val safe = DisplaySafeArea.resolve(rearGeometry(drift = PxOffset(0, 0)))

        assertEquals(PxOffset(0, 0), safe.driftBounds)
        assertEquals(safe.contentRect, safe.layoutRect)
    }

    @Test
    fun `漂移幅度把布局框四边收紧一个幅度`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        assertEquals(PxOffset(8, 8), safe.driftBounds)
        assertEquals(PxRect(304, 105, 799, 467), safe.layoutRect)
    }

    @Test
    fun `漂移幅度超过可承受范围时按安全矩形收紧`() {
        val safe = DisplaySafeArea.resolve(rearGeometry(drift = PxOffset(10_000, 10_000)))

        // 半宽/半高收紧：511/2=255、378/2=189，布局框塌缩为 1×0 但不翻转（病态幅度的极限）。
        assertEquals(PxOffset(255, 189), safe.driftBounds)
        assertEquals(PxRect(551, 286, 552, 286), safe.layoutRect)
    }

    @Test
    fun `负漂移幅度按 0 处理`() {
        val safe = DisplaySafeArea.resolve(rearGeometry(drift = PxOffset(-5, -5)))

        assertEquals(PxOffset(0, 0), safe.driftBounds)
    }

    // ---- 漂移调度与边界 ----

    @Test
    fun `漂移调度四角轮驻，相邻分钟都是极限位`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        assertEquals(PxOffset(-8, -8), safe.driftFor(0))
        assertEquals(PxOffset(8, -8), safe.driftFor(1))
        assertEquals(PxOffset(8, 8), safe.driftFor(2))
        assertEquals(PxOffset(-8, 8), safe.driftFor(3))
    }

    @Test
    fun `漂移调度相位周期为 4 且每分钟都落在极限位`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        for (minute in 0..50) {
            assertEquals(safe.driftFor(minute), safe.driftFor(minute + 4), "相位周期 4")
            val drift = safe.driftFor(minute)
            assertEquals(safe.driftBounds.x, abs(drift.x), "x 恒在极限位")
            assertEquals(safe.driftBounds.y, abs(drift.y), "y 恒在极限位")
        }
    }

    @Test
    fun `漂移调度任一分钟都不越出内容安全矩形（万分钟扫描）`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        for (minute in 0..10_000) {
            val drift = safe.driftFor(minute)
            val placed = safe.layoutRect.translated(drift.x, drift.y)
            assertTrue(
                safe.contentRect.contains(placed),
                "minute=$minute 漂移 $drift 后 $placed 越出 ${safe.contentRect}",
            )
        }
    }

    @Test
    fun `漂移极限位 + 缩放内容整体仍在安全矩形内（本机组合）`() {
        // 端到端不变量：内容摆进布局框、按 fitScale 缩放、漂到四角极限位，全程在安全矩形内。
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val contentWidth = 382   // 时间 + 两枚 64dp 图标（450dpi）的实测量级
        val contentHeight = 387
        val scale = safe.fitScale(contentWidth, contentHeight)
        val scaledWidth = (contentWidth * scale).toInt()
        val scaledHeight = (contentHeight * scale).toInt()

        for (minute in 0..3) {
            val drift = safe.driftFor(minute)
            val placed = PxRect(
                left = safe.layoutRect.left + drift.x,
                top = safe.layoutRect.top + drift.y,
                right = safe.layoutRect.left + drift.x + scaledWidth,
                bottom = safe.layoutRect.top + drift.y + scaledHeight,
            )
            assertTrue(
                safe.contentRect.contains(placed),
                "minute=$minute 极限位 $drift：$placed 越出 ${safe.contentRect}",
            )
        }
    }

    // ---- fitScale ----

    private val fitBox = SafeArea(contentRect = PxRect(0, 0, 100, 100), driftBounds = PxOffset(10, 10))
    // layoutRect = [10,10,90,90] = 80×80

    @Test
    fun `fitScale 装得下时返回 1 不缩`() {
        assertEquals(1.0, fitBox.fitScale(40, 40), 1e-9)
        assertEquals(1.0, fitBox.fitScale(80, 80), 1e-9)
    }

    @Test
    fun `fitScale 超宽超高按较紧的边等比缩`() {
        assertEquals(0.5, fitBox.fitScale(160, 40), 1e-9)   // 超宽
        assertEquals(0.25, fitBox.fitScale(40, 320), 1e-9)  // 超高
        assertEquals(0.4, fitBox.fitScale(200, 100), 1e-9)  // 两边都超，取小
    }

    @Test
    fun `fitScale 非法尺寸返回 1，退化安全矩形返回 0`() {
        assertEquals(1.0, fitBox.fitScale(0, 40), 1e-9)
        assertEquals(1.0, fitBox.fitScale(40, -1), 1e-9)

        val degenerate = SafeArea(contentRect = PxRect(0, 0, 1, 1), driftBounds = PxOffset(10, 10))
        assertEquals(0.0, degenerate.fitScale(40, 40), 1e-9)
    }

    // ---- 文字水平留白（可读文字不进相机带）----

    @Test
    fun `本机背屏：文字横跨让开相机带且带漂移余量`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        // 设计留白 70px（lg）是地板；左缘被相机带抬到布局框左界 304，右缘被圆角弧深收到 799。
        val pad = safe.textHorizontalPadding(windowWidth = rearWidth, designGutterPx = 70)

        assertEquals(PxPadding(start = 304, end = 105), pad)
        val span = PxRect(pad.start, 0, rearWidth - pad.end, rearHeight)
        assertFalse(span.overlaps(rearCutoutLeftBand), "文字横跨 $span 压进相机带")
        // 漂移到四角极限位，文字横跨的水平区间仍不出内容安全矩形（漂移余量 8px 在内）。
        for (minute in 0..3) {
            val drift = safe.driftFor(minute)
            val moved = span.translated(drift.x, drift.y)
            assertTrue(
                moved.left >= safe.contentRect.left && moved.right <= safe.contentRect.right,
                "minute=$minute 漂移 $drift 后横跨 $moved 越出 ${safe.contentRect}",
            )
        }
    }

    @Test
    fun `正文阅读面 readingGutter 档：右留空 150、左缘仍由相机带抬到 304`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        // grill #89 定案：右留空取 150（≈readingGutter @450dpi），文字宽 450；
        // 左缘被相机带几何抬高（304），设计留白地板在左缘不起作用。
        val pad = safe.textHorizontalPadding(windowWidth = rearWidth, designGutterPx = 150)

        assertEquals(PxPadding(start = 304, end = 150), pad)
        assertEquals(450, rearWidth - pad.start - pad.end)
    }

    @Test
    fun `cutout 在右时文字横跨同样让开`() {
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(
                width = 904,
                height = 572,
                cutouts = listOf(PxRect(left = 608, top = 0, right = 904, bottom = 572)),
                cornerRadius = 0,
                driftAmplitude = PxOffset(0, 0),
            ),
        )

        val pad = safe.textHorizontalPadding(windowWidth = 904, designGutterPx = 48)

        assertEquals(48, pad.start)
        assertEquals(296, pad.end)
        val span = PxRect(pad.start, 0, 904 - pad.end, 572)
        assertFalse(span.overlaps(PxRect(608, 0, 904, 572)))
    }

    @Test
    fun `无 cutout 时文字留白不小于设计留白`() {
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(
                width = 1000,
                height = 600,
                cutouts = emptyList(),
                cornerRadius = 0,
                driftAmplitude = PxOffset(0, 0),
            ),
        )

        assertEquals(PxPadding(start = 48, end = 48), safe.textHorizontalPadding(1000, designGutterPx = 48))
    }

    @Test
    fun `文字横跨恒不超出布局框水平区间（任意几何）`() {
        val geometries = listOf(
            rearGeometry(),
            DisplayGeometry(904, 572, listOf(PxRect(608, 0, 904, 572)), 0, PxOffset(0, 0)),
            DisplayGeometry(904, 572, emptyList(), 97, PxOffset(8, 8)),
            DisplayGeometry(904, 572, listOf(PxRect(0, 0, 296, 400)), 0, PxOffset(4, 4)),
        )
        for (geometry in geometries) {
            val safe = DisplaySafeArea.resolve(geometry)
            val pad = safe.textHorizontalPadding(geometry.width, designGutterPx = 0)
            val span = PxRect(pad.start, 0, geometry.width - pad.end, geometry.height)
            assertEquals(safe.layoutRect.left, span.left, "$geometry")
            assertEquals(safe.layoutRect.right, span.right, "$geometry")
        }
    }

    // ---- 测试助手 ----

    private fun PxRect.translated(dx: Int, dy: Int) = PxRect(left + dx, top + dy, right + dx, bottom + dy)

    private fun PxRect.contains(other: PxRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    private fun PxRect.overlaps(other: PxRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** 点到矩形（闭）的距离；点在矩形内为 0。 */
    private fun distanceToRect(rect: PxRect, x: Double, y: Double): Double {
        val dx = maxOf(rect.left - x, 0.0, x - rect.right)
        val dy = maxOf(rect.top - y, 0.0, y - rect.bottom)
        return hypot(dx, dy)
    }

    /**
     * 内容安全矩形到某角「圆角切掉的不可用区」的最小距离（px）：
     * 不可用区 = 角部 r×r 方块减去内切圆盘；最近点必在其边界（圆弧 + 两条贴显示边的直边），
     * 对边界采样后取点到矩形距离的最小值（采样步长 ≤0.25°/1px，数值误差 <0.01px）。
     */
    private fun cornerLensDistance(
        rect: PxRect,
        width: Int,
        height: Int,
        radius: Int,
        corner: String,
    ): Double {
        val cx: Double
        val cy: Double
        val dirX: Int // 圆心指向显示角的水平方向
        val dirY: Int
        when (corner) {
            "TL" -> { cx = radius.toDouble(); cy = radius.toDouble(); dirX = -1; dirY = -1 }
            "TR" -> { cx = (width - radius).toDouble(); cy = radius.toDouble(); dirX = 1; dirY = -1 }
            "BL" -> { cx = radius.toDouble(); cy = (height - radius).toDouble(); dirX = -1; dirY = 1 }
            "BR" -> { cx = (width - radius).toDouble(); cy = (height - radius).toDouble(); dirX = 1; dirY = 1 }
            else -> error("未知角 $corner")
        }
        val cornerX = if (dirX > 0) width.toDouble() else 0.0
        val cornerY = if (dirY > 0) height.toDouble() else 0.0

        var min = Double.MAX_VALUE
        // 圆弧边界。
        for (step in 0..360) {
            val phi = Math.toRadians(step * 0.25)
            val x = cx + dirX * radius * kotlin.math.sin(phi)
            val y = cy + dirY * radius * kotlin.math.cos(phi)
            min = minOf(min, distanceToRect(rect, x, y))
        }
        // 两条直边（弧端点 → 显示角）。
        val arcStartX = cx
        val arcStartY = cy + dirY * radius
        val arcEndX = cx + dirX * radius
        val arcEndY = cy
        for (step in 0..radius) {
            val t = step.toDouble() / radius
            min = minOf(min, distanceToRect(rect, arcStartX + (cornerX - arcStartX) * t, arcStartY))
            min = minOf(min, distanceToRect(rect, arcEndX, arcEndY + (cornerY - arcEndY) * t))
        }
        return min
    }
}
