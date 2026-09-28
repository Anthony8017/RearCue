package com.rearcue.poc.tile

import com.rearcue.poc.rear.Presence

/**
 * Quick Tile Entry 的纯决策（spec 0006 / 票 #54，JVM 可测）。
 *
 * spec 的状态语义与点击语义本就同源（OnScreen→退出语义）：Presence 不在 Absent 时
 * tile 为激活态且点击=退出，Absent 时停用且点击=投送——所以只有一个谓词。
 *
 * 为什么单独一层：TileService（Android 类）里内联这个判断没人测得到；与
 * RearDisplaySignalPolicy / DndGate.fromInterruptionFilter 同一惯例——决策在纯类、
 * 接线在 Android 胶水。
 */
object TilePolicy {

    /** 点击 = 退出？true（激活态，退出语义）/false（停用态，投送语义）。 */
    fun tapExits(presence: Presence): Boolean = presence != Presence.ABSENT
}
