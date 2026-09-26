package com.rearcue.poc.feed

import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.feed.AutoDismissSteps.DurationLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto-dismiss 档位表测试（spec 0007 story 5 / 票 #56）：取值域 5 秒～无上限的吸附、
 * 步进游标与文案形态是设置页唯一决策——步进器吐的每个值都必须合法且可显示。
 */
class AutoDismissStepsTest {

    @Test
    fun `档位表覆盖 5 秒下限与无上限，且含默认 10 秒`() {
        assertEquals(AutoDismissSteps.MIN_MS, AutoDismissSteps.steps.first())
        assertEquals(AutoDismissSteps.UNLIMITED_MS, AutoDismissSteps.steps.last())
        assertTrue(DashboardCore.AUTO_DISMISS_DEFAULT_MS in AutoDismissSteps.steps)
        // 严格升序：游标的「上一档/下一档」才有唯一解。
        assertEquals(
            AutoDismissSteps.steps,
            AutoDismissSteps.steps.distinct().sorted(),
        )
    }

    @Test
    fun `越界与手改值就近吸附到档位（平手取小档、无上限只认精确 MAX）`() {
        val table = listOf(
            0L to 5_000L, // 下限钳制
            4_999L to 5_000L,
            7_499L to 5_000L, // 就近
            7_500L to 5_000L, // 平手取小档
            7_501L to 10_000L,
            10_000L to 10_000L, // 档内值原样
            400_000L to 300_000L, // 大于最大有限档 → 吸附最大有限档
            Long.MAX_VALUE - 1 to 300_000L, // 无上限只认精确 MAX_VALUE
            Long.MAX_VALUE to Long.MAX_VALUE,
        )
        table.forEach { (input, expected) ->
            assertEquals(expected, AutoDismissSteps.coerce(input), "输入 $input")
        }
    }

    @Test
    fun `步进游标从 5 秒走到无上限，端点不再移动`() {
        val steps = AutoDismissSteps.steps

        assertEquals(0, AutoDismissSteps.indexOf(steps.first()))
        assertEquals(steps.lastIndex, AutoDismissSteps.indexOf(steps.last()))
        assertFalse(AutoDismissSteps.canShorten(steps.first()))
        assertTrue(AutoDismissSteps.canLengthen(steps.first()))
        assertEquals(steps.first(), AutoDismissSteps.previous(steps.first())) // 端点自环
        assertEquals(steps[1], AutoDismissSteps.next(steps.first()))

        val unlimited = steps.last()
        assertTrue(AutoDismissSteps.canShorten(unlimited))
        assertFalse(AutoDismissSteps.canLengthen(unlimited))
        assertEquals(steps[steps.lastIndex - 1], AutoDismissSteps.previous(unlimited))
        assertEquals(unlimited, AutoDismissSteps.next(unlimited)) // 端点自环
    }

    @Test
    fun `文案形态：整分钟按分钟、其余按秒、无上限单独一词`() {
        val table = listOf(
            5_000L to DurationLabel(DurationLabel.Kind.SECONDS, 5),
            15_000L to DurationLabel(DurationLabel.Kind.SECONDS, 15),
            30_000L to DurationLabel(DurationLabel.Kind.SECONDS, 30),
            60_000L to DurationLabel(DurationLabel.Kind.MINUTES, 1),
            120_000L to DurationLabel(DurationLabel.Kind.MINUTES, 2),
            300_000L to DurationLabel(DurationLabel.Kind.MINUTES, 5),
        )
        table.forEach { (input, expected) ->
            assertEquals(expected, AutoDismissSteps.label(input), "输入 $input")
        }
        assertEquals(DurationLabel.Kind.UNLIMITED, AutoDismissSteps.label(Long.MAX_VALUE).kind)
        assertEquals(DurationLabel.Kind.SECONDS, AutoDismissSteps.label(0L).kind) // 越界先吸附再出形态
    }
}
