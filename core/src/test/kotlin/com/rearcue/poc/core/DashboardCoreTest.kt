package com.rearcue.poc.core

import com.rearcue.poc.core.DashboardEvent.Allowlist
import com.rearcue.poc.core.DashboardEvent.AutostartStatus
import com.rearcue.poc.core.DashboardEvent.BatteryLevel
import com.rearcue.poc.core.DashboardEvent.ChargingAnimation
import com.rearcue.poc.core.DashboardEvent.DashboardDetached
import com.rearcue.poc.core.DashboardEvent.DetailToggled
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

    // ---------- spec 0006 / 票 #52：手动投送/退出（Debug Bypass 记 manual） ----------
    //
    // 判例退役（票 #99）：原「DND 门控（开/关 × auto/manual）」整节与
    // 「interruption filter → DndGate 纯映射」整节随 DND Follow 删除——勿扰不再影响投送，
    // 门控判例由下面的 Posture 门控节承接（豁免源语义不变）。

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
    fun `auto 升级为 manual 后门控不再撤它`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat)) // auto 上屏

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(ManualCast))

        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = false)))
    }

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

    // ---------- spec 0006 / 票 #53：Posture 门控（正放/倒扣/翻正 × auto/manual） ----------

    @Test
    fun `正放期间通知不投送但呼吸（呼吸是视图级效果，不绑姿态门）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))

        // 不投（倒扣不投语义不变），但呼吸照常——正放无屏时效果无处渲染、到期失效（无害）。
        assertEquals(
            listOf(highlight(wechat)),
            core.onEvent(NotificationPosted(wechat)),
        )
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
        assertEquals(listOf(highlight(wechat)), core.onEvent(NotificationPosted(wechat))) // 不投但呼吸

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), core.onEvent(ManualCast))
    }

    @Test
    fun `manual 在屏正放到达照常呼吸（豁免源在屏，视图级效果）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))
        core.onEvent(ManualCast)

        // 手动屏不被撤（豁免），到达的通知更新图标并呼吸——呼吸不要求姿态门全开。
        assertEquals(
            listOf(UpdateIconSet(setOf(wechat)), highlight(wechat)),
            core.onEvent(NotificationPosted(wechat)),
        )
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
    fun `正放撤下后兜底通道恢复不重投`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(PostureGate(faceDown = false)) // 撤下

        assertEquals(emptyList(), core.onEvent(FallbackAvailable))
    }

    @Test
    fun `auto 在屏被撤下后 Takeover 不复活（票 #99 承接原 DND 判例）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(PostureGate(faceDown = false)) // 撤下 auto 在屏

        assertEquals(emptyList(), core.onEvent(TakeoverDetected))
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
        core.onEvent(NotificationPosted(wechat)) // 不投，呼吸照常

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
    fun `正放时插电照样投（门不拦独立触发）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))

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
    fun `充电在屏不被翻正撤下`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected)

        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = false)))
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

        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = false))) // 门不撤充电屏
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

        assertEquals(listOf(ExitDashboard), core.onEvent(PostureGate(faceDown = false)))
    }

    @Test
    fun `门关着时拔电交还自动规则立即撤下（充电期间被豁免的门恢复生效）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PostureGate(faceDown = false))
        core.onEvent(PowerConnected) // 门关着照样投

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

    // ---------- spec 0008 / 票 #67：Charging Animation 显示面数据（电量比例进状态） ----------

    @Test
    fun `电量读数进状态，68→69 随事件刷新且不产投送效果`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected) // 空集充电屏

        // 显示面是状态投影不是投送效果（同 DetailToggled 口径）：事件不动投撤、不触发呼吸。
        assertEquals(emptyList(), core.onEvent(BatteryLevel(percent = 68)))
        assertEquals(68, core.batteryPercent)

        assertEquals(emptyList(), core.onEvent(BatteryLevel(percent = 69)))
        assertEquals(69, core.batteryPercent)
        assertEquals(CastSource.CHARGING, core.castSource) // 屏与来源都不被电量事件扰动
        assertEquals(true, core.chargingOnScreen)
    }

    @Test
    fun `电量读数越界收口到 0 与 100 之间，同值幂等`() {
        val core = core()

        assertEquals(emptyList(), core.onEvent(BatteryLevel(percent = 150)))
        assertEquals(100, core.batteryPercent)

        assertEquals(emptyList(), core.onEvent(BatteryLevel(percent = -3)))
        assertEquals(0, core.batteryPercent)
    }

    @Test
    fun `未充电时电量读数照常进状态（显示面呈现由充电在屏面单独管）`() {
        val core = core()
        core.onEvent(ProjectionReady)

        assertEquals(emptyList(), core.onEvent(BatteryLevel(percent = 53)))
        assertEquals(53, core.batteryPercent) // 读数先到先记，与是否在屏无关
        assertEquals(false, core.chargingOnScreen) // 不充电：不呈现
    }

    @Test
    fun `充电中通知到达：图标集与呼吸照常，电量读数随后刷新互不扰`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(PowerConnected) // 空集充电屏
        core.onEvent(BatteryLevel(percent = 68))

        // 通知到达：图标集照常更新、呼吸照常（视图级共存判例，票 #65 已立）——电量事件前后
        // 互不干扰；比例数字随电量事件刷新（68→69），Icon Set 与高亮不动。
        assertEquals(
            listOf(UpdateIconSet(setOf(wechat)), highlight(wechat)),
            core.onEvent(NotificationPosted(wechat)),
        )
        assertEquals(emptyList(), core.onEvent(BatteryLevel(percent = 69)))
        assertEquals(69, core.batteryPercent)
        assertEquals(CastSource.CHARGING, core.castSource)
        assertEquals(listOf(wechat), core.iconSet)
    }

    // 判例退役（spec 0008 反转：充电动画从 2D 闪电改为整屏绿色电量比例，
    // docs/specs/0008-rear-visual-notification-highlight.md Implementation Decisions「充电语义」）：
    // 2D 闪电是纯渲染面（:rear 的 Canvas 折线动画），core 从无闪电专属状态——本文件无判例可删；
    // 显示面数据面由本节 BatteryLevel 判例接替（渲染动效不写 JVM 测试，实机验收链替代，spec 0008 story 25）。

    // ---------- spec 0008 / 票 #65：Notification Highlight（呼吸 + 高亮集 + 冷却 + 熄灭） ----------
    //
    // 语义权威：CONTEXT.md「Notification Highlight」+ docs/specs/0008…md Implementation Decisions。
    // 触发＝白名单 App 的 Posted / 同 key Updated；呼吸窗约 3s ⊂ 冷却窗 30s（状态机只记冷却截止）；
    // 熄灭＝该 App 全部 Active Notification 被清除 / Detail View 看过（HighlightSeen，#66 接线）；
    // 门控：撤下清高亮；Posture Gate 只绑投/撤
    // （「倒扣不投/翻正撤下语义不变」）——呼吸是视图级效果，手动等豁免源在屏照常呼吸；
    // （票 #99 删 DND：原「DND 中到达不呼吸」随勿扰门控一并删除，呼吸只看通道就绪与冷却窗；）
    // Degrade 恢复重建高亮集、不触发呼吸。
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
    fun `快照重放（fromSnapshot）入高亮集但不呼吸、不消耗冷却`() {
        val logs = mutableListOf<String>()
        val now = LongArray(1)
        val core = highlightCore(now, logs)
        core.onEvent(ProjectionReady)

        // 重连快照差分补报：新 key Posted + 同 key 内容变 Updated——重建不是到达（票 #65 评审定案）；
        // 补投照常走对账（通道就绪 → Launch），但不呼吸。
        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(NotificationPosted(wechat, fromSnapshot = true)),
        )
        assertEquals(emptyList(), core.onEvent(NotificationUpdated(wechat, title = "新标题", fromSnapshot = true)))
        assertEquals(setOf(wechat), core.highlightApps)
        assertEquals(listOf("highlight add $wechat"), logs)

        // 冷却未被快照消耗：随后的真实到达立刻呼吸（已 Casting，Icon Set 走 Update）。
        assertEquals(
            listOf(UpdateIconSet(setOf(wechat, qq)), HighlightBreath(setOf(wechat, qq), DashboardCore.HIGHLIGHT_BREATH_MS)),
            core.onEvent(NotificationPosted(qq)),
        )
        assertEquals(setOf(wechat, qq), core.highlightApps)
    }

    @Test
    fun `快照重放先于通道就绪只入集，就绪对账补投，真实到达照常呼吸`() {
        val now = LongArray(1)
        val core = highlightCore(now)
        core.onEvent(NotificationPosted(wechat, fromSnapshot = true))
        assertEquals(setOf(wechat), core.highlightApps)

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(ProjectionReady),
        )
        assertEquals(
            listOf(UpdateIconSet(setOf(wechat, qq)), HighlightBreath(setOf(wechat, qq), DashboardCore.HIGHLIGHT_BREATH_MS)),
            core.onEvent(NotificationPosted(qq)),
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

    // ---------- spec 0008 / 票 #66：Detail View（打开/收起/自动收/切换/快照/联动） ----------
    //
    // 语义权威：CONTEXT.md「Detail View」+ spec 0008 Implementation Decisions 的 Detail View 语义。
    // 打开＝点按 Icon Set 某枚图标，所示＝该 App 最新一条 Active Notification 的 title+text
    // 快照（打开即冻结）；收起＝再点按同一图标（卡片点按同形，同一事件）；所示 notification key
    // 被清除自动收起；同一时刻至多一个（点另一枚＝切换）；打开即产出 HighlightSeen 语义
    // （看过即熄，#65 判例路径直达）；Icon Set 之外的 App 点不开（防御）；无时限、无隐私档、
    // 无列表。Detail 是状态投影不是投送效果——判例断言 `core.detail` 状态面 + 效果序列双面，
    // 日志锚词形契约 `detail open|close <pkg>`（DashboardCore.LOG_DETAIL_CONTRACT）经注入捕获。

    private val k1 = "0|com.tencent.mm|1|null|10210"
    private val k2 = "0|com.tencent.mm|2|null|10210"
    private val kq = "0|com.tencent.mobileqq|1|null|10211"

    @Test
    fun `Detail 打开＝该 App 最新一条快照，打开即熄该 App 高亮（HighlightSeen 联动）`() {
        val logs = mutableListOf<String>()
        val now = LongArray(1)
        val core = highlightCore(now, logs)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "标题一", text = "内容一"))
        now[0] = DashboardCore.HIGHLIGHT_COOLDOWN_MS // 冷却外第二条（两枚都在高亮集）
        core.onEvent(NotificationPosted(wechat, key = k2, title = "标题二", text = "内容二"))

        // 打开不产出投送效果（状态投影，接线层 refresh 重发 DetailFeed）。
        assertEquals(emptyList(), core.onEvent(DetailToggled(wechat)))
        assertEquals(NotificationDetail(wechat, k2, "标题二", "内容二"), core.detail)
        assertEquals(emptySet(), core.highlightApps) // 打开即熄：两条都熄（看过即熄按 App 记）
        assertEquals(
            setOf("detail open $wechat", "highlight remove $wechat"),
            logs.filter { it.startsWith("detail") || it.startsWith("highlight remove") }.toSet(),
        )
    }

    @Test
    fun `再点按同一图标收起（卡片同形同事件），可再开再收（无时限）`() {
        val logs = mutableListOf<String>()
        val now = LongArray(1)
        val core = highlightCore(now, logs)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "标题一", text = "内容一"))

        core.onEvent(DetailToggled(wechat))
        assertEquals(NotificationDetail(wechat, k1, "标题一", "内容一"), core.detail)

        assertEquals(emptyList(), core.onEvent(DetailToggled(wechat))) // 卡片点按走的同形事件
        assertEquals(null, core.detail)
        assertEquals(listOf("detail open $wechat", "detail close $wechat"), logs.filter { it.startsWith("detail") })

        core.onEvent(DetailToggled(wechat)) // 无时限：看完再点再开
        assertEquals(NotificationDetail(wechat, k1, "标题一", "内容一"), core.detail)
    }

    @Test
    fun `所示 key 被清除自动收起，异 key 清除不收`() {
        val logs = mutableListOf<String>()
        val now = LongArray(1)
        val core = highlightCore(now, logs)
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "标题一", text = "内容一"))
        core.onEvent(NotificationPosted(wechat, key = k2, title = "标题二", text = "内容二"))
        core.onEvent(DetailToggled(wechat)) // 所示＝最新一条 k2

        core.onEvent(NotificationRemoved(wechat, key = k1)) // 清的是没显示的那条：卡片不动
        assertEquals(NotificationDetail(wechat, k2, "标题二", "内容二"), core.detail)

        assertEquals(listOf(ExitDashboard), core.onEvent(NotificationRemoved(wechat, key = k2))) // 全清退屏
        assertEquals(null, core.detail) // 所示 key 被清除 → 自动收起（key 对账路径）
        assertEquals(
            listOf("detail open $wechat", "detail close $wechat"),
            logs.filter { it.startsWith("detail") },
        )
    }

    @Test
    fun `点另一 App 图标切换，同一时刻至多一个 Detail`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "微信标题", text = "微信内容"))
        core.onEvent(NotificationPosted(qq, key = kq, title = "QQ标题", text = "QQ内容"))

        core.onEvent(DetailToggled(wechat))
        assertEquals(NotificationDetail(wechat, k1, "微信标题", "微信内容"), core.detail)

        core.onEvent(DetailToggled(qq))
        assertEquals(NotificationDetail(qq, kq, "QQ标题", "QQ内容"), core.detail)
    }

    @Test
    fun `无通知或白名单外的 App 不可点开（防御判例）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "标题", text = "内容"))
        core.onEvent(NotificationPosted("com.stranger.app", key = "0|com.stranger.app|1|null|99999", title = "外人", text = "不在 Icon Set"))

        assertEquals(emptyList(), core.onEvent(DetailToggled(qq))) // 无 Active Notification
        assertEquals(emptyList(), core.onEvent(DetailToggled("com.stranger.app"))) // 白名单外：不进 Icon Set
        assertEquals(null, core.detail)
    }

    @Test
    fun `快照语义：打开后同 key 更新与新到达不刷新卡片，原 key 清除仍自动收`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "标题一", text = "内容一"))
        core.onEvent(NotificationPosted(wechat, key = k2, title = "标题二", text = "内容二"))
        core.onEvent(DetailToggled(wechat))

        core.onEvent(NotificationUpdated(wechat, key = k2, title = "改后标题", text = "改后内容")) // 同 key 更新
        core.onEvent(NotificationPosted(wechat, key = "0|com.tencent.mm|3|null|10210", title = "标题三", text = "内容三")) // 新到达
        assertEquals(NotificationDetail(wechat, k2, "标题二", "内容二"), core.detail) // 打开即冻结

        core.onEvent(NotificationRemoved(wechat, key = k2))
        assertEquals(null, core.detail) // 冻结的 key 被清除照样自动收
    }

    @Test
    fun `最新一条被清除后打开，次新一条顶上（选择语义随清除对账）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "标题一", text = "内容一"))
        core.onEvent(NotificationPosted(wechat, key = k2, title = "标题二", text = "内容二"))

        core.onEvent(NotificationRemoved(wechat, key = k2))
        core.onEvent(DetailToggled(wechat))
        assertEquals(NotificationDetail(wechat, k1, "标题一", "内容一"), core.detail)
    }

    @Test
    fun `组摘要（空内容聚合件）不顶掉最新有内容的一条`() {
        val core = core()
        val summaryKey = "0|com.tencent.mm|0|0|com.tencent.mm|g:Aggregate_AlertingSection|10210"
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "标题一", text = "内容一"))
        // MIUI 组摘要：随每条通知刷新、title/text 恒空，但到达序在最后（实机 E16 观察）。
        core.onEvent(NotificationPosted(wechat, key = summaryKey, title = "", text = ""))

        core.onEvent(DetailToggled(wechat))
        // 卡片显示最新「有内容」的一条，不是空摘要。
        assertEquals(NotificationDetail(wechat, k1, "标题一", "内容一"), core.detail)
    }

    @Test
    fun `全部空内容退化回严格最新一条，如实显示空卡`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "", text = ""))
        core.onEvent(NotificationPosted(wechat, key = k2, title = "", text = ""))

        core.onEvent(DetailToggled(wechat))
        assertEquals(NotificationDetail(wechat, k2, "", ""), core.detail)
    }

    @Test
    fun `Dashboard 撤下与降级 Detail 随之清（卡片宿主没了）`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "标题", text = "内容"))
        core.onEvent(DetailToggled(wechat))
        assertEquals(NotificationDetail(wechat, k1, "标题", "内容"), core.detail)

        // 门控撤下（姿态门关，auto 在屏）→ ExitDashboard + Detail 清。
        assertEquals(listOf(ExitDashboard), core.onEvent(PostureGate(faceDown = false)))
        assertEquals(null, core.detail)

        // 重开后通道降级 → Degrade，Detail 同清（重投回的是纯图标常态）。
        core.onEvent(PostureGate(faceDown = true))
        core.onEvent(DetailToggled(wechat))
        assertEquals(NotificationDetail(wechat, k1, "标题", "内容"), core.detail)
        assertEquals(listOf(Degrade), core.onEvent(ProjectionUnavailable))
        assertEquals(null, core.detail)
    }

    @Test
    fun `抢回重投回纯图标常态，Detail 随之清`() {
        val core = core()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat, key = k1, title = "标题", text = "内容"))
        core.onEvent(DetailToggled(wechat))
        assertEquals(NotificationDetail(wechat, k1, "标题", "内容"), core.detail)

        assertEquals(
            listOf(LaunchDashboard(setOf(wechat))),
            core.onEvent(TakeoverDetected),
        )
        assertEquals(null, core.detail)
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
    fun `翻正撤下时高亮集清空`() {
        val core = highlightCore()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))

        assertEquals(listOf(ExitDashboard), core.onEvent(PostureGate(faceDown = false)))
        assertEquals(emptySet(), core.highlightApps)
    }

    @Test
    fun `翻正不撤 manual 在屏也不清高亮（手动豁免不变）`() {
        val core = highlightCore()
        core.onEvent(ProjectionReady)
        core.onEvent(NotificationPosted(wechat))
        core.onEvent(ManualCast)

        assertEquals(emptyList(), core.onEvent(PostureGate(faceDown = false)))
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
