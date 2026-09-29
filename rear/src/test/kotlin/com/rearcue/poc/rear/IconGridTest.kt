package com.rearcue.poc.rear

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IconGridTest {
    private val safe = DisplaySafeArea.resolve(
        DisplayGeometry(904, 572, listOf(PxRect(0, 0, 296, 572)), 97, PxOffset(8, 8)),
    )

    private fun slot(width: Int = 199) = chargingNumberPlaceholder(
        chargingNumberRect(904, 572, 97, 45, width, 106, verticalExtraInsetPx = 8), 23,
    )

    private fun placement(count: Int, number: PxRect? = null, drift: PxOffset = PxOffset(0, 0), badges: Boolean = true) =
        assertNotNull(notificationIconPlacement(
            List(count) { IconGridEntry("app$it", it + 1) }, safe,
            targetIconSizePx = 162, gapXPx = 23, gapYPx = 23, chipWidthPx = 101, chipHeightPx = 56,
            showBadges = badges, numberPlaceholder = number, drift = drift,
        ))

    @Test
    fun `单条不出角标 同应用多条也出角标`() {
        assertFalse(IconGrid.isGridMode(mapOf("a" to 1)))
        assertTrue(IconGrid.isGridMode(mapOf("a" to 2)))
        assertTrue(IconGrid.isGridMode(mapOf("a" to 1, "b" to 1)))
        assertTrue(IconGrid.isGridMode(mapOf("a" to Int.MAX_VALUE, "b" to 1)))
        assertFalse(IconGrid.isGridMode(emptyMap()))
    }

    @Test
    fun `桌面参照的162像素不因数量 充电或三位电量缩水`() {
        for (number in listOf(null, slot(152), slot(199), slot(240))) {
            for (count in 1..12) {
                assertEquals(162, placement(count, number).iconSizePx, "count=$count number=$number")
            }
        }
        assertEquals(162, placement(1, slot(), badges = false).iconSizePx, "单条也不能回到270px")
    }

    @Test
    fun `未存在的行和溢出徽标不再进入图标包围盒`() {
        val one = placement(1).grid
        val six = placement(6).grid
        assertEquals(178, one.width)
        assertEquals(178, one.height)
        assertEquals(548, six.width)
        assertEquals(363, six.height)
        assertEquals(six.width, placement(7).grid.width)
        assertEquals(six.height, placement(7).grid.height)
    }

    @Test
    fun `最多三列两行 每行居中 顺序及完整计数透传`() {
        for (count in 1..9) {
            val grid = placement(count).grid
            assertEquals(minOf(count, 6), grid.cells.size)
            assertEquals((count - 6).coerceAtLeast(0), grid.overflow)
            assertEquals(List(minOf(count, 6)) { "app$it" }, grid.cells.map { it.app })
            assertEquals(List(minOf(count, 6)) { it + 1 }, grid.cells.map { it.unread })
            grid.cells.groupBy { it.row }.values.forEach { row ->
                assertTrue(row.size <= 3)
                assertEquals((grid.width - (row.size * 162 + (row.size - 1) * 23 + 16)) / 2, row.first().x)
                row.zipWithNext().forEach { (a, b) -> assertEquals(185, b.x - a.x) }
            }
            assertTrue(grid.cells.all { it.row in 0..1 })
        }
    }

    @Test
    fun `桌面角标锚点在右上外探16像素 中心误差小于一像素`() {
        val badge = iconBadgeLayout(162, 56, 11, 18, 34)
        val overhang = placement(6).badgeOverhangPx
        assertEquals(16, overhang)
        val desktopInward = 14.5 / 186 * 162
        assertEquals(-desktopInward, overhang - badge.width / 2.0, 1.0)
        assertEquals(desktopInward, badge.height / 2.0 - overhang, 1.0)
    }

    @Test
    fun `全部漂移相位中 图标角标完整避开相机和真实圆角且不撞电量`() {
        for (count in 1..9) for (number in listOf(null, slot(152), slot(), slot(240))) for (phase in 0..3) {
            val drift = safe.driftFor(phase)
            val p = placement(count, number, drift)
            val shiftedSlot = number?.shift(drift)
            val cellBoxes = p.grid.cells.map { c ->
                PxRect(p.block.x + c.x, p.block.y + c.y - p.badgeOverhangPx,
                    p.block.x + c.x + p.iconSizePx + p.badgeOverhangPx, p.block.y + c.y + p.iconSizePx)
            }
            (cellBoxes + listOfNotNull(p.chip)).forEach { rect ->
                assertSafe(rect)
                shiftedSlot?.let { assertFalse(rect.overlaps(it), "$rect overlaps $it") }
            }
            cellBoxes.forEachIndexed { i, rect ->
                cellBoxes.drop(i + 1).forEach { assertFalse(rect.overlaps(it)) }
            }
        }
    }

    @Test
    fun `不充电加N居中 充电时在电量左侧的下方空白居中`() {
        val plain = placement(9)
        val plainChip = assertNotNull(plain.chip)
        assertEquals(3, plain.grid.overflow)
        assertTrue(abs((plainChip.left + plainChip.right) / 2.0 - 600) <= 1)
        val number = slot()
        val charging = placement(9, number)
        val chip = assertNotNull(charging.chip)
        assertTrue(chip.top >= charging.block.rect.bottom + 23)
        assertTrue(chip.right <= number.left)
        assertTrue(abs((chip.left + chip.right) / 2.0 - (304 + number.left) / 2.0) <= 1)
    }

    @Test
    fun `长计数完整收在角标底片内且不跨进邻格`() {
        for (textWidth in listOf(18, 36, 54, 72, 180)) {
            val badge = iconBadgeLayout(162, 56, 11, textWidth, 34)
            assertTrue(badge.width <= 162)
            assertTrue(badge.textX + textWidth * badge.textScale <= badge.width + 0.001)
            assertTrue(badge.textY + 34 * badge.textScale <= badge.height + 0.001)
            assertTrue(16 - badge.width + 162 >= 0, "加宽只覆盖自己的图标")
        }
    }

    @Test
    fun `电量仅下移37像素 字号盒不变且全部漂移相位仍在物理圆角内`() {
        val old = chargingNumberRect(904, 572, 97, 45, 199, 106)
        val moved = chargingNumberRect(904, 572, 97, 45, 199, 106, verticalExtraInsetPx = 8)
        assertEquals(old.width, moved.width)
        assertEquals(old.height, moved.height)
        assertEquals(old.left, moved.left)
        assertEquals(old.top + 37, moved.top)
        for (phase in 0..3) assertSafe(moved.shift(safe.driftFor(phase)))
    }

    @Test
    fun `小设备确实放不下才缩小 图标角标仍保留安全边界`() {
        val small = DisplaySafeArea.resolve(DisplayGeometry(400, 300, emptyList(), 30, PxOffset(4, 4)))
        val p = assertNotNull(notificationIconPlacement(List(6) { IconGridEntry("a$it", 1) }, small,
            162, 23, 23, 101, 56, true))
        assertTrue(p.iconSizePx < 162)
        assertTrue(p.block.rect.left >= 4 && p.block.rect.right <= 396)
        assertTrue(p.block.rect.top >= 4 && p.block.rect.bottom <= 296)
    }

    @Test
    fun `跨六格边界换挡 其余图标按新档重排且过渡帧从旧档连到新档`() {
        // 桌面参照下 7 枚不缩尺寸；只有小设备才逼出「档位尺寸收口」这个真正的换挡场景。
        val small = DisplaySafeArea.resolve(DisplayGeometry(400, 300, emptyList(), 30, PxOffset(4, 4)))
        fun p(count: Int) = assertNotNull(notificationIconPlacement(
            List(count) { IconGridEntry("app$it", it + 1) }, small, 162, 23, 23, 101, 56, true,
        ))
        val six = p(6)
        val seven = p(7)
        assertTrue(
            seven.iconSizePx < six.iconSizePx,
            "跨过容量边界应逐像素收口：${six.iconSizePx} -> ${seven.iconSizePx}",
        )
        assertEquals(1, seven.grid.overflow)
        assertEquals(6, seven.grid.cells.size)
        assertEquals(List(6) { "app$it" }, seven.grid.cells.map { it.app })

        // 其余图标的新位置：三列两行、每行居中、同列间距 = 新尺寸 + 间距。
        val columns = minOf(7, IconGrid.COLUMNS)
        assertEquals(columns * seven.iconSizePx + (columns - 1) * 23 + seven.badgeOverhangPx, seven.grid.width)
        assertEquals(2 * seven.iconSizePx + 23 + seven.badgeOverhangPx, seven.grid.height)
        seven.grid.cells.groupBy { it.row }.values.forEach { row ->
            assertEquals(
                (seven.grid.width - (row.size * seven.iconSizePx + (row.size - 1) * 23 + seven.badgeOverhangPx)) / 2,
                row.first().x,
            )
            row.zipWithNext().forEach { (a, b) -> assertEquals(seven.iconSizePx + 23, b.x - a.x) }
        }
        assertEquals(
            seven.iconSizePx + 23,
            seven.grid.cells.first { it.row == 1 }.y - seven.grid.cells.first { it.row == 0 }.y,
        )

        // 过渡帧：progress 0 仍是旧档落点，progress 1 与目标档出口逐枚一致。
        val entries = List(7) { IconGridEntry("app$it", it + 1) }
        val from = tierFrameOf(six)
        val toCenterX = seven.block.x + seven.block.width / 2f
        val toCenterY = seven.block.y + seven.block.height / 2f
        val atStart = assertNotNull(tierTransitionFrame(
            entries, six.iconSizePx, 23, 23, true, from, toCenterX, toCenterY, 0f,
        ))
        assertEquals(six.iconSizePx, atStart.iconSizePx)
        from.cells.forEach { old ->
            val now = atStart.cells.first { it.app == old.app }
            assertEquals(old.x, now.x)
            assertEquals(old.y, now.y)
        }
        val atEnd = assertNotNull(tierTransitionFrame(
            entries, seven.iconSizePx, 23, 23, true, from, toCenterX, toCenterY, 1f,
        ))
        assertEquals(tierFrameOf(seven), atEnd)
    }

    @Test
    fun `一行到两行换挡 逐枚落点按新档重排且过渡帧在两档之间`() {
        val small = DisplaySafeArea.resolve(DisplayGeometry(400, 300, emptyList(), 30, PxOffset(4, 4)))
        fun p(count: Int) = assertNotNull(notificationIconPlacement(
            List(count) { IconGridEntry("app$it", it + 1) }, small, 162, 23, 23, 101, 56, true,
        ))
        val one = p(1)
        val four = p(4)
        assertTrue(four.iconSizePx < one.iconSizePx, "两行换挡需要收口：${one.iconSizePx} -> ${four.iconSizePx}")

        // 换挡后的整组尺寸与居中：三列两行（3 + 1），第二行那枚单独居中。
        assertEquals(2, four.grid.cells.map { it.row }.distinct().size)
        assertEquals(3, four.grid.cells.count { it.row == 0 })
        assertEquals(1, four.grid.cells.count { it.row == 1 })
        assertEquals(200f, four.block.x + four.block.width / 2f, 0.51f)
        val bottom = four.grid.cells.single { it.row == 1 }
        assertEquals((four.grid.width - (four.iconSizePx + four.badgeOverhangPx)) / 2, bottom.x)

        // 过渡帧：旧图标落在两档落点之间；新入场的三枚直接落新位。
        val entries = List(4) { IconGridEntry("app$it", it + 1) }
        val from = tierFrameOf(one)
        val toCenterX = four.block.x + four.block.width / 2f
        val toCenterY = four.block.y + four.block.height / 2f
        val midSize = (one.iconSizePx + four.iconSizePx) / 2
        val atEnd = assertNotNull(tierTransitionFrame(
            entries, midSize, 23, 23, true, from, toCenterX, toCenterY, 1f,
        ))
        val mid = assertNotNull(tierTransitionFrame(
            entries, midSize, 23, 23, true, from, toCenterX, toCenterY, 0.5f,
        ))
        assertEquals(midSize, mid.iconSizePx)
        assertEquals(4, mid.cells.size)
        val old = from.cells.single()
        // 新入场的三枚直接落新位：两行结构在中间帧已经成立。
        assertEquals(2, mid.cells.filter { it.app != old.app }.map { it.y }.distinct().size)
        val midOld = mid.cells.first { it.app == old.app }
        val endOld = atEnd.cells.first { it.app == old.app }
        // 中间帧严格落在旧落点与当前尺寸下的新落点之间（x 与 y 各自单调）。
        assertTrue(minOf(old.x, endOld.x) <= midOld.x && midOld.x <= maxOf(old.x, endOld.x))
        assertTrue(minOf(old.y, endOld.y) <= midOld.y && midOld.y <= maxOf(old.y, endOld.y))
        assertTrue(midOld != endOld, "过渡中间帧不应提前到达新落点")
    }

    @Test
    fun `空输入不生成占位`() {
        assertEquals(null, notificationIconPlacement(emptyList(), safe, 162, 23, 23, 101, 56, false))
    }

    private fun PxRect.shift(p: PxOffset) = PxRect(left + p.x, top + p.y, right + p.x, bottom + p.y)

    /** 独立检验四角点是否位于物理圆角内部，不复用生产的反三角落位公式。 */
    private fun assertSafe(rect: PxRect) {
        assertTrue(rect.left >= 296 && rect.right <= 904 && rect.top >= 0 && rect.bottom <= 572, "$rect")
        for (x in listOf(rect.left, rect.right)) for (y in listOf(rect.top, rect.bottom)) {
            if (x > 807 && (y < 97 || y > 475)) {
                val dx = x - 807
                val dy = y - if (y < 97) 97 else 475
                assertTrue(dx * dx + dy * dy <= 97 * 97, "corner clipped: $rect at ($x,$y)")
            }
        }
    }
}
