package com.rearcue.poc.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShadeVisibilityDumpTest {

    @Test
    fun `解析 SystemUI 当前可见 key`() {
        val dump = """
            NotifCollection:
            NotifCollection unsorted/unfiltered notifications: 5
                [0]  -1|android|0|-1|android|g:Aggregate_AlertingSection|1000|-1|android|g:Aggregate_AlertingSection section=4:6:Alerting
                [1]  -1|android|26|null|1000 section=4:6:Alerting
                [2]  -1|android|32|null|1000 section=4:6:Alerting
                [3]  0|com.openai.chatgpt|595233003|tag|10370 section=4:6:Alerting
                [4]  0|com.xiaomi.smarthome|1877659213|null|10305 section=4:6:Alerting

            notificationsWithoutRankings: 0
            missingNotifications: 8
              0|com.miui.misound|887|null|10184
              0|com.xiaomi.aicr|1|null|10133
        """.trimIndent()

        assertEquals(
            setOf(
                "-1|android|0|-1|android|g:Aggregate_AlertingSection|1000|-1|android|g:Aggregate_AlertingSection",
                "-1|android|26|null|1000",
                "-1|android|32|null|1000",
                "0|com.openai.chatgpt|595233003|tag|10370",
                "0|com.xiaomi.smarthome|1877659213|null|10305",
            ),
            ShadeVisibilityDump.parse(dump),
        )
    }

    @Test
    fun `空可见集合是合法结果`() {
        val dump = """
            NotifCollection unsorted/unfiltered notifications: 0
            notificationsWithoutRankings: 0
            missingNotifications: 1
              0|com.miui.misound|887|null|10184
        """.trimIndent()

        assertEquals(emptySet(), ShadeVisibilityDump.parse(dump))
    }

    @Test
    fun `目标段缺失时返回 null 走 fail-open`() {
        assertNull(ShadeVisibilityDump.parse("not a systemui dump"))
        assertNull(
            ShadeVisibilityDump.parse(
                """
                NotifCollection unsorted/unfiltered notifications: 1
                    [0]  0|com.example|1|null|1000 section=4:6:Alerting
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `可见条目数量与头部计数不符时返回 null 走 fail-open`() {
        val dump = """
            NotifCollection unsorted/unfiltered notifications: 2
                [0]  0|com.openai.chatgpt|1|null|10370 section=4:6:Alerting
            missingNotifications: 0
        """.trimIndent()
        assertNull(ShadeVisibilityDump.parse(dump))
    }

    @Test
    fun `pre-group 列表里带 filter 的摘要不计入可见集合`() {
        val dump = """
            NotifCollection unsorted/unfiltered notifications: 2
                [0]  0|com.xiaomi.aicr|0|0|com.xiaomi.aicr|g:Aggregate_AlertingSection|10133|0|com.xiaomi.aicr|g:Aggregate_AlertingSection
                StatusBarNotification(pkg=com.xiaomi.aicr user=UserHandle{0} id=0 tag=0|com.xiaomi.aicr|g:Aggregate_AlertingSection key=0|com.xiaomi.aicr|0|0|com.xiaomi.aicr|g:Aggregate_AlertingSection|10133|0|com.xiaomi.aicr|g:Aggregate_AlertingSection: Notification(channel=phrase flags=GROUP_SUMMARY))
                    filter=SummaryFilter
                [1]  0|com.openai.chatgpt|1|null|10370 section=4:6:Alerting
            missingNotifications: 1
              0|com.miui.misound|887|null|10184
        """.trimIndent()
        assertEquals(setOf("0|com.openai.chatgpt|1|null|10370"), ShadeVisibilityDump.parse(dump))
    }
}
