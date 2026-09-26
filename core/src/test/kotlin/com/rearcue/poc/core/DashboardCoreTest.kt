package com.rearcue.poc.core

import com.rearcue.poc.core.DashboardEvent.Allowlist
import com.rearcue.poc.core.DashboardEvent.AutostartStatus
import com.rearcue.poc.core.DashboardEvent.ChargingAnimation
import com.rearcue.poc.core.DashboardEvent.DashboardDetached
import com.rearcue.poc.core.DashboardEvent.DndGate
import com.rearcue.poc.core.DashboardEvent.FallbackAvailable
import com.rearcue.poc.core.DashboardEvent.HighlightSeen
import com.rearcue.poc.core.DashboardEvent.ListenerHealth
import com.rearcue.poc.core.DashboardEvent.ListenerProbe
import com.rearcue.poc.core.DashboardEvent.ManualCast
import com.rearcue.poc.core.DashboardEvent.ManualExit
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEvent.NotificationRemoved
import com.rearcue.poc.core.DashboardEvent.NotificationUpdated
import com.rearcue.poc.core.DashboardEvent.PostureGate
import com.rearcue.poc.core.DashboardEvent.PowerConnected
import com.rearcue.poc.core.DashboardEvent.PowerDisconnected
import com.rearcue.poc.core.DashboardEvent.ProjectionReady
import com.rearcue.poc.core.DashboardEvent.ProjectionUnavailable
import com.rearcue.poc.core.DashboardEvent.TakeoverDetected
import com.rearcue.poc.core.DashboardEffect.Degrade
import com.rearcue.poc.core.DashboardEffect.ExitDashboard
import com.rearcue.poc.core.DashboardEffect.HideUsabilityBanner
import com.rearcue.poc.core.DashboardEffect.HighlightBreath
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import com.rearcue.poc.core.DashboardEffect.RequestRebind
import com.rearcue.poc.core.DashboardEffect.ShowUsabilityBanner
import com.rearcue.poc.core.DashboardEffect.UpdateIconSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DashboardCore 行为测试：只断言「事件序列 → 效果序列」，不断言内部状态。
 *
 * 词汇见 CONTEXT.md：Active Notification、Allowlist App、Icon Set、Degrade、Takeover。
 */
class DashboardCoreTest {

    private val wechat = "com.tencent.mm"
    private val qq = "com.tencent.mobileqq"

    /** 时钟定在 t=0 的 core：呼吸窗断言确定（untilMs = 3000）；既有判例语义与时钟无涉。 */
    private fun core(vararg allowlist: String = arrayOf(wechat, qq)) =
        DashboardCore(allowlist.toSet(), { 0L })

    /** 默认虚拟时钟（t=0）下首触呼吸的预期效果（呼吸窗截止 = 呼吸窗长）。 */
    private fun highlight(vararg apps: String) = HighlightBreath(apps.toSet(), DashboardCore.HIGHLIGHT_BREATH_MS)

    // ---------- 首条通知上屏 ----------

