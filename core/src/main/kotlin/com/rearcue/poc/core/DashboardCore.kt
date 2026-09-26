package com.rearcue.poc.core

/** 输入事件：Android 层胶水把系统信号翻译成这些事件喂给 DashboardCore。 */
sealed interface DashboardEvent {
    data class NotificationPosted(val pkg: String) : DashboardEvent
    data class NotificationRemoved(val pkg: String) : DashboardEvent
    data class Allowlist(val apps: Set<String>) : DashboardEvent

    /**
     * 投送通道就绪：运行时识别到背屏（见 CONTEXT.md「投送通道」）。
     *
     * 应用内投送不需要 Shizuku（票 #4 的 E1 实测），所以通道可用性只看背屏在不在；
     * Shizuku 掉线/恢复的兜底语义由票 #6 收口。
     */
    data object ProjectionReady : DashboardEvent

    /** 投送通道不可用（未识别到背屏）→ Degrade。 */
    data object ProjectionUnavailable : DashboardEvent

    /**
     * 兜底通道（Shizuku）恢复可用：授权成功或 server 重新上线后按当前 Icon Set 幂等重投一次
     * （票 #6 / E8，票 #8 收口）。掉线不发事件——投送通道判据只看背屏（CONTEXT.md「投送通道」）。
     */
    data object FallbackAvailable : DashboardEvent

    data object TakeoverDetected : DashboardEvent

    /** Dashboard 实例在未收到主动退出请求时消失，按当前通知状态判断是否需要恢复。 */
    data object DashboardDetached : DashboardEvent

    /**
     * 自启动状态实测读数（票 #28 横幅输入）：Android 层读 AppOpsManager 10008/10053 后
     * 经 [AutostartJudge] 判定的 [AutostartState]，进入页面/从 MIUI 设置页返回时复查。
     */
    data class AutostartStatus(val state: AutostartState) : DashboardEvent

    /** 通知监听健康（票 #28 横幅输入）：监听服务连接/断开的系统信号。 */
    data class ListenerHealth(val healthy: Boolean) : DashboardEvent

    /**
     * 监听探针：同时携带通知使用权与监听连接状态；仅已授权但未连接时请求系统重绑。
     *
     * 探针不代表健康状态，横幅仍只由 [ListenerHealth] 的系统连接信号驱动。
     */
    data class ListenerProbe(val enabled: Boolean, val listenerConnected: Boolean) : DashboardEvent

    /**
     * DND 门控输入（spec 0006）：Android 层把 NotificationListenerService 的 interruption filter
     * 回调翻译成布尔（零新权限、不轮询；见 [fromInterruptionFilter]）。开启 = 拦截自动投送并撤下
     * auto 在屏；关闭 = Icon Set 非空时补投。MANUAL 在屏全程豁免（「撤下只撤 auto」是状态机结论，非特判）。
     */
    data class DndGate(val active: Boolean) : DashboardEvent {
        companion object {
            /** interruption filter 常量值（公共 API 契约；core 不引 Android 依赖，落成字面量）。 */
            const val FILTER_UNKNOWN = 0
            const val FILTER_ALL = 1
            const val FILTER_PRIORITY = 2
            const val FILTER_NONE = 3
            const val FILTER_ALARMS = 4

            /**
             * filter → 布尔的纯映射（胶水层只搬运，不决策）：PRIORITY/NONE/ALARMS 都算 DND 开启；
             * UNKNOWN 当关闭——没有实证不冒充开启。
             */
            fun fromInterruptionFilter(filter: Int): DndGate =
                DndGate(filter != FILTER_ALL && filter != FILTER_UNKNOWN)
        }
    }

    /**
     * 手动投送请求（Debug Bypass；spec 0006 的 Quick Tile Entry 复用同事件）：发起即记
     * [CastSource.MANUAL]，豁免 DND 门控，也不被自动逻辑撤下——退出只能由 [ManualExit] 触发。
     */
    data object ManualCast : DashboardEvent

    /** 手动退出请求：结束在屏（不论来源）；不影响通知驱动的自动流转，新通知仍会自动投送。 */
    data object ManualExit : DashboardEvent

