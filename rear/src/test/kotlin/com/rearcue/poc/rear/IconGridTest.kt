package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 纯图标网格排布的行为测试（issue #101 验收面）：行/列切分、整组与每行居中、+N 计数、
 * 角标数据透传、图标 pitch 恒定（96dp 不随条数缩放）。
 *
 * 数值判例用 100/10/6 这组好口算的 px 档（cell/gapX/gapY），语义与实机 dp 档一致——
 * 本机换算见 RearDashboardActivity.IconGridContent（96dp / md / sm）。
 */
class IconGridTest {

    private val cell = 100
    private val gapX = 10
    private val gapY = 6
    private val chipW = 40
    private val chipH = 20

    private fun layout(vararg apps: String): IconGridLayout =
        iconGridLayout(
            entries = apps.map { IconGridEntry(it, unread = 1) },
            cellPx = cell,
            gapXPx = gapX,
            gapYPx = gapY,
            chipWidthPx = chipW,
            chipHeightPx = chipH,
        )

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
    fun `1 个：单格落在原点，无徽标`() {
        val grid = layout("a")

        assertEquals(1, grid.cells.size)
        assertEquals(IconGridCell("a", 1, row = 0, col = 0, x = 0, y = 0), grid.cells[0])
        assertEquals(0, grid.overflow)
        assertEquals(cell, grid.width)
        assertEquals(cell, grid.height)
    }

    @Test
    fun `2 个：同一行、间距恒定`() {
        val grid = layout("a", "b")

        assertEquals(listOf(0, 0), grid.cells.map { it.row })
        assertEquals(cell + gapX, grid.cells[1].x - grid.cells[0].x)
        assertEquals(2 * cell + gapX, grid.width)
        assertEquals(cell, grid.height)
    }

    @Test
    fun `3 个：恰满一行`() {
        val grid = layout("a", "b", "c")

        assertEquals(3, grid.cells.size)
        assertTrue(grid.cells.all { it.row == 0 })
        assertEquals(3 * cell + 2 * gapX, grid.width)
        assertEquals(cell, grid.height)
    }

    @Test
    fun `4 个：第二行 1 枚在组内水平居中，不偏坠`() {
        val grid = layout("a", "b", "c", "d")

        val full = 3 * cell + 2 * gapX // 满行宽 = 组宽
        val last = grid.cells[3]
        assertEquals(1, last.row)
        assertEquals(0, last.col)
        assertEquals((full - cell) / 2, last.x, "单枚第二行要居中在满行正中下方")
        assertEquals(cell + gapY, last.y)
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
        assertEquals(2 * cell + gapY, grid.height)
        assertEquals(3 * cell + 2 * gapX, grid.width)
        assertEquals(2, grid.cells.map { it.row }.toSet().size)
    }

    @Test
    fun `7 个起：只摆 6 枚，+N 徽标在末行下方居中`() {
        val grid = layout("a", "b", "c", "d", "e", "f", "g")

        assertEquals(6, grid.cells.size, "最多 2 行 6 个")
        assertEquals(1, grid.overflow)
        assertEquals((3 * cell + 2 * gapX - chipW) / 2, grid.chipX, "徽标水平居中")
        assertEquals(2 * cell + gapY + gapY, grid.chipY, "末行下方再隔一档竖距")
        assertEquals(2 * cell + gapY + gapY + chipH, grid.height)
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
}
