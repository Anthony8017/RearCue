package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 纯图标网格排布的行为测试（issue #101 验收面）：行/列切分、整组与每行居中、+N 计数、
 * 角标数据透传、图标 pitch 恒定（96dp 不随条数缩放），以及 AC 的核心不变量——
 * **组包围盒与格数无关**，因此本机 fitScale 收口系数、图标渲染尺寸不随条数变。
 *
 * 数值判例用 100/10/6 这组好口算的 px 档（cell/gapX/gapY），语义与实机 dp 档一致——
 * 本机换算见 RearDashboardActivity.IconGridContent（DesignTokens 的图标与网格间距）。
 */
class IconGridTest {

    private val cell = 100
    private val gapX = 10
    private val gapY = 6
    private val chipW = 40
    private val chipH = 20

    /** 恒定组包围盒（issue #101 AC）：3 列满行宽 ×（2 行 + 一档竖距 + 徽标带）。 */
    private val boxW = 3 * cell + 2 * gapX
    private val boxH = 2 * cell + gapY + gapY + chipH

    private fun layout(vararg apps: String): IconGridLayout =
        iconGridLayout(
            entries = apps.map { IconGridEntry(it, unread = 1) },
            cellPx = cell,
            gapXPx = gapX,
            gapYPx = gapY,
            chipWidthPx = chipW,
            chipHeightPx = chipH,
        )

    private fun layoutOf(count: Int): IconGridLayout = layout(*Array(count) { "app$it" })

    // ---------- 单/多切换判据（数据源求和） ----------

    @Test
    fun `单条档判据：总数恰 1 不切网格，2 条起切网格`() {
        assertFalse(IconGrid.isGridMode(mapOf("a" to 1)), "1 条维持现状（图标 + Detail）")
        assertTrue(IconGrid.isGridMode(mapOf("a" to 2)), "同应用 2 条也切网格（按通知条数计）")
        assertTrue(IconGrid.isGridMode(mapOf("a" to 1, "b" to 1)), "2 应用各 1 条同样切网格")
        assertFalse(IconGrid.isGridMode(emptyMap()), "无通知不进网格")
    }

    @Test
    fun `+N 计数：6 个内为 0，第 7 个起出现且总数正确`() {
        assertEquals(0, IconGrid.overflowCount(6))
        assertEquals(1, IconGrid.overflowCount(7))
        assertEquals(3, IconGrid.overflowCount(9))
        assertEquals(0, IconGrid.overflowCount(1))
    }

    // ---------- 行/列切分与坐标 ----------

    @Test
    fun `1 个：单格在恒定盒内横竖居中，无徽标`() {
        val grid = layout("a")

        assertEquals(1, grid.cells.size)
        assertEquals(
            IconGridCell(
                "a",
                1,
                row = 0,
                col = 0,
                x = (boxW - cell) / 2,
                y = (boxH - cell) / 2,
            ),
            grid.cells[0],
        )
        assertEquals(0, grid.overflow)
        assertEquals(boxW, grid.width, "组宽恒满行宽（与格数无关）")
        assertEquals(boxH, grid.height, "组高恒 2 行 + 徽标带（与格数无关）")
    }

    @Test
    fun `2 个：同一行、间距恒定、落在恒定组盒内`() {
        val grid = layout("a", "b")

        assertEquals(listOf(0, 0), grid.cells.map { it.row })
        assertEquals(cell + gapX, grid.cells[1].x - grid.cells[0].x)
        assertEquals((boxW - (2 * cell + gapX)) / 2, grid.cells[0].x, "不满一行 → 组内居中")
        assertEquals(boxW, grid.width)
        assertEquals(boxH, grid.height)
    }

    @Test
    fun `3 个：恰满一行`() {
        val grid = layout("a", "b", "c")

        assertEquals(3, grid.cells.size)
        assertTrue(grid.cells.all { it.row == 0 })
        assertEquals(3 * cell + 2 * gapX, grid.width)
        assertEquals(boxH, grid.height)
        assertEquals((boxH - cell) / 2, grid.cells[0].y, "单行内容在恒定盒内垂直居中")
    }

    @Test
    fun `4 个：第二行 1 枚在组内水平居中，不偏坠`() {
        val grid = layout("a", "b", "c", "d")

        val full = 3 * cell + 2 * gapX // 恒定组宽
        val rowsHeight = 2 * cell + gapY
        val contentTop = (boxH - rowsHeight) / 2 // 2 行内容在恒定盒内垂直居中
        val last = grid.cells[3]
        assertEquals(1, last.row)
        assertEquals(0, last.col)
        assertEquals((full - cell) / 2, last.x, "单枚第二行要居中在满行正中下方")
        assertEquals(contentTop + cell + gapY, last.y)
        assertEquals(full, grid.width)
    }

    @Test
    fun `5 个：第二行 2 枚整组居中，不偏坠`() {
        val grid = layout("a", "b", "c", "d", "e")

        val full = 3 * cell + 2 * gapX
        val pairWidth = 2 * cell + gapX
        val rowLeft = (full - pairWidth) / 2
        assertEquals(listOf(rowLeft, rowLeft + cell + gapX), grid.cells.drop(3).map { it.x })
        assertEquals(1, grid.cells.drop(3).map { it.row }.toSet().single(), "第二行 row 下标（0 起）")
        assertEquals(full, grid.width)
    }

