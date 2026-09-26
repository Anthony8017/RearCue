package com.rearcue.poc.core

/** 输入事件：Android 层胶水把系统信号翻译成这些事件喂给 DashboardCore。 */
sealed interface DashboardEvent {
    data class NotificationPosted(val pkg: String) : DashboardEvent
    data class NotificationRemoved(val pkg: String) : DashboardEvent

    /**
     * 同 key 内容更新（spec 0008 / 票 #65）：Notification Highlight 的触发源之一——
     * 「新通知到达（含同 key 内容更新）⇒ 整屏呼吸约 3 秒」（CONTEXT.md「Notification Highlight」）。
     * Icon Set **不重计**（key 对账在 :notification，集合成员没变；票 #50 判例继续成立）。
     */
    data class NotificationUpdated(val pkg: String) : DashboardEvent

    data class Allowlist(val apps: Set<String>) : DashboardEvent

    /**
     * 某 App 的 Detail View 被点开看过（spec 0008 票 #65 只留事件接口，#66 接线 UI 源）：
     * 该 App 图标高亮即熄——「看过即熄」（CONTEXT.md「Notification Highlight」）。
     */
    data class HighlightSeen(val app: String) : DashboardEvent

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

    // ---------- Charging Animation（spec 0007 / 票 #57：插电即投 + 门控豁免 + 退出合取） ----------

    /**
     * 插电（`ACTION_POWER_CONNECTED`）：通知之外的**独立投送触发源**——无通知时插电也把
     * Dashboard（可只含充电动画）送上背屏。与 [ManualCast] 同形：不过 `reconcile()` 的门控判据，
     * 正放/DND 中照样投；投出后记 [CastSource.CHARGING]，此后两道门不撤它。
     * 总开关（[ChargingAnimation]）关闭时本事件只是记录插电态、不投（关了就该没反应）。
     */
    data object PowerConnected : DashboardEvent

    /**
     * 拔电（`ACTION_POWER_DISCONNECTED`）：充电理由结束——退出条件「拔电 ∧ Icon Set 空」
     * （spec 0008 定案：横幅项随横幅退役删除）的第一项由此满足；后一项由统一出口按当下状态判
     * （不是本事件的特判）。
     */
    data object PowerDisconnected : DashboardEvent

    /**
     * 充电动画总开关（spec 0007 story 11，设置页充电区）：**默认开**（[DashboardCore.CHARGING_ANIMATION_DEFAULT]）。
     * 关闭 = 充电理由结束（等价拔电，充电屏按合取条件收口）；开启且在充电 = 理由恢复，
     * 即时生效。存储层缺键即默认，进程启动首读是一次幂等对齐。
     */
    data class ChargingAnimation(val enabled: Boolean) : DashboardEvent
}

/**
 * 投送来源（spec 0006 扩 spec 0007）：通知驱动记 [AUTO]，Debug Bypass（及 QS tile）记 [MANUAL]，
 * 插电独立投送记 [CHARGING]（豁免两道门，退出只认「拔电 ∧ Icon Set 空」合取——spec 0008
 * 横幅退役后合取只剩两项）。
 */
enum class CastSource { AUTO, MANUAL, CHARGING }

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

    /**
     * Notification Highlight 呼吸指令（spec 0008 / 票 #65）：整屏呼吸**一次**（非循环），
     * 高亮集内图标暖白描边。[apps] 是本次呼吸时的高亮集快照（排障用；图标描边的常态数据
     * 经 HighlightFeed 状态流重发，同 IconSetFeed 口径）。[untilMs] 是呼吸窗截止（epoch ms，
     * core 时钟给出）——背屏晚挂载时按剩余时长播放、已过期不播。
     */
    data class HighlightBreath(val apps: Set<String>, val untilMs: Long) : DashboardEffect

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
            is HighlightBreath -> "HighlightBreath(${apps.size})"
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
 *
 * [nowMs] 是虚拟时钟（JVM 判例可拨）：Highlight 的呼吸窗/冷却窗按它计时（票 #65）。
 * [log] 是 Highlight 日志锚的注入口，词形契约见 [LOG_HIGHLIGHT_CONTRACT]：纯 Kotlin 面
 * 不引 `android.util.Log`（README seam），logcat 实现（`Log.i`，TAG=RearCue）由构造方注入
 * （进程内收口在 app 层 AppContainer——DashboardCore 由它构造，同 WakeKeepAlive 的注入口径）。
 */
