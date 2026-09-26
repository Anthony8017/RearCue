package com.rearcue.poc.feed

import com.rearcue.poc.core.DashboardCore

/**
 * Auto-dismiss 取值域与输入解析（spec 0007 story 5 / 票 #56，JVM 可测）。
 *
 * 取值域「5 秒～无上限」在这一层收口：设置页的数字输入 + 单位档（[Unit] 秒/分/时）吐出的
 * 值都过 [parseInput]，存储层原样照记、core 原样照判（`AutoDismissTick` 到期判定），
 * 三处不各记一份规则。**任意整数秒 ≥5 秒都可达**（不再是固定档位表——7 秒、10 分、2 小时
 * 都是合法值，spec「5 秒～无上限」的字面口径）；无上限 = [UNLIMITED_MS] 单独一档
 * （正是 core `feedExpiresAtMs` 的饱和值——常驻直到通知被清除），只经「无上限」行进入。
 *
 * 为什么单独一层（沿 TilePolicy 惯例：决策在纯类、接线在 Compose 胶水）：解析、越界回退、
 * 单位换算若内联进 Composable 就没人测得到；这里是唯一合法值与换算处。
 */
object AutoDismissPolicy {

    /** 最短时限：5 秒（spec 0007 story 5 的下限）。 */
    const val MIN_MS = 5_000L

    /** 无上限档 = 常驻：core 的到期判定对 [Long.MAX_VALUE] 恒不满足。 */
    const val UNLIMITED_MS = Long.MAX_VALUE

    /** 数字输入的单位档（秒/分/时）：本档换成 ms 的乘数。 */
    enum class Unit(val msPerUnit: Long) {
        SECONDS(1_000L),
        MINUTES(60_000L),
        HOURS(3_600_000L),
    }

    /** 输入框的 (数值, 单位) 条目：渲染层只按 [unit] 亮档、拿 [amount] 填数，不自判换算。 */
    data class Entry(val amount: Long, val unit: Unit)

    /**
     * 越界防御（存储首读/手改值的兜底，也是设置页写入口的钳制）：低于 [MIN_MS] 钳到
     * [MIN_MS]，[UNLIMITED_MS] 只认精确 MAX（无上限不由数值表达），其余**原样**——
     * 任意整数毫秒 ≥5 秒都合法（与旧档位表的「就近吸附」相反，吸附会把 7 秒吃掉）。
     */
    fun coerce(durationMs: Long): Long = when {
        durationMs >= UNLIMITED_MS -> UNLIMITED_MS
        durationMs < MIN_MS -> MIN_MS
        else -> durationMs
    }

    /**
     * 数字输入 → 时限：纯数字 × 单位。null = 非法输入（空/非数字/负数/乘法溢出/低于 5 秒），
     * 调用方据此不落值、失焦时回退到上一个生效值（零悬空态）。不静默钳制——手输 3 秒是越界，
     * 拒绝比悄悄改成 5 秒更诚实；钳制兜底在 [coerce]（写入口与存储首读各走一遍）。
     */
    fun parseInput(amountText: String, unit: Unit): Long? {
        val amount = amountText.trim().toLongOrNull() ?: return null
        if (amount < 0) return null
        val ms = try {
            java.lang.Math.multiplyExact(amount, unit.msPerUnit)
        } catch (e: ArithmeticException) {
            return null
        }
        return if (ms < MIN_MS) null else ms
    }

    /** 无上限判定（「无上限」行与输入框启停共用一个判据）。 */
    fun isUnlimited(durationMs: Long): Boolean = durationMs >= UNLIMITED_MS

    /**
     * 时限 → 输入条目（单位择整：整时按时、整分按分、其余按秒，秒以下截去）；
     * null = 无上限档（渲染层回退 [defaultEntry]——无上限开启时输入框本就禁用）。
     */
    fun entryOf(durationMs: Long): Entry? = when {
        isUnlimited(durationMs) -> null
        durationMs % Unit.HOURS.msPerUnit == 0L -> Entry(durationMs / Unit.HOURS.msPerUnit, Unit.HOURS)
        durationMs % Unit.MINUTES.msPerUnit == 0L -> Entry(durationMs / Unit.MINUTES.msPerUnit, Unit.MINUTES)
        else -> Entry(durationMs / Unit.SECONDS.msPerUnit, Unit.SECONDS)
    }

    /** 默认条目：core 默认 10 秒的输入形态（无上限档回显与关闭回填的兜底，与默认值同源）。 */
    fun defaultEntry(): Entry = Entry(
        amount = DashboardCore.AUTO_DISMISS_DEFAULT_MS / Unit.SECONDS.msPerUnit,
        unit = Unit.SECONDS,
    )
}