    @Test
    fun `6 个：恰满 2 行，无 +N 徽标`() {
        val grid = layout("a", "b", "c", "d", "e", "f")

        assertEquals(6, grid.cells.size)
        assertEquals(0, grid.overflow)
        assertEquals(boxH, grid.height, "组高不因「有没有第 7 个」而变")
        assertEquals(boxW, grid.width)
        assertEquals(2, grid.cells.map { it.row }.toSet().size)
    }

    @Test
    fun `7 个起：只摆 6 枚，+N 徽标在盒底居中`() {
        val grid = layout("a", "b", "c", "d", "e", "f", "g")

        assertEquals(6, grid.cells.size, "最多 2 行 6 个")
        assertEquals(1, grid.overflow)
        assertEquals((3 * cell + 2 * gapX - chipW) / 2, grid.chipX, "徽标水平居中")
        assertEquals(2 * cell + gapY + gapY, grid.chipY, "内容下方再隔一档竖距（内容顶格）")
        assertEquals(boxH, grid.height)
        assertEquals(2 * cell + gapY + gapY + chipH, grid.height, "盒底＝徽标底")
    }

    @Test
    fun `9 个：+N 总数为 3，徽标不越出组包围盒`() {
        val grid = layout("a", "b", "c", "d", "e", "f", "g", "h", "i")

        assertEquals(3, grid.overflow)
        assertEquals(6, grid.cells.size)
        assertTrue(grid.chipX >= 0 && grid.chipX + chipW <= grid.width, "徽标在组内")
        assertTrue(grid.chipY + chipH <= grid.height, "徽标在组内")
    }

    // ---------- 恒定尺寸 / 顺序 / 角标数据 ----------

    @Test
    fun `图标 pitch 恒定：不随条数缩放（3 枚与 6 枚同距）`() {
        val three = layout("a", "b", "c")
        val six = layout("a", "b", "c", "d", "e", "f")

        assertEquals(three.cells[1].x - three.cells[0].x, six.cells[1].x - six.cells[0].x, "行内 pitch")
        assertEquals(six.cells[3].y - six.cells[0].y, cell + gapY, "行间 pitch")
    }

    // ---------- AC 核心不变量：渲染尺寸不随条数变（issue #101） ----------

    @Test
    fun `恒定组包围盒：1 至 9 条同宽同高——fitScale 的输入与格数无关`() {
        val one = layoutOf(1)
        assertEquals(boxW, one.width)
        assertEquals(boxH, one.height)

        for (count in 2..9) {
            val grid = layoutOf(count)
            assertEquals(one.width, grid.width, "$count 条的组宽应与 1 条相同")
            assertEquals(one.height, grid.height, "$count 条的组高应与 1 条相同")
        }
    }

    @Test
    fun `本机 SafeArea 收口：2、3、6 条的图标渲染像素恒等（含充电数字占位让位分支）`() {
        // 本机实测档（docs/poc-findings.md）：背屏 904×572、左相机带 296px、圆角 97、漂移 3dp；
        // 密度 450dpi → 96dp=270px、16dp=45px、8dp=23px、36dp=101px、20dp=56px。
        val cellPx = 270
        val gapXpx = 45
        val gapYpx = 23
        val chipWpx = 101
        val chipHpx = 56
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(
                width = 904,
                height = 572,
                cutouts = listOf(PxRect(0, 0, 296, 572)),
                cornerRadius = 97,
                driftAmplitude = PxOffset(8, 8),
            ),
        )
        fun grid(count: Int) = iconGridLayout(
            entries = List(count) { IconGridEntry("app$it", 1) },
            cellPx = cellPx,
            gapXPx = gapXpx,
            gapYPx = gapYpx,
            chipWidthPx = chipWpx,
            chipHeightPx = chipHpx,
        )

        // 充电数字占位（票 #102）：整屏右下角 + 缺口，走 placeIconBlock 的让位分支。
        val numberRect = chargingNumberRect(
            windowWidth = 904,
            windowHeight = 572,
            cornerRadiusPx = 97,
            extraInsetPx = 45,
            numberWidth = 200,
            numberHeight = 100,
        )
        val placeholder = chargingNumberPlaceholder(numberRect, gapPx = 22)

