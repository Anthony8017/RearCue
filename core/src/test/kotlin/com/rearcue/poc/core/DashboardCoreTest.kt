package com.rearcue.poc.core

import com.rearcue.poc.core.DashboardEvent.Allowlist
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.NotificationRemoved
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.ProjectionUnavailable
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

        core.onEvent(ProjectionReady)

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(NotificationPosted(wechat)),
        )
    }

    @Test
    fun `未就绪时首条通知不投屏，通道就绪后立即补投`() {
        val core = core()

        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat)))

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(ProjectionReady),
        )
    }

    @Test
    fun `非 Allowlist 应用通知无效果`() {
        val core = core()

        core.onEvent(ProjectionReady)

        assertEquals(emptyList(), core.onEvent(NotificationPosted("com.stranger.app")))
    }

    @Test
    fun `默认 Allowlist 为 POC 四应用`() {
        val core = DashboardCore() // 不传初始 Allowlist，用 PocAllowlist.APPS

        core.onEvent(ProjectionReady)
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
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(
            listOf(UpdateIconSet(setOf(qq, wechat))),
            core.onEvent(NotificationPosted(qq)),
        )
    }

    @Test
    fun `同一应用重复通知不改变 Icon Set，无效果`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat)))
    }

    @Test
    fun `多枚通知需逐枚移除才退出`() {
        val core = core()
        core.onEvent(ProjectionReady)
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
        core.onEvent(ProjectionReady)

        assertEquals(emptyList(), core.onEvent(NotificationRemoved(wechat)))
    }

    // ---------- 末条通知 ExitDashboard ----------

    @Test
    fun `末条通知移除后 ExitDashboard`() {
        val core = core()
        core.onEvent(ProjectionReady)
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
    fun `票 5 链路：首条通知自动上屏、Icon Set 同步、末条自动退出、再来再上屏`() {
        val core = core()
        core.onEvent(ProjectionReady) // 投送通道就绪

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(NotificationPosted(wechat)),
        )
        assertEquals(
            listOf(UpdateIconSet(setOf(wechat, qq))),
            core.onEvent(NotificationPosted(qq)),
        )
        assertEquals(
            listOf(UpdateIconSet(setOf(qq))),
            core.onEvent(NotificationRemoved(wechat)),
        )
        assertEquals(listOf(ExitDashboard), core.onEvent(NotificationRemoved(qq)))
        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(NotificationPosted(wechat)),
        )
    }

    @Test
    fun `退出后再来新通知重新 LaunchDashboard`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationRemoved(wechat))

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(NotificationPosted(wechat)),
        )
    }

    // ---------- 投送通道不可用 Degrade / 恢复重投 ----------

    @Test
    fun `Dashboard 在屏时通道不可用产出 Degrade`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(Degrade), core.onEvent(ProjectionUnavailable))
    }

    @Test
    fun `不在屏时通道不可用无效果`() {
        val core = core()

        assertEquals(emptyList(), core.onEvent(ProjectionUnavailable))
    }

    @Test
    fun `重复断开只 Degrade 一次`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ProjectionUnavailable)

        assertEquals(emptyList(), core.onEvent(ProjectionUnavailable))
    }

    @Test
    fun `Degrade 期间通知增减只维护 Icon Set，不产出效果`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ProjectionUnavailable)

        assertEquals(emptyList(), core.onEvent(NotificationPosted(qq)))
        assertEquals(emptyList(), core.onEvent(NotificationRemoved(wechat)))
    }

    @Test
    fun `通道恢复后按当前 Icon Set 重投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(qq))
        core.onEvent(ProjectionUnavailable)
        core.onEvent(NotificationRemoved(wechat)) // Degrade 期间 QQ 仍在

        assertEquals(
            listOf(LaunchDashboard(setOf(qq))),
            core.onEvent(ProjectionReady),
        )
    }

    @Test
    fun `Degrade 期间通知清空则恢复后不投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ProjectionUnavailable)
        core.onEvent(NotificationRemoved(wechat))

        assertEquals(emptyList(), core.onEvent(ProjectionReady))
    }

    // ---------- Takeover 重投 ----------

    @Test
    fun `Takeover 后按当前 Icon Set 幂等重投`() {
        val core = core()
        core.onEvent(ProjectionReady)
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
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ProjectionUnavailable)

        assertEquals(emptyList(), core.onEvent(TakeoverDetected))
    }

    @Test
    fun `无通知时 Takeover 无效果`() {
        val core = core()
        core.onEvent(ProjectionReady)

        assertEquals(emptyList(), core.onEvent(TakeoverDetected))
    }

    // ---------- 票 #6 韧性：锁屏 / AOD 抢回 / 背屏信号 ----------

    @Test
    fun `末条通知退出后，背屏亮起信号不复活 Dashboard`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationRemoved(wechat)) // ExitDashboard：无通知就不该占着背屏

        assertEquals(emptyList(), core.onEvent(TakeoverDetected))
    }

    @Test
    fun `抢回重投后 Icon Set 再变化只更新，不重复投送`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(TakeoverDetected) // 锁屏/AOD 抢回后重投

        assertEquals(
            listOf(UpdateIconSet(setOf(wechat, qq))),
            core.onEvent(NotificationPosted(qq)),
        )
    }

    @Test
    fun `连续抢回信号每次都按当前 Icon Set 重投（幂等）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(TakeoverDetected))
        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(TakeoverDetected))
    }

    @Test
    fun `锁屏抢回后通道不可用只降级，恢复即重投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(TakeoverDetected)

        // 通道不可用：Dashboard 在屏，产出一次 Degrade；此后抢回信号没有可重投的通道。
        assertEquals(listOf(Degrade), core.onEvent(ProjectionUnavailable))
        assertEquals(emptyList(), core.onEvent(TakeoverDetected))

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(ProjectionReady),
        )
    }

    // ---------- Icon Set 只读视图（主屏调试页用） ----------

    @Test
    fun `iconSet 跟随通知增减，与非 Allowlist 无关`() {
        val core = core(wechat, qq)

        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted("com.stranger.app"))
        core.onEvent(NotificationPosted(qq))
        assertEquals(listOf(wechat, qq), core.iconSet)

        core.onEvent(NotificationRemoved(wechat))
        assertEquals(listOf(qq), core.iconSet)

        core.onEvent(NotificationRemoved(qq))
        assertEquals(emptyList(), core.iconSet)
    }

    @Test
    fun `iconSet 未连接时同样可见`() {
        val core = core(wechat)

        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(wechat), core.iconSet)
    }

    @Test
    fun `Allowlist 变更后 iconSet 立即收窄`() {
        val core = core(wechat, qq)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(qq))

        core.onEvent(Allowlist(setOf(wechat)))

        assertEquals(listOf(wechat), core.iconSet)
    }

    @Test
    fun `iconSet 顺序为首次出现顺序，重复通知不重排`() {
        val core = core(wechat, qq)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(qq))

        core.onEvent(NotificationPosted(wechat)) // 微信再来一枚

        assertEquals(listOf(wechat, qq), core.iconSet)
    }

    // ---------- Allowlist 变更 ----------

    @Test
    fun `Allowlist 收窄使 Icon Set 变化时 UpdateIconSet`() {
        val core = core()
        core.onEvent(ProjectionReady)
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
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(
            listOf(ExitDashboard),
            core.onEvent(Allowlist(setOf("com.other.app"))),
        )
    }

    @Test
    fun `Allowlist 扩容使既有通知上屏`() {
        val core = core(wechat)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(qq)) // 非 Allowlist，无效果

        assertEquals(
            listOf(LaunchDashboard(setOf(qq))),
            core.onEvent(Allowlist(setOf(wechat, qq))),
        )
    }

    @Test
    fun `Allowlist 变更不影响 Icon Set 内容时无效果`() {
        val core = core(wechat, qq)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(emptyList(), core.onEvent(Allowlist(setOf(qq, wechat))))
    }
}
