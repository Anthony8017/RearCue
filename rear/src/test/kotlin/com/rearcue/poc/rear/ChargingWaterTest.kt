package com.rearcue.poc.rear

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 充电水位几何的行为测试（spec 0009 / 票 #71、票 #75）：断言「百分比 → 水面上缘」与
 * 「采样点 → 波面偏移」。本机基准同 [DisplaySafeAreaTest]：背屏 904×572。全屏语义
 * （含相机带）由「无 x 向边界入参」构造性成立——本对象没有左右边界可收。
 */
class ChargingWaterTest {

    private val height = 572f

    // ---- fillTopPx（票 #71） ----

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

    // ---- surfaceOffsetPx（票 #75 微波） ----

    /** 本机 450dpi：DesignTokens 的 1.4dp/0.7dp/72dp/41dp 换算 px（density 2.8125）。 */
    private val density = 2.8125f
    private val ampMain = 1.4f * density
    private val wlMain = 72f * density
    private val ampRipple = 0.7f * density
    private val wlRipple = 41f * density

    @Test
    fun `波面在原点与零相位处不偏移`() {
        assertEquals(
            0f,
            ChargingWater.surfaceOffsetPx(0f, ampMain, wlMain, ampRipple, wlRipple, phaseRad = 0f),
            1e-4f,
        )
    }

    @Test
    fun `波面峰谷不超两列振幅之和（全相位全位置扫描）`() {
        val bound = ampMain + ampRipple
        var phase = 0f
        while (phase < ChargingWater.PHASE_PERIOD_RAD) {
            for (step in 0..904) {
                val offset = ChargingWater.surfaceOffsetPx(
                    step.toFloat(), ampMain, wlMain, ampRipple, wlRipple, phase,
                )
                assertTrue(abs(offset) <= bound + 1e-3f, "phase=$phase x=$step offset=$offset 越界 $bound")
            }
            phase += ChargingWater.PHASE_PERIOD_RAD / 24
        }
    }

    @Test
    fun `波面对相位以公共周期（涟漪系数 0 点 6 对应 10 兀）回卷无缝`() {
        for (step in 0..904) {
            val x = step.toFloat()
            assertEquals(
                ChargingWater.surfaceOffsetPx(x, ampMain, wlMain, ampRipple, wlRipple, 0f),
                ChargingWater.surfaceOffsetPx(x, ampMain, wlMain, ampRipple, wlRipple, ChargingWater.PHASE_PERIOD_RAD),
                1e-3f,
            )
        }
    }

    @Test
    fun `振幅为 0 时水面平直（渲染退化为票 71 的直线水面上缘）`() {
        assertEquals(
            0f,
            ChargingWater.surfaceOffsetPx(123f, 0f, wlMain, 0f, wlRipple, phaseRad = 1.23f),
            1e-6f,
        )
    }
}
