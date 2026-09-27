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

    /**
     * 本机 450dpi（density 2.8125）把 DesignTokens 的 RearCueChargingWave 换算到 px：
     * 有意镜像令牌而非引用（纯函数测试不依赖 UI 模块常量的重排），令牌改档时这里同步。
     */
    private val columns = ChargingWater.WaveColumns(
        ampMainPx = 1.2f * 2.8125f,
        wavelengthMainPx = 72f * 2.8125f,
        ampRipplePx = 0.5f * 2.8125f,
        wavelengthRipplePx = 41f * 2.8125f,
    )

    @Test
    fun `波面在原点与零相位处不偏移`() {
        assertEquals(0f, ChargingWater.surfaceOffsetPx(0f, columns, phaseRad = 0f), 1e-4f)
    }

    @Test
    fun `波面峰谷不超两列振幅之和（全相位全位置扫描）`() {
        val bound = columns.crestPx
        var phase = 0f
        while (phase < ChargingWater.PHASE_PERIOD_RAD) {
            for (step in 0..904) {
                val offset = ChargingWater.surfaceOffsetPx(step.toFloat(), columns, phase)
                assertTrue(abs(offset) <= bound + 1e-3f, "phase=$phase x=$step offset=$offset 越界 $bound")
            }
            phase += ChargingWater.PHASE_PERIOD_RAD / 24
        }
    }

    @Test
    fun `波面对相位以公共周期 10π 回卷无缝（涟漪系数 0 点 6 的整周期条件）`() {
        for (step in 0..904) {
            val x = step.toFloat()
            assertEquals(
                ChargingWater.surfaceOffsetPx(x, columns, 0f),
                ChargingWater.surfaceOffsetPx(x, columns, ChargingWater.PHASE_PERIOD_RAD),
                1e-3f,
            )
        }
    }

    @Test
    fun `振幅为 0 时水面平直（渲染退化为票 71 的直线水面上缘）`() {
        val flat = ChargingWater.WaveColumns(0f, columns.wavelengthMainPx, 0f, columns.wavelengthRipplePx)
        assertEquals(0f, ChargingWater.surfaceOffsetPx(123f, flat, phaseRad = 1.23f), 1e-6f)
    }
}
