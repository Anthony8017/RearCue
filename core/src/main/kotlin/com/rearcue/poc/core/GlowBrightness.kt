package com.rearcue.poc.core

/**
 * 状态光带亮度倍率（spec 0021 修订 / 票 #214）：设置层（主屏滑动条）与渲染层（背屏
 * [com.rearcue.poc.rear.AgentMirrorParams]）共用的同一份范围事实——沿 [MirrorTextSize]
 * 的判例，类型/常量放 [:core]，两边零决策照读。
 *
 * 连续倍率（非档位）：滑动条要的就是无级手感；默认 1×＝参数层各档基础亮度
 * （2026-09-30 机主实机反馈提亮后的口径）。上限 2× 是「拉满」档——呼吸档在下限被
 * 封顶 1.0 后近乎拍平，是机主知情选择的代价。
 */
object GlowBrightness {
    /** 滑动条下限（0.5×——夜里嫌亮往低调）。 */
    const val MIN = 0.5f

    /** 滑动条上限（2×——「上限高一点」机主原话；alpha 封顶 1.0）。 */
    const val MAX = 2f

    /** 默认 1×（倍率中性档：不放大不缩小，呈现参数层基础亮度）。 */
    const val DEFAULT = 1f

    /** 越界钳回范围（存储老数据/异常值兜底，读侧零决策）。 */
    fun coerce(value: Float): Float = value.coerceIn(MIN, MAX)
}
