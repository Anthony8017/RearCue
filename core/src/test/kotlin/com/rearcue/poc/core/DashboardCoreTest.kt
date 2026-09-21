package com.rearcue.poc.core

import com.rearcue.poc.core.DashboardEvent.Allowlist
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.NotificationRemoved
import com.rearcue.poc.core.DashboardEvent.ShizukuConnected
import com.rearcue.poc.core.DashboardEvent.ShizukuDisconnected
import com.rearcue.poc.core.DashboardEvent.TakeoverDetected
import com.rearcue.poc.core.DashboardEffect.Degrade
import com.rearcue.poc.core.DashboardEffect.ExitDashboard
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import com.rearcue.poc.core.DashboardEffect.UpdateIconSet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DashboardCore 行为测试：只断言「事件序列 → 效果序列」，不断言内部状态。
 *
 * 词汇见 CONTEXT.md：Active Notification、Allowlist App、Icon Set、Degrade、Takeover。
 */
class DashboardCoreTest {

    private val wechat = "com.tencent.mm"
    private val qq = "com.tencent.mobileqq"

    private fun core(vararg allowlist: String = arrayOf(wechat, qq)) = DashboardCore(allowlist.toSet())

    // ---------- 首条通知上屏 ----------

    @Test
    fun `首条 Allowlist 通知 LaunchDashboard 上屏`() {
        val core = core()

        core.onEvent(ShizukuConnected)

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(NotificationPosted(wechat)),
        )
    }

    @Test
    fun `未连接时首条通知不投屏，连接后立即补投`() {
        val core = core()

        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat)))

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(ShizukuConnected),
        )
    }

    @Test
    fun `非 Allowlist 应用通知无效果`() {
        val core = core()

        core.onEvent(ShizukuConnected)

        assertEquals(emptyList(), core.onEvent(NotificationPosted("com.stranger.app")))
    }

    @Test
    fun `默认 Allowlist 为 POC 四应用`() {
        val core = DashboardCore() // 不传初始 Allowlist，用 PocAllowlist.APPS

        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted("com.android.shell"))

        assertEquals(
            listOf(UpdateIconSet(setOf("com.android.shell", "com.tencent.mm"))),
            core.onEvent(NotificationPosted("com.tencent.mm")),
        )
    }

    // ---------- Icon Set 维护 ----------

    @Test
    fun `第二个 Allowlist 应用进 Icon Set 时 UpdateIconSet`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(
            listOf(UpdateIconSet(setOf(qq, wechat))),
            core.onEvent(NotificationPosted(qq)),
        )
    }

    @Test
    fun `同一应用重复通知不改变 Icon Set，无效果`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat)))
    }

    @Test
    fun `多枚通知需逐枚移除才退出`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(wechat))

        assertEquals(emptyList(), core.onEvent(NotificationRemoved(wechat)))

        assertEquals(
            listOf(ExitDashboard),
            core.onEvent(NotificationRemoved(wechat)),
        )
    }

    @Test
    fun `移除未知应用通知无效果`() {
        val core = core()
        core.onEvent(ShizukuConnected)

        assertEquals(emptyList(), core.onEvent(NotificationRemoved(wechat)))
    }

    // ---------- 末条通知 ExitDashboard ----------

    @Test
    fun `末条通知移除后 ExitDashboard`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(qq))

        assertEquals(
            listOf(UpdateIconSet(setOf(qq))),
            core.onEvent(NotificationRemoved(wechat)),
        )

        assertEquals(
            listOf(ExitDashboard),
            core.onEvent(NotificationRemoved(qq)),
        )
    }

    @Test
    fun `退出后再来新通知重新 LaunchDashboard`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationRemoved(wechat))

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(NotificationPosted(wechat)),
        )
    }

    // ---------- Shizuku 断开 Degrade / 恢复重投 ----------

    @Test
    fun `Dashboard 在屏时 Shizuku 断开产出 Degrade`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(Degrade), core.onEvent(ShizukuDisconnected))
    }

    @Test
    fun `不在屏时 Shizuku 断开无效果`() {
        val core = core()

        assertEquals(emptyList(), core.onEvent(ShizukuDisconnected))
    }

    @Test
    fun `重复断开只 Degrade 一次`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ShizukuDisconnected)

        assertEquals(emptyList(), core.onEvent(ShizukuDisconnected))
    }

    @Test
    fun `Degrade 期间通知增减只维护 Icon Set，不产出效果`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ShizukuDisconnected)

        assertEquals(emptyList(), core.onEvent(NotificationPosted(qq)))
        assertEquals(emptyList(), core.onEvent(NotificationRemoved(wechat)))
    }

    @Test
    fun `Shizuku 恢复后按当前 Icon Set 重投`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(qq))
        core.onEvent(ShizukuDisconnected)
        core.onEvent(NotificationRemoved(wechat)) // Degrade 期间 QQ 仍在

        assertEquals(
            listOf(LaunchDashboard(setOf(qq))),
            core.onEvent(ShizukuConnected),
        )
    }

    @Test
    fun `Degrade 期间通知清空则恢复后不投`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ShizukuDisconnected)
        core.onEvent(NotificationRemoved(wechat))

        assertEquals(emptyList(), core.onEvent(ShizukuConnected))
    }

    // ---------- Takeover 重投 ----------

    @Test
    fun `Takeover 后按当前 Icon Set 幂等重投`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(qq))

        assertEquals(
            listOf(LaunchDashboard(setOf(qq, wechat))),
            core.onEvent(TakeoverDetected),
        )
    }

    @Test
    fun `Degrade 期间 Takeover 无效果`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ShizukuDisconnected)

        assertEquals(emptyList(), core.onEvent(TakeoverDetected))
    }

    @Test
    fun `无通知时 Takeover 无效果`() {
        val core = core()
        core.onEvent(ShizukuConnected)

        assertEquals(emptyList(), core.onEvent(TakeoverDetected))
    }

    // ---------- Allowlist 变更 ----------

    @Test
    fun `Allowlist 收窄使 Icon Set 变化时 UpdateIconSet`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(qq))

        assertEquals(
            listOf(UpdateIconSet(setOf(wechat))),
            core.onEvent(Allowlist(setOf(wechat))),
        )
    }

    @Test
    fun `Allowlist 变更清空 Icon Set 时 ExitDashboard`() {
        val core = core()
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(
            listOf(ExitDashboard),
            core.onEvent(Allowlist(setOf("com.other.app"))),
        )
    }

    @Test
    fun `Allowlist 扩容使既有通知上屏`() {
        val core = core(wechat)
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(qq)) // 非 Allowlist，无效果

        assertEquals(
            listOf(LaunchDashboard(setOf(qq))),
            core.onEvent(Allowlist(setOf(wechat, qq))),
        )
    }

    @Test
    fun `Allowlist 变更不影响 Icon Set 内容时无效果`() {
        val core = core(wechat, qq)
        core.onEvent(ShizukuConnected)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(emptyList(), core.onEvent(Allowlist(setOf(qq, wechat))))
    }
}