    @Test
    fun `首条 Allowlist 通知 LaunchDashboard 上屏`() {
        val core = core()

        core.onEvent(ProjectionReady)

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat)), highlight(wechat)),
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
            listOf(
                UpdateIconSet(setOf("com.android.shell", "com.tencent.mm")),
            ),
            core.onEvent(NotificationPosted("com.tencent.mm")), // 冷却内：只更新图标不重复呼吸
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
            listOf(LaunchDashboard(setOf(wechat)), highlight(wechat)),
            core.onEvent(NotificationPosted(wechat)),
        )
        // 冷却内（虚拟时钟未动）：后续通知只同步图标，不重复呼吸。
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
            listOf(LaunchDashboard(setOf(wechat)), highlight(wechat)),
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
        assertEquals(
            listOf(LaunchDashboard(setOf(wechat)), highlight(wechat)),
            core.onEvent(NotificationPosted(wechat)),
        )
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
            listOf(LaunchDashboard(setOf(wechat)), highlight(wechat)),
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

    // ---------- spec 0006 / 票 #52：interruption filter → DndGate 纯映射 ----------

    @Test
    fun `filter ALL 映射为 DND 关闭`() {
        assertEquals(false, DndGate.fromInterruptionFilter(DndGate.FILTER_ALL).active)
    }

    @Test
    fun `filter UNKNOWN 映射为 DND 关闭（没有实证不冒充开启）`() {
        assertEquals(false, DndGate.fromInterruptionFilter(DndGate.FILTER_UNKNOWN).active)
    }

    @Test
    fun `filter PRIORITY NONE ALARMS 都映射为 DND 开启`() {
        assertEquals(true, DndGate.fromInterruptionFilter(DndGate.FILTER_PRIORITY).active)
        assertEquals(true, DndGate.fromInterruptionFilter(DndGate.FILTER_NONE).active)
        assertEquals(true, DndGate.fromInterruptionFilter(DndGate.FILTER_ALARMS).active)
    }

    @Test
    fun `filter 常量值与公共 API 契约一致`() {
        assertEquals(0, DndGate.FILTER_UNKNOWN)
        assertEquals(1, DndGate.FILTER_ALL)
        assertEquals(2, DndGate.FILTER_PRIORITY)
        assertEquals(3, DndGate.FILTER_NONE)
        assertEquals(4, DndGate.FILTER_ALARMS)
    }

    // ---------- spec 0006 / 票 #53：Posture 门控（正放/倒扣/翻正 × auto/manual） ----------

    @Test
    fun `正放期间新 Allowlist 通知不投送`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))

        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat)))
    }

    @Test
    fun `正放后倒扣且 Icon Set 非空时补投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))
        core.onEvent(NotificationPosted(wechat)) // 正放静默

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(PostureGate(faceDown = true)),
        )
    }

    @Test
    fun `倒扣 auto 在屏翻正撤下`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat)) // 默认倒扣放行，auto 上屏

        assertEquals(listOf(ExitDashboard), core.onEvent(PostureGate(faceDown = false)))
    }

    @Test
    fun `翻正不撤 manual 在屏`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ManualCast)

        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = false)))
    }

    @Test
    fun `正放下 manual 投送豁免姿态门控`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))
        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat))) // auto 被拦

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(ManualCast))
    }

    @Test
    fun `正放时通道就绪不投，倒扣后补投`() {
        val core = core()
        core.onEvent(PostureGate(faceDown = false))
        core.onEvent(NotificationPosted(wechat))

        assertEquals(emptyList(), core.onEvent(ProjectionReady))

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(PostureGate(faceDown = true)),
        )
    }

    @Test
    fun `两门独立：全开才投，任一关撤、再开补投（顺序无关）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(DndGate(active = true))
        core.onEvent(PostureGate(faceDown = true))
        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat)))

        // DND 先开 → 补投
        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(DndGate(active = false)),
        )
        // 姿态后关 → 撤下
        assertEquals(listOf(ExitDashboard), core.onEvent(PostureGate(faceDown = false)))
        // 姿态再开 → 补投（DND 仍关）
        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(PostureGate(faceDown = true)),
        )
        // DND 后开 → 撤下（与先例对称）
        assertEquals(listOf(ExitDashboard), core.onEvent(DndGate(active = true)))
    }

    @Test
    fun `正放撤下后兜底通道恢复不重投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(PostureGate(faceDown = false)) // 撤下

        assertEquals(emptyList(), core.onEvent(FallbackAvailable))
    }

    @Test
    fun `DND 已撤 auto 后翻正无额外效果（两门关闭幂等叠加）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(DndGate(active = true)) // 撤下

        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = false)))
    }

    @Test
    fun `姿态重复事件幂等`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(ExitDashboard), core.onEvent(PostureGate(faceDown = false)))
        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = false)))
        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(PostureGate(faceDown = true)),
        )
        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = true)))
    }

    @Test
    fun `正放静默期间通知清空则倒扣后不补投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationRemoved(wechat))

        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = true)))
    }

    @Test
    fun `正放下 auto 升 manual 后翻正不撤`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))
        core.onEvent(NotificationPosted(wechat)) // 静默

        core.onEvent(ManualCast) // 正放下手动投送成功

        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = false)))
    }

    // ---------- spec 0007 / 票 #55：Notification Feed ----------

    // 判例退役（spec 0008 反转：横幅从背屏撤下，docs/specs/0008-rear-visual-notification-highlight.md）：
    // 原横幅显隐/隐私档/Auto-dismiss/横幅随门控撤补/横幅挂屏判退等判例随 FeedPosted、FeedRemoved、
    // AutoDismissTick、PrivacyMode、AutoDismiss 事件与 ShowFeedBanner、HideFeedBanner 效果整体删除。
    // （票 #65 起 Updated 的新真相——Highlight 触发源——判例在本文件 Highlight 节与 app 模块
    // NotificationEventWiringTest；#64 的「Updated 不产生任何效果」退役断言已改写。）

    // 判例退役（spec 0008 反转）：原「档位读数视图缺省即默认档 / 随设置页事件更新」随
    // Privacy Mode / Auto-dismiss 设置面（feedPrivacyMode、feedAutoDismissMs）删除。

    // ---------- spec 0007 / 票 #57：Charging Animation（插电即投 + 门控豁免 + 退出合取） ----------

    @Test
    fun `插电即投：无通知也投空集 Dashboard，来源记 charging`() {
        val core = core()
        core.onEvent(ProjectionReady)

        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(PowerConnected))
        assertEquals(CastSource.CHARGING, core.castSource)
        assertEquals(true, core.chargingOnScreen)
    }

    @Test
    fun `通道未就绪时插电暂存，就绪后按充电投送`() {
        val core = core()

        assertEquals(emptyList(), core.onEvent(PowerConnected))

        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(ProjectionReady))
        assertEquals(CastSource.CHARGING, core.castSource)
    }

    @Test
    fun `正放且 DND 中插电照样投（两道门不拦独立触发）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))
        core.onEvent(DndGate(active = true))

        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(PowerConnected))
    }

    @Test
    fun `重复插电事件幂等，不重复投送`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)

        assertEquals(emptyList(), core.onEvent(PowerConnected))
        assertEquals(CastSource.CHARGING, core.castSource)
    }

    @Test
    fun `已在屏时插电只改记 charging，不重投（通知内容与动画共存）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat)) // auto 上屏

        assertEquals(emptyList(), core.onEvent(PowerConnected))
        assertEquals(CastSource.CHARGING, core.castSource)
        assertEquals(true, core.chargingOnScreen)
    }

    @Test
    fun `充电在屏不被翻正与勿扰撤下`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)

        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = false)))
        assertEquals(emptyList(), core.onEvent(DndGate(active = true)))
        assertEquals(CastSource.CHARGING, core.castSource)
        assertEquals(true, core.chargingOnScreen)
    }

    @Test
    fun `充电在屏 Icon Set 照常更新，门关着也不撤（共存于同一 Dashboard）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected) // 空集充电屏

        assertEquals(
            listOf(UpdateIconSet(setOf(wechat)), highlight(wechat)),
            core.onEvent(NotificationPosted(wechat)),
        )
        assertEquals(CastSource.CHARGING, core.castSource)

        assertEquals(emptyList(), core.onEvent(DndGate(active = true))) // 门不撤充电屏
        assertEquals(
            listOf(UpdateIconSet(setOf(wechat, qq))),
            core.onEvent(NotificationPosted(qq)), // 冷却内：不重复呼吸
        )
    }

    @Test
    fun `拔电 ∧ Icon Set 空 → 退出（合取成立；spec 0008 横幅项退役）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)

        assertEquals(listOf(ExitDashboard), core.onEvent(PowerDisconnected))
        assertEquals(null, core.castSource)
        assertEquals(false, core.chargingOnScreen)
    }

    @Test
    fun `拔电但 Icon Set 非空不退出，交还自动规则（门开保留、门关撤下）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat)) // auto 上屏
        core.onEvent(PowerConnected) // 改记 charging，内容不动

        assertEquals(emptyList(), core.onEvent(PowerDisconnected))
        assertEquals(CastSource.AUTO, core.castSource)

        assertEquals(listOf(ExitDashboard), core.onEvent(DndGate(active = true)))
    }

    @Test
    fun `门关着时拔电交还自动规则立即撤下（充电期间被豁免的门恢复生效）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(DndGate(active = true))
        core.onEvent(PowerConnected) // DND 中照样投

        assertEquals(listOf(ExitDashboard), core.onEvent(PowerDisconnected))
        assertEquals(null, core.castSource)
    }

    // 判例退役（spec 0008 反转）：原「拔电但有横幅需求不退出，横幅销毁后才退（合取第三项）」
    // 与「充电屏横幅随在屏显示」随横幅语义删除——退出合取只剩「拔电 ∧ Icon Set 空」两项，
    // 判定改写见上例与 reconcileExit 的 KDoc。

    @Test
    fun `总开关默认开，启动首读同值幂等`() {
        val core = core()
        core.onEvent(ProjectionReady)

        assertEquals(true, core.chargingAnimationEnabled)
        assertEquals(emptyList(), core.onEvent(ChargingAnimation(enabled = true)))
        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(PowerConnected))
    }

    @Test
    fun `总开关关闭后插电无反应`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(ChargingAnimation(enabled = false))

        assertEquals(emptyList(), core.onEvent(PowerConnected))
        assertEquals(null, core.castSource)
        assertEquals(false, core.chargingOnScreen)
    }

    @Test
    fun `关闭总开关即时收口充电屏（等价拔电，按合取判退）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)

        assertEquals(listOf(ExitDashboard), core.onEvent(ChargingAnimation(enabled = false)))
        assertEquals(false, core.chargingOnScreen)
        // 仍在插电，但理由不成立：再收到插电事件也不投
        assertEquals(emptyList(), core.onEvent(PowerConnected))
    }

    @Test
    fun `充电中重新打开总开关立即补投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(ChargingAnimation(enabled = false))
        core.onEvent(PowerConnected) // 关着时插电无反应

        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(ChargingAnimation(enabled = true)))
        assertEquals(CastSource.CHARGING, core.castSource)
    }

    @Test
    fun `通道不可用抹掉充电屏后，恢复时按充电重投且不被门拦`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)

        assertEquals(listOf(Degrade), core.onEvent(ProjectionUnavailable))
        assertEquals(false, core.chargingOnScreen)

        core.onEvent(PostureGate(faceDown = false)) // 门关着
        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(ProjectionReady))
        assertEquals(CastSource.CHARGING, core.castSource)
    }

    @Test
    fun `充电屏被抢回后重投保持 charging`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)

        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(TakeoverDetected))
        assertEquals(CastSource.CHARGING, core.castSource)
    }

    @Test
    fun `充电屏上通知清空：图标面刷成空但屏不退`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)
        core.onEvent(NotificationPosted(wechat)) // 充电屏上多一枚图标（Launch 效果 + 呼吸）

        // 通知清空：图标行清空，充电理由仍持有 Dashboard（退出合取的「拔电」项不成立）。
        // 高亮随「全部通知清除」同步熄灭（无独立效果，logcat 锚可见）。
        assertEquals(listOf(UpdateIconSet(emptySet())), core.onEvent(NotificationRemoved(wechat)))
        assertEquals(CastSource.CHARGING, core.castSource)
        assertEquals(true, core.chargingOnScreen)

        // 仍在充电：拔电才轮到合取判退。
        assertEquals(listOf(ExitDashboard), core.onEvent(PowerDisconnected))
    }

    @Test
    fun `手动投送覆盖 charging 标签，拔电不影响手动屏`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)

        // 最新意图获胜（ManualCast 同款语义）：改记 manual，此后拔电不交还、门控不撤。
        assertEquals(listOf(LaunchDashboard(emptySet())), core.onEvent(ManualCast))
        assertEquals(CastSource.MANUAL, core.castSource)
        assertEquals(true, core.chargingOnScreen) // 仍插电：动画面看理由，不看标签

        assertEquals(emptyList(), core.onEvent(PowerDisconnected))
        assertEquals(CastSource.MANUAL, core.castSource)
        assertEquals(false, core.chargingOnScreen)

        assertEquals(listOf(ExitDashboard), core.onEvent(ManualExit))
    }

    @Test
    fun `充电屏手动退出后不因仍在充电而重投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)

        assertEquals(listOf(ExitDashboard), core.onEvent(ManualExit))
        assertEquals(null, core.castSource)
        assertEquals(false, core.chargingOnScreen) // 理由在身但不在屏：动画面不残留
    }

    // ---------- spec 0008 / 票 #65：Notification Highlight（呼吸 + 高亮集 + 冷却 + 熄灭） ----------
    //
    // 语义权威：CONTEXT.md「Notification Highlight」+ docs/specs/0008…md Implementation Decisions。
    // 触发＝白名单 App 的 Posted / 同 key Updated；呼吸窗约 3s ⊂ 冷却窗 30s（状态机只记冷却截止）；
    // 熄灭＝该 App 全部 Active Notification 被清除 / Detail View 看过（HighlightSeen，#66 接线）；
    // 门控随自动路径（DND/倒扣撤下清高亮、manual 豁免）；Degrade 恢复重建高亮集、不触发呼吸。
    // 日志锚词形契约（highlight add/remove/breath start）经构造注入捕获，byte 不可改。

    /** 带虚拟时钟与日志捕获的 core：`now[0]` 拨时钟，`logs` 断言锚词形。 */
    private fun highlightCore(
        now: LongArray = longArrayOf(0L),
        logs: MutableList<String> = mutableListOf(),
        vararg allowlist: String = arrayOf(wechat, qq),
    ) = DashboardCore(allowlist.toSet(), { now[0] }, logs::add)

    @Test
    fun `首条通知触发呼吸并入高亮集，日志锚按词形契约`() {
        val logs = mutableListOf<String>()
        val now = LongArray(1)
        val core = highlightCore(now, logs)
        core.onEvent(ProjectionReady)

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat)), highlight(wechat)),
            core.onEvent(NotificationPosted(wechat)),
        )
        assertEquals(setOf(wechat), core.highlightApps)
        assertEquals(listOf("highlight add $wechat", "highlight breath start"), logs)
    }

    @Test
    fun `同 key 内容更新（NotificationUpdated）触发呼吸，Icon Set 不重计`() {
        val now = LongArray(1)
        val core = highlightCore(now)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        now[0] = DashboardCore.HIGHLIGHT_COOLDOWN_MS // 冷却外

        assertEquals(
            listOf(HighlightBreath(setOf(wechat), DashboardCore.HIGHLIGHT_COOLDOWN_MS + DashboardCore.HIGHLIGHT_BREATH_MS)),
            core.onEvent(NotificationUpdated(wechat)),
        )
    }

    @Test
    fun `呼吸窗内与冷却窗内再触发都不重复呼吸`() {
        val now = LongArray(1)
        val core = highlightCore(now)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat)) // 呼吸起点 t=0

        now[0] = 1_000 // 呼吸窗内（<3s）：新 App 只入高亮 + 更新图标
        assertEquals(
            listOf(UpdateIconSet(setOf(wechat, qq))),
            core.onEvent(NotificationPosted(qq)),
        )
        now[0] = 29_999 // 冷却窗尾（<30s）：同 key 更新不重复呼吸
        assertEquals(emptyList(), core.onEvent(NotificationUpdated(wechat)))
    }

    @Test
    fun `新应用冷却内照常入高亮集（呼吸不重复）`() {
        val now = LongArray(1)
        val core = highlightCore(now)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        now[0] = 10_000
        assertEquals(listOf(UpdateIconSet(setOf(wechat, qq))), core.onEvent(NotificationPosted(qq)))
        assertEquals(setOf(wechat, qq), core.highlightApps)
    }

    @Test
    fun `冷却恰满 30 秒边界恢复呼吸`() {
        val now = LongArray(1)
        val core = highlightCore(now)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        now[0] = DashboardCore.HIGHLIGHT_COOLDOWN_MS - 1
        assertEquals(emptyList(), core.onEvent(NotificationUpdated(wechat)))
        now[0] = DashboardCore.HIGHLIGHT_COOLDOWN_MS
        assertEquals(
            listOf(HighlightBreath(setOf(wechat), DashboardCore.HIGHLIGHT_COOLDOWN_MS + DashboardCore.HIGHLIGHT_BREATH_MS)),
            core.onEvent(NotificationUpdated(wechat)),
        )
    }

    @Test
    fun `该 App 全部通知清除后高亮熄灭（多枚逐枚清）`() {
        val logs = mutableListOf<String>()
        val core = highlightCore(logs = logs)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(NotificationPosted(wechat)) // 第二枚（冷却内，无呼吸）

        core.onEvent(NotificationRemoved(wechat)) // 还剩一枚：高亮保持
        assertEquals(setOf(wechat), core.highlightApps)

        assertEquals(listOf(ExitDashboard), core.onEvent(NotificationRemoved(wechat))) // 全清
        assertEquals(emptySet(), core.highlightApps)
        assertEquals(listOf("highlight add $wechat", "highlight breath start", "highlight remove $wechat"), logs)
    }

    @Test
    fun `HighlightSeen 熄灭（Detail View 看过即熄的事件接口，幂等）`() {
        val logs = mutableListOf<String>()
        val core = highlightCore(logs = logs)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(emptyList(), core.onEvent(HighlightSeen(wechat)))
        assertEquals(emptySet(), core.highlightApps)
        assertEquals(emptyList(), core.onEvent(HighlightSeen(wechat))) // 未在集内：幂等无日志
        assertEquals(
            listOf("highlight add $wechat", "highlight breath start", "highlight remove $wechat"),
            logs,
        )
        // 看过即熄只动强调面：通知还在，Icon Set 与在屏不变。
        assertEquals(listOf(wechat), core.iconSet)
        assertEquals(CastSource.AUTO, core.castSource)
    }

    @Test
    fun `非白名单应用不触发呼吸不入高亮集`() {
        val core = highlightCore()
        core.onEvent(ProjectionReady)

        assertEquals(emptyList(), core.onEvent(NotificationPosted("com.stranger.app")))
        assertEquals(emptyList(), core.onEvent(NotificationUpdated("com.stranger.app")))
        assertEquals(emptySet(), core.highlightApps)
    }

    @Test
    fun `DND 中通知不呼吸但照常入高亮集，DND 关闭补投也不补呼吸`() {
        val logs = mutableListOf<String>()
        val core = highlightCore(logs = logs)
        core.onEvent(ProjectionReady)
        core.onEvent(DndGate(active = true))

        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat)))
        assertEquals(setOf(wechat), core.highlightApps)

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(DndGate(active = false)))
        assertEquals(setOf(wechat), core.highlightApps)
        assertTrue(logs.none { it.startsWith("highlight breath") })
    }

    @Test
    fun `DND 撤下 auto 在屏时高亮集清空`() {
        val logs = mutableListOf<String>()
        val core = highlightCore(logs = logs)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(ExitDashboard), core.onEvent(DndGate(active = true)))
        assertEquals(emptySet(), core.highlightApps)
        assertEquals(
            listOf("highlight add $wechat", "highlight breath start", "highlight remove $wechat"),
            logs,
        )
    }

    @Test
    fun `翻正撤下时高亮集清空（Posture Gate 同语义）`() {
        val core = highlightCore()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(ExitDashboard), core.onEvent(PostureGate(faceDown = false)))
        assertEquals(emptySet(), core.highlightApps)
    }

    @Test
    fun `DND 不撤 manual 在屏也不清高亮（手动豁免不变）`() {
        val core = highlightCore()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ManualCast)

        assertEquals(emptyList(), core.onEvent(DndGate(active = true)))
        assertEquals(setOf(wechat), core.highlightApps)
    }

    @Test
    fun `通道未就绪不呼吸只入高亮，就绪后重建且不补呼吸`() {
        val logs = mutableListOf<String>()
        val core = highlightCore(logs = logs)

        assertEquals(emptyList(), core.onEvent(NotificationPosted(wechat)))
        assertEquals(setOf(wechat), core.highlightApps)

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(ProjectionReady))
        assertEquals(setOf(wechat), core.highlightApps)
        assertTrue(logs.none { it.startsWith("highlight breath") })
    }

    @Test
    fun `Degrade 恢复后按当前活动通知重建高亮集且不呼吸`() {
        val logs = mutableListOf<String>()
        val now = LongArray(1)
        val core = highlightCore(now, logs)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat)) // 呼吸一次
        core.onEvent(ProjectionUnavailable)

        // Degrade 期间：监听与高亮集照常维护，不呼吸（无屏可显）。
        assertEquals(emptyList(), core.onEvent(NotificationPosted(qq)))
        assertEquals(setOf(wechat, qq), core.highlightApps)
        core.onEvent(NotificationRemoved(wechat)) // 微信全清：高亮熄灭照常维护
        assertEquals(setOf(qq), core.highlightApps)

        // 恢复：重建高亮集（此时与在册一致，无增减日志）、按当前 Icon Set 重投、不呼吸。
        assertEquals(listOf(LaunchDashboard(setOf(qq))), core.onEvent(ProjectionReady))
        assertEquals(setOf(qq), core.highlightApps)
        assertEquals(1, logs.count { it.startsWith("highlight breath") })

        // 重建后新到达照常呼吸（恢复本身不吞冷却）。
        now[0] = DashboardCore.HIGHLIGHT_COOLDOWN_MS
        assertEquals(
            listOf(UpdateIconSet(setOf(wechat, qq)), HighlightBreath(setOf(qq, wechat), DashboardCore.HIGHLIGHT_COOLDOWN_MS + DashboardCore.HIGHLIGHT_BREATH_MS)),
            core.onEvent(NotificationPosted(wechat)),
        )
    }

    @Test
    fun `重建高亮集补上未高亮的在册应用并留锚（白名单扩容不是触发源）`() {
        val logs = mutableListOf<String>()
        val core = highlightCore(longArrayOf(0L), logs, wechat) // 初始白名单只有微信
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(qq)) // 非白名单：只记账，不投、不入高亮

        // 扩容上屏；扩容不是 Highlight 触发源（高亮只由 Posted/Updated 触发）。
        assertEquals(listOf(LaunchDashboard(setOf(qq))), core.onEvent(Allowlist(setOf(wechat, qq))))
        assertEquals(listOf(qq), core.iconSet)
        assertEquals(emptySet<String>(), core.highlightApps)

        // Degrade 恢复重建：在册未高亮的 qq 补进高亮集、不呼吸。
        core.onEvent(ProjectionUnavailable)
        assertEquals(
            listOf(LaunchDashboard(setOf(qq))),
            core.onEvent(ProjectionReady),
        )
        assertEquals(setOf(qq), core.highlightApps)
        assertEquals(listOf("highlight add $qq"), logs.filter { it.startsWith("highlight") })
    }
}
