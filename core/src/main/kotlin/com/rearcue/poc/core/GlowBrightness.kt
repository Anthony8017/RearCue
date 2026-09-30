package com.rearcue.poc.core

/**
 * 状态光带亮度倍率（spec 0021 修订 / 票 #214）：设置层（主屏滑动条）与渲染层（背屏
 * [com.rearcue.poc.rear.AgentMirrorParams]）共用的同一份范围事实——沿 [MirrorTextSize]
 * 的判例，类型/常量放 [:core]，两边零决策照读。
 *
 * 连续倍率（非档位）：滑动条要的就是无级手感；默认 1×＝参数层各档基础亮度
 * （2026-09-30 机主实机反馈提亮后的口径）。上限 10×（1000%，机主第二轮反馈）——alpha
 * 本身封顶 1.0，>100% 的增量走**屏内光晕深度**（发光面积更大＝观感更亮，票 #216）；
 * 呼吸档在下限被封顶后起伏近乎拍平，是拉满的已知代价。
 */
object GlowBrightness {
    /** 滑动条下限（0.5×——夜里嫌亮往低调）。 */
    const val MIN = 0.5f

    /** 滑动条上限（10×＝1000%，机主定夺；alpha 封顶 1.0，超 100% 增量走光晕深度）。 */
    const val MAX = 10f

    /** 默认 1×（倍率中性档：不放大不缩小，呈现参数层基础亮度）。 */
    const val DEFAULT = 1f

    /** 越界钳回范围（存储老数据/异常值兜底，读侧零决策）。 */
    fun coerce(value: Float): Float = value.coerceIn(MIN, MAX)
}