    /**
     * Posture Gate 输入（spec 0006）：app 层接近传感器读数经稳定窗防抖后翻译的布尔——
     * 倒扣（主屏朝下，传感器「近」）= true。与 DND 门相互独立、判定顺序无关：
     * 自动投送需两门同开（DND 关 **且** 倒扣）；任一门由开转关撤下 auto 在屏，由关转开且
     * Icon Set 非空补投。MANUAL 在屏全程豁免。
     */
    data class PostureGate(val faceDown: Boolean) : DashboardEvent
}

/** 投送来源（spec 0006）：通知驱动记 [AUTO]，Debug Bypass（及未来的 QS tile）记 [MANUAL]。 */
enum class CastSource { AUTO, MANUAL }

/**
 * 在屏 Dashboard 的核心记账：来源与已投出的 Icon Set 同生同灭（data clump 收拢成一个类型，
 * 撤下路径只置一次 null，不再三个字段各自清）。
 */
private data class OnScreen(val source: CastSource, val iconSet: Set<String>)

/** 输出效果：Android 层胶水按序执行（投送/更新/退出/降级/监听重绑）。 */
sealed interface DashboardEffect {
    /** 投送 Dashboard 到背屏（含首投与重投，幂等）。 */
    data class LaunchDashboard(val iconSet: Set<String>) : DashboardEffect

    /** Dashboard 已在背屏上时更新 Icon Set。 */
    data class UpdateIconSet(val iconSet: Set<String>) : DashboardEffect

    /** 末条通知消失，退出 Dashboard，恢复原生背屏。 */
    data object ExitDashboard : DashboardEffect

    /** 投送通道不可用：停止投送，通知监听与 Icon Set 照常维护（见 CONTEXT.md「Degrade」）。 */
    data object Degrade : DashboardEffect

    /**
     * 显示可用性引导横幅（票 #28），携带触发原因；含 [UsabilityReason.AUTOSTART_IN_DOUBT]
     * 即降级形态（仅手动跳转 + 明示文案，绝不显示「健康」）。原因集变化时重发一次（内容更新）。
     */
    data class ShowUsabilityBanner(val reasons: Set<UsabilityReason>) : DashboardEffect

    /** 恢复健康（自启动已放行 + 监听健康）：隐藏可用性引导横幅。 */
    data object HideUsabilityBanner : DashboardEffect

    /** 通知使用权已开启但监听尚未连接：请求系统重绑，等待真实连接信号确认健康。 */
    data object RequestRebind : DashboardEffect

    /** 短名：日志与调试页展示用（`posted com.tencent.mm → LaunchDashboard(2)`）。 */
    val label: String
        get() = when (this) {
            is LaunchDashboard -> "LaunchDashboard(${iconSet.size})"
            is UpdateIconSet -> "UpdateIconSet(${iconSet.size})"
            ExitDashboard -> "ExitDashboard"
            Degrade -> "Degrade"
            is ShowUsabilityBanner -> "ShowUsabilityBanner(" +
                reasons.sortedBy { it.name }.joinToString("+") + ")"
            HideUsabilityBanner -> "HideUsabilityBanner"
            RequestRebind -> "RequestRebind"
        }
}

/** POC Allowlist 常量（微信、QQ、飞书、本应用、PC 自动化测试通道）。 */
object PocAllowlist {
    val APPS: Set<String> = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.ss.android.lark",
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

    /** 每个 pkg 的 Active Notification 数（Icon Set 只看 >0 与否）。LinkedHashMap 保住首次出现顺序。 */
    private val activeCounts = LinkedHashMap<String, Int>()

    private var projectionReady = false

    /**
     * 在屏 Dashboard 的核心记账（spec 0006）：投送来源 + 已投出的 Icon Set，三者同生同灭。
     * null = 核心认为不在屏。注意这是「投出后」的模型——真正的在屏事实（Presence）以
     * Android 层的实例证据为准，两者由 DashboardDetached/TakeoverDetected 事件对齐。
     */
    private var onScreen: OnScreen? = null

    /** DND 门控（spec 0006）：开启期间自动投送路径完全静默（不投、只撤 auto、不重投）。 */
    private var dnd = false

