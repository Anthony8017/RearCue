package com.rearcue.poc.feed

/**
 * Auto-dismiss 档位表（spec 0007 story 5 / 票 #56，JVM 可测）。
 *
 * 取值域「5 秒～无上限」在这一层收口：设置页的步进器只在 [steps] 上移动，存储层原样照记、
 * core 原样照判（`AutoDismissTick` 到期判定），三处不各记一份规则。无上限 = [UNLIMITED_MS]，
 * 正是 core `feedExpiresAtMs` 的饱和值——常驻直到通知被清除。
 *
 * 为什么单独一层（沿 TilePolicy 惯例：决策在纯类、接线在 Compose 胶水）：档位吸附/游标/
 * 文案形态若内联进 Composable 就没人测得到；这里是唯一合法值与换算处。
 */
object AutoDismissSteps {

    /** 最短档：5 秒（spec 0007 story 5 的下限）。 */
    const val MIN_MS = 5_000L

    /** 无上限档 = 常驻：core 到期判定对 [Long.MAX_VALUE] 恒不满足。 */
    const val UNLIMITED_MS = Long.MAX_VALUE

    /** 可选档位（升序）：5/10/15/30/60/120/300 秒 + 无上限；默认 10 秒在表内。 */
    val steps: List<Long> = listOf(
        5_000L,
        10_000L,
        15_000L,
        30_000L,
        60_000L,
        120_000L,
        300_000L,
        UNLIMITED_MS,
    )

    /**
     * 任意值吸附到档位（步进器只吐档位值；这里是越界/手改值的防御，也是存储首读的兜底）：
     * 无上限只由精确的 [UNLIMITED_MS] 表达，其余就近吸附（平手取小档），两端钳到 [MIN_MS]/最大有限档。
     */
    fun coerce(durationMs: Long): Long = when {
        durationMs >= UNLIMITED_MS -> UNLIMITED_MS
        durationMs < MIN_MS -> MIN_MS
        else -> steps.filter { it < UNLIMITED_MS }.minBy { kotlin.math.abs(it - durationMs) }
    }

    /** 档位游标（先 [coerce]）：步进器高亮与端点禁用的依据。 */
    fun indexOf(durationMs: Long): Int = steps.indexOf(coerce(durationMs))

    /** 上一档（最短档返回自身，步进器据此禁用）。 */
    fun previous(durationMs: Long): Long = steps[(indexOf(durationMs) - 1).coerceAtLeast(0)]

    /** 下一档（无上限档返回自身，步进器据此禁用）。 */
    fun next(durationMs: Long): Long = steps[(indexOf(durationMs) + 1).coerceAtMost(steps.lastIndex)]

    /** 还能缩短？（已在 5 秒档为 false）。 */
    fun canShorten(durationMs: Long): Boolean = indexOf(durationMs) > 0

    /** 还能延长？（已在无上限档为 false）。 */
    fun canLengthen(durationMs: Long): Boolean = indexOf(durationMs) < steps.lastIndex

    /**
     * 档位文案形态（票 #56 的纯文案决策）：渲染层只按 [Kind] 选资源、拿 [value] 填数，
     * 不自判单位。[Kind.UNLIMITED] 时 [value] 为占位 0（不使用）。
     */
    data class DurationLabel(val kind: Kind, val value: Int) {
        enum class Kind { SECONDS, MINUTES, UNLIMITED }
    }

    /** 当前档的文案形态：整分钟档按分钟、其余按秒、无上限单独一词。 */
    fun label(durationMs: Long): DurationLabel {
        val coerced = coerce(durationMs)
        if (coerced >= UNLIMITED_MS) return DurationLabel(DurationLabel.Kind.UNLIMITED, 0)
        val seconds = (coerced / 1_000L).toInt()
        return if (seconds % 60 == 0) {
            DurationLabel(DurationLabel.Kind.MINUTES, seconds / 60)
        } else {
            DurationLabel(DurationLabel.Kind.SECONDS, seconds)
        }
    }
}
