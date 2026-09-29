package com.rearcue.poc.rear

import androidx.compose.ui.unit.Density
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.readingGutterFloorPx
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
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

    // ---- 可读正文水平留白（排满：左避相机带 + 右距 8px + 弧区外扩，票 #97）----

    // 本机阅读面的垂直占位（窗口 px，与渲染层上下内边距同值）：
    // Detail 上下 lg=24dp@450dpi=67.5→68；Agent 上下 32dp@450dpi=90。
    private val detailTextTop = 68
    private val detailTextBottom = rearHeight - detailTextTop
    private val agentTextTop = 90
    private val agentTextBottom = rearHeight - agentTextTop

    @Test
    fun `本机背屏 Detail 阅读面：左缘 304 避相机带、右距屏缘 8px 排满`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        // 票 #97 推翻 0011 的「右留空 150」：右距 = 8px 视觉地板（按本机所见钉死、不做 DPI 换算），
        // 左缘仍由相机带几何抬到 304；designGutterPx 只作左缘地板（本例 150 在左缘不起作用）。
        val pad = safe.textHorizontalPadding(
            designGutterPx = 150,
            textTopPx = detailTextTop,
            textBottomPx = detailTextBottom,
        )

        assertEquals(PxPadding(start = 304, end = 8), pad)
        assertEquals(8, DisplaySafeArea.TEXT_EDGE_GUTTER_PX) // 视觉值钉死
        // 排满判据：正文宽 592，明显大于 0011 方案的 450（904−304−150）。
        assertEquals(592, rearWidth - pad.start - pad.end)
        assertTrue(rearWidth - pad.start - pad.end > 450, "正文宽未明显大于 150 方案")
        val span = PxRect(pad.start, detailTextTop, rearWidth - pad.end, detailTextBottom)
        assertFalse(span.overlaps(rearCutoutLeftBand), "文字横跨 $span 压进相机带")
    }

    @Test
    fun `Agent 对话正文右距与 Detail 同一规则：同为 8px、左缘同为 304`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        val pad = safe.textHorizontalPadding(
            designGutterPx = 150,
            textTopPx = agentTextTop,
            textBottomPx = agentTextBottom,
        )

        assertEquals(PxPadding(start = 304, end = 8), pad)
    }

    @Test
    fun `本机背屏：文字横跨让开相机带，漂移极限位仍不进带`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        // 设计留白是左缘地板（本例喂字面量 70，非令牌折算值：lg=24dp@450dpi=67.5 → roundToPx 68px）；
        // 左缘被相机带抬到布局框左界 304，右距是 8px 视觉地板。
        val pad = safe.textHorizontalPadding(
            designGutterPx = 70,
            textTopPx = detailTextTop,
            textBottomPx = detailTextBottom,
        )

        assertEquals(PxPadding(start = 304, end = 8), pad)
        val span = PxRect(pad.start, 0, rearWidth - pad.end, rearHeight)
        assertFalse(span.overlaps(rearCutoutLeftBand), "文字横跨 $span 压进相机带")
        // 漂移到四角极限位，左缘仍不进带、右缘不出屏（阅读面右距是窗口锚定值，见下例弧区判据）。
        for (minute in 0..3) {
            val drift = safe.driftFor(minute)
            val moved = span.translated(drift.x, drift.y)
            assertTrue(
                moved.left >= safe.contentRect.left && moved.right <= rearWidth,
                "minute=$minute 漂移 $drift 后横跨 $moved 越界",
            )
            assertFalse(moved.overlaps(rearCutoutLeftBand), "minute=$minute 漂移 $drift 后压进相机带")
        }
    }

    @Test
    fun `文字上缘进圆角弧区时右距自动外扩到 51（构造性不切角）`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        // 上缘 20、漂移极限位 −8 → 12，落在 r=97 弧区内：该高度圆弧可用右界
        // 807+√(97²−85²)=853.73，右缘收到 904−51=853 恰在其内。
        val pad = safe.textHorizontalPadding(designGutterPx = 150, textTopPx = 20, textBottomPx = 300)

        assertEquals(51, pad.end)
        assertTrue(rearWidth - pad.end <= 853.73, "右缘未收回圆弧可用边界内")
    }

    @Test
    fun `文字满高铺进上下弧区时右距外扩到圆角半径 97`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        // 上下缘都在弧区内：屏顶/屏底处的可用右界恰是 宽−r=807，右缘整段让出弧深投影。
        val pad = safe.textHorizontalPadding(designGutterPx = 150, textTopPx = 0, textBottomPx = rearHeight)

        assertEquals(97, pad.end)
    }

    @Test
    fun `文字在弧区之外时右距就是 8px 地板，圆角为 0 不外扩`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        // 中段（漂移极限位后上缘 192、下缘 408 都在弧区外）：地板说了算。
        assertEquals(8, safe.textHorizontalPadding(150, 200, 400).end)

        val noCorner = DisplaySafeArea.resolve(rearGeometry().copy(cornerRadius = 0))
        assertEquals(8, noCorner.textHorizontalPadding(150, 0, rearHeight).end)
    }

    @Test
    fun `cutout 在右时避带地板优先于 8px 视觉地板`() {
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(
                width = 904,
                height = 572,
                cutouts = listOf(PxRect(left = 608, top = 0, right = 904, bottom = 572)),
                cornerRadius = 0,
                driftAmplitude = PxOffset(0, 0),
            ),
        )

        val pad = safe.textHorizontalPadding(designGutterPx = 48, textTopPx = 100, textBottomPx = 472)

        assertEquals(PxPadding(start = 48, end = 296), pad)
        val span = PxRect(pad.start, 0, 904 - pad.end, 572)
        assertFalse(span.overlaps(PxRect(608, 0, 904, 572)))
    }

    @Test
    fun `无 cutout 时左缘仍吃设计留白地板、右距 8px 排满`() {
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(
                width = 1000,
                height = 600,
                cutouts = emptyList(),
                cornerRadius = 0,
                driftAmplitude = PxOffset(0, 0),
            ),
        )

        assertEquals(PxPadding(start = 48, end = 8), safe.textHorizontalPadding(48, 100, 500))
    }

    @Test
    fun `readingGutter 的 dp 转 px 向上取整：本机恰 150px 而非四舍五入的 149`() {
        // 转换环单测：53dp@450dpi=149.06，roundToPx 四舍五入会得 149、落不到 150 档；
        // readingGutterFloorPx 向上取整（地板不许向下取整）→ 150。上例喂的 designGutterPx=150
        // 字面量由此钉住——它现在只作**左缘**地板（右距 8px 见票 #97）。
        assertEquals(150, with(Density(2.8125f)) { readingGutterFloorPx() })
    }

    @Test
    fun `构造性保证：文字矩形在漂移极限位仍不与四角圆角不可用区相交`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val cases = listOf(
            "Detail 阅读面" to (detailTextTop to detailTextBottom),
            "Agent 阅读面" to (agentTextTop to agentTextBottom),
            "上缘贴屏顶" to (20 to 300),
            "满高" to (0 to rearHeight),
        )
        for ((name, range) in cases) {
            val (top, bottom) = range
            for (gutter in listOf(0, 150)) {
                val pad = safe.textHorizontalPadding(gutter, top, bottom)
                val span = PxRect(pad.start, top, rearWidth - pad.end, bottom)
                // 水平窗口锚定（Detail 卡片与 Agent 层都不在漂移子树里、右距是实测判据），
                // 纵向按漂移极限位取最坏位置——弧区判据不依赖漂移相位（票 #97「含漂移极限位」）。
                for (driftY in listOf(-safe.driftBounds.y, 0, safe.driftBounds.y)) {
                    val moved = span.translated(0, driftY)
                    for (corner in listOf("TL", "TR", "BL", "BR")) {
                        assertFalse(
                            textHitsCornerLens(moved, rearWidth, rearHeight, rearCornerRadius, corner),
                            "$name gutter=$gutter driftY=$driftY：$corner 角不可用区与文字 $moved 相交",
                        )
                    }
                }
                // 左缘避相机带按全相位平移复核（漂移把文字往左推的极限位）。
                for (minute in 0..3) {
                    val drift = safe.driftFor(minute)
                    val moved = span.translated(drift.x, drift.y)
                    assertFalse(moved.overlaps(rearCutoutLeftBand), "$name gutter=$gutter minute=$minute 压进相机带")
                }
            }
        }
    }

    @Test
    fun `文字横跨恒不越出窗口、右留白不小于 8px 地板（任意几何）`() {
        val geometries = listOf(
            rearGeometry(),
            DisplayGeometry(904, 572, listOf(PxRect(608, 0, 904, 572)), 0, PxOffset(0, 0)),
            DisplayGeometry(904, 572, emptyList(), 97, PxOffset(8, 8)),
            DisplayGeometry(904, 572, listOf(PxRect(0, 0, 296, 400)), 0, PxOffset(4, 4)),
        )
        for (geometry in geometries) {
            val safe = DisplaySafeArea.resolve(geometry)
            val pad = safe.textHorizontalPadding(
                designGutterPx = 0,
                textTopPx = 0,
                textBottomPx = geometry.height,
            )
            // 左缘恒由布局框左界抬着（cutout/圆角收缩 + 漂移余量）；右缘 ≥ 8px 视觉地板。
            assertEquals(safe.layoutRect.left, pad.start, "$geometry")
            assertTrue(pad.end >= DisplaySafeArea.TEXT_EDGE_GUTTER_PX, "$geometry end=${pad.end}")
            assertTrue(geometry.width - pad.end >= pad.start, "$geometry 横跨翻转")
        }
    }

    // ---- 充电电量数字占位与图标让位（票 #102）----

    // 本机折算档（450dpi = Density(2.8125f)，转换环由下方单测钉住）：md 16dp → 45px、
    // sm 8dp → 23px、图标 96dp → 270px；圆角 97 × 0.35 → 33 ⇒ 数字内缩 inset = 33 + 45 = 78。
    private val numberExtraInsetPx = 45
    private val numberGapPx = 23
    private val iconPx = 270
    private val iconGapXPx = 45 // FlowRow 横距 md
    private val iconGapYPx = 23 // FlowRow 竖距 sm

    private fun rearNumberRect(numberWidth: Int = 169, numberHeight: Int = 95): PxRect =
        chargingNumberRect(
            windowWidth = rearWidth,
            windowHeight = rearHeight,
            cornerRadiusPx = rearCornerRadius,
            extraInsetPx = numberExtraInsetPx,
            numberWidth = numberWidth,
            numberHeight = numberHeight,
        )

    @Test
    fun `数字内缩与缺口的 dp 转 px 档：md=45、sm=23、圆角项 33（450dpi）`() {
        // 上例的 45 / 23 / 78 / 33 字面量由此钉住（同 readingGutter 的转换环单测口径）。
        assertEquals(45, with(Density(2.8125f)) { RearCueSpacing.md.roundToPx() })
        assertEquals(23, with(Density(2.8125f)) { RearCueSpacing.sm.roundToPx() })
        assertEquals(33, (rearCornerRadius * 0.35f).toInt())
    }

    @Test
    fun `本机：电量数字本体贴整屏右下、圆角感知内缩 78`() {
        assertEquals(PxRect(657, 399, 826, 494), rearNumberRect())
    }

    @Test
    fun `数字占位 = 本体四向外扩缺口 23，本体 rect 不被改写`() {
        val rect = rearNumberRect()

        assertEquals(PxRect(634, 376, 849, 517), chargingNumberPlaceholder(rect, numberGapPx))
        assertEquals(PxRect(657, 399, 826, 494), rect)
        assertEquals(rect, chargingNumberPlaceholder(rect, 0))
        // 负缺口按 0 处理（外扩单调不越缩）。
        assertEquals(rect, chargingNumberPlaceholder(rect, -10))
    }

    @Test
    fun `无数字占位时图标落位与既有居中式逐位一致（不回退）`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val drift = PxOffset(8, 8)

        for ((blockWidth, blockHeight) in listOf(270 to 270, 270 to 563, 900 to 563, 100 to 40)) {
            val scale = safe.fitScale(blockWidth, blockHeight)
            val legacyX = (safe.layoutRect.left + drift.x + (safe.layoutRect.width - blockWidth * scale) / 2).roundToInt()
            val legacyY = (safe.layoutRect.top + drift.y + (safe.layoutRect.height - blockHeight * scale) / 2).roundToInt()
            val placed = safe.placeIconBlock(blockWidth, blockHeight, numberPlaceholder = null, drift = drift)

            assertEquals(scale, placed.scale, 1e-12, "block=${blockWidth}x$blockHeight")
            assertEquals(legacyX, placed.x, "block=${blockWidth}x$blockHeight x")
            assertEquals(legacyY, placed.y, "block=${blockWidth}x$blockHeight y")
        }
    }

    @Test
    fun `构造性：任意图标数 × 任意漂移，图标块与数字占位不相交且不出安全矩形`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val slot = chargingNumberPlaceholder(rearNumberRect(), numberGapPx)
        // 两种块尺寸口径（只当输入喂，函数不关心谁产的）：现 FlowRow 在布局框宽 493 下
        // 两枚 270 + 45 超框 → 每行 1 竖排；每行 3 的整组网格（#101 口径）。
        val patterns: List<Pair<String, (Int) -> Pair<Int, Int>>> = listOf(
            "单列 FlowRow" to { count -> iconPx to (count * iconPx + (count - 1) * iconGapYPx) },
            "每行 3 网格" to { count ->
                val rows = (count + 2) / 3
                (3 * iconPx + 2 * iconGapXPx) to (rows * iconPx + (rows - 1) * iconGapYPx)
            },
        )
        val drifts = listOf(PxOffset(0, 0)) + (0..3).map { safe.driftFor(it) }

        for ((name, blockSize) in patterns) {
            for (count in 1..30) {
                val (blockWidth, blockHeight) = blockSize(count)
                for (drift in drifts) {
                    val placed = safe.placeIconBlock(blockWidth, blockHeight, slot, drift)
                    // 数字与图标同量平移（漂移对两者是同一个偏移），同帧比较：
                    val slotHere = slot.translated(drift.x, drift.y)
                    assertFalse(
                        placed.rect.overlaps(slotHere),
                        "$name count=$count drift=$drift 块 ${placed.rect} 压进占位 $slotHere",
                    )
                    assertTrue(
                        safe.contentRect.contains(placed.rect),
                        "$name count=$count drift=$drift 块 ${placed.rect} 越出 ${safe.contentRect}",
                    )
                }
            }
        }
    }

    @Test
    fun `让位分支①：单图标上移让位，原缩放与水平居中不变`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val slot = chargingNumberPlaceholder(rearNumberRect(), numberGapPx)

        val placed = safe.placeIconBlock(iconPx, iconPx, slot, PxOffset(0, 0))

        // 原位 [417,151,687,421] 与占位相交 → 底边贴占位顶边 376，水平居中 x 原样
        //（(304 + (495-270)/2) = 416.5 → 417）。
        assertEquals(1.0, placed.scale, 1e-12)
        assertEquals(417, placed.x)
        assertEquals(slot.top - iconPx, placed.y)
        assertFalse(placed.rect.overlaps(slot))
        assertTrue(safe.contentRect.contains(placed.rect))
    }

    @Test
    fun `让位分支②：上移放不下时左移，原缩放不变`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val slot = chargingNumberPlaceholder(rearNumberRect(), numberGapPx)

        // 270×400：fitScale = 362/400 = 0.905（缩放保住），上移要出布局框顶边 → 左移。
        val placed = safe.placeIconBlock(iconPx, 400, slot, PxOffset(0, 0))

        assertEquals(0.905, placed.scale, 1e-12)
        assertEquals(slot.left - placed.width, placed.x)
        assertFalse(placed.rect.overlaps(slot))
        assertTrue(safe.contentRect.contains(placed.rect))
    }

    @Test
    fun `让位分支③：两条路线都放不下时按净空框收缩并在框内居中`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val slot = chargingNumberPlaceholder(rearNumberRect(), numberGapPx)

        // 每行 3 整组（900×563）：上移净空 271 高、左移净空 330 宽都放不下 →
        // 取缩放更大的上方净空框 271/563，底边贴占位顶边。
        val blockWidth = 3 * iconPx + 2 * iconGapXPx
        val blockHeight = 2 * iconPx + iconGapYPx
        val placed = safe.placeIconBlock(blockWidth, blockHeight, slot, PxOffset(0, 0))

        assertEquals(271.0 / 563.0, placed.scale, 1e-12)
        assertEquals(slot.top, placed.rect.bottom)
        assertFalse(placed.rect.overlaps(slot))
        assertTrue(safe.contentRect.contains(placed.rect))
    }

    // ---- Detail 居中、物理像素留白与局部圆角（spec 0012） ----

    @Test
    fun `Detail 阅读视口保留相机带以右的完整宽度和上下8物理像素`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val viewport = safe.detailTextViewport(150)

        assertEquals(PxRect(304, 8, 896, 564), viewport)
        assertFalse(viewport.overlaps(rearCutoutLeftBand))
        // 保留旧水平留白出口的兼容结果；两种详情现共用上述完整阅读视口。
        assertEquals(97, safe.textHorizontalPadding(150, 8, 564).end)
    }

    // ---- Agent 页右距 16dp（spec 0017 / 票 #169） ----

    @Test
    fun `正文不得顶进固定的会话标识行那条带（票 #169 实机验收修订）`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val viewport = safe.detailTextViewport(150)
        // 一段很矮的内容：默认（minBefore=0）会往视口正中放，离顶很近。
        val lines = listOf(PxRect(180, 0, 412, 62))
        val height = 62
        val plain = safe.detailTextPadding(viewport, height, lines)
        val reserved = safe.detailTextPadding(viewport, height, lines, minBeforePx = 68)

        assertTrue(
            reserved.before >= 68,
            "内容顶端距视口顶至少有预留带那么高（实机：提问泡的底衬压住了会话名）",
        )
        // 预留带是**下限**，不是加性偏移：内容矮到本来就居中得比它靠下时，不该把它推得更下。
        assertTrue(reserved.before >= plain.before - 1, "预留带不该把内容推得比居中更靠上")
        // 挤不下时下留白夹到 0（负值会让 Compose 的 padding 崩）。
        assertTrue(reserved.after >= 0, "下留白不得为负")
        assertTrue(reserved.before + height + reserved.after <= viewport.height)
    }

    @Test
    fun `预留带不传时行为逐值不变（Detail 不受影响）`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val viewport = safe.detailTextViewport(150)
        val lines = listOf(PxRect(180, 0, 412, 62), PxRect(50, 79, 542, 141))
        val height = 141
        assertEquals(
            safe.detailTextPadding(viewport, height, lines, minBeforePx = 0),
            safe.detailTextPadding(viewport, height, lines),
        )
    }

    @Test
    fun `Agent 页右距可按页加宽而 Detail 的默认值一字不动`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())

        // 默认（Detail / 会话选择器）：右距 8px，与既有判例逐值一致。
        assertEquals(896, safe.detailTextViewport(150).right)
        assertEquals(896, safe.detailTextViewport(150, rightInsetPx = 8).right)

        // Agent 页：右距 16 → 右缘再收 8px，左缘与上下不动。
        val agent = safe.detailTextViewport(150, rightInsetPx = 16)
        assertEquals(PxRect(304, 8, 888, 564), agent)
    }

    @Test
    fun `右距是下限不是上限——相机带避让更大时取大者`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        // 喂一个远小于相机带避让的右距：不许把文字推进带里，仍取相机带需要的那个更宽的值。
        val tiny = safe.detailTextViewport(150, rightInsetPx = 0)
        assertEquals(safe.detailTextViewport(150).right, tiny.right)
    }

    @Test
    fun `右距大到吃满版心时左右缘不反转`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val extreme = safe.detailTextViewport(150, rightInsetPx = safe.windowWidth)
        assertTrue(extreme.right >= extreme.left, "右缘不得越过左缘（否则版心宽度变负）")
        assertEquals(0, extreme.width)
    }

    @Test
    fun `Detail 短句多行仅标题及省略标题后均按实际剩余内容居中`() {        val safe = DisplaySafeArea.resolve(rearGeometry())
        val viewport = safe.detailTextViewport(150)
        val examples = listOf(
            listOf(PxRect(180, 0, 412, 62)), // 仅正文，或应用名标题被省略
            listOf(PxRect(150, 0, 442, 68)), // 仅标题，不保留不存在的标题正文间隙
            listOf(PxRect(200, 0, 392, 68), PxRect(50, 79, 542, 141)), // 标题 + 正文
            listOf(PxRect(200, 0, 392, 68), PxRect(0, 79, 592, 141), PxRect(100, 141, 492, 203)),
        )
        for (lines in examples) {
            val height = lines.last().bottom
            val padding = safe.detailTextPadding(viewport, height, lines)
            val blockTop = viewport.top + padding.before
            assertEquals(viewport.height, padding.before + height + padding.after)
            assertTrue(abs((2 * blockTop + height) - (viewport.top + viewport.bottom)) <= 1)
            for (line in lines) {
                val placed = line.translated(viewport.left, blockTop)
                assertTrue(viewport.contains(placed))
                assertEquals(viewport.left + viewport.right, placed.left + placed.right)
                assertFalse(textHitsCornerLens(placed, rearWidth, rearHeight, rearCornerRadius, "TR"))
                assertFalse(textHitsCornerLens(placed, rearWidth, rearHeight, rearCornerRadius, "BR"))
            }
        }
    }

    @Test
    fun `Detail 长文仅首尾实际碰圆角的行增加留边且全文保持原宽`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val viewport = safe.detailTextViewport(150)
        val lines = (0 until 24).map { row -> PxRect(0, row * 62, viewport.width, (row + 1) * 62) }
        val height = lines.last().bottom
        val padding = safe.detailTextPadding(viewport, height, lines)
        val maxScroll = padding.before + height + padding.after - viewport.height

        assertTrue(maxScroll > 0)
        assertTrue(padding.before > 0 && padding.after > 0)
        val first = lines.first().translated(viewport.left, viewport.top + padding.before)
        val last = lines.last().translated(viewport.left, viewport.top + padding.before - maxScroll)
        assertTrue(viewport.contains(first))
        assertTrue(viewport.contains(last))
        assertFalse(textHitsCornerLens(first, rearWidth, rearHeight, rearCornerRadius, "TR"))
        assertFalse(textHitsCornerLens(last, rearWidth, rearHeight, rearCornerRadius, "BR"))
        assertEquals(rearWidth - 8, first.right)
        assertEquals(rearWidth - 8, last.right)
        // 滚动边缘可局部裁剪，但每一行都有完整可读位置，不能永久截断任何一行。
        for (line in lines) {
            val targetScroll = (viewport.top + padding.before + (line.top + line.bottom) / 2 - rearHeight / 2)
                .coerceIn(0, maxScroll)
            val placed = line.translated(viewport.left, viewport.top + padding.before - targetScroll)
            assertTrue(viewport.contains(placed))
            assertFalse(textHitsCornerLens(placed, rearWidth, rearHeight, rearCornerRadius, "TR"))
            assertFalse(textHitsCornerLens(placed, rearWidth, rearHeight, rearCornerRadius, "BR"))
        }
    }

    @Test
    fun `Detail 窄首尾行不受中间宽行影响仍能用到上下8px`() {
        val safe = DisplaySafeArea.resolve(rearGeometry())
        val viewport = safe.detailTextViewport(150)
        val lines = (0 until 24).map { row ->
            val inset = if (row == 0 || row == 23) 100 else 0
            PxRect(inset, row * 62, viewport.width - inset, (row + 1) * 62)
        }
        val padding = safe.detailTextPadding(viewport, lines.last().bottom, lines)

        assertEquals(DetailTextPadding(0, 0), padding)
        assertEquals(8, viewport.top + padding.before)
        assertEquals(8, rearHeight - viewport.bottom + padding.after)
    }

    @Test
    fun `Detail 上下有cutout时8px地板不覆盖开孔且原共享结果不变`() {
        for (cutout in listOf(PxRect(400, 0, 500, 50), PxRect(400, 522, 500, 572))) {
            val safe = DisplaySafeArea.resolve(rearGeometry().copy(cutouts = listOf(cutout)))
            val viewport = safe.detailTextViewport(150)
            assertFalse(viewport.overlaps(cutout))
            assertTrue(viewport.top >= 8 && rearHeight - viewport.bottom >= 8)
        }
    }

    @Test
    fun `Detail 无圆角长文不额外增加首尾留白`() {
        val safe = DisplaySafeArea.resolve(rearGeometry().copy(cornerRadius = 0))
        val viewport = safe.detailTextViewport(150)
        val lines = (0 until 24).map { PxRect(0, it * 62, viewport.width, (it + 1) * 62) }
        assertEquals(DetailTextPadding(0, 0), safe.detailTextPadding(viewport, lines.last().bottom, lines))
    }

    // ---- 测试助手 ----

    private fun PxRect.translated(dx: Int, dy: Int) = PxRect(left + dx, top + dy, right + dx, bottom + dy)

    private fun PxRect.contains(other: PxRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    private fun PxRect.overlaps(other: PxRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /**
     * 文字矩形是否与某角的「圆角不可用区」相交（票 #97 构造性判据）：
     * 对 r×r 角块按 0.5px 网格取格心，「在角块内且在角圆盘外」的格心即不可用点，
     * 任一不可用点落进文字矩形内部即相交。判据是独立网格采样，**不复用实现的解析式**
     * （不自证）；圆盘上的边界点算可用（不可用区 = 角块 − 圆盘）。
     */
    private fun textHitsCornerLens(
        text: PxRect,
        width: Int,
        height: Int,
        radius: Int,
        corner: String,
    ): Boolean {
        val rightSide = corner.endsWith("R")
        val bottomSide = corner.startsWith("B")
        val boxLeft = if (rightSide) width - radius else 0
        val boxTop = if (bottomSide) height - radius else 0
        val cx = if (rightSide) width - radius else radius
        val cy = if (bottomSide) height - radius else radius
        val cells = radius * 2 // 0.5px 一格
        for (iy in 0 until cells) {
            for (ix in 0 until cells) {
                val px = boxLeft + (ix + 0.5) * 0.5
                val py = boxTop + (iy + 0.5) * 0.5
                if (hypot(px - cx, py - cy) <= radius) continue // 圆盘内 = 可用
                if (px > text.left && px < text.right && py > text.top && py < text.bottom) return true
            }
        }
        return false
    }

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
