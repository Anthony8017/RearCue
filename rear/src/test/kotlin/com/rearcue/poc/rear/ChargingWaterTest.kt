package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 充电水位几何的行为测试（spec 0009 / 票 #71）：只断言「百分比 → 水面上缘」。
 * 本机基准同 [DisplaySafeAreaTest]：背屏 904×572。全屏语义（含相机带）由「无 x 向入参」
 * 构造性成立——本对象没有左右边界可收。
 */
class ChargingWaterTest {

    private val height = 572f

    @Test
    fun `0% 全空：上缘贴底`() {
        assertEquals(height, ChargingWater.fillTopPx(height, 0))
    }

    @Test
    fun `100% 满屏：上缘贴顶`() {
        assertEquals(0f, ChargingWater.fillTopPx(height, 100))
    }

    @Test
    fun `68% 上缘落在 32% 高度（本机 572px 基准）`() {
        assertEquals(height * 0.32f, ChargingWater.fillTopPx(height, 68), 1e-4f)
    }

    @Test
    fun `越界百分比收进 0 到 100 区间不翻转`() {
        assertEquals(height, ChargingWater.fillTopPx(height, -5))
        assertEquals(0f, ChargingWater.fillTopPx(height, 150))
    }
}
