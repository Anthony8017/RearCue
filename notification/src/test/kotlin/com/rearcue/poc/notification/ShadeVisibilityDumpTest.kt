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

    @Test
    fun `锁屏态 KeyguardCoordinator 过滤器不算隐藏（真机锁屏 dump 原文）`() {
        // 2026-09-29 真机（HyperOS 3）锁屏下的 dump 原文：全部用户通知都被标
        // filter=KeyguardCoordinator。Shade-visible 的定义是「解锁状态下下拉栏会列出的通知」，
        // 所以这些 key 必须照常可见——否则锁屏时背屏会把自己刚收到的通知删掉。
        val dump = """
            NotifCollection:
            ----------------------------------------------------------------------------
            	NotifCollection unsorted/unfiltered notifications: 3
            		[0]  0|com.android.shell|2020|filt1|2000
                pkgName=com.android.shell appUid=2000 sdk=36 sysApp=T priApp=F hasShown=F float=F keyguard=F
                filter=KeyguardCoordinator
            		[1]  0|com.ss.android.lark|2053562389|null|10414
                pkgName=com.ss.android.lark appUid=10414 sdk=35 sysApp=F priApp=F hasShown=F float=T keyguard=F
                filter=KeyguardCoordinator
            		[2]  0|com.xiaomi.smarthome|1877659641|null|10305
                pkgName=com.xiaomi.smarthome appUid=10305 sdk=36 sysApp=F priApp=F hasShown=T float=F keyguard=F
            missingNotifications: 1
              0|com.miui.misound|887|null|10184
        """.trimIndent()

        assertEquals(
            setOf(
                "0|com.android.shell|2020|filt1|2000",
                "0|com.ss.android.lark|2053562389|null|10414",
                "0|com.xiaomi.smarthome|1877659641|null|10305",
            ),
            ShadeVisibilityDump.parse(dump),
        )
    }

    @Test
    fun `锁屏态豁免只认 KeyguardCoordinator，内容过滤器照样剔除`() {
        val dump = """
            NotifCollection unsorted/unfiltered notifications: 3
                [0]  0|com.xiaomi.aicr|0|group|10133
                    filter=SummaryFilter
                [1]  0|com.android.shell|1|null|2000
                    filter=KeyguardCoordinator
                [2]  0|com.android.shell|2|null|2000
                    filter=KeyguardCoordinator: extra
            missingNotifications: 0
        """.trimIndent()

        assertEquals(
            setOf("0|com.android.shell|1|null|2000", "0|com.android.shell|2|null|2000"),
            ShadeVisibilityDump.parse(dump),
        )
    }

    @Test
    fun `条目形状变化（缺少序号前缀）返回 null 走 fail-open`() {
        val dump = """
            NotifCollection unsorted/unfiltered notifications: 1
                0|com.example|1|null|1000 section=4:6:Alerting
            missingNotifications: 0
        """.trimIndent()
        assertNull(ShadeVisibilityDump.parse(dump))
    }
}
