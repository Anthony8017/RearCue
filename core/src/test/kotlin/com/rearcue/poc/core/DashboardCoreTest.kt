package com.rearcue.poc.core

import com.rearcue.poc.core.DashboardEvent.Allowlist
import com.rearcue.poc.core.DashboardEvent.AutostartStatus
import com.rearcue.poc.core.DashboardEvent.DashboardDetached
import com.rearcue.poc.core.DashboardEvent.DndGate
import com.rearcue.poc.core.DashboardEvent.FallbackAvailable
import com.rearcue.poc.core.DashboardEvent.ListenerHealth
import com.rearcue.poc.core.DashboardEvent.ListenerProbe
import com.rearcue.poc.core.DashboardEvent.ManualCast
import com.rearcue.poc.core.DashboardEvent.ManualExit
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.NotificationRemoved
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.ProjectionUnavailable
import com.rearcue.poc.core.DashboardEvent.TakeoverDetected
import com.rearcue.poc.core.DashboardEffect.Degrade
import com.rearcue.poc.core.DashboardEffect.ExitDashboard
import com.rearcue.poc.core.DashboardEffect.HideUsabilityBanner
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import com.rearcue.poc.core.DashboardEffect.RequestRebind
import com.rearcue.poc.core.DashboardEffect.ShowUsabilityBanner
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
    fun `Dashboard 意外销毁后按当前 Icon Set 重投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(DashboardDetached),
        )
    }

    @Test
    fun `Dashboard 意外销毁且无通知时不复活`() {
        val core = core()
        core.onEvent(ProjectionReady)

        assertEquals(emptyList(), core.onEvent(DashboardDetached))
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

    // ---------- 票 #8：兜底通道（Shizuku）恢复后重投 ----------

    @Test
    fun `兜底通道恢复后按当前 Icon Set 幂等重投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(qq))

        assertEquals(
            listOf(LaunchDashboard(setOf(qq, wechat))),
            core.onEvent(FallbackAvailable),
        )
        // 重复恢复（授权成功 + server 上线会各报一次）不改变结果。
        assertEquals(
            listOf(LaunchDashboard(setOf(qq, wechat))),
            core.onEvent(FallbackAvailable),
        )
    }

    @Test
    fun `无通知时兜底通道恢复无效果`() {
        val core = core()
        core.onEvent(ProjectionReady)

        assertEquals(emptyList(), core.onEvent(FallbackAvailable))
    }

    @Test
    fun `投送通道不可用时兜底通道恢复无效果`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ProjectionUnavailable)

        assertEquals(emptyList(), core.onEvent(FallbackAvailable))
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

    // ---------- 票 #28：可用性横幅（运行时事件变更：出现/消失/健康不打扰三态） ----------

    @Test
    fun `自启动未放行时横幅出现`() {
        val core = core()

        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.AUTOSTART_DENIED))),
            core.onEvent(AutostartStatus(AutostartState.DENIED)),
        )
    }

    @Test
    fun `自启动状态存疑时横幅以降级形态出现（绝不显示健康）`() {
        val core = core()

        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.AUTOSTART_IN_DOUBT))),
            core.onEvent(AutostartStatus(AutostartState.IN_DOUBT)),
        )
    }

    @Test
    fun `监听不健康时横幅出现`() {
        val core = core()

        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.LISTENER_UNHEALTHY))),
            core.onEvent(ListenerHealth(false)),
        )
    }

    @Test
    fun `通知使用权从未授予（服务从未连接）时横幅出现，不静默`() {
        val core = core()

        // 服务从未连接 = 只会等到「未授权」读数（Android 层 isListenerEnabled=false →
        // ListenerHealth(false)），三态判例的「未授权」态必须出横幅。
        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.LISTENER_UNHEALTHY))),
            core.onEvent(ListenerHealth(false)),
        )
    }

    @Test
    fun `自启动已放行不豁免监听未授权，横幅仍出现`() {
        val core = core()
        core.onEvent(AutostartStatus(AutostartState.GRANTED))

        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.LISTENER_UNHEALTHY))),
            core.onEvent(ListenerHealth(false)),
        )
    }

    @Test
    fun `恢复放行后横幅消失`() {
        val core = core()
        core.onEvent(AutostartStatus(AutostartState.DENIED))

        assertEquals(
            listOf(HideUsabilityBanner),
            core.onEvent(AutostartStatus(AutostartState.GRANTED)),
        )
    }

    @Test
    fun `监听恢复健康后横幅消失`() {
        val core = core()
        core.onEvent(ListenerHealth(false))

        assertEquals(
            listOf(HideUsabilityBanner),
            core.onEvent(ListenerHealth(true)),
        )
    }

    @Test
    fun `存疑恢复为已放行后横幅消失`() {
        val core = core()
        core.onEvent(AutostartStatus(AutostartState.IN_DOUBT))

        assertEquals(
            listOf(HideUsabilityBanner),
            core.onEvent(AutostartStatus(AutostartState.GRANTED)),
        )
    }

    @Test
    fun `健康时横幅不打扰`() {
        val core = core()

        assertEquals(emptyList(), core.onEvent(AutostartStatus(AutostartState.GRANTED)))
        assertEquals(emptyList(), core.onEvent(ListenerHealth(true)))
        assertEquals(emptyList(), core.onEvent(AutostartStatus(AutostartState.GRANTED)))
    }

    @Test
    fun `重复同类异常不重复弹横幅`() {
        val core = core()
        core.onEvent(AutostartStatus(AutostartState.DENIED))

        assertEquals(emptyList(), core.onEvent(AutostartStatus(AutostartState.DENIED)))
        // 另一维度读到健康不改变原因集，同样无效果。
        assertEquals(emptyList(), core.onEvent(ListenerHealth(true)))
    }

    @Test
    fun `异常叠加时横幅原因集更新`() {
        val core = core()
        core.onEvent(AutostartStatus(AutostartState.DENIED))

        assertEquals(
            listOf(
                ShowUsabilityBanner(
                    setOf(UsabilityReason.AUTOSTART_DENIED, UsabilityReason.LISTENER_UNHEALTHY),
                ),
            ),
            core.onEvent(ListenerHealth(false)),
        )
    }

    @Test
    fun `部分恢复只收窄原因集，横幅不隐藏`() {
        val core = core()
        core.onEvent(AutostartStatus(AutostartState.DENIED))
        core.onEvent(ListenerHealth(false))

        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.LISTENER_UNHEALTHY))),
            core.onEvent(AutostartStatus(AutostartState.GRANTED)),
        )
    }

    @Test
    fun `横幅决策不干扰投送链路`() {
        val core = core()
        core.onEvent(ProjectionReady)

        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.AUTOSTART_DENIED))),
            core.onEvent(AutostartStatus(AutostartState.DENIED)),
        )
        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(NotificationPosted(wechat)),
        )
    }

    // ---------- 票 #31：监听探针 → 重绑效果 ----------

    @Test
    fun `已授权但监听未连接时探针请求重绑`() {
        val core = core()

        assertEquals(
            listOf(RequestRebind),
            core.onEvent(ListenerProbe(enabled = true, listenerConnected = false)),
        )
    }

    @Test
    fun `已授权且监听已连接时探针无效果`() {
        val core = core()

        assertEquals(
            emptyList(),
            core.onEvent(ListenerProbe(enabled = true, listenerConnected = true)),
        )
    }

    @Test
    fun `未授权且监听未连接时探针无效果，既有未授权横幅仍出现`() {
        val core = core()

        assertEquals(
            emptyList(),
            core.onEvent(ListenerProbe(enabled = false, listenerConnected = false)),
        )
        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.LISTENER_UNHEALTHY))),
            core.onEvent(ListenerHealth(false)),
        )
    }

    @Test
    fun `未授权且监听已连接时探针无效果，既有未授权横幅仍出现`() {
        val core = core()

        assertEquals(
            emptyList(),
            core.onEvent(ListenerProbe(enabled = false, listenerConnected = true)),
        )
        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.LISTENER_UNHEALTHY))),
            core.onEvent(ListenerHealth(false)),
        )
    }

    @Test
    fun `探针只请求重绑，不改变横幅或投送决策`() {
        val core = core()
        core.onEvent(ProjectionReady)
        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(NotificationPosted(wechat)))
        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.LISTENER_UNHEALTHY))),
            core.onEvent(ListenerHealth(false)),
        )

        // 重绑请求不是健康正读数，也不触碰已有横幅原因或投送状态。
        assertEquals(
            listOf(RequestRebind),
            core.onEvent(ListenerProbe(enabled = true, listenerConnected = false)),
        )
        assertEquals(
            listOf(
                ShowUsabilityBanner(
                    setOf(UsabilityReason.AUTOSTART_DENIED, UsabilityReason.LISTENER_UNHEALTHY),
                ),
            ),
            core.onEvent(AutostartStatus(AutostartState.DENIED)),
        )
        assertEquals(
            listOf(ShowUsabilityBanner(setOf(UsabilityReason.LISTENER_UNHEALTHY))),
            core.onEvent(AutostartStatus(AutostartState.GRANTED)),
        )
        assertEquals(
            listOf(UpdateIconSet(setOf(wechat, qq))),
            core.onEvent(NotificationPosted(qq)),
        )
        assertEquals(listOf(HideUsabilityBanner), core.onEvent(ListenerHealth(true)))
        assertEquals(
            listOf(UpdateIconSet(setOf(qq))),
            core.onEvent(NotificationRemoved(wechat)),
        )
        assertEquals(listOf(ExitDashboard), core.onEvent(NotificationRemoved(qq)))
    }

    // ---------- spec 0006 / 票 #52：DND 门控（开/关 × auto/manual） ----------

    @Test
    fun `DND 开启期间新 Allowlist 通知不投送`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(DndGate(active = true))

        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat)))
    }

    @Test
    fun `DND 开启撤下 auto 在屏 Dashboard`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(ExitDashboard), core.onEvent(DndGate(active = true)))
    }

    @Test
    fun `DND 关闭且 Icon Set 非空时补投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(DndGate(active = true))
        core.onEvent(NotificationPosted(wechat)) // DND 期间静默

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(DndGate(active = false)),
        )
    }

    @Test
    fun `DND 关闭且 Icon Set 为空时不补投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(DndGate(active = true))

        assertEquals(emptyList(), core.onEvent(DndGate(active = false)))
    }

    @Test
    fun `补投记 auto 来源，DND 再开仍会撤下（开-关-开全周期）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(DndGate(active = true))
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(DndGate(active = false)) // 补投，记 auto

        assertEquals(listOf(ExitDashboard), core.onEvent(DndGate(active = true)))
    }

    @Test
    fun `DND 期间通道就绪不投，DND 关闭后补投`() {
        val core = core()
        core.onEvent(DndGate(active = true))
        core.onEvent(NotificationPosted(wechat))

        assertEquals(emptyList(), core.onEvent(ProjectionReady))

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(DndGate(active = false)),
        )
    }

    @Test
    fun `DND 期间兜底通道恢复不重投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(DndGate(active = true)) // 撤下 auto 在屏

        assertEquals(emptyList(), core.onEvent(FallbackAvailable))
    }

    @Test
    fun `auto 在屏被 DND 撤下后 Takeover 不复活`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(DndGate(active = true))

        assertEquals(emptyList(), core.onEvent(TakeoverDetected))
    }

    @Test
    fun `DND 重复开关事件幂等`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(ExitDashboard), core.onEvent(DndGate(active = true)))
        assertEquals(emptyList(), core.onEvent(DndGate(active = true)))
        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(DndGate(active = false)),
        )
        assertEquals(emptyList(), core.onEvent(DndGate(active = false)))
    }

    @Test
    fun `DND 开启不撤 manual 在屏`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(ManualCast)

        assertEquals(emptyList(), core.onEvent(DndGate(active = true)))
    }

    @Test
    fun `manual 投送豁免 DND 门控（DND 开着也投）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(DndGate(active = true))

        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(ManualCast))
    }

    @Test
    fun `manual 在屏末条通知清空不退出（手动投的手动撤）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ManualCast) // 改记 manual

        assertEquals(emptyList(), core.onEvent(NotificationRemoved(wechat)))
    }

    @Test
    fun `manual 在屏 Allowlist 清空不退出`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ManualCast)

        assertEquals(emptyList(), core.onEvent(Allowlist(setOf("com.other.app"))))
    }

    @Test
    fun `manual 在屏 Icon Set 变化仍更新内容（更新不是投或撤）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ManualCast)
        core.onEvent(DndGate(active = true)) // manual 豁免，仍留在屏

        assertEquals(
            listOf(UpdateIconSet(setOf(qq, wechat))),
            core.onEvent(NotificationPosted(qq)),
        )
    }

    @Test
    fun `manual 在屏被抢回后重投且保持 manual（末条清空仍不退）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ManualCast)

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(TakeoverDetected))
        assertEquals(emptyList(), core.onEvent(NotificationRemoved(wechat)))
    }

    @Test
    fun `manual 投送后通道不可用仍 Degrade（通道物理失效不分来源）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(ManualCast)

        assertEquals(listOf(Degrade), core.onEvent(ProjectionUnavailable))
    }

    @Test
    fun `auto 升级为 manual 后 DND 不再撤它`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat)) // auto 上屏

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(ManualCast))

        assertEquals(emptyList(), core.onEvent(DndGate(active = true)))
    }

    // ---------- spec 0006 / 票 #52：手动投送/退出（Debug Bypass 记 manual） ----------

    @Test
    fun `手动投送投当前 Icon Set，无通知时投空集`() {
        val core = core()
        core.onEvent(ProjectionReady)

        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(ManualCast))
        assertEquals(CastSource.MANUAL, core.castSource)
    }

    @Test
    fun `手动退出结束在屏，随后新通知恢复自动投送`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(ManualCast)

        assertEquals(listOf(ExitDashboard), core.onEvent(ManualExit))
        assertEquals(null, core.castSource)

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(NotificationPosted(wechat)),
        )
    }

    @Test
    fun `手动退出无在屏时无效果`() {
        val core = core()
        core.onEvent(ProjectionReady)

        assertEquals(emptyList(), core.onEvent(ManualExit))
    }

    @Test
    fun `手动投送通道未就绪无效果`() {
        val core = core()

        assertEquals(emptyList(), core.onEvent(ManualCast))
        assertEquals(null, core.castSource)
    }

    @Test
    fun `手动退出后 DND 关闭补投不受影响`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(ManualCast)
        core.onEvent(ManualExit)
        core.onEvent(DndGate(active = true))
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(DndGate(active = false)) // 补投记 auto

        assertEquals(listOf(ExitDashboard), core.onEvent(DndGate(active = true)))
    }
}
