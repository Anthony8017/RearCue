package com.rearcue.poc

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import com.rearcue.poc.allowlist.AllowlistStore
import com.rearcue.poc.autostart.readAutostartState
import com.rearcue.poc.charging.ChargingSettingsStore
import com.rearcue.poc.charging.PowerSignals
import com.rearcue.poc.core.CastSource
import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEffect
import com.rearcue.poc.core.DashboardEvent
import com.rearcue.poc.core.PocAllowlist
import com.rearcue.poc.core.UsabilityReason
import com.rearcue.poc.notification.ActiveNotification
import com.rearcue.poc.notification.ActiveNotificationEvent
import com.rearcue.poc.notification.ActiveNotificationListener
import com.rearcue.poc.notification.NotificationRepository
import com.rearcue.poc.notify.RearNotificationListener
import com.rearcue.poc.notify.ensureTestChannel
import com.rearcue.poc.notify.isListenerEnabled
import com.rearcue.poc.tile.TilePolicy
import com.rearcue.poc.posture.PostureGateMonitor
import com.rearcue.poc.rear.HyperOsRearDisplayBackend
import com.rearcue.poc.rear.IconSetFeed
import com.rearcue.poc.rear.ChargingFeed
import com.rearcue.poc.rear.HighlightFeed
import com.rearcue.poc.rear.DashboardPresence
import com.rearcue.poc.rear.Presence
import com.rearcue.poc.rear.RearDashboardHost
import com.rearcue.poc.rear.RearDisplayBackend
import com.rearcue.poc.rear.RearDisplaySignalPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 日志 TAG：设备实验（`adb logcat -s RearCue`）的观测面。 */
const val LOG_TAG = "RearCue"

/** 调试页要展示的全部状态；由 [AppContainer] 在每次事件后重建。 */
data class AppState(
    /** 当前 Icon Set（Allowlist App 包名），来自 DashboardCore。 */
    val iconSet: List<String> = emptyList(),
    /** 监听服务是否已连接（未授权通知使用权时为 false）。 */
    val listenerConnected: Boolean = false,
    /** 在册的 Active Notification 总枚数（含非 Allowlist 应用）。 */
    val activeNotificationCount: Int = 0,
    /** 最近一次变化，供调试页与 logcat 展示。 */
    val lastEvent: String = "-",
    /** 投送通道是否就绪（识别到背屏）：就绪后通知事件会自动上/下屏（票 #5）。 */
    val channelReady: Boolean = false,
    /** 可用性引导横幅（票 #28）：null = 隐藏；非空 = 显示及其触发原因（显隐决策在 DashboardCore）。 */
    val usabilityBanner: Set<UsabilityReason>? = null,
    /** 当前 Allowlist App 包名（spec 0005：设置页增删、持久化；展示序稳定用字典序）。 */
    val allowlist: List<String> = emptyList(),
    /** DND 门控读数（spec 0006）：interruption filter 翻译结果，DND Follow 的展示面。 */
    val dndActive: Boolean = false,
    /** Posture 门控读数（spec 0006）：true = 倒扣（放行自动投送）。 */
    val postureFaceDown: Boolean = true,
    /** 在屏 Dashboard 的投送来源（spec 0006）：null = 不在屏。 */
    val castSource: CastSource? = null,
    /** 充电动画总开关（spec 0007 story 11 / 票 #57）：默认值与 core 初值同源，设置页充电区的展示面。 */
    val chargingEnabled: Boolean = DashboardCore.CHARGING_ANIMATION_DEFAULT,
)

/**
 * 进程级接线（POC 期不引 DI 框架）：Android 层只做「系统信号 → 事件 → 效果/状态」的搬运。
 *
 * [repository] 维护按 notification key 去重的 Active Notification 集合（:notification），
 * [core] 决定 Icon Set 与投送效果（上屏/更新/退出/降级），[rearBackend] 执行效果（票 #5）。
 * 三者吃同一批通知事件，因此不会互相漂移。
 */
class AppContainer(private val context: Context) {

    val repository = NotificationRepository()

    /** 决策核心：Icon Set 与投送效果都由它算（票 #3 的 Icon Set、票 #5 的自动上/下屏）。 */
    // Highlight 日志锚的 logcat 实现统一在这（TAG=RearCue，`adb logcat -s RearCue` 观测面）：
    // 词形契约见 DashboardCore.LOG_HIGHLIGHT_CONTRACT，tools/ex 验收链按词形读，byte 不可改。
    val core = DashboardCore(log = { line -> Log.i(LOG_TAG, line) })

