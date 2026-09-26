package com.rearcue.poc.feed

import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.feed.AutoDismissPolicy.Entry
import com.rearcue.poc.feed.AutoDismissPolicy.Unit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Auto-dismiss 取值域测试（spec 0007 story 5 / 票 #56）：取值域「5 秒～无上限」——
 * 任意整数秒 ≥5 秒都必须可达（7 秒/10 分/2 小时不再是档位外值），无上限单独一档，
 * 非法输入必须拒收（不落值、由调用方回退）。
 */
class AutoDismissPolicyTest {

    @Test
    fun `coerce 只钳下限与无上限，中间值原样（不再就近吸附档位）`() {
        val table = listOf(
            0L to 5_000L, // 下限钳制
            4_999L to 5_000L,
            5_000L to 5_000L,
            7_000L to 7_000L, // 7 秒：#56 取值域的档外值，现在原样合法
            7_500L to 7_500L,
            600_000L to 600_000L, // 10 分钟
            7_200_000L to 7_200_000L, // 2 小时
            Long.MAX_VALUE - 1 to Long.MAX_VALUE - 1, // 上端无有限钳位（无上限只认精确 MAX）
            Long.MAX_VALUE to Long.MAX_VALUE,
        )
        table.forEach { (input, expected) ->
            assertEquals(expected, AutoDismissPolicy.coerce(input), "输入 $input")
        }
    }

    @Test
    fun `输入解析：任意整数 ≥5 秒按所选单位档可达`() {
        val table = listOf(
            Triple("5", Unit.SECONDS, 5_000L),
            Triple("7", Unit.SECONDS, 7_000L),
            Triple("10", Unit.SECONDS, 10_000L), // 默认档
            Triple("120", Unit.SECONDS, 120_000L),
            Triple("5", Unit.MINUTES, 300_000L),
            Triple("10", Unit.MINUTES, 600_000L),
            Triple("600", Unit.SECONDS, 600_000L), // 同一时长也能用秒表达
            Triple("2", Unit.HOURS, 7_200_000L),
            Triple("48", Unit.HOURS, 172_800_000L),
        )
        table.forEach { (text, unit, expected) ->
            assertEquals(expected, AutoDismissPolicy.parseInput(text, unit), "输入 $text $unit")
        }
    }

    @Test
    fun `输入解析拒收空、非数字、负数、低于 5 秒与溢出（不落值）`() {
        val table = listOf(
            Triple("", Unit.SECONDS, "空输入"),
            Triple("   ", Unit.SECONDS, "空白输入"),
            Triple("abc", Unit.SECONDS, "非数字"),
            Triple("5x", Unit.SECONDS, "数字混杂"),
            Triple("-5", Unit.SECONDS, "负数"),
            Triple("0", Unit.SECONDS, "零"),
            Triple("3", Unit.SECONDS, "低于 5 秒：拒绝而不是静默改成 5 秒"),
            Triple("9223372036854775807", Unit.SECONDS, "乘法溢出"),
            Triple("99999999999999999999999", Unit.SECONDS, "超出 Long"),
        )
        table.forEach { (text, unit, why) ->
            assertNull(AutoDismissPolicy.parseInput(text, unit), "$why（输入 $text $unit）")
        }
    }

    @Test
    fun `条目回显：整时按时、整分按分、其余按秒，无上限返回 null`() {
        val table = listOf(
            5_000L to Entry(5, Unit.SECONDS),
            7_000L to Entry(7, Unit.SECONDS),
            7_500L to Entry(7, Unit.SECONDS), // 秒以下截去
            10_000L to Entry(10, Unit.SECONDS),
            60_000L to Entry(1, Unit.MINUTES),
            300_000L to Entry(5, Unit.MINUTES),
            3_600_000L to Entry(1, Unit.HOURS),
            7_200_000L to Entry(2, Unit.HOURS),
        )
        table.forEach { (ms, expected) ->
            assertEquals(expected, AutoDismissPolicy.entryOf(ms), "输入 $ms")
        }
        assertNull(AutoDismissPolicy.entryOf(AutoDismissPolicy.UNLIMITED_MS)) // 无上限没有数值条目
    }

    @Test
    fun `条目与解析互为往返（显示什么就是生效什么）`() {
        listOf(5_000L, 7_000L, 10_000L, 45_000L, 60_000L, 300_000L, 3_600_000L, 7_200_000L)
            .forEach { ms ->
                val entry = AutoDismissPolicy.entryOf(ms)
                assertEquals(ms, AutoDismissPolicy.parseInput(entry!!.amount.toString(), entry.unit), "$ms ms")
            }
    }

    @Test
    fun `默认档可达，无上限只由精确 MAX 表达`() {
        assertEquals(
            DashboardCore.AUTO_DISMISS_DEFAULT_MS,
            AutoDismissPolicy.parseInput("10", Unit.SECONDS),
        )
        assertTrue(AutoDismissPolicy.isUnlimited(AutoDismissPolicy.UNLIMITED_MS))
        assertFalse(AutoDismissPolicy.isUnlimited(Long.MAX_VALUE - 1))
        assertFalse(AutoDismissPolicy.isUnlimited(AutoDismissPolicy.MIN_MS))
    }
}