    /**
     * Posture 门控（spec 0006）：倒扣才放行自动投送。初值倒扣（true，放行）——门在收到 app 层
     * 首个防抖提交前不拦截（进程启动后 <1s 即提交），正放判定一到立即收口；
     * 无接近传感器的设备不提交，门恒开（产品可用优先于门控完备）。
     */
    private var faceDown = true

    /** 可用性横幅输入：null = 尚无实测读数（不打扰，也绝不冒充健康）。 */
    private var autostartState: AutostartState? = null
    private var listenerHealthy: Boolean? = null

    /** 当前横幅原因集；空集 = 横幅隐藏。 */
    private var bannerReasons: Set<UsabilityReason> = emptySet()

    /**
     * 当前 Icon Set：存在 Active Notification 的 Allowlist App，按首次出现顺序。
     *
     * 与投送无关的只读视图——主屏调试页直接展示它；投送效果仍由 [onEvent] 产出。
     */
    val iconSet: List<String>
        get() = activeCounts.keys.filter { it in allowlist }

    /** 在屏 Dashboard 的投送来源（spec 0006，调试页展示用）：null = 核心认为不在屏。 */
    val castSource: CastSource?
        get() = onScreen?.source

    /** DND 门控当前读数（spec 0006，调试页展示用）。 */
    val dndActive: Boolean
        get() = dnd

    /** Posture 门控当前读数（spec 0006，调试页展示用）：true = 倒扣（放行自动投送）。 */
    val postureFaceDown: Boolean
        get() = faceDown

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

        DashboardEvent.ProjectionReady -> {
            projectionReady = true
            reconcile() // 通道恢复/首次就绪：按当前 Icon Set 上屏
        }

        DashboardEvent.ProjectionUnavailable -> degrade()

        DashboardEvent.FallbackAvailable -> retryProjection()

        DashboardEvent.TakeoverDetected, DashboardEvent.DashboardDetached -> retake()

        is DashboardEvent.AutostartStatus -> {
            autostartState = event.state
            reconcileUsability()
        }

        is DashboardEvent.ListenerHealth -> {
            listenerHealthy = event.healthy
            reconcileUsability()
        }

        is DashboardEvent.ListenerProbe ->
            if (event.enabled && !event.listenerConnected) {
                listOf(DashboardEffect.RequestRebind)
            } else {
                emptyList()
            }

        is DashboardEvent.DndGate -> {
            dnd = event.active
            onGateChanged()
        }

        is DashboardEvent.PostureGate -> {
            faceDown = event.faceDown
            onGateChanged()
        }

        DashboardEvent.ManualCast ->
            // 豁免 DND 门控；无通知时投空集（纯黑 + 时间）。已在屏（不论来源）重投并改记 manual——
            // 最新意图获胜，此后自动撤下对它失效，直到手动退出。
            if (projectionReady) {
                val icons = projectedIconSet()
                onScreen = OnScreen(CastSource.MANUAL, icons)
                listOf(DashboardEffect.LaunchDashboard(icons))
            } else {
                emptyList()
            }

