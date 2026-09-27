package com.rearcue.poc.rear

import kotlin.math.PI
import kotlin.math.sin

/**
 * 充电水位几何（spec 0009 / 票 #71、票 #75）：「电量百分比 × 屏幕尺寸（× 相位）→ 水面」
 * 的纯函数，渲染层照单执行、不自算。纯 Kotlin 无 Compose 依赖（同 [DisplaySafeArea] 判例），
 * JVM 可测。
 *
 * spec 0009 反转 spec 0008「相机带不染色」：绿色水铺满整个背屏（含相机带），填充 x 向恒为
 * 全宽——x 向边界语义在本对象里不出现，本对象只管 y 向。
 */
object ChargingWater {

    /**
     * 水面上缘 y（px，向下为正）：[percent] 先收 0..100，自底部向上按比例——
     * 0% 全空（上缘贴底）、100% 满屏（上缘贴顶）。
     */
    fun fillTopPx(heightPx: Float, percent: Int): Float =
        heightPx * (1f - percent.coerceIn(0, 100) / 100f)

    /**
     * 相位漂移的公共周期（弧度）：主列周期 2π、涟漪列周期 2π/0.6，公共周期取 **10π**
     * （主列走满 5 周、涟漪走满 3 周）——渲染循环以此为回卷点，水面无跳变（票 #75）。
     */
    val PHASE_PERIOD_RAD = (10.0 * PI).toFloat()

    /** 涟漪列的相位反向慢漂比例（<1：次列相位走得比主列慢，水面不呈机械同步感）。 */
    private const val RIPPLE_PHASE_RATIO = 0.6f

    /**
     * 水面波面偏移（px，向下为正）：主列长波 + 涟漪短波两列复合正弦，涟漪相位按
     * [RIPPLE_PHASE_RATIO] 反向慢漂。峰谷合计在 ±(ampMain + ampRipple) 内；
     * x = 0 且 phase = 0 时恒为 0（水面左端无跳变），渲染按 x 采样后连成波面。
     */
    fun surfaceOffsetPx(
        xPx: Float,
        ampMainPx: Float,
        wavelengthMainPx: Float,
        ampRipplePx: Float,
        wavelengthRipplePx: Float,
        phaseRad: Float,
    ): Float =
        ampMainPx * sin((2.0 * PI * xPx / wavelengthMainPx) + phaseRad).toFloat() +
            ampRipplePx * sin((2.0 * PI * xPx / wavelengthRipplePx) - RIPPLE_PHASE_RATIO * phaseRad).toFloat()
}
