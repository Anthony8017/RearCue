package com.rearcue.poc.rear

/**
 * 充电水位几何（spec 0009 / 票 #71）：「电量百分比 × 屏幕尺寸 → 水面上缘」的纯函数，
 * 渲染层照单执行、不自算。纯 Kotlin 无 Compose 依赖（同 [DisplaySafeArea] 判例），JVM 可测。
 *
 * spec 0009 反转 spec 0008「相机带不染色」：绿色水铺满整个背屏（含相机带），填充 x 向恒为
 * 全宽——x 向语义在本对象里不出现，本对象只管 y 向。
 */
object ChargingWater {

    /**
     * 水面上缘 y（px，向下为正）：[percent] 先收 0..100，自底部向上按比例——
     * 0% 全空（上缘贴底）、100% 满屏（上缘贴顶）。
     */
    fun fillTopPx(heightPx: Float, percent: Int): Float =
        heightPx * (1f - percent.coerceIn(0, 100) / 100f)
}