    /** 背屏后端：HyperOS 专有投送操作全在实现里（票 #4）。 */
    val rearBackend: RearDisplayBackend = HyperOsRearDisplayBackend(context)

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * 容器内异步（Allowlist 持久化读写，spec 0005）。Main.immediate 与既有事件入口同线程，
     * DashboardCore 不需要加锁。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前 Allowlist（spec 0005）：存储首读完成前沿用种子，与既有行为一致；此后以存储为准。 */
    private var allowlist: Set<String> = PocAllowlist.APPS

    /** 投送通道是否就绪；只在与上次不同时喂 DashboardCore（避免重复重投）。 */
    private var channelReady = false

    /** DND 门控读数（spec 0006）；只在与上次不同时喂 DashboardCore，重复回调不产生事件。 */
    private var dndActive = false

    /** Posture 门控读数镜像（spec 0006）；与 core 同初值（倒扣放行），首个防抖提交即对齐。 */
    private var postureFaceDown = true

    /** 可用性横幅原因集（Show/Hide 效果的搬运落点；null = 隐藏）。 */
    @Volatile
    private var bannerReasons: Set<UsabilityReason>? = null

    /** 背屏注册/注销（息屏后重新注册、热插拔）都会改变通道可用性，据此重判。 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            syncChannel()
        }

        override fun onDisplayRemoved(displayId: Int) {
            syncChannel()
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    /** Posture 监听实例（spec 0006 / 票 #53；进程存活期间持续监听）。 */
    lateinit var postureMonitor: PostureGateMonitor
        private set

    /** 充电插拔监听（spec 0007 / 票 #57；进程存活期间注册一次，回调只搬运事件）。 */
    private val powerSignals = PowerSignals(context, ::onPowerChanged)

    init {
        repository.subscribe(ActiveNotificationListener(::onNotificationEvent))
        ensureTestChannel(context)
        syncChannel()
        context.getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        // 背屏归属信号（锁屏/AOD 抢回/解锁）→ Takeover 事件（票 #6 / E4）。
        rearBackend.onRearDisplaySignal(::onRearSignal)
        // Shizuku 授权成功 / server 上线 → 兜底通道恢复重投（票 #4 的手动路径与票 #6 的 E8 共用）。
        rearBackend.onFallbackChanged(::onFallbackChanged)
        // Dashboard 被系统意外销毁且持续缺失 → 归一化为核心事件，由 DashboardCore 决定是否重投。
        RearDashboardHost.onUnexpectedDetach(::onDashboardDetached)
        // 自启动状态初读（票 #28）：横幅输入只来自实测读数，返回页面时复查。
        checkAutostart()
        // 监听授权与连接初读：补上「服务从未连接」的静默缺口，并按探针效果请求重绑。
        probeNotificationListener(triggerSource = "process-start")
        // Allowlist 持久化首读（spec 0005）：空则种子 POC 五枚（升级零迁移），再对齐核心状态机。
        scope.launch {
            val stored = AllowlistStore.load(context)
            applyAllowlist(stored, source = "store-load")
        }
        // 充电动画总开关首读（spec 0007 / 票 #57）：缺键即默认（默认开，与 core 初值同源），
        // 首读是一次幂等对齐；写入口归设置页充电区（同一个事件，不各记一份状态）。
        scope.launch {
            applyChargingEnabled(ChargingSettingsStore.load(context))
        }
        // 注（spec 0008）：横幅设置（Privacy Mode / Auto-dismiss，原 FeedSettingsStore 首读）
        // 随横幅退役整体删除。DataStore 里的 `feed_settings` 残键**废弃容忍**：已无任何读者，
        // 残值无害，为一次性 POC 数据写清理/迁移代码不值当（卸载重装即消失）。
        // 充电插拔（spec 0007 票 #57）：ACTION_POWER_CONNECTED/DISCONNECTED → core 事件，
        // 插电即投/拔电退出/门控豁免的决策全在 DashboardCore（接线层零决策）。
        powerSignals.start()
        // Posture Gate（spec 0006 / 票 #53）：接近传感器 → 稳定窗防抖 → 姿态提交。
        postureMonitor = PostureGateMonitor(context) { faceDown -> onPostureCommitted(faceDown) }
        postureMonitor.start()
    }