        DashboardEvent.ManualExit ->
            if (onScreen != null) {
                onScreen = null
                listOf(DashboardEffect.ExitDashboard)
            } else {
                emptyList()
            }
    }

    /** Icon Set：每个存在 Active Notification 的 Allowlist App 恰好一枚图标。 */
    private fun projectedIconSet(): Set<String> = activeCounts.keys.filter { it in allowlist }.toSet()

    /** 自动投送的两道门（spec 0006）：DND 关 **且** 倒扣，缺一不可；门控只作用于自动路径。 */
    private fun gatesOpen() = !dnd && faceDown

    /**
     * 任一门状态变化后的统一对齐（两道门相互独立、判定顺序无关）：
     * 门全开 → reconcile（Icon Set 非空且不在屏则补投，记 auto）；任一门关 → 撤下 auto 在屏
     * （manual 豁免，不在屏则静默；hand-back 由 ExitDashboard 执行侧完成）。
     */
    private fun onGateChanged(): List<DashboardEffect> =
        if (gatesOpen()) {
            reconcile()
        } else if (onScreen?.source == CastSource.AUTO) {
            onScreen = null
            listOf(DashboardEffect.ExitDashboard)
        } else {
            emptyList()
        }

    /**
     * 把「当前应显示的 Icon Set」与「背屏现状」对齐，产出效果：
     * 空集 → 仅 auto 在屏时 ExitDashboard（manual 由手动退出收）；有集合且未投 → LaunchDashboard
     * （两门未全开不投，spec 0006）；集合变化 → UpdateIconSet（不论来源，内容更新不是投/撤）。
     * 通道不可用期间只维护状态、不产出投送效果。
     */
    private fun reconcile(): List<DashboardEffect> {
        if (!projectionReady) return emptyList()
        val icons = projectedIconSet()
        val current = onScreen
        return when {
            icons.isEmpty() -> {
                if (current == null || current.source == CastSource.MANUAL) {
                    emptyList()
                } else {
                    onScreen = null
                    listOf(DashboardEffect.ExitDashboard)
                }
            }
            current == null -> {
                if (!gatesOpen()) return emptyList()
                onScreen = OnScreen(CastSource.AUTO, icons)
                listOf(DashboardEffect.LaunchDashboard(icons))
            }
            icons == current.iconSet -> emptyList()
            else -> {
                onScreen = current.copy(iconSet = icons)
                listOf(DashboardEffect.UpdateIconSet(icons))
            }
        }
    }

    /** 通道不可用：仅在 Dashboard 在屏时产出一次 Degrade（停止投送）；记账（含来源标签）一并清零。 */
    private fun degrade(): List<DashboardEffect> {
        projectionReady = false
        if (onScreen == null) return emptyList()
        onScreen = null
        return listOf(DashboardEffect.Degrade)
    }

    /** Takeover 或 Dashboard 意外消失后重投，幂等：同一 Icon Set 重新 LaunchDashboard，来源标签保持。 */
    private fun retake(): List<DashboardEffect> =
        if (projectionReady) {
            onScreen?.let { listOf(DashboardEffect.LaunchDashboard(it.iconSet)) } ?: emptyList()
        } else {
            emptyList()
        }

    /**
     * 兜底通道恢复后重投当前 Icon Set，幂等；任一门未开不重投（spec 0006：自动重投路径同样过门）。
     *
     * 与 [retake]（被抢回后重投）的分工：这里只看「通道就绪 + 有通知」，不看核心是否认为界面在屏。
     * 当前状态机里 `projectionReady + Icon Set 非空 + 门全开` 已经蕴含在屏，所以两者今天效果相同；
     * 分开写是为了让「通道恢复」这条路径不依赖那个不变量——将来界面自愈逻辑变了也不会静默漏投。
     */
    private fun retryProjection(): List<DashboardEffect> {
        if (!projectionReady || !gatesOpen()) return emptyList()
        val icons = projectedIconSet()
        return if (icons.isEmpty()) emptyList() else listOf(DashboardEffect.LaunchDashboard(icons))
    }

    // ---------- 可用性引导横幅（票 #28：出现/消失/健康不打扰的纯决策） ----------

    /** 当前触发原因：自启动非 GRANTED、监听不健康各自独立触发；无读数（null）不触发。 */
    private fun usabilityReasons(): Set<UsabilityReason> = buildSet {
        when (autostartState) {
            AutostartState.DENIED -> add(UsabilityReason.AUTOSTART_DENIED)
            AutostartState.IN_DOUBT -> add(UsabilityReason.AUTOSTART_IN_DOUBT)
            AutostartState.GRANTED, null -> Unit // 已放行/尚无读数：不触发、也不显示健康
        }
        if (listenerHealthy == false) add(UsabilityReason.LISTENER_UNHEALTHY)
    }

    /**
     * 横幅显隐对齐：原因集变化才产出效果——出现（隐藏→任一异常）/ 消失（异常清空→隐藏）/
     * 健康不打扰（健康且未显示 → 无效果）；原因集变化但横幅在屏时重发 Show（内容更新）。
     */
    private fun reconcileUsability(): List<DashboardEffect> {
        val reasons = usabilityReasons()
        if (reasons == bannerReasons) return emptyList()
        bannerReasons = reasons
        return if (reasons.isEmpty()) {
            listOf(DashboardEffect.HideUsabilityBanner)
        } else {
            listOf(DashboardEffect.ShowUsabilityBanner(reasons))
        }
    }
}
