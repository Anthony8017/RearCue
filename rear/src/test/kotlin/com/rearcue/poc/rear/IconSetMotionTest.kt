package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Icon Set 身份与占格对账判例（spec 0015 / 票 #147 出入场身份对账、#148 退场占格与容量）：
 * 出入场只看包名，顺序变化不是出入场；冷启动首帧不播入场，但空集是有效上一帧。
 */
class IconSetMotionTest {

    @Test
    fun `入场按包名对账且不受顺序影响`() {
        assertEquals(
            setOf("c"),
            IconSetMotion.enteringApps(previous = listOf("a", "b"), current = listOf("c", "b", "a")),
        )
        assertEquals(
            emptySet(),
            IconSetMotion.enteringApps(previous = listOf("a", "b"), current = listOf("b", "a")),
        )
    }

    @Test
    fun `冷启动首帧不播入场 空集后的新图标仍入场`() {
        assertEquals(
            emptySet(),
            IconSetMotion.enteringApps(previous = null, current = listOf("a")),
        )
        assertEquals(
            setOf("a"),
            IconSetMotion.enteringApps(previous = emptyList(), current = listOf("a")),
        )
    }

    @Test
    fun `退场按包名对账`() {
        assertEquals(
            setOf("b"),
            IconSetMotion.exitingApps(previous = listOf("a", "b"), current = listOf("a", "c")),
        )
        assertEquals(
            emptySet(),
            IconSetMotion.exitingApps(previous = null, current = emptyList()),
        )
    }

    @Test
    fun `退场条目留在上一帧槽位 其余条目按当前帧顺序补进空槽`() {
        // a 退出：b、c 保持原槽位不动（退场条目占格，补位这一步不发生）。
        assertEquals(
            listOf("a", "b", "c"),
            IconSetMotion.presentedOrder(listOf("a", "b", "c"), listOf("b", "c"), setOf("a")),
        )
        // a 退出、c 挪到最前：a 留在它原来的槽位，其余条目按当前帧顺序填其余槽。
        assertEquals(
            listOf("a", "c", "b"),
            IconSetMotion.presentedOrder(listOf("a", "b", "c"), listOf("c", "b"), setOf("a")),
        )
        // 没有退场条目就是当前帧顺序：新图标入场照旧按当前几何落位。
        assertEquals(
            listOf("c", "a", "b"),
            IconSetMotion.presentedOrder(listOf("a", "b"), listOf("c", "a", "b"), emptySet()),
        )
    }

    @Test
    fun `退场条目占格不占容量 六格容量先给当前帧的条目`() {
        // 六格满 + 一枚退场：退场条目让出格位，当前帧六枚全在网格里（不被挤进「+N」）。
        assertEquals(
            listOf("b", "c", "d", "e", "f", "g"),
            IconSetMotion.presentedOrder(
                previousOnScreen = listOf("a", "b", "c", "d", "e", "f"),
                current = listOf("b", "c", "d", "e", "f", "g"),
                exiting = setOf("a"),
                capacity = 6,
            ),
        )
    }

    @Test
    fun `呈现序列自幂 退场占格帧的落点就是上一帧落点`() {
        val previous = listOf("a", "b", "c")
        val current = listOf("b", "c")
        val once = IconSetMotion.presentedOrder(previous, current, setOf("a"))
        val twice = IconSetMotion.presentedOrder(once, current, setOf("a"))
        // 同一帧的组合跑几趟都不会再挪一次（自幂），补位不发生两次。
        assertEquals(once, twice)
        // 占格帧仍是三格布局：落点与上一帧逐格一致，其余图标这一步不动。
        val before = iconGridLayout(previous.map { IconGridEntry(it, 1) }, 162, 23, 23, 16)
        val during = iconGridLayout(once.map { IconGridEntry(it, 1) }, 162, 23, 23, 16)
        assertEquals(listOf("a", "b", "c"), during.cells.map { it.app })
        assertEquals(before.cells.map { it.x to it.y }, during.cells.map { it.x to it.y })
    }

    @Test
    fun `退场账本 溢出的条目不在账内 演完才让位`() {
        val ledger = IconSetExitLedger(capacity = IconGrid.MAX_ICONS)
        // 上一帧画在网格里的是 a、b（c 只在 +N 里，不在账内）。
        ledger.onScreen(listOf("a", "b"))
        assertEquals(emptySet(), ledger.frame(listOf("a", "b")).exiting)
        // a 被清掉：登记退场，本帧继续占格，其余条目不动。
        val exiting = ledger.frame(listOf("b"))
        assertEquals(setOf("a"), exiting.exiting)
        assertEquals(listOf("a", "b"), exiting.apps)
        ledger.onScreen(exiting.apps)
        // 演完前一直占格，不重复登记。
        assertEquals(setOf("a"), ledger.frame(listOf("b")).exiting)
        // 宽限窗内 a 回来：撤销退场，交回入场路径。
        assertEquals(emptySet(), ledger.frame(listOf("a", "b")).exiting)
        ledger.onScreen(listOf("a", "b"))
        // 再退一次照旧登记；演完摘掉占格，其余条目这一步补位。
        val again = ledger.frame(listOf("b"))
        assertEquals(setOf("a"), again.exiting)
        ledger.onScreen(again.apps)
        ledger.finish("a")
        val settled = ledger.frame(listOf("b"))
        assertEquals(emptySet(), settled.exiting)
        assertEquals(listOf("b"), settled.apps)
    }

    @Test
    fun `详情当口被清掉的图标静默移除 不演退场`() {
        val ledger = IconSetExitLedger()
        ledger.onScreen(listOf("a", "b"))
        val frame = ledger.frame(current = listOf("b"), silent = setOf("a"))
        assertEquals(emptySet(), frame.exiting)
        assertEquals(listOf("b"), frame.apps)
    }
}