    // ---------- Allowlist 管理（spec 0005：增删仍走 DashboardEvent.Allowlist，决策在 DashboardCore） ----------

    /**
     * 移除一枚 Allowlist App：即时生效——Icon Set 摘除/清空退出由核心 reconcile 算出，
     * 这里只搬运效果并写盘（无暂存态、无保存按钮，spec 0005 的交互决策）。
     */
    fun removeAllowlistApp(pkg: String) {
        applyAllowlist(allowlist - pkg, source = "remove $pkg")
        scope.launch { AllowlistStore.save(context, allowlist) }
    }

    /** 添加一枚 Allowlist App（spec 0005 #46）：即时生效 + 写盘；已在册时幂等（集合语义，无效果）。 */
    fun addAllowlistApp(pkg: String) {
        if (pkg in allowlist) return
        applyAllowlist(allowlist + pkg, source = "add $pkg")
        scope.launch { AllowlistStore.save(context, allowlist) }
    }

    private fun applyAllowlist(apps: Set<String>, source: String) {
        allowlist = apps
        val applied = dispatch(core.onEvent(DashboardEvent.Allowlist(apps)))
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "allowlist $source size=${apps.size}" + applied.describe(),
        )
    }

    // ---------- 充电动画总开关（spec 0007 / 票 #57：存储与写入口都走同一个事件，决策在 core） ----------

    /**
     * 总开关存储值对齐：喂 [DashboardEvent.ChargingAnimation]——档位语义（关 = 插电无反应、
     * 关掉 = 按退出合收取口、开且在充电 = 即时补投）都在 DashboardCore；与 core 初值相同
     * （首读常态）时无任何效果。
     */
    private fun applyChargingEnabled(enabled: Boolean) {
        val applied = dispatch(core.onEvent(DashboardEvent.ChargingAnimation(enabled)))
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "charging-anim=$enabled" + applied.describe(),
        )
    }

    /**
     * 充电动画总开关写入口（spec 0007 story 11，设置页充电区）：即时生效（事件进 core）
     * + 写盘；本层不做任何决策（零决策搬运，同 Allowlist 增删口径）。
     */
    fun setChargingAnimationEnabled(enabled: Boolean) {
        applyChargingEnabled(enabled)
        scope.launch { ChargingSettingsStore.saveChargingAnimationEnabled(context, enabled) }
    }

    /**
     * 充电插拔（spec 0007 票 #57）：插电是通知之外的独立投送触发源，投/撤与门控豁免的决策
     * 全在 DashboardCore；总开关关闭时 core 自然不投（接线层不加第二套判断）。
     */
    private fun onPowerChanged(connected: Boolean) {
        val applied = dispatch(
            core.onEvent(if (connected) DashboardEvent.PowerConnected else DashboardEvent.PowerDisconnected),
        )
        Log.i(LOG_TAG, "电源${if (connected) "接入" else "断开"} → ${applied.describeApplied()}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = (if (connected) "power-connected" else "power-disconnected") + applied.describe(),
        )
    }

    // ---------- 自动上/下屏（票 #5：通知事件 → 效果 → 背屏动作） ----------

    /**
     * 投送通道对齐：识别到背屏即视为就绪（见 CONTEXT.md「投送通道」）。
     *
     * 通道可用性只看背屏在不在——投送主路径是应用内 `setLaunchDisplayId`，不需要 Shizuku
     * （票 #4 的 E1 实测）；Shizuku 只是兜底，掉线不改变「能不能投送」，其降级语义归票 #6。
     */
    private fun syncChannel() {
        val ready = rearBackend.refresh().rearDisplayId != null
        if (ready == channelReady) return
        channelReady = ready
        Log.i(LOG_TAG, "投送通道${if (ready) "就绪" else "不可用"} 背屏=${rearBackend.state.rearDisplayId}")
        dispatch(core.onEvent(if (ready) DashboardEvent.ProjectionReady else DashboardEvent.ProjectionUnavailable))
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = if (ready) "通道就绪" else "通道不可用")
    }

    /**
     * Shizuku 授权成功 / server 上线：兜底通道可用即按当前 Icon Set 幂等重投一次（票 #8 / E8）。
     *
     * 「要不要重投」在 DashboardCore（[DashboardEvent.FallbackAvailable]），这里只搬运效果；
     * 上线本身不改变投送通道判据（CONTEXT.md「投送通道」），所以先把通道对齐再喂事件。
     */
    private fun onFallbackChanged(available: Boolean) {
        syncChannel()
        val applied = dispatch(if (available) core.onEvent(DashboardEvent.FallbackAvailable) else emptyList())
        // 掉线没有可搬运的效果：主路径是应用内投送，背屏内容不受影响（CONTEXT.md「投送通道」）。
        val what = if (available) applied.describeApplied() else "Dashboard 不受影响（应用内投送）"
        Log.i(LOG_TAG, "兜底通道${if (available) "恢复" else "掉线"} → $what")
    }

    /**
     * 自启动状态复查（票 #28）：AppOpsManager 实测读数 → [DashboardEvent.AutostartStatus] →
     * 横幅效果。进入主屏页面与从 MIUI 自启动设置页返回（ON_RESUME）各查一次；
     * 判定与降级在 [com.rearcue.poc.core.AutostartJudge]，这里只搬运。
     */
    fun checkAutostart() {
        val state = readAutostartState(context)
        val applied = dispatch(core.onEvent(DashboardEvent.AutostartStatus(state)))
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "autostart $state" + applied.describe(),
        )
    }

    /**
     * 通知监听探针（票 #28 修复：监听未授权静默）：通知使用权**从未授予**时监听服务永远不会连接、
     * 也就永远不会产出连接/断开信号——横幅会一直静默。这里用 [isListenerEnabled] 读数补上这条
     * 静默路径：未授权即喂 [DashboardEvent.ListenerHealth]（false），显隐判定仍在 DashboardCore。
     *
     * 未授权时保留既有 ListenerHealth(false) 横幅路径；每次也把授权与当前连接读数送入探针。
     * 重绑是否需要由 DashboardCore 决定，健康的正读数仍只认服务连接回调。
     * 返回通知使用权授权状态供页面显示。
     */
    fun probeNotificationListener(triggerSource: String): Boolean {
        val enabled = isListenerEnabled(context)
        val listenerConnected = _state.value.listenerConnected

        if (!enabled) {
            val healthEffects = dispatch(core.onEvent(DashboardEvent.ListenerHealth(false)))
            refresh(
                listenerConnected = _state.value.listenerConnected,
                lastEvent = "listener-not-granted" + healthEffects.describe(),
            )
        }

        val probeEffects = dispatch(
            core.onEvent(DashboardEvent.ListenerProbe(enabled, listenerConnected)),
            requestRebindSource = triggerSource,
        )
        if (probeEffects.isNotEmpty()) {
            refresh(
                listenerConnected = _state.value.listenerConnected,
                lastEvent = "listener-probe source=$triggerSource" + probeEffects.describe(),
            )
        }
        return enabled
    }

    /**
     * 背屏信号（锁屏/AOD 抢回/解锁）→ 按 [RearDisplaySignalPolicy] 选择是否发 Takeover 事件。
     * Dashboard 仍有实例时，普通息屏信号不重复投送；实例确实消失后再恢复。
     */
    private fun onRearSignal(action: String) {
        val dashboardInstances = RearDashboardHost.instanceCount
        val shouldRetake = RearDisplaySignalPolicy.shouldRetake(action, dashboardInstances)
        val applied = if (shouldRetake) {
            dispatch(core.onEvent(DashboardEvent.TakeoverDetected))
        } else {
            emptyList()
        }
        val detail = if (shouldRetake) {
            applied.describeApplied()
        } else {
            "保持现有 Dashboard（实例数=$dashboardInstances）"
        }
        Log.i(LOG_TAG, "背屏信号 $action → $detail")
        val eventSummary = if (shouldRetake) {
            applied.describe()
        } else {
            " → 保持现有 Dashboard"
        }
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "signal $action" + eventSummary,
        )
    }

    private fun onDashboardDetached() {
        val effects = core.onEvent(DashboardEvent.DashboardDetached)
        val applied = dispatch(effects)
        Log.i(LOG_TAG, "Dashboard 意外销毁 → ${applied.describeApplied()}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "dashboard-detached" + applied.describe(),
        )
    }

    /**
     * Posture Gate 输入（spec 0006 / 票 #53）：防抖后的姿态提交 → [DashboardEvent.PostureGate]。
     * 「倒扣=近」判定与稳定窗在 [PostureGateMonitor]，门控语义（投/撤/豁免）在 DashboardCore。
     */
    private fun onPostureCommitted(faceDown: Boolean) {
        if (faceDown == postureFaceDown) return
        postureFaceDown = faceDown
        val applied = dispatch(core.onEvent(DashboardEvent.PostureGate(faceDown)))
        Log.i(LOG_TAG, "姿态${if (faceDown) "倒扣" else "正放"} → ${applied.describeApplied()}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "posture ${if (faceDown) "down" else "up"}" + applied.describe(),
        )
    }

    /**
     * DND Follow 输入（spec 0006）：interruption filter → [DashboardEvent.DndGate]（filter→布尔的
     * 纯映射在 core，JVM 可测；这里只搬运）。撤下/补投/豁免的决策也在 DashboardCore。
     */
    fun onDndFilterChanged(filter: Int) {
        val gate = DashboardEvent.DndGate.fromInterruptionFilter(filter)
        if (gate.active == dndActive) return
        dndActive = gate.active
        val applied = dispatch(core.onEvent(gate))
        Log.i(LOG_TAG, "DND${if (gate.active) "开启" else "关闭"} → ${applied.describeApplied()}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "dnd ${if (gate.active) "on" else "off"}" + applied.describe(),
        )
    }

    /**
     * 效果 → 背屏动作：上屏/更新/退出/降级的决策在 DashboardCore，这里只搬运。
     *
     * 每条效果返回日志短名（[DashboardEffect.label]），进 logcat 与调试页——E7 的验收面。
     */
    private fun dispatch(
        effects: List<DashboardEffect>,
        requestRebindSource: String? = null,
    ): List<String> = effects.map { effect ->
        when (effect) {
            is DashboardEffect.LaunchDashboard ->
                if (!rearBackend.project(effect.iconSet.toList())) {
                    // 上屏没发出（背屏不在/通道失败）：不谎报成功；下一次 Icon Set 变化会经
                    // update() 的自愈重投再试一次（被白名单静默拒绝时只能靠设备实验发现）。
                    Log.w(LOG_TAG, "上屏未发出 iconSet=${effect.iconSet.size}")
                }
            is DashboardEffect.UpdateIconSet -> rearBackend.update(effect.iconSet.toList())
            DashboardEffect.ExitDashboard -> rearBackend.exit()
            // 通道已不可用，没有可停的投送；通知监听与 Icon Set 照常维护，通道回来即重投。
            DashboardEffect.Degrade -> Log.w(LOG_TAG, "Degrade：投送通道不可用，仅维护 Icon Set")
            // Notification Highlight 呼吸指令（spec 0008 / 票 #65）：转发呼吸截止给背屏界面
            // （晚挂载播剩余、过期不播）。日志锚 `highlight breath start`——词形契约见
            // DashboardCore.LOG_HIGHLIGHT_CONTRACT（`highlight breath end` 由背屏动画播完打），
            // tools/ex 验收链按词形读，byte 不可改。
            is DashboardEffect.HighlightBreath -> {
                HighlightFeed.publishBreath(effect.untilMs)
                Log.i(LOG_TAG, "highlight breath start")
            }
            // 可用性横幅（票 #28）：显隐与降级形态的决策在 DashboardCore，这里只落状态供调试页渲染。
            is DashboardEffect.ShowUsabilityBanner -> bannerReasons = effect.reasons
            DashboardEffect.HideUsabilityBanner -> bannerReasons = null
            DashboardEffect.RequestRebind -> requestListenerRebind(requestRebindSource ?: "unknown")
        }
        effect.label
    }

    /** 执行 DashboardCore 已决定的重绑效果；API 返回只表示请求调用已发出，不代表监听已连接。 */
    private fun requestListenerRebind(source: String) {
        val component = ComponentName(context, RearNotificationListener::class.java)
        try {
            NotificationListenerService.requestRebind(component)
            Log.i(LOG_TAG, "listener requestRebind issued source=$source component=$component")
        } catch (e: Exception) {
            Log.e(
                LOG_TAG,
                "listener requestRebind failed source=$source component=$component " +
                    "error=${e.javaClass.name}: ${e.message}",
                e,
            )
        }
    }

    // ---------- 手动入口（主屏调试页；spec 0006 起进 core 记 manual 来源，不再旁路状态机） ----------

    /**
     * 手动投送（Debug Bypass）：投当前 Icon Set（无通知时投空集，纯黑常态——spec 0008
     * 起无时间、无横幅）。
     *
     * 记 [CastSource.MANUAL]：豁免 DND 门控、不被自动逻辑撤下；通道未就绪时无效果（不谎报在屏）。
     */
    fun projectToRear() {
        Log.i(LOG_TAG, "手动投送背屏 iconSet=${core.iconSet}")
        val applied = dispatch(core.onEvent(DashboardEvent.ManualCast))
        if (applied.isEmpty()) {
            Log.w(LOG_TAG, "手动投送未发出（投送通道未就绪）")
        }
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "manual-cast" + applied.describe())
    }

    /**
     * 手动退出（Debug Bypass）：结束在屏 Dashboard（不论来源），监听照常。
     *
     * 与旧旁路的差别：core 的在屏状态同步清零，不再留「以为在屏」的漂移；此后新通知
     * 恢复自动投送（有通知就该在屏，符合本应用语义）。
     */
    fun exitRear() {
        Log.i(LOG_TAG, "手动退出背屏 Dashboard")
        val applied = dispatch(core.onEvent(DashboardEvent.ManualExit))
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "manual-exit" + applied.describe())
    }

    /**
     * Quick Tile Entry 的切换入口（spec 0006 / 票 #54）：无 Dashboard → 投送；有 → 退出。
     *
     * 判据是调用方读到的 Presence 快照（CONTEXT.md：全项目唯一的在屏事实）而不是 core 的
     * 记账——tile 的状态、点击日志与本动作必须是同一次读数。决策本身在
     * [TilePolicy.tapExits]（纯类，JVM 可测）。两条腿各走 [projectToRear]/[exitRear]
     * （core 记 manual，豁免两道门、不被自动逻辑撤下）。
     */
    fun toggleRearDashboard(presence: Presence = DashboardPresence.read()) {
        if (TilePolicy.tapExits(presence)) exitRear() else projectToRear()
    }

    // ---------- 监听服务入口 ----------

    /**
     * 调试旁路（票 #7）：监听服务登记「撤销某包全部 Active Notification」的能力。
     *
     * 撤销他人通知是系统只授予监听服务的权限，容器自己没有这条通道；服务连接时登记、
     * 断开或销毁时注销。只在 PC 实验脚本（`tools/ex`）清场时用到，不参与自动流转。
     */
    @Volatile
    private var notificationCanceller: ((String) -> Int)? = null

    fun onCancellerChanged(cancel: ((String) -> Int)?) {
        notificationCanceller = cancel
    }

    /** 调试用：撤销某包的全部通知；`-1` = 监听服务未连接或系统不让撤（没撤成，不谎报）。 */
    fun cancelNotificationsOf(pkg: String): Int {
        val canceller = notificationCanceller
        if (canceller == null) {
            Log.w(LOG_TAG, "debug cancel pkg=$pkg cancelled=-1（监听服务未连接）")
            return -1
        }
        val cancelled = canceller(pkg)
        Log.i(LOG_TAG, "debug cancel pkg=$pkg cancelled=$cancelled")
        return cancelled
    }

    fun onListenerConnected(count: Int) {
        Log.i(LOG_TAG, "listener connected active=$count")
        // 监听健康信号（票 #28）：连接/断开的系统信号 → 横幅效果，决策在 DashboardCore。
        val applied = dispatch(core.onEvent(DashboardEvent.ListenerHealth(true)))
        refresh(listenerConnected = true, lastEvent = "listener-connected active=$count" + applied.describe())
    }
    fun onListenerDisconnected() {
        Log.w(LOG_TAG, "listener disconnected iconSet=${_state.value.iconSet} active=${repository.currentNotifications.size}")
        val applied = dispatch(core.onEvent(DashboardEvent.ListenerHealth(false)))
        refresh(listenerConnected = false, lastEvent = "listener-disconnected" + applied.describe())
        // MIUI 可能在锁屏后解绑通知监听；在回调里用授权/连接双读数请求一次系统重绑。
        probeNotificationListener(triggerSource = "listener-disconnected")
    }

    fun onListenerPosted(notification: ActiveNotification) {
        repository.onPosted(notification)
    }

    fun onListenerRemoved(notification: ActiveNotification) {
        repository.onRemoved(notification)
    }

    fun onListenerSnapshot(active: List<ActiveNotification>) {
        repository.replaceSnapshot(active)
    }

    // ---------- 状态广播 ----------

    /** 仓库事件 → DashboardCore 事件序列 → 效果（Icon Set 是 core 的唯一通知消费面，spec 0008）。 */
    private fun onNotificationEvent(event: ActiveNotificationEvent) {
        val effects = event.toCoreEvents().flatMap(core::onEvent)
        val applied = dispatch(effects)
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = event.describe() + applied.describe(),
        )
    }

    private fun refresh(listenerConnected: Boolean, lastEvent: String) {
        val previous = _state.value
        val iconSet = core.iconSet.toList()
        // 背屏界面与调试页共用同一份 Icon Set（app → rear 单向依赖）。
        IconSetFeed.publish(iconSet)
        // 充电动画面同点重发（spec 0007 票 #57）：core 的「充电理由 ∧ 在屏」投影是唯一事实，
        // 投送/更新/退出等一切内容产出路径都收口到本方法，每刷必发、不落旧值。
        ChargingFeed.publish(core.chargingOnScreen)
        // 高亮集同点重发（spec 0008 / 票 #65）：core.highlightApps 的投影，图标暖白描边的常态数据。
        HighlightFeed.publish(core.highlightApps)
        _state.value = AppState(
            iconSet = iconSet,
            listenerConnected = listenerConnected,
            activeNotificationCount = repository.currentNotifications.size,
            lastEvent = lastEvent,
            channelReady = channelReady,
            usabilityBanner = bannerReasons,
            allowlist = allowlist.toList().sorted(),
            dndActive = dndActive,
            postureFaceDown = postureFaceDown,
            castSource = core.castSource,
            chargingEnabled = core.chargingAnimationEnabled,
        )
        Log.i(
            LOG_TAG,
            "$lastEvent iconSet ${previous.iconSet} -> $iconSet active=${_state.value.activeNotificationCount}",
        )
    }
}