class DashboardCore(
    initialAllowlist: Set<String> = PocAllowlist.APPS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private var allowlist = initialAllowlist

    /** 每个 pkg 的 Active Notification 数（Icon Set 只看 >0 与否）。LinkedHashMap 保住首次出现顺序。 */
    private val activeCounts = LinkedHashMap<String, Int>()

    /**
     * 高亮集（Notification Highlight，spec 0008 / 票 #65）：正在以暖白描边示人的 App。
     * 入集＝白名单 App 的 Posted/Updated（呼吸中/冷却中照常入集）；出集＝该 App 全部
     * Active Notification 被清除、Detail View 看过（[DashboardEvent.HighlightSeen]，#66 接线）、
     * 或门控撤下 auto 在屏时整组清空。
     */
    private val highlightSet = LinkedHashSet<String>()

    /** 当前呼吸窗/冷却窗的截止（epoch ms）：呼吸窗（3s）⊂ 冷却窗（30s），只记后者即可判「能否呼吸」。 */
    private var highlightCooldownUntilMs = 0L

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
     * 充电动画总开关（spec 0007 story 11）：默认开（[CHARGING_ANIMATION_DEFAULT]）。可读不可写——
     * 设置页与调试页读它，改档只能经 [DashboardEvent.ChargingAnimation] 事件（决策仍在状态机）。
     */
    var chargingAnimationEnabled: Boolean = CHARGING_ANIMATION_DEFAULT
        private set

    /**
     * 插电态（spec 0007 / 票 #57）：[DashboardEvent.PowerConnected]/[DashboardEvent.PowerDisconnected]
     * 的记录。与 [chargingAnimationEnabled] 合取才是「充电理由」（见 [chargingReason]）。
     */
    private var plugged = false

    /** 充电理由 = 插电 ∧ 总开关开：出现在屏记账里（[CastSource.CHARGING]）即持有 Dashboard。 */
    private val chargingReason: Boolean
        get() = plugged && chargingAnimationEnabled

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

    /**
     * 充电动画在屏面（spec 0007 票 #57）：`充电理由 ∧ Dashboard 在屏`——**内容投影**
     * （不是事件流），接线层每次刷新按它重发，投送/更新/退出等一切路径统一收口。
     * 充电理由消失（拔电/关开关）或 Dashboard 撤下即隐，动画面不残留。
     */
    val chargingOnScreen: Boolean
        get() = onScreen != null && chargingReason

    /**
     * 处理一个事件，返回本事件引发的效果（可能为空）。
     *
     * 固有效果 + 退出合取判定（[reconcileExit]）在统一出口收口——充电内容与投送状态变化的
     * 每条路径都经过这里，不依赖各分支各自记得补效果。
     * （spec 0008：Notification Feed 横幅面整体退役——原 FeedPosted/AutoDismissTick/PrivacyMode/
     * AutoDismiss 事件与 Show/Hide 横幅效果已删除；票 #65 起 [DashboardEvent.NotificationUpdated]
     * 接管同 key 内容更新的消费面——Highlight 触发源，Icon Set 仍不重计。）
     */
    fun onEvent(event: DashboardEvent): List<DashboardEffect> =
        handle(event) + reconcileExit()

    /** 事件的固有效果（状态更新 + 投送决策）；退出合取判定在 [onEvent] 的统一出口。 */
    private fun handle(event: DashboardEvent): List<DashboardEffect> = when (event) {
        is DashboardEvent.Allowlist -> {
            allowlist = event.apps
            reconcile()
        }

        is DashboardEvent.NotificationPosted -> {
            activeCounts[event.pkg] = (activeCounts[event.pkg] ?: 0) + 1
            reconcile() + highlightTrigger(event.pkg)
        }

        is DashboardEvent.NotificationUpdated ->
            // 同 key 内容更新：集合成员没变，Icon Set 不重计；Highlight 语义的触发源（票 #65）。
            highlightTrigger(event.pkg)

        is DashboardEvent.NotificationRemoved -> {
            val count = activeCounts[event.pkg] ?: 0
            if (count > 0) {
                if (count == 1) activeCounts.remove(event.pkg) else activeCounts[event.pkg] = count - 1
            }
            reconcile() + highlightExtinguishIfCleared(event.pkg)
        }

        DashboardEvent.ProjectionReady -> {
            projectionReady = true
            // 通道就绪/恢复：高亮集按当前活动通知重建（票 #65 的 Degrade 恢复语义，
            // 首次就绪是同语义的幂等空转）——**不触发呼吸**（呼吸只由通知到达触发）。
            rebuildHighlights()
            // 通道恢复/首次就绪：充电理由在身就按充电重投（Degrade 抹掉在屏记账后充电屏要能回来，
            // 且不受门控——插电是独立触发），否则按当前 Icon Set 上屏。
            if (onScreen == null && chargingReason) launchCharging() else reconcile()
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
            // 豁免 DND 门控；无通知时投空集（纯黑常态，spec 0008：无时间无横幅）。已在屏（不论来源）
            // 重投并改记 manual——最新意图获胜，此后自动撤下对它失效，直到手动退出。
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

        // Charging Animation：插电/拔电/总开关只改充电理由，投撤决策在 [onChargingReasonChanged]。

        DashboardEvent.PowerConnected -> {
            plugged = true
            onChargingReasonChanged()
        }

        DashboardEvent.PowerDisconnected -> {
            plugged = false
            onChargingReasonChanged()
        }

        is DashboardEvent.ChargingAnimation -> {
            // 同档幂等（存储首读常态：与 core 初值相同则不产生任何效果）。
            if (chargingAnimationEnabled == event.enabled) {
                emptyList()
            } else {
                chargingAnimationEnabled = event.enabled
                onChargingReasonChanged()
            }
        }

        is DashboardEvent.HighlightSeen ->
            // Detail View 看过即熄（spec 0008 / 票 #65 的事件接口，#66 接线 UI 源）：
            // 只动高亮集，不触碰投送/Icon Set（内容还在屏上，只是不强调）。
            highlightSeen(event.app)
    }

    /** Icon Set：每个存在 Active Notification 的 Allowlist App 恰好一枚图标。 */
    private fun projectedIconSet(): Set<String> = activeCounts.keys.filter { it in allowlist }.toSet()

    // ---------- Notification Highlight（spec 0008 / 票 #65：呼吸 + 高亮集 + 冷却 + 熄灭） ----------

    /**
     * Highlight 触发（[DashboardEvent.NotificationPosted] / [DashboardEvent.NotificationUpdated]）：
     * 白名单 App 照常入高亮集（呼吸中/冷却中也入），再判「能否呼吸」——通道就绪、两门全开
     * （DND Follow / Posture Gate 随自动路径，spec 0008）且冷却窗（[HIGHLIGHT_COOLDOWN_MS]，
     * 覆盖呼吸窗）外才呼吸一次；冷却内不重复呼吸。
     */
    private fun highlightTrigger(pkg: String): List<DashboardEffect> {
        if (pkg !in allowlist) return emptyList()
        if (highlightSet.add(pkg)) logHighlight("highlight add $pkg")
        if (!projectionReady || !gatesOpen()) return emptyList()
        val now = nowMs()
        if (now < highlightCooldownUntilMs) return emptyList()
        highlightCooldownUntilMs = now + HIGHLIGHT_COOLDOWN_MS
        logHighlight("highlight breath start")
        return listOf(DashboardEffect.HighlightBreath(highlightSet.toSet(), now + HIGHLIGHT_BREATH_MS))
    }

    /** 该 App 全部 Active Notification 被清除 → 高亮熄灭（未看即清除即熄，spec 0008 story 6）。 */
    private fun highlightExtinguishIfCleared(pkg: String): List<DashboardEffect> {
        if (pkg !in activeCounts && highlightSet.remove(pkg)) logHighlight("highlight remove $pkg")
        return emptyList()
    }

    /** Detail View 看过即熄（[DashboardEvent.HighlightSeen]）；未在集内幂等无效果。 */
    private fun highlightSeen(app: String): List<DashboardEffect> {
        if (highlightSet.remove(app)) logHighlight("highlight remove $app")
        return emptyList()
    }

    /**
     * 高亮集随 Dashboard 撤下清空（spec 0008 票 #65 门控交叠）：任一门关、auto 在屏被撤下时
     * 整组熄灭——屏都撤了，背屏上不存在可强调的图标。manual/charging 在屏不被撤、高亮集不清
     * （手动投送豁免不变）；不在屏（无撤可做）同样不清——未看的「未看」语义保留。
     */
    private fun clearHighlightsOnWithdraw() {
        highlightSet.toList().forEach { pkg ->
            highlightSet -= pkg
            logHighlight("highlight remove $pkg")
        }
    }

    /**
     * 通道就绪/恢复后的高亮集重建（票 #65 定案并判例化）：按当前活动通知（白名单内）重建——
     * 已不在册的摘除、在册未高亮的补上，**不触发呼吸**（呼吸只由通知到达触发，恢复不是到达）。
     */
    private fun rebuildHighlights() {
        val target = projectedIconSet()
        highlightSet.filter { it !in target }.forEach { pkg ->
            highlightSet -= pkg
            logHighlight("highlight remove $pkg")
        }
        target.filter { it !in highlightSet }.forEach { pkg ->
            highlightSet += pkg
            logHighlight("highlight add $pkg")
        }
    }

    /**
     * 当前高亮集（只读视图，spec 0008 / 票 #65）：HighlightFeed 的发布源与调试观测面；
     * 渲染层取其与 Icon Set 的交集（图标只对在屏图标描边）。
     */
    val highlightApps: Set<String>
        get() = highlightSet.toSet()

    /**
     * Highlight 日志锚注入口（词形契约，[LOG_HIGHLIGHT_CONTRACT]）。
     */
    private fun logHighlight(line: String) = log(line)

    /** 自动投送的两道门（spec 0006）：DND 关 **且** 倒扣，缺一不可；门控只作用于自动路径。 */
    private fun gatesOpen() = !dnd && faceDown

    /**
     * 任一门状态变化后的统一对齐（两道门相互独立、判定顺序无关）：
     * 门全开 → reconcile（Icon Set 非空且不在屏则补投，记 auto）；任一门关 → 撤下 auto 在屏
     * （manual 豁免，不在屏则静默；hand-back 由 ExitDashboard 执行侧完成）。
     *
     * 撤下只认 `source == AUTO`：manual 手动投的手动撤，**charging 不被翻正/勿扰撤下**
     * （spec 0007 票 #57）——充电理由持有 Dashboard 期间门控对它整体无效。
     */
    private fun onGateChanged(): List<DashboardEffect> =
        if (gatesOpen()) {
            reconcile()
        } else if (onScreen?.source == CastSource.AUTO) {
            onScreen = null
            clearHighlightsOnWithdraw() // 高亮集随 Dashboard 撤下清空（票 #65 门控交叠）
            listOf(DashboardEffect.ExitDashboard)
        } else {
            emptyList()
        }

    /**
     * 充电理由（插电 ∧ 总开关开）变化后的统一对齐（spec 0007 票 #57）：
     *
     * - 理由出现且不在屏 → [launchCharging]：独立投送触发，绕过两道门（[ManualCast] 同形）；
     * - 理由出现且已在屏 → 只改记 charging（内容不动，已投出的界面不用重投；动画面由
     *   [chargingOnScreen] 投影）；manual 在屏不改记——手动意图后到者获胜；
     * - 理由消失且记账是 charging → 改记 auto 交还自动规则，随后过一遍门
     *   （门关着即撤、开着保留），空 Icon Set 的判退由统一出口 [reconcileExit] 收口
     *   （spec 0008：合取只剩「拔电 ∧ Icon Set 空」两项）。
     */
    private fun onChargingReasonChanged(): List<DashboardEffect> {
        val current = onScreen
        return when {
            chargingReason && current == null ->
                if (projectionReady) launchCharging() else emptyList()

            chargingReason && current != null && current.source == CastSource.AUTO -> {
                // 改记 charging：此后门控撤不掉它（充电在屏不被翻正/勿扰撤下）。
                onScreen = current.copy(source = CastSource.CHARGING)
                emptyList()
            }

            !chargingReason && current != null && current.source == CastSource.CHARGING -> {
                onScreen = current.copy(source = CastSource.AUTO)
                onGateChanged()
            }

            else -> emptyList()
        }
    }

    /**
     * 按充电理由投送：记 [CastSource.CHARGING] + [DashboardEffect.LaunchDashboard]
     * （无通知时投空集，与 [DashboardEvent.ManualCast] 的空集投送同款）。不看门控——插电是
     * 通知之外的独立触发源（spec 0007）；执行侧仍走 `project()`，Wake Keep-alive 照常注入。
     */
    private fun launchCharging(): List<DashboardEffect> {
        val icons = projectedIconSet()
        onScreen = OnScreen(CastSource.CHARGING, icons)
        return listOf(DashboardEffect.LaunchDashboard(icons))
    }

    /**
     * 把「当前应显示的 Icon Set」与「背屏现状」对齐，产出效果：
     * 空集 → 无投送效果（判退是退出合取的事，统一出口 [reconcileExit] 收口——充电屏可以
     * 持着空 Icon Set 在屏，spec 0007）；有集合且未投 → LaunchDashboard（两门未全开不投，
     * spec 0006）；集合变化 → UpdateIconSet（不论来源，内容更新不是投/撤）。
     * 通道不可用期间只维护状态、不产出投送效果。
     */
    private fun reconcile(): List<DashboardEffect> {
        if (!projectionReady) return emptyList()
        val icons = projectedIconSet()
        val current = onScreen
        return when {
            icons.isEmpty() -> emptyList()
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

    /**
     * 退出合取判定（spec 0007 票 #57 立、spec 0008 反转收口，统一出口）：
     * `退出 ⇐ 在屏 ∧ 非 manual ∧ 非充电持有 ∧ Icon Set 空`——「拔电 ∧ Icon Set 空」是状态机
     * 结论而非特判：
     *
     * - **manual**：手动投的手动撤（退出只能由 [DashboardEvent.ManualExit] 触发）；
     * - **充电持有**（[chargingReason]）：拔电是退出的必要条件，插电期间永不退出；
     * - **Icon Set 空**：有通知内容就该留在屏上。
     * - （spec 0008 反转：原「无横幅」第三项随 Notification Feed 退役删除——横幅不再是
     *   把 Dashboard 挂在屏上的内容，判据见 docs/specs/0008-rear-visual-notification-highlight.md。）
     *
     * 每个事件后都跑一遍，所以合取的任一项变化都会触发判退，不依赖投送分支自己记得补
     * ExitDashboard。
     *
     * 屏不退时（充电持有）图标面照常对齐（[syncIconSet]）：内容变了屏还留着，
     * 留着的屏不能显示已经不存在的图标。
     */
    private fun reconcileExit(): List<DashboardEffect> {
        if (!projectionReady) return emptyList()
        val current = onScreen ?: return emptyList()
        // manual 不走对齐也不判退：沿票 #52 判例，手动屏是投出那一刻的快照、只能手动撤。
        if (current.source == CastSource.MANUAL) return emptyList()
        if (chargingReason) return syncIconSet(current)
        if (projectedIconSet().isNotEmpty()) return emptyList()
        onScreen = null
        return listOf(DashboardEffect.ExitDashboard)
    }

    /**
     * 屏不退时把图标面刷成当前 Icon Set：充电持有屏上通知清空 → 图标行清空、屏照留
     * （`UpdateIconSet` 是内容更新，不是投/撤）。集合未变时无效果。
     */
    private fun syncIconSet(current: OnScreen): List<DashboardEffect> {
        val icons = projectedIconSet()
        if (icons == current.iconSet) return emptyList()
        onScreen = current.copy(iconSet = icons)
        return listOf(DashboardEffect.UpdateIconSet(icons))
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
     * 充电理由在身时按充电重投、不过门（插电是独立触发，spec 0007）；manual 在屏不改记，
     * 仍走下面的既有判据。
     *
     * 与 [retake]（被抢回后重投）的分工：这里只看「通道就绪 + 有通知」，不看核心是否认为界面在屏。
     * 当前状态机里 `projectionReady + Icon Set 非空 + 门全开` 已经蕴含在屏，所以两者今天效果相同；
     * 分开写是为了让「通道恢复」这条路径不依赖那个不变量——将来界面自愈逻辑变了也不会静默漏投。
     */
    private fun retryProjection(): List<DashboardEffect> {
        if (!projectionReady) return emptyList()
        if (chargingReason && onScreen?.source != CastSource.MANUAL) return launchCharging()
        if (!gatesOpen()) return emptyList()
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

    companion object {
        /** 充电动画总开关默认档（spec 0007 story 11）：开——设置层与 core 同源，不各记一份。 */
        const val CHARGING_ANIMATION_DEFAULT = true

        /**
         * Notification Highlight 呼吸窗（spec 0008 / 票 #65）：约 3 秒、一次性非循环。
         * 时长决策在 core（效果携带 [DashboardEffect.HighlightBreath.untilMs]），渲染层按剩余时长播放。
         */
        const val HIGHLIGHT_BREATH_MS = 3_000L

        /**
         * Notification Highlight 冷却窗（spec 0008 / 票 #65）：30 秒——呼吸中/冷却中再触发
         * 不重复呼吸，新 App 图标照常入高亮集。呼吸窗 ⊂ 冷却窗，状态机只记冷却截止。
         */
        const val HIGHLIGHT_COOLDOWN_MS = 30_000L

        /**
         * Highlight 日志锚词形契约（票 #65，同 `wake-keep-alive` / `task-move word=` 惯例）：
         * `highlight breath start` / `highlight breath end` / `highlight add <pkg>` /
         * `highlight remove <pkg>`，ASCII 前缀，tools/ex 验收链按词形读——**byte 不可改**。
         * `add`/`remove`/`breath start` 由 DashboardCore 打（经构造注入的 [log]）；
         * `breath end` 由背屏动画播完打（RearDashboardActivity）；logcat 实现统一 TAG=RearCue。
         */
        const val LOG_HIGHLIGHT_CONTRACT =
            "highlight breath start|end; highlight add <pkg>; highlight remove <pkg>"
    }
}