        listOf(placeholder, null).forEach { slot ->
            val scales = (2..9).map { count ->
                val g = grid(count)
                safe.placeIconBlock(
                    blockWidth = g.width,
                    blockHeight = g.height,
                    numberPlaceholder = slot,
                    drift = PxOffset(8, 8),
                ).scale
            }
            val first = scales.first()
            assertTrue(first < 1.0, "3 列 900px 天然超框，必然收口（slot=$slot）")
            scales.forEachIndexed { index, scale ->
                // 图标渲染尺寸 = 96dp 的 px 值 × 收口系数；系数恒定 ⇒ 渲染尺寸不随条数变。
                assertEquals(
                    cellPx * first,
                    cellPx * scale,
                    "第 ${index + 2} 条的图标渲染像素应与 2 条相同（slot=$slot）",
                )
            }
        }
        // 两条断言都真才算数：无占位与有占位必须落到不同分支（否则让位分支没被覆盖）。
        val plain = grid(3).let { g ->
            safe.placeIconBlock(g.width, g.height, numberPlaceholder = null, drift = PxOffset(8, 8)).scale
        }
        val withSlot = grid(3).let { g ->
            safe.placeIconBlock(g.width, g.height, numberPlaceholder = placeholder, drift = PxOffset(8, 8)).scale
        }
        assertTrue(plain > withSlot, "有占位时收得更紧（让位分支确实生效）")
    }

    @Test
    fun `顺序照输入（时间倒序由状态机给），角标计数逐格透传`() {
        val grid = iconGridLayout(
            entries = listOf(IconGridEntry("newest", 3), IconGridEntry("older", 1), IconGridEntry("oldest", 2)),
            cellPx = cell,
            gapXPx = gapX,
            gapYPx = gapY,
            chipWidthPx = chipW,
            chipHeightPx = chipH,
        )

        assertEquals(listOf("newest", "older", "oldest"), grid.cells.map { it.app })
        assertEquals(listOf(3, 1, 2), grid.cells.map { it.unread })
    }

    @Test
    fun `空输入：空布局不崩`() {
        val grid = iconGridLayout(emptyList(), cell, gapX, gapY, chipW, chipH)

        assertTrue(grid.cells.isEmpty())
        assertEquals(0, grid.overflow)
        assertEquals(0, grid.width)
        assertEquals(0, grid.height)
    }

    @Test
    fun `角标字号独立于网格收口且一位两位数字均保持最终大小`() {
        listOf(24, 48).forEach { textWidth ->
            listOf(1.0, 0.6, 0.4).forEach { scale ->
                val badge = iconBadgeLayout(270, scale, 50, 8, textWidth, 36)
                assertEquals(1.0, badge.textScale * scale, 0.00001, "普通计数不随整组缩小")
                assertTrue(abs(badge.height * scale - 50) <= 1, "最终底片高度保持在一像素舍入误差内")
                assertBadgeContainsText(badge, textWidth, 36)
            }
        }
    }

    @Test
    fun `完整长计数只在格内收口而不越出相邻格或裁掉文字`() {
        listOf(0.6, 0.4, 0.2).forEach { scale ->
            val badge = iconBadgeLayout(270, scale, 50, 8, 300, 36)
            assertTrue(badge.width <= 270 && badge.height <= 270)
            assertTrue(badge.textScale * scale < 1, "完整长计数超过格宽时才缩字")
            assertBadgeContainsText(badge, 300, 36)
        }
    }

    @Test
    fun `各种应用数量与充电漂移中角标仍在内容安全区且避开电量占位`() {
        val safe = DisplaySafeArea.resolve(
            DisplayGeometry(904, 572, listOf(PxRect(0, 0, 296, 572)), 97, PxOffset(8, 8)),
        )
        val slot = chargingNumberPlaceholder(chargingNumberRect(904, 572, 97, 45, 200, 100), 22)
        for (count in 1..9) {
            val grid = layoutOf(count)
            for (placeholder in listOf(null, slot)) {
                for (minute in 0..3) {
                    val drift = safe.driftFor(minute)
                    val placement = safe.placeIconBlock(grid.width, grid.height, placeholder, drift)
                    val badge = iconBadgeLayout(cell, placement.scale, 40, 6, 45, 28)
                    val badgeRects = grid.cells.map { c ->
                        fun x(local: Int) = placement.x + (local * placement.scale).roundToInt()
                        fun y(local: Int) = placement.y + (local * placement.scale).roundToInt()
                        PxRect(x(c.x + cell - badge.width), y(c.y), x(c.x + cell), y(c.y + badge.height))
                    }
                    badgeRects.forEach { rect ->
                        assertTrue(rect.left >= safe.contentRect.left && rect.right <= safe.contentRect.right)
                        assertTrue(rect.top >= safe.contentRect.top && rect.bottom <= safe.contentRect.bottom)
                        if (placeholder != null) {
                            val shiftedSlot = PxRect(
                                placeholder.left + drift.x, placeholder.top + drift.y,
                                placeholder.right + drift.x, placeholder.bottom + drift.y,
                            )
                            assertFalse(rect.overlaps(shiftedSlot), "角标也不能侵入电量占位")
                        }
                    }
                    badgeRects.forEachIndexed { index, rect ->
                        badgeRects.drop(index + 1).forEach { other -> assertFalse(rect.overlaps(other)) }
                    }
                }
            }
        }
    }

    private fun assertBadgeContainsText(badge: IconBadgeLayout, width: Int, height: Int) {
        assertTrue(badge.textX >= 0 && badge.textY >= 0)
        assertTrue(badge.textX + width * badge.textScale <= badge.width + 0.001, "计数右缘完整")
        assertTrue(badge.textY + height * badge.textScale <= badge.height + 0.001, "计数底缘完整")
    }
}