/**
 * 仓库事件 → DashboardCore 事件序列（spec 0008 反转 → 票 #65 收口）：
 * Posted/Removed 只喂 Icon Set 语义（[DashboardEvent.NotificationPosted]/
 * [DashboardEvent.NotificationRemoved]）；同 key 内容更新（[ActiveNotificationEvent.Updated]）
 * 改喂 [DashboardEvent.NotificationUpdated]——spec 0008 立新真相：Updated 是 Notification Highlight
 * 的触发源（含同 key 更新 ⇒ 呼吸），Icon Set 仍不重计（key 对账在 :notification，票 #50 判例继续成立：
 * 计数事件压根不含 Updated）。原「只喂 FeedPosted 刷横幅」的消费面随横幅退役删除。
 */
internal fun ActiveNotificationEvent.toCoreEvents(): List<DashboardEvent> = when (this) {
    is ActiveNotificationEvent.Posted -> listOf(DashboardEvent.NotificationPosted(notification.pkg))
    is ActiveNotificationEvent.Updated -> listOf(DashboardEvent.NotificationUpdated(notification.pkg))
    is ActiveNotificationEvent.Removed -> listOf(DashboardEvent.NotificationRemoved(notification.pkg))
    is ActiveNotificationEvent.SnapshotReplaced -> emptyList()
}

/** 事件摘要：进日志与调试页（E7 的验收面）。 */
private fun ActiveNotificationEvent.describe(): String = when (this) {
    is ActiveNotificationEvent.Posted -> "posted ${notification.pkg}"
    is ActiveNotificationEvent.Updated -> "updated ${notification.pkg}"
    is ActiveNotificationEvent.Removed -> "removed ${notification.pkg}"
    is ActiveNotificationEvent.SnapshotReplaced -> "snapshot ${notifications.size}"
}

/** 效果摘要：投送链路是否自动触发，一眼可查。 */
private fun List<String>.describe(): String =
    if (isEmpty()) "" else " → " + joinToString("+")

/**
 * 效果短名的日志摘要：空列表（这次触发什么都没做）报「无需重投」，否则按执行顺序用 `+` 连接。
 *
 * 背屏信号与兜底通道恢复两处共用——两者的效果为空时都要解释一句，否则日志只剩一个空箭头。
 */
private fun List<String>.describeApplied(): String =
    ifEmpty { listOf("无需重投") }.joinToString("+")

class RearCueApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Log.i(LOG_TAG, "app start")
    }
}
