package com.rearcue.poc.core

/** 输入事件：Android 层胶水把系统信号翻译成这些事件喂给 DashboardCore。 */
sealed interface DashboardEvent {
    data class NotificationPosted(val pkg: String) : DashboardEvent
    data class NotificationRemoved(val pkg: String) : DashboardEvent
    data class Allowlist(val apps: Set<String>) : DashboardEvent
    data object ShizukuConnected : DashboardEvent
    data object ShizukuDisconnected : DashboardEvent
    data object TakeoverDetected : DashboardEvent
}

/** 输出效果：Android 层胶水按序执行（投送/更新/退出/降级）。 */
sealed interface DashboardEffect {
    /** 投送 Dashboard 到背屏（含首投与重投，幂等）。 */
    data class LaunchDashboard(val iconSet: Set<String>) : DashboardEffect

    /** Dashboard 已在背屏上时更新 Icon Set。 */
    data class UpdateIconSet(val iconSet: Set<String>) : DashboardEffect

    /** 末条通知消失，退出 Dashboard，恢复原生背屏。 */
    data object ExitDashboard : DashboardEffect

    /** Shizuku 不可用：停止投送，通知监听与 Icon Set 照常维护（见 CONTEXT.md「Degrade」）。 */
    data object Degrade : DashboardEffect
}

/** POC Allowlist 常量（微信、QQ、本应用、PC 自动化测试通道）。 */
object PocAllowlist {
    val APPS: Set<String> = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.rearcue.poc",
        "com.android.shell",
    )
}

/**
 * 决策核心：事件序列 → 效果序列的纯 Kotlin 状态机，唯一 JVM 测试 seam。
 *
 * 不持有任何 Android 框架引用；单测只断言 [onEvent] 返回的效果序列，不断言内部状态。
 */
class DashboardCore(
    initialAllowlist: Set<String> = PocAllowlist.APPS,
) {
    private var allowlist = initialAllowlist

    /** 每个 pkg 的 Active Notification 数（Icon Set 只看 >0 与否）。 */
    private val activeCounts = HashMap<String, Int>()

    private var shizukuConnected = false
    private var dashboardShown = false
    private var displayedIconSet: Set<String> = emptySet()

    /** 处理一个事件，返回本事件引发的效果（可能为空）。 */
    fun onEvent(event: DashboardEvent): List<DashboardEffect> = when (event) {
        is DashboardEvent.Allowlist -> {
            allowlist = event.apps
            reconcile()
        }

        is DashboardEvent.NotificationPosted -> {
            activeCounts[event.pkg] = (activeCounts[event.pkg] ?: 0) + 1
            reconcile()
        }

        is DashboardEvent.NotificationRemoved -> {
            val count = activeCounts[event.pkg] ?: 0
            if (count > 0) {
                if (count == 1) activeCounts.remove(event.pkg) else activeCounts[event.pkg] = count - 1
            }
            reconcile()
        }

        DashboardEvent.ShizukuConnected -> {
            shizukuConnected = true
            reconcile() // 恢复重投当前 Icon Set
        }

        DashboardEvent.ShizukuDisconnected -> degrade()

        DashboardEvent.TakeoverDetected -> retake()
    }

    /** Icon Set：每个存在 Active Notification 的 Allowlist App 恰好一枚图标。 */
    private fun iconSet(): Set<String> = activeCounts.keys.filter { it in allowlist }.toSet()

    /**
     * 把「当前应显示的 Icon Set」与「背屏现状」对齐，产出效果：
     * 空集 → ExitDashboard；有集合且未投 → LaunchDashboard；集合变化 → UpdateIconSet。
     * Degrade 期间（未连接）只维护状态、不产出投送效果。
     */
    private fun reconcile(): List<DashboardEffect> {
        if (!shizukuConnected) return emptyList()
        val icons = iconSet()
        if (icons.isEmpty()) {
            if (!dashboardShown) return emptyList()
            dashboardShown = false
            displayedIconSet = emptySet()
            return listOf(DashboardEffect.ExitDashboard)
        }
        if (!dashboardShown) {
            dashboardShown = true
            displayedIconSet = icons
            return listOf(DashboardEffect.LaunchDashboard(icons))
        }
        if (icons == displayedIconSet) return emptyList()
        displayedIconSet = icons
        return listOf(DashboardEffect.UpdateIconSet(icons))
    }

    /** Shizuku 断开：仅在 Dashboard 在屏时产出一次 Degrade（停止投送）。 */
    private fun degrade(): List<DashboardEffect> {
        shizukuConnected = false
        if (!dashboardShown) return emptyList()
        dashboardShown = false
        displayedIconSet = emptySet()
        return listOf(DashboardEffect.Degrade)
    }

    /** Takeover（原生背屏抢回）后重投，幂等：同一 Icon Set 重新 LaunchDashboard。 */
    private fun retake(): List<DashboardEffect> =
        if (shizukuConnected && dashboardShown) {
            listOf(DashboardEffect.LaunchDashboard(displayedIconSet))
        } else {
            emptyList()
        }
}