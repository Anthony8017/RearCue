package com.rearcue.poc

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.agent.BridgeRelayClient
import com.rearcue.poc.agent.ActionReceipt
import com.rearcue.poc.agent.AgentApproveShape
import com.rearcue.poc.agent.AgentSessionKeys
import com.rearcue.poc.agent.SessionActionKind
import com.rearcue.poc.agent.SessionActionRequest
import com.rearcue.poc.agent.SourceCapabilities
import com.rearcue.poc.agentmirror.AgentAlertKind
import com.rearcue.poc.agentmirror.AgentAlertPolicy
import com.rearcue.poc.agentmirror.AgentAlertTracker
import com.rearcue.poc.agentmirror.AgentApprovePolicy
import com.rearcue.poc.agentmirror.AgentMirrorSettingsStore
import com.rearcue.poc.agentmirror.AgentStateLogic
import com.rearcue.poc.agentmirror.BridgeAddress
import com.rearcue.poc.agentmirror.BridgeAddressProbe
import com.rearcue.poc.agentmirror.BridgeAddressProbeClient
import com.rearcue.poc.agentmirror.BridgeAddressSource
import com.rearcue.poc.agentmirror.BridgeLinkStore
import com.rearcue.poc.agentmirror.SessionLockStore
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import com.rearcue.poc.autostart.readAutostartState
import com.rearcue.poc.charging.BatterySignals
import com.rearcue.poc.charging.ChargingSettingsStore
import com.rearcue.poc.charging.PowerSignals
import com.rearcue.poc.core.CastSource
import com.rearcue.poc.core.AgentPickerLogContract
import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEffect
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurns
import com.rearcue.poc.core.DashboardEvent
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.core.UsabilityReason
import com.rearcue.poc.notification.ActiveNotification
import com.rearcue.poc.notification.ActiveNotificationEvent
import com.rearcue.poc.notification.ActiveNotificationListener
import com.rearcue.poc.notification.NotificationRepository
import com.rearcue.poc.notification.ShadeVisibleNotificationGate
import com.rearcue.poc.notify.RearNotificationListener
import com.rearcue.poc.notify.AlertAction
import com.rearcue.poc.notify.cancelAgentAlerts
import com.rearcue.poc.notify.ensureTestChannel
import com.rearcue.poc.notify.postAgentAlert
import com.rearcue.poc.notify.questionActions
import com.rearcue.poc.notify.ShadeVisibilityMonitor
import com.rearcue.poc.notify.isListenerEnabled
import com.rearcue.poc.tile.TilePolicy
import com.rearcue.poc.posture.PostureGateMonitor
import com.rearcue.poc.posture.PostureGateSettingsStore
import com.rearcue.poc.rear.HyperOsRearDisplayBackend
import com.rearcue.poc.rear.IconSetFeed
import com.rearcue.poc.rear.ChargingFeed
import com.rearcue.poc.rear.DetailFeed
import com.rearcue.poc.rear.HighlightFeed
import com.rearcue.poc.rear.AgentFeed
import com.rearcue.poc.rear.AgentPickerRow
import com.rearcue.poc.rear.DashboardPresence
import com.rearcue.poc.rear.Presence
import com.rearcue.poc.rear.RearDashboardHost
import com.rearcue.poc.rear.RearDisplayBackend
import com.rearcue.poc.rear.RearDisplaySignalPolicy
import com.rearcue.poc.rear.ShizukuShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 日志 TAG：设备实验（`adb logcat -s RearCue`）的观测面。 */
const val LOG_TAG = "RearCue"

/** SystemUI 可见性低频校准周期。 */
private const val SHADE_VISIBILITY_RECONCILE_MS = 15_000L

/** 调试页要展示的全部状态；由 [AppContainer] 在每次事件后重建。 */
data class AppState(
    /** 当前 Icon Set（有 Shade-visible Notification 的应用包名，见 CONTEXT.md），来自 DashboardCore。 */
    val iconSet: List<String> = emptyList(),
    /** 监听服务是否已连接（未授权通知使用权时为 false）。 */
    val listenerConnected: Boolean = false,
    /** 当前 Shade-visible Notification 枚数（进入背屏的可见集合，不等于 NLS 原始在册数）。 */
    val visibleNotificationCount: Int = 0,
    /** 最近一次变化，供调试页与 logcat 展示。 */
    val lastEvent: String = "-",
    /** 投送通道是否就绪（识别到背屏）：就绪后通知事件会自动上/下屏（票 #5）。 */
    val channelReady: Boolean = false,
    /** 可用性引导横幅（票 #28）：null = 隐藏；非空 = 显示及其触发原因（显隐决策在 DashboardCore）。 */
    val usabilityBanner: Set<UsabilityReason>? = null,
    /** Posture 门控读数（spec 0006）：true = 倒扣。 */
    val postureFaceDown: Boolean = true,
    /** 姿态门控开关（票 #100）：默认值与 core 初值同源，设置页姿态区的展示面。 */
    val postureGateEnabled: Boolean = DashboardCore.POSTURE_GATE_DEFAULT,
    /** 在屏 Dashboard 的投送来源（spec 0006）：null = 不在屏。 */
    val castSource: CastSource? = null,
    /** 充电动画总开关（spec 0007 story 11 / 票 #57）：默认值与 core 初值同源，设置页充电区的展示面。 */
    val chargingEnabled: Boolean = DashboardCore.CHARGING_ANIMATION_DEFAULT,
    /** Agent 镜像：PC 桥配置、总开关、链路状态（#234）。 */
    /** PC 桥已配置（URL 在案）：设置区即使未配 ZCode 也要露出三来源会话列表。 */
    val agentBridgeConfigured: Boolean = false,
    /** PC 桥链路状态（票 #165）：主屏设置页状态行与主页概览显示这一份，与背屏状态点同源。 */
    val bridgeLinkStatus: BridgeLinkStatus = BridgeLinkStatus.DISABLED,
    /** 桥地址当前值（票 #171）：手填输入框的回填面（未配置为空串）。 */
    val bridgeAddress: String = "",
    /** 桥地址来源：电脑推送 / 手填 / 调试入口——界面据此说「这行是电脑推来的」还是「你手填的」。 */
    val bridgeAddressSource: BridgeAddressSource? = null,
    /** 电脑最后一次推送桥地址的时刻（仅来源为推送时有值）：地址被换过的事后判据（票 #171）。 */
    val bridgePushedAt: Long? = null,
    /** 手填地址的当场探测结果（票 #171）：探测中 / 探通了 / 没探通（区分格式、HTTP 码、连不上）。 */
    val bridgeAddressProbe: BridgeAddressProbe = BridgeAddressProbe.Idle,
    val agentEnabled: Boolean = AgentMirrorSettingsStore.MIRROR_ENABLED_DEFAULT,
    /** 镜像所示会话（spec 0010 / 票 #82）：core 仲裁后的选择，状态行与背屏共源。
     *  票 #104 补投：`refresh()` 此前从未赋值（恒 null），主屏列表与状态行拿不到 core 投影。 */
    val agentState: AgentSessionState? = null,
    /** Session Lock 当前档（票 #104）：core.sessionLock 投影——状态行文案与列表选中同源，
     *  改档仍只走 [setSessionLock] 写入口（读侧零决策）。 */
    val sessionLock: SessionLockMode = SessionLockMode.Auto,
    /** 统一在册会话（#234）：ZCode / Codex / Claude / DSH 都来自 PC 桥事件与快照。 */
    val agentRoster: List<AgentSessionState> = emptyList(),
    /** 锁定档显示名（会话短暂离册时用缓存标题，完整 sessionId 不进界面）。 */
    val sessionLockName: String? = null,
    /** Agent 页正文档位（spec 0017 / 票 #169）：core.mirrorTextSize 投影——设置页选中态与
     *  背屏字号读同一份事实，改档仍只走 [setMirrorTextSize] 写入口（读侧零决策）。 */
    val mirrorTextSize: MirrorTextSize = MirrorTextSize.DEFAULT,
    /** 角部避让开关（spec 0019 / 票 #194）：core.cornerAvoidanceEnabled 投影——设置页开关态与
     *  背屏贴缘/避让读同一份事实，改档仍只走 [setCornerAvoidance] 写入口（读侧零决策）。 */
    val cornerAvoidance: Boolean = DashboardCore.CORNER_AVOIDANCE_DEFAULT,
    /** Agent 提醒总开关（spec 0018-3 / 票 #173）：默认开；关＝提醒整体不存在（含撤掉已发的）。 */
    val agentAlertEnabled: Boolean = AgentMirrorSettingsStore.ALERT_ENABLED_DEFAULT,
    /** Agent 提醒震动开关（spec 0018-3）：默认开；关＝只留通知栏静默提示（不响铃恒成立）。 */
    val agentAlertVibrate: Boolean = AgentMirrorSettingsStore.ALERT_VIBRATE_DEFAULT,
    /** 远程批准开关（spec 0018 §五 / review 2026-09-30）：默认开；关＝三处批准入口全部不出现。 */
    val agentApproveEnabled: Boolean = AgentMirrorSettingsStore.APPROVE_ENABLED_DEFAULT,
    /** 光带亮度倍率（spec 0021 修订 / 票 #214）：设置页滑动条与背屏光带同一份事实，
     *  改值仍只走 [setGlowBrightness] 写入口（读侧零决策）。 */
    val glowBrightness: Float = AgentMirrorSettingsStore.GLOW_BRIGHTNESS_DEFAULT,
    /** 来源能力表（spec 0018-4 / 票 #174）：批准入口显隐的判定输入（谁声明了 approve）。 */
    val agentCapabilities: SourceCapabilities = SourceCapabilities.DEFAULTS,
    /** 调试旁路伪会话（spec 0018-4 验收链）：批准入口与动作链对它闭合；非调试态为 null。 */
    val agentDebugSession: AgentSessionState? = null,
    /** 最近一次批准动作的失败提示（AC3：失败提示一句、不自动重试轰炸）；成功即清。 */
    val agentActionNote: String? = null,
)

/**
 * 进程级接线（POC 期不引 DI 框架）：Android 层只做「系统信号 → 事件 → 效果/状态」的搬运。
 *
 * [repository] 维护按 notification key 去重的 Shade-visible Notification 集合（:notification；
 * NLS 原始在册集合先经可见性路由 [ShadeVisibleNotificationGate]，票 #130），
 * [core] 决定 Icon Set 与投送效果（上屏/更新/退出/降级），[rearBackend] 执行效果（票 #5）。
 * 三者吃同一批通知事件，因此不会互相漂移。
 */
class AppContainer(private val context: Context) {

    val repository = NotificationRepository()

    /** 下拉栏可见性路由：NLS 原始在册集合 → 仅 Shade-visible Notification 进入 repository/core。 */
    private val shadeVisibilityGate = ShadeVisibleNotificationGate(repository)

    /** 唯一 Shizuku 通道实例（票 #129）：背屏兜底链与可见性探测共用，避免绑定两个 UserService。 */
    private val shell = ShizukuShell(context)

    /** 决策核心：Icon Set 与投送效果都由它算（票 #3 的 Icon Set、票 #5 的自动上/下屏）。 */
    // Highlight 日志锚的 logcat 实现统一在这（TAG=RearCue，`adb logcat -s RearCue` 观测面）：
    // 词形契约见 DashboardCore.LOG_HIGHLIGHT_CONTRACT，tools/ex 验收链按词形读，byte 不可改。
    val core = DashboardCore(log = { line -> Log.i(LOG_TAG, line) })

    /** 背屏后端：HyperOS 专有投送操作全在实现里（票 #4）。 */
    val rearBackend: RearDisplayBackend = HyperOsRearDisplayBackend(context, shell)

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * 容器内异步（设置存储读写：充电动画、Agent 配对/开关）。Main.immediate 与既有事件入口同线程，
     * DashboardCore 不需要加锁。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 退屏宽限到期唤醒（spec 0015 / 票 #146）：core 只做同步「事件 → 效果」；这里按
     * [DashboardCore.exitGraceDeadlineMs] 到点补派 [DashboardEvent.ExitGraceElapsed]，
     * 让真机上最后一条通知清掉约 0.25 秒后再退屏。新通知取消宽限时旧定时器随截止清空取消。
     */
    private var exitGraceWakeUpJob: Job? = null

    /** SystemUI 可见性探测：Shizuku 在线时精确校准，掉线/失败时 fail-open。 */
    private val shadeVisibilityMonitor = ShadeVisibilityMonitor(shell, shadeVisibilityGate, scope)

    /** 投送通道是否就绪；只在与上次不同时喂 DashboardCore（避免重复重投）。 */
    private var channelReady = false

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

    /**
     * 电量读数监听（spec 0008 / 票 #67；进程存活期间注册一次，sticky 首读即刻到位）：
     * `ACTION_BATTERY_CHANGED` → 百分比回调，只搬运事件。
     */
    private val batterySignals = BatterySignals(context, ::onBatteryLevel)

    // ---------- Agent Mirror 记账（spec 0010 / 票 #81） ----------

    @Volatile
    private var agentEnabled = AgentMirrorSettingsStore.MIRROR_ENABLED_DEFAULT

    // ---------- Agent Alert 记账（spec 0018-3 / 票 #173） ----------

    /** 提醒总开关（落盘在 [AgentMirrorSettingsStore]，缺键即默认开）。 */
    @Volatile
    private var agentAlertEnabled = AgentMirrorSettingsStore.ALERT_ENABLED_DEFAULT

    /** 震动开关（落盘同上；不响铃是既定口径，震动可关）。 */
    @Volatile
    private var agentAlertVibrate = AgentMirrorSettingsStore.ALERT_VIBRATE_DEFAULT

    /** 远程批准开关镜像（spec 0018 §五 / review 2026-09-30）：关＝三处批准入口不出现、已弹浮层撤掉。 */
    @Volatile
    private var agentApproveEnabled = AgentMirrorSettingsStore.APPROVE_ENABLED_DEFAULT

    /** 光带亮度倍率镜像（spec 0021 修订 / 票 #214）：主屏滑动条与背屏光带同一份事实的 app 侧持有。 */
    @Volatile
    private var glowBrightness = AgentMirrorSettingsStore.GLOW_BRIGHTNESS_DEFAULT

    /**
     * 提醒判定簿记（spec 0018-3）：各会话上一状态与同类上次提醒时刻——判定全在
     * [AgentAlertPolicy] 纯函数（JVM 判例锁死），这里只喂状态流（dispatchAgentMerged /
     * 桥 onSession / debug 注入三处收口），桥快照对账**不喂**（重连重建不提醒，
     * 与 Notification Highlight「重连快照重建不呼吸」同口径）。
     */
    private val agentAlertTracker = AgentAlertTracker()

    /** 调试旁路伪会话的最近注入态（spec 0018-4 验收链）：批准入口与动作链对它闭合。 */
    @Volatile
    private var lastDebugSession: AgentSessionState? = null

    /** 最近一次批准动作的失败提示（AC3：提示一句、不重试轰炸）；成功即清。 */
    @Volatile
    private var agentActionNote: String? = null

    /**
     * 桥在册（桥已见会话键，spec 0016 / 票 #154）：桥事件按 sessionId 去重、保留首次到达序。
     * 直接喂 core 的 AgentRoster 对账集与主屏列表投影；桥断线期间
     * 本内存集不清——**只有链路重连后的在册快照**才能替换它（票 #155，[applyBridgeSnapshot]）。
     */
    @Volatile
    private var lastBridgeRoster: List<AgentSessionState> = emptyList()

    /**
     * 锁定会话的展示缓存（#234）：锁定后短暂离开在册名册时继续显示缓存标题，
     * 不退成完整 sessionId；快照确认离册并清锁后随锁一起清掉。
     */
    @Volatile
    private var retainedLockedSession: AgentSessionState? = null

    /**
     * 桥在册集是否为当下事实（spec 0016 / 票 #155）：桥链路在线**且**本轮链路已拿到在册快照
     * → true。false（断线/未对账）时 core 不拿桥名册的缺席清桥来源的锁——[applyMergedRoster]
     * 每拍随合并在册集一起喂进去。
     */
    @Volatile
    private var bridgeRosterKnown = false

    /**
     * PC 桥客户端（ADR 0014 / #234）：ZCode / Codex / Claude / DSH 的唯一 Agent Mirror
     * 传输。会话事实直进 core（桥已归一）；生命周期由 [reconcileBridge] 收口
     * （Agent Mirror 总开关 ∧ 已配置 URL 才起），断线保留最后一帧并等待快照对账恢复。
     */
    val bridgeClient = BridgeRelayClient(log = { line -> Log.i(LOG_TAG, line) }).apply {
        onStatusChanged = { status ->
            scope.launch {
                // 一份事实三处显示（票 #165）：状态行/主页概览/背屏状态点都读 bridgeLinkStatus，
                // 连接旗标同点更新（OR 口径不变）。
                bridgeLinkStatus = status
                bridgeLinkUp = status == BridgeLinkStatus.CONNECTED
                feedAgentConnection(bridgeLinkUp)
                refresh(
                    listenerConnected = _state.value.listenerConnected,
                    lastEvent = "bridge status ${status.name.lowercase()}",
                )
            }
        }
        onLinkUp = {
            scope.launch {
                // 新链路 = 尚未对账（等本条链路的在册快照；期间桥来源的锁只保不清）。
                bridgeRosterKnown = false
                bridgeLinkUp = true
                feedAgentConnection(bridgeLinkUp)
            }
        }
        onLinkDown = {
            scope.launch {
                // 断线：桥名册不再代表当下事实——快照对账的资格随链路一起作废（保锁）。
                bridgeRosterKnown = false
                bridgeLinkUp = false
                feedAgentConnection(bridgeLinkUp)
            }
        }
        onSession = { state ->
            scope.launch {
                // ASCII 验收锚：手机 logcat 可直接断言桥事件的来源。
                Log.i(LOG_TAG, "bridge event source=${state.source ?: "unknown"} session=${state.sessionId} status=${state.status.name.lowercase()}")
                // 桥事件先纳入合并在册集再对账，锁定的桥来源会话不会被同拍 ZCode 名册清掉。
                lastBridgeRoster = AgentStateLogic.mergeRoster(lastBridgeRoster, listOf(state))
                if ((core.sessionLock as? SessionLockMode.Locked)?.sessionId == state.sessionId) {
                    retainedLockedSession = state
                }
                val (rosterApplied, _) = applyBridgeRoster()
                val applied = dispatch(core.onEvent(DashboardEvent.AgentSessionUpdated(state)))
                noteAgentAlert(state)
                refresh(
                    listenerConnected = _state.value.listenerConnected,
                    lastEvent = "bridge ${state.status.name.lowercase()}" + rosterApplied.describe() + applied.describe(),
                )
            }
        }
        onSnapshot = { sessions ->
            scope.launch { applyBridgeSnapshot(sessions) }
        }
        // 批准无果终态（review 2026-09-30 / spec 0018-4 AC3 补遗）：桥侧判死「已受理之后
        // 无人取走/过期」的动作 → 一次失败提示（沿回执失败链，不重试轰炸）。
        onActionExpired = { sessionId ->
            scope.launch { onActionReceipt(sessionId, ActionReceipt.TIMEDOUT) }
        }
    }

    @Volatile
    private var bridgeLinkUp = false

    /**
     * PC 桥链路状态（票 #165）：**一份事实**——主屏设置页状态行、主页概览与背屏状态点都读它，
     * 由 [com.rearcue.poc.agent.BridgeRelayClient.onStatusChanged] 驱动，`refresh()` 同帧重发。
     */
    @Volatile
    private var bridgeLinkStatus: BridgeLinkStatus = BridgeLinkStatus.DISABLED

    /** 桥 URL（内存镜像；落盘在 [BridgeLinkStore]）。 */
    @Volatile
    private var bridgeUrl: String? = null

    /** 桥地址来源与电脑最后推送时刻（票 #171）：界面据此区分「电脑推来的」与「你手填的」。 */
    @Volatile
    private var bridgeAddressSource: BridgeAddressSource? = null

    @Volatile
    private var bridgePushedAt: Long? = null

    /** 手填桥地址的当场探测结果（票 #171）：输入框下方的即时反馈，保存前的一次确认。 */
    @Volatile
    private var bridgeAddressProbe: BridgeAddressProbe = BridgeAddressProbe.Idle

    /** 统一在册集（#234）：PC 桥事件/快照是唯一来源，core 对账与列表投影同吃这一份。 */
    private fun bridgeRoster(): List<AgentSessionState> = lastBridgeRoster

    /**
     * 桥在册集 → core AgentRoster（#234 唯一对账出口）：断线/未对账期间缺席不算数，
     * 保锁等下一次快照对账；快照确认离册才清锁并写盘。返回（效果, 是否发生清锁），
     * 调用方只在主线程执行。
     */
    private fun applyBridgeRoster(): Pair<List<String>, Boolean> {
        val before = core.sessionLock
        val rosterIds = AgentStateLogic.rosterIds(bridgeRoster())
        // 离册清提醒账（spec 0018-3）：重进按首见判定，冷却不陈年跨册。
        agentAlertTracker.retain(rosterIds)
        val applied = dispatch(
            core.onEvent(
                DashboardEvent.AgentRoster(
                    rosterIds,
                    bridgeRosterKnown = bridgeRosterKnown,
                ),
            ),
        )
        val cleared = core.sessionLock != before
        if (cleared) {
            retainedLockedSession = null
            scope.launch { SessionLockStore.save(context, core.sessionLock) }
            Log.i(LOG_TAG, "lock auto-cleared: locked session left the bridge roster → auto")
        }
        return applied to cleared
    }

    /**
     * 在册快照对账（spec 0016 / 票 #155）：链路重新连上后桥交出的在册全量**替换**本地桥在册集
     * ——不在快照里的桥会话才算确实不在册（此后桥名册即当下事实，缺席可清锁）。快照携带
     * 最小字段（来源/工作区/状态），故重启 App 后不等事件也能列出桥会话。写盘跟随清锁
     * 由 [applyMergedRoster] 收口，本方法不碰存储。
     */
    private fun applyBridgeSnapshot(sessions: List<AgentSessionState>) {
        val before = AgentStateLogic.rosterIds(lastBridgeRoster)
        val lockedId = (core.sessionLock as? SessionLockMode.Locked)?.sessionId
        retainedLockedSession = sessions.firstOrNull { it.sessionId == lockedId } ?: retainedLockedSession
        lastBridgeRoster = sessions
        bridgeRosterKnown = true
        val dropped = (before - AgentStateLogic.rosterIds(sessions)).size
        val (applied, cleared) = applyBridgeRoster()
        // ASCII 验收锚：对账一行看清桥在册规模、掉了几条、是否因此清锁。
        Log.i(LOG_TAG, "bridge snapshot reconcile in-roster=${sessions.size} dropped=$dropped cleared=$cleared")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "bridge snapshot n=${sessions.size} dropped=$dropped" + applied.describe(),
        )
    }

    /**
     * 会话列表条目（spec 0016 / 票 #156）：走同一份列表投影（[AgentStateLogic.projectRoster]，
     * :app 的纯逻辑投影面，与主屏 Agent 设置区的列表逐字同源）→ 背屏渲染行；本层只搬类型，
     * 不派生标题/来源/排序/选中。「自动」档的行文案归背屏资源
     * （[AgentPickerRow.sessionId] 为 null 即该行）。
     */
    private fun agentPickerRows(): List<AgentPickerRow> =
        AgentStateLogic.projectRoster(
            bridgeRoster(),
            core.sessionLock,
        ).map { row ->
            AgentPickerRow(
                sessionId = row.sessionId,
                title = row.title,
                sourceLabel = row.sourceLabel,
                waiting = row.status == AgentStatus.WAITING_FOR_APPROVAL,
                selected = row.selected,
            )
        }

    /** 链路事实 → core（spec 0010：AgentConnectionChanged），回落与插队的决策全在 DashboardCore。 */
    private fun feedAgentConnection(connected: Boolean) {
        val applied = dispatch(core.onEvent(DashboardEvent.AgentConnectionChanged(connected)))
        Log.i(LOG_TAG, "agent 链路${if (connected) "上线" else "失联"} → ${applied.describeApplied()}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "agent-${if (connected) "up" else "down"}" + applied.describe(),
        )
    }

    /**
     * 桥生命周期收口（ADR 0014 / #234）：Agent Mirror 总开关 ∧ 已配置 URL 才起链路。
     * 这是四类来源唯一传输；不存在 ZCode 直连自动切换或兜底。
     */
    private fun reconcileBridge() {
        val url = bridgeUrl
        if (agentEnabled && url != null) {
            bridgeClient.start(url)
        } else {
            bridgeClient.stop()
        }
    }

    /**
     * 桥地址写入口（票 #171）：电脑推送 / 手机手填 / Debug Bypass 三条路都收口到这里——
     * 落盘（含来源与推送时刻）+ 即时起停（事件面收口在 [reconcileBridge]，本层零决策）。
     *
     * 自动推送**照旧覆盖**手填地址（手填是 adb 不在场时的兜底，不永久接管，见 ADR 0006 补记）；
     * 界面靠 [BridgeAddressSource] 说清「当前这行是谁写的」，不靠"手填过就不再覆盖"来防呆。
     */
    fun setBridgeAddress(url: String?, source: BridgeAddressSource) {
        bridgeUrl = url?.trim()?.takeIf { it.isNotEmpty() }
        bridgeAddressSource = if (bridgeUrl == null) null else source
        if (source == BridgeAddressSource.PUSHED && bridgeUrl != null) {
            bridgePushedAt = System.currentTimeMillis()
        }
        reconcileBridge()
        scope.launch {
            BridgeLinkStore.save(
                context,
                bridgeUrl?.let { BridgeAddress(it, source, if (source == BridgeAddressSource.PUSHED) bridgePushedAt else null) },
            )
        }
        Log.i(LOG_TAG, "bridge address set has=${bridgeUrl != null} source=${bridgeAddressSource?.name ?: "cleared"}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = if (bridgeUrl != null) "bridge-address set (${source.name.lowercase()})" else "bridge-address cleared",
        )
    }

    /** 旧入口（Debug Bypass 广播）：等价于 [setBridgeAddress] 且来源记 [BridgeAddressSource.DEBUG_BYPASS]。 */
    fun setBridgeUrl(url: String?) = setBridgeAddress(url, BridgeAddressSource.DEBUG_BYPASS)

    /**
     * 手填保存（票 #171）：**先探一次再存**——手打一长串随机域名很容易错一位，
     * 而链路失败信号要等好几秒才在状态点显形；探不通照样存（地址可能只是暂时不可达），
     * 结果同时写进 [AppState.bridgeAddressProbe]，由设置页那行支持文案说清是哪一种不通。
     */
    fun probeAndSaveBridgeAddress(raw: String?) {
        scope.launch {
            val normalized = BridgeAddressProbeClient.normalize(raw)
            if (normalized == null) {
                bridgeAddressProbe = BridgeAddressProbe.BadFormat
                refresh(_state.value.listenerConnected, "bridge-address bad format")
                return@launch
            }
            bridgeAddressProbe = BridgeAddressProbe.Probing
            refresh(_state.value.listenerConnected, "bridge-address probing")
            val result = try {
                BridgeAddressProbeClient.probe(normalized)
            } catch (e: Exception) {
                BridgeAddressProbe.Unreachable(e.javaClass.simpleName)
            }
            bridgeAddressProbe = result
            setBridgeAddress(normalized, BridgeAddressSource.MANUAL)
        }
    }

    /** 清除桥地址（设置页「清除」；等价于广播不带 url）。 */
    fun clearBridgeAddress() {
        // 探测结果一并复位：留着上一次的「已连上」会在清空后继续显示，看着像还连着（返修实测踩到）。
        bridgeAddressProbe = BridgeAddressProbe.Idle
        setBridgeAddress(null, BridgeAddressSource.MANUAL)
    }

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
        // 背屏图标/卡片点按（spec 0008 / 票 #66）：Detail View 的 UI 源，决策在 DashboardCore。
        RearDashboardHost.onIconTap(::onRearIconTap)
        // 背屏非交互区域点按（spec 0013 / 票 #132）：内容页切换的 UI 源，决策在 DashboardCore。
        RearDashboardHost.onContentPageTap(::onRearContentPageTap)
        // 背屏会话标识行点按与列表选定（spec 0016 / 票 #156）：会话选择器的 UI 源，
        // 开关决策在 DashboardCore、选定走 Session Lock 单入口。
        RearDashboardHost.onSessionLineTap(::onRearSessionLineTap)
        RearDashboardHost.onSessionPick(::onRearSessionPick)
        // 背屏批准浮层动作（spec 0018-5 / 票 #175）：二次确认生效后走会话动作单入口，
        // 与通知栏按钮/主屏批准区同一动作语义（恰好三类，无自由文字）。
        RearDashboardHost.onAgentAction { request ->
            sendAgentAction(request)
        }
        // 自启动状态初读（票 #28）：横幅输入只来自实测读数，返回页面时复查。
        checkAutostart()
        // 监听授权与连接初读：补上「服务从未连接」的静默缺口，并按探针效果请求重绑。
        probeNotificationListener(triggerSource = "process-start")
        shadeVisibilityMonitor.request("process-start")
        scope.launch {
            while (isActive) {
                delay(SHADE_VISIBILITY_RECONCILE_MS)
                // 只在校准后有可见内容时轮询；隐藏-only 由事件/ranking 更新触发（ADR 0007）。
                if (_state.value.listenerConnected && repository.currentNotifications.isNotEmpty()) {
                    shadeVisibilityMonitor.request("periodic")
                }
            }
        }
        // 注（票 #98）：原 Allowlist 持久化（DataStore `allowlist`）随白名单概念整体删除——
        // 读取路径已不存在，升级安装留下的残键只是死数据、无人解析即无害（同 spec 0008 对
        // `feed_settings` 残键的废弃容忍口径，不写一次性清理代码）。
        // 充电动画总开关首读（spec 0007 / 票 #57）：缺键即默认（默认开，与 core 初值同源），
        // 首读是一次幂等对齐；写入口归设置页充电区（同一个事件，不各记一份状态）。
        scope.launch {
            applyChargingEnabled(ChargingSettingsStore.load(context))
        }
        // 姿态门控开关首读（票 #100）：缺键即默认（**默认关** = 门控旁路，出厂/升级同档，与
        // core 初值同源），首读是一次幂等对齐；写入口归设置页姿态区（同一个事件，不各记一份状态）。
        scope.launch {
            applyPostureGateEnabled(PostureGateSettingsStore.load(context))
        }
        // Agent 页正文档位首读（spec 0017 / 票 #169）：缺键即默认中档（与 core 初值同源），
        // 首读是一次幂等对齐；写入口归首页 Agent 卡片。
        scope.launch {
            applyMirrorTextSize(AgentMirrorSettingsStore.loadTextSize(context))
        }
        // 角部避让开关首读（spec 0019 / 票 #194）：缺键即默认关（＝贴满，与 core 初值同源），
        // 首读是一次幂等对齐；写入口归设置页 Agent 区。
        scope.launch {
            applyCornerAvoidance(AgentMirrorSettingsStore.loadCornerAvoidance(context))
        }
        // Agent 提醒两开关首读（spec 0018-3 / 票 #173）：缺键即默认（双默认开），
        // 首读是一次幂等对齐；写入口归设置页 Agent 区。
        scope.launch {
            val alerts = AgentMirrorSettingsStore.loadAlerts(context)
            agentAlertEnabled = alerts.enabled
            agentAlertVibrate = alerts.vibrate
            // 远程批准开关首读（review 2026-09-30）：缺键即默认开，同一条幂等对齐口径。
            agentApproveEnabled = AgentMirrorSettingsStore.loadApprovalEnabled(context)
            // 光带亮度倍率首读（spec 0021 修订 / 票 #214）：缺键即 1×，越界钳回范围。
            applyGlowBrightness(AgentMirrorSettingsStore.loadGlowBrightness(context))
        }
        // Agent Mirror 首读（#234）：总开关与 PC 桥地址齐备才起唯一链路（断线退避在 client）。
        scope.launch {
            agentEnabled = AgentMirrorSettingsStore.loadMirrorEnabled(context)
            // PC 桥首读（ADR 0014）：有 URL 且总开关开 → 起长轮询（断线退避在 client）。
            // 票 #171：连来源与推送时刻一起读回——界面要能说清「这行是电脑推来的」，以及推于何时。
            BridgeLinkStore.loadAddress(context)?.let { address ->
                bridgeUrl = address.url
                bridgeAddressSource = address.source
                bridgePushedAt = address.pushedAt
            }
            reconcileBridge()
        }
        // Session Lock 首读（票 #103）：缺键即默认「自动」，首读是一次幂等对齐（与 core 初值
        // 相同时零效果）；写入口归设置区/Debug Bypass（同一个事件，不各记一份状态）。
        scope.launch {
            applySessionLock(SessionLockStore.load(context))
        }
        // 注（spec 0008）：横幅设置（Privacy Mode / Auto-dismiss，原 FeedSettingsStore 首读）
        // 随横幅退役整体删除。DataStore 里的 `feed_settings` 残键**废弃容忍**：已无任何读者，
        // 残值无害，为一次性 POC 数据写清理/迁移代码不值当（卸载重装即消失）。
        // 充电插拔（spec 0007 票 #57）：ACTION_POWER_CONNECTED/DISCONNECTED → core 事件，
        // 插电即投/拔电退出/门控豁免的决策全在 DashboardCore（接线层零决策）。
        powerSignals.start()
        // 电量读数（spec 0008 / 票 #67）：ACTION_BATTERY_CHANGED → BatteryLevel 事件进 core，
        // 显示面（绿色比例 + 大数字）随电量刷新；sticky 注册即得首读。
        batterySignals.start()
        // Posture Gate（spec 0006 / 票 #53）：接近传感器 → 稳定窗防抖 → 姿态提交。
        postureMonitor = PostureGateMonitor(context) { faceDown -> onPostureCommitted(faceDown) }
        postureMonitor.start()
    }

    // ---------- 充电动画总开关（spec 0007 / 票 #57：存储与写入口都走同一个事件，决策在 core） ----------

    /**
     * 开关的「即时生效（事件进 core）+ 同点刷新」同形搬运：充电总开关（票 #57）与姿态门控
     * 开关（票 #100）共用——两者只有事件类型与日志前缀不同，档位语义（关/开各产生什么投撤）
     * 全在 DashboardCore，本层只搬运；写盘归各自的 `set*` 入口。
     */
    private fun applySwitch(event: DashboardEvent, label: String, enabled: Boolean) {
        val applied = dispatch(core.onEvent(event))
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "$label=$enabled" + applied.describe(),
        )
    }

    /**
     * 总开关存储值对齐：喂 [DashboardEvent.ChargingAnimation]——档位语义（关 = 插电无反应、
     * 关掉 = 按退出合收取口、开且在充电 = 即时补投）都在 DashboardCore；与 core 初值相同
     * （首读常态）时无任何效果。
     */
    private fun applyChargingEnabled(enabled: Boolean) =
        applySwitch(DashboardEvent.ChargingAnimation(enabled), "charging-anim", enabled)

    /**
     * 充电动画总开关写入口（spec 0007 story 11，设置页充电区）：即时生效（事件进 core）
     * + 写盘；本层不做任何决策（零决策搬运）。
     */
    fun setChargingAnimationEnabled(enabled: Boolean) {
        applyChargingEnabled(enabled)
        scope.launch { ChargingSettingsStore.saveChargingAnimationEnabled(context, enabled) }
    }

    // ---------- 姿态门控开关（票 #100：存储与写入口都走同一个事件，决策在 core） ----------

    /**
     * 开关存储值对齐：喂 [DashboardEvent.PostureGateEnabled]——档位语义（关 = 姿态旁路、
     * 开 = 正放拦/撤、倒扣补投）都在 DashboardCore；与 core 初值相同（首读常态）时无任何效果。
     */
    private fun applyPostureGateEnabled(enabled: Boolean) =
        applySwitch(DashboardEvent.PostureGateEnabled(enabled), "posture-gate", enabled)

    /**
     * 姿态门控开关写入口（票 #100，设置页姿态区）：即时生效（事件进 core，门开合结果变了
     * 立即拦/撤/补投）+ 写盘；本层不做任何决策（零决策搬运，同充电总开关口径）。
     */
    fun setPostureGateEnabled(enabled: Boolean) {
        applyPostureGateEnabled(enabled)
        scope.launch { PostureGateSettingsStore.savePostureGateEnabled(context, enabled) }
    }

    // ---------- Agent 页正文档位（spec 0017 / 票 #169：存储与写入口都走同一个事件） ----------

    /**
     * 档位存储值对齐：喂 [DashboardEvent.MirrorTextSizeChanged]——档位语义（三档字号与
     * 标识行联动）都在渲染侧；core 只记事实，与初值相同（首读常态）时无任何效果。
     */
    private fun applyMirrorTextSize(size: MirrorTextSize) {
        core.onEvent(DashboardEvent.MirrorTextSizeChanged(size))
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "mirror-text-size ${size.name}")
    }

    /**
     * 正文档位写入口（spec 0017 / 票 #169，主屏首页 Agent 卡片）：即时生效（事件进 core →
     * `refresh()` 重发 AgentFeed → 在屏 Agent 页立刻按新档重排）+ 写盘；本层零决策搬运
     * （同姿态门控/充电总开关口径）。
     */
    fun setMirrorTextSize(size: MirrorTextSize) {
        applyMirrorTextSize(size)
        scope.launch { AgentMirrorSettingsStore.saveTextSize(context, size) }
    }

    // ---------- 角部避让开关（spec 0019 / 票 #194：存储与写入口都走同一个事件） ----------

    /**
     * 开关存储值对齐：喂 [DashboardEvent.CornerAvoidanceChanged]——core 只记事实，
     * 与初值相同（首读常态）时无任何效果。
     */
    private fun applyCornerAvoidance(enabled: Boolean) {
        core.onEvent(DashboardEvent.CornerAvoidanceChanged(enabled))
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "corner-avoidance $enabled")
    }

    /**
     * 角部避让写入口（spec 0019 / 票 #194，主屏 Agent 设置区）：即时生效（事件进 core →
     * `refresh()` 重发 AgentFeed → 在屏 Agent 会话页与会话列表立刻按新口径重排）+ 写盘；
     * 本层零决策搬运（同正文档位口径）。
     */
    fun setCornerAvoidance(enabled: Boolean) {
        applyCornerAvoidance(enabled)
        scope.launch { AgentMirrorSettingsStore.saveCornerAvoidance(context, enabled) }
    }

    // ---------- 光带亮度滑动条（spec 0021 修订 / 票 #214：呈现偏好走 AgentFeed，不进 core） ----------

    /**
     * 亮度倍率对齐：钳制到 [com.rearcue.poc.core.GlowBrightness] 范围后记账并重发——
     * 滑动条连续值由它兜底（拖动中的中间值也合法，落点前的过程值不写盘）。
     */
    private fun applyGlowBrightness(brightness: Float) {
        glowBrightness = com.rearcue.poc.core.GlowBrightness.coerce(brightness)
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "glow-brightness $glowBrightness")
    }

    /**
     * 亮度倍率写入口（主屏 Agent 设置区滑动条）：即时生效（`refresh()` 重发 AgentFeed →
     * 在屏光带立刻按新倍率点亮）；[persist]＝true 才写盘——滑动条拖动中的连续过程值
     * 传 false（只生效），松手（onValueChangeFinished）传 true 落点。本层零决策搬运
     * （同正文档位口径，仅钳制）。
     */
    fun setGlowBrightness(brightness: Float, persist: Boolean) {
        applyGlowBrightness(brightness)
        if (persist) {
            scope.launch { AgentMirrorSettingsStore.saveGlowBrightness(context, brightness) }
        }
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

    /**
     * 电量读数（spec 0008 / 票 #67）：百分比 → [DashboardEvent.BatteryLevel] 进 core 状态
     * （只进状态不产效果，决策在 DashboardCore）；refresh 把新读数重发给背屏 Feed——
     * 充电在屏时绿色比例填充与白色大号数字随事件刷新（68→69）。
     */
    private fun onBatteryLevel(percent: Int) {
        val applied = dispatch(core.onEvent(DashboardEvent.BatteryLevel(percent)))
        Log.i(LOG_TAG, "电量 $percent% → ${applied.describeApplied()}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "battery $percent%" + applied.describe(),
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
        if (available) {
            shadeVisibilityMonitor.request("shizuku-up")
        } else {
            shadeVisibilityMonitor.onSourceUnavailable("shizuku-down")
        }
        val applied = dispatch(if (available) core.onEvent(DashboardEvent.FallbackAvailable) else emptyList())
        // 掉线没有可搬运的效果：主路径是应用内投送，背屏内容不受影响（CONTEXT.md「投送通道」）。
        val what = if (available) applied.describeApplied() else "Dashboard 不受影响（应用内投送）"
        Log.i(LOG_TAG, "兜底通道${if (available) "恢复" else "掉线"} → $what")
        // 这里不再补 refresh：重投经投送链路的 project/update 本身就带包名 + 未读角标计数
        // 全量发布（IconSetFeed.publish 恒两参），不会留下「计数停在旧值」的半更新态。
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
     * 背屏图标/卡片点按（spec 0008 / 票 #66）：翻译成 [DashboardEvent.DetailToggled]——
     * 打开/收起（再点同一图标或卡片）/切换（点另一枚）的决策全在 DashboardCore，
     * 本层零决策只搬运。
     *
     * `rear-tap received` 探针锚（票 #63 词形契约）升级为真实点击处理的收口点：
     * 图标点按与卡片点按都经 [com.rearcue.poc.rear.RearDashboardHost.emitIconTap] 到这里，
     * 一次点按一条锚，词形 byte 不可改（tools/ex 验收链按词形读）。
     */
    fun onRearIconTap(pkg: String) {
        Log.i(LOG_TAG, "rear-tap received app=$pkg")
        val applied = dispatch(core.onEvent(DashboardEvent.DetailToggled(pkg)))
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "detail-toggle $pkg" + applied.describe(),
        )
    }

    /**
     * 背屏非交互区域点按（spec 0013 / 票 #132）：只上报 [DashboardEvent.ContentPageToggle]——
     * 能否切、切到哪页、WFA 期间是否忽略全在 DashboardCore；UI 不做页码/内容判断。
     * `rear-tap received` 锚沿用票 #63 词形，空白同报一次。
     */
    fun onRearContentPageTap() {
        Log.i(LOG_TAG, "rear-tap received area=content-page")
        val applied = dispatch(core.onEvent(DashboardEvent.ContentPageToggle))
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "content-page-toggle" + applied.describe(),
        )
    }

    /**
     * 背屏 Agent 页会话标识行点按（spec 0016 / 票 #156）：上报
     * [DashboardEvent.AgentPickerToggle]——开列表、再点关、能否开（非 Agent 页不开）全在
     * DashboardCore，UI 零决策。日志锚 `agent picker open|close <reason>` 由 core 打。
     */
    fun onRearSessionLineTap() {
        Log.i(LOG_TAG, "rear-tap received area=agent-session-line")
        val applied = dispatch(core.onEvent(DashboardEvent.AgentPickerToggle))
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "agent-picker-toggle" + applied.describe(),
        )
    }

    /**
     * 背屏会话列表选定（spec 0016 / 票 #156）：复用 Session Lock 单入口
     * （[setSessionLock]：事件进 core + 写盘，不新增存储），`agent picker select <sessionId>`
     * 是实机验收锚（选「自动」档记 `auto`）；列表的关闭由 core 在 SessionLock 事件上收口。
     */
    fun onRearSessionPick(sessionId: String?) {
        Log.i(LOG_TAG, AgentPickerLogContract.select(sessionId ?: AgentPickerLogContract.SELECT_AUTO))
        setSessionLock(sessionId?.let { SessionLockMode.Locked(it) } ?: SessionLockMode.Auto)
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

    // ---------- Agent Mirror 总开关（#234：PC 桥唯一传输） ----------

    /** 总开关（默认开）：关 = PC 桥链路停机并撤提醒；开 = 有桥地址即恢复唯一链路。 */
    fun setAgentMirrorEnabled(enabled: Boolean) {
        agentEnabled = enabled
        scope.launch { AgentMirrorSettingsStore.saveMirrorEnabled(context, enabled) }
        if (!enabled) {
            cancelAgentAlerts(context)
            Log.i(LOG_TAG, "agent disabled")
        }
        reconcileBridge()
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "agent-enabled=$enabled")
    }

    // ---------- Agent Alert（spec 0018-3 / 票 #173：三类提醒——等你确认/干完/出错） ----------

    /**
     * 会话状态到达的提醒判定入口（三处收口：[dispatchAgentMerged] 批次、桥 onSession、
     * debug 注入；桥快照对账不喂——重连重建不提醒）。判定全在 [AgentAlertPolicy]／
     * [AgentAlertTracker]，本层只搬运结果。
     */
    private fun noteAgentAlert(state: AgentSessionState) {
        val kind = agentAlertTracker.onSessionState(state.sessionId, state.status, System.currentTimeMillis())
            ?: return
        fireAgentAlert(state, kind)
    }

    /**
     * 发一条提醒（判定已定）：总开关/提醒开关任一关都不发（提醒整体不存在的口径），
     * 正文由 [AgentAlertPolicy.contentLine] 组装（会话名＋摘要，缺失退化为会话名），不带输出原文。
     */
    private fun fireAgentAlert(state: AgentSessionState, kind: AgentAlertKind) {
        if (!agentEnabled || !agentAlertEnabled) return
        val line = AgentAlertPolicy.contentLine(state.summary, AgentStateLogic.sessionName(state))
        // ASCII 验收锚：PC 脚本按 kind= 断言三类提醒的触发。
        Log.i(LOG_TAG, "agent alert kind=${kind.name.lowercase()} session=${state.sessionId}")
        postAgentAlert(context, state.sessionId, kind, line, vibrate = agentAlertVibrate, actions = alertActions(state, kind))
    }

    /**
     * 等确认提醒的动作按钮组（spec 0018-4 / 票 #174）：只随 [AgentAlertKind.WAITING] 且过
     * [AgentApprovePolicy] 批准入门才出现——提问类＝选项点选、确认类＝同意/拒绝；
     * 其余提醒与只提醒来源**没有批准按钮**（AC4）。没有自由文字入口（ADR 0009）。
     */
    private fun alertActions(state: AgentSessionState, kind: AgentAlertKind): List<AlertAction> {
        if (kind != AgentAlertKind.WAITING) return emptyList()
        if (!AgentApprovePolicy.canApprove(state, bridgeClient.capabilities(), agentApproveEnabled)) return emptyList()
        // 按钮组形状的分流收口在 shapeFor（review 2026-09-30）：这里只做「形状 → 通知栏按钮」的搬运。
        return when (val shape = AgentApprovePolicy.shapeFor(state)) {
            is AgentApproveShape.Question -> questionActions(shape.options)
            AgentApproveShape.Confirm -> listOf(
                AlertAction(context.getString(R.string.agent_action_approve), SessionActionKind.APPROVE),
                AlertAction(context.getString(R.string.agent_action_reject), SessionActionKind.REJECT),
            )
        }
    }

    /** 提醒总开关写入口（设置页 Agent 区）：关＝不再提醒并撤掉已发的；写盘同点收口。 */
    fun setAgentAlertEnabled(enabled: Boolean) {
        agentAlertEnabled = enabled
        if (!enabled) cancelAgentAlerts(context)
        scope.launch { AgentMirrorSettingsStore.saveAlertEnabled(context, enabled) }
        Log.i(LOG_TAG, "agent alert enabled=$enabled")
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "agent-alert=$enabled")
    }

    /** 震动开关写入口（设置页 Agent 区）：关＝只留通知栏静默提示；写盘同点收口。 */
    fun setAgentAlertVibrate(vibrate: Boolean) {
        agentAlertVibrate = vibrate
        scope.launch { AgentMirrorSettingsStore.saveAlertVibrate(context, vibrate) }
        Log.i(LOG_TAG, "agent alert vibrate=$vibrate")
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "agent-alert-vibrate=$vibrate")
    }

    /**
     * 远程批准开关写入口（review 2026-09-30 / spec 0018 §五）：关＝三处批准入口（通知栏按钮/
     * 主屏批准区/背屏浮层）全部不出现——浮层是「批准投影」驱动的，本刷新即撤掉已弹的层。
     */
    fun setAgentApproveEnabled(enabled: Boolean) {
        agentApproveEnabled = enabled
        scope.launch { AgentMirrorSettingsStore.saveApprovalEnabled(context, enabled) }
        Log.i(LOG_TAG, "agent approve enabled=$enabled")
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "agent-approve=$enabled")
    }

    /**
     * 伪提醒注入（DebugCommandReceiver.AGENT_ALERT 的落点，spec 0018-3 验收链）：
     * 不经状态跃迁、直接走 [fireAgentAlert] 同一发放口——「等确认 → 三处提醒呈现 → 开关」
     * 的验收链不必真造会话状态跃迁。开关门照常生效（总开关关＝什么都不发）。
     * [kind] 取 waiting|done|error，非法值记日志忽略。
     */
    fun debugInjectAgentAlert(kind: String, summary: String?) {
        val parsed = when (kind.trim().lowercase()) {
            "waiting" -> AgentAlertKind.WAITING
            "done" -> AgentAlertKind.DONE
            "error" -> AgentAlertKind.ERROR
            else -> {
                Log.w(LOG_TAG, "debug agent alert 忽略未知 kind=$kind（waiting|done|error）")
                return
            }
        }
        val state = AgentSessionState(
            sessionId = DEBUG_SESSION_ID,
            workspace = "debug",
            // 等确认提醒按等待态造（spec 0018-4）：批准入口的判定（canApprove 要求等待中）
            // 与真实链路同一条口径，验收链在通知栏就能点「同意/拒绝」。
            status = if (parsed == AgentAlertKind.WAITING) AgentStatus.WAITING_FOR_APPROVAL else AgentStatus.WORKING,
            summary = summary,
            updatedAt = System.currentTimeMillis(),
        )
        lastDebugSession = state
        fireAgentAlert(state, parsed)
    }

    // ---------- Remote Approval（spec 0018-4 / ADR 0009：除批准外只读——唯一写方向） ----------

    /**
     * 会话动作写入口（spec 0018-4）：通知栏按钮 / 主屏批准区 / 背屏浮层（票 #175）与
     * Debug Bypass 共用这一条链。**恰好三类应答**（同意/拒绝/选项点选），没有自由文字入口
     * （ADR 0009 红线，判例锁死）。桥动作经 [BridgeRelayClient.sendAction] 出去、恒拿明确
     * 回执；调试伪会话本地模拟受理（验收链不必真桥）。失败提示一次、不自动重试（AC3）。
     */
    fun sendAgentAction(action: SessionActionRequest) {
        val sessionId = action.sessionId
        val kind = action.kind
        // ASCII 验收锚：PC 脚本按 kind=/receipt= 断言批准链的每一步。
        Log.i(LOG_TAG, "agent action kind=${kind.wire()} session=$sessionId requestId=${action.requestId}")
        if (AgentApprovePolicy.isDebugSession(sessionId)) {
            // 调试旁路伪会话：本地模拟受理——等待标记消失、状态推进（验收链手机侧闭合）。
            onActionReceipt(sessionId, ActionReceipt.ACCEPTED)
            advanceDebugSessionAfterApproval()
            return
        }
        val rawId = if (AgentSessionKeys.isBridge(sessionId)) {
            sessionId.removePrefix(com.rearcue.poc.agent.BridgeEventCodec.SESSION_PREFIX)
        } else {
            sessionId
        }
        val request = action.copy(sessionId = rawId)
        bridgeClient.sendAction(request) { receipt -> scope.launch { onActionReceipt(sessionId, receipt) } }
    }

    /**
     * 回执落点（AC3）：成功＝不留提示（等待标记消失由来源状态推进驱动——桥事件/伪会话本地
     * 推进）；失败＝主屏提示一句＋提醒通知更新为失败说明，**不自动重试轰炸**。
     */
    private fun onActionReceipt(sessionId: String, receipt: ActionReceipt) {
        if (receipt.accepted) {
            agentActionNote = null
            Log.i(LOG_TAG, "agent action receipt=accepted session=$sessionId")
        } else {
            agentActionNote = context.getString(R.string.agent_action_failed, receipt.name.lowercase())
            Log.w(LOG_TAG, "agent action receipt=${receipt.name.lowercase()} session=$sessionId")
            // 提醒通知原位更新为失败提示（tag=sessionId 覆盖同一条），震动关（失败不再震）。
            postAgentAlert(
                context,
                sessionId,
                AgentAlertKind.WAITING,
                agentActionNote!!,
                vibrate = false,
            )
        }
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "agent-action ${receipt.name.lowercase()}",
        )
    }

    /**
     * 批准浮层的渲染投影（spec 0018-5 / 票 #175）：等待 ∧ 可批准才给（[AgentApprovePolicy]
     * 是入口显隐的唯一判定出口），内容照 [AgentSessionState] 原样搬——背屏零决策照单渲染。
     */
    private fun approvePrompt(): com.rearcue.poc.rear.AgentApprovePrompt? {
        val st = core.agentState ?: return null
        if (!AgentApprovePolicy.canApprove(st, bridgeClient.capabilities(), agentApproveEnabled)) return null
        val shape = AgentApprovePolicy.shapeFor(st)
        return com.rearcue.poc.rear.AgentApprovePrompt(
            sessionId = st.sessionId,
            isQuestion = shape is AgentApproveShape.Question,
            summary = st.summary,
            options = (shape as? AgentApproveShape.Question)?.options ?: emptyList(),
        )
    }

    /**
     * 伪会话批准后的状态推进（验收链「成功→等待标记消失、状态推进」）：本地造一条 idle 事实。 */
    private fun advanceDebugSessionAfterApproval() {
        val previous = lastDebugSession ?: return
        val advanced = previous.copy(status = AgentStatus.IDLE, updatedAt = System.currentTimeMillis())
        lastDebugSession = advanced
        dispatch(core.onEvent(DashboardEvent.AgentSessionUpdated(advanced)))
    }

    // ---------- Session Lock（票 #103：存储与写入口都走同一个事件，决策在 core） ----------

    /**
     * 锁定档位存储值对齐：喂 [DashboardEvent.SessionLock]——档位语义（显示谁、等确认插队、
     * 锁会话空闲回落常规内容、在册对账清锁）全在 DashboardCore；与 core 初值相同（首读常态）
     * 时无任何效果。锁定会话展示缓存同点跟随，短暂离册仍显示缓存标题。
     */
    private fun applySessionLock(mode: SessionLockMode) {
        retainedLockedSession = when (mode) {
            SessionLockMode.Auto -> null
            is SessionLockMode.Locked ->
                bridgeRoster().firstOrNull { it.sessionId == mode.sessionId } ?: retainedLockedSession
        }
        val applied = dispatch(core.onEvent(DashboardEvent.SessionLock(mode)))
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "session-lock=$mode" + applied.describe(),
        )
    }

    /**
     * 锁定偏好写入口（票 #103，设置区与 Debug Bypass 共用，照 [setChargingAnimationEnabled]）：
     * 即时生效（事件进 core + 订阅跟随）+ 写盘；本层零决策。锁定会话从任务表消失后的
     * 自动清锁写盘跟随在 [feedAgentRoster]（同一存储，不第二份事实）。
     */
    fun setSessionLock(mode: SessionLockMode) {
        applySessionLock(mode)
        scope.launch { SessionLockStore.save(context, mode) }
    }

    /**
     * 注入锁定（DebugCommandReceiver.SESSION_LOCK 的落点，票 #103 验收链）：空串/`auto` =
     * 自动档，其余 = 锁定该会话键——与设置区同一事件入口＋写盘（三段式零特例）。
     */
    fun debugInjectSessionLock(sessionId: String?) {
        val mode = if (sessionId.isNullOrBlank() || sessionId == "auto") {
            SessionLockMode.Auto
        } else {
            SessionLockMode.Locked(sessionId)
        }
        Log.i(LOG_TAG, "debug session lock $mode")
        setSessionLock(mode)
    }

    // ---------- Debug Bypass（spec 0010 / 票 #84：伪 agent 状态注入，仅 debug 构建可达） ----------

    /**
     * 注入伪会话状态（DebugCommandReceiver.AGENT_STATE 的落点）：走与真实数据完全相同的
     * core 事件入口（AgentSessionUpdated），仲裁/渲染零特例——PC 脚本一条命令演示各状态。
     * [status] 取 working|waiting|idle，非法值记日志忽略。
     *
     * [turns]（spec 0017 / 票 #169 验收链）：`--es turns "<role>|<text>;<role>|<text>"` 形态的
     * 问答流——背屏的左对齐版式、提问泡、间距细分都靠它离线复现，不必等真链路攒出多轮。
     * 形态不合法即当作没有（回落旧字段），不抛。
     *
     * [source]（spec 0018 验收链）：`--es source codex|claude|zcode|dsh` —— 注入的会话归属哪个来源，
     * 会话标识行/列表的来源标记（[AgentSessionDisplay]）靠它复现。认不出的值当作没给（记一行日志），
     * 不抛、不写脏值。
     */
    fun debugInjectAgentState(
        status: String,
        workspace: String?,
        action: String?,
        reply: String?,
        turns: String? = null,
        source: String? = null,
        title: String? = null,
    ) {
        val agentStatus = com.rearcue.poc.agent.BridgeEventCodec.statusFromWord(status) ?: run {
            Log.w(LOG_TAG, "debug agent state 忽略未知 status=$status")
            return
        }
        val parsedTurns = parseDebugTurns(turns)
        val knownSource = source?.trim()?.takeIf { it.isNotEmpty() }?.also { value ->
            if (value !in DEBUG_AGENT_SOURCES) {
                Log.w(LOG_TAG, "debug agent state 忽略未知 source=$value")
            }
        }?.takeIf { it in DEBUG_AGENT_SOURCES }
        val state = AgentSessionState(
            sessionId = DEBUG_SESSION_ID,
            workspace = workspace,
            status = agentStatus,
            currentAction = action,
            latestReply = reply,
            updatedAt = System.currentTimeMillis(),
            source = knownSource,
            turns = parsedTurns,
            title = title?.trim()?.takeIf { it.isNotEmpty() },
        )
        lastDebugSession = state
        val applied = dispatch(core.onEvent(DashboardEvent.AgentSessionUpdated(state)))
        noteAgentAlert(state)
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "agent-debug $status source=${knownSource ?: "-"} turns=${parsedTurns.size}" +
                applied.describe(),
        )
    }

    /** 注入链路事实（断连回落/恢复演示）：同 [feedAgentConnection] 的真实路径。 */
    fun debugInjectAgentConnection(connected: Boolean) = feedAgentConnection(connected)

    /**
     * Debug 问答流串 → turns（spec 0017 验收链）。
     *
     * 两种形态：
     * - **base64**（验收脚本用的形态）：`adb shell` 对 `|;()空格` 的引号处理极脆（实测
     *   `sh -c` 会重解析、反引号触发命令替换），所以脚本先 base64 再传，这里解回来；
     * - 明文 `<role>|<text>` 之间用 `;` 分隔（顺手手输用），role 取 `u`/`user` 或 `a`/`assistant`。
     *
     * 逐段容错——认不出的段跳过，整串不合法就当没有（回落旧字段），不抛。
     */
    private fun parseDebugTurns(raw: String?): List<com.rearcue.poc.agent.AgentTurn> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(raw.trim()), Charsets.UTF_8)
        }.getOrNull()
        val spec = (decoded ?: raw).trim()
        if (spec.isEmpty()) return emptyList()
        return spec.split(';').mapNotNull { segment ->
            val cut = segment.indexOf('|')
            if (cut <= 0) return@mapNotNull null
            val role = when (segment.substring(0, cut).trim().lowercase()) {
                "u", "user" -> com.rearcue.poc.agent.AgentTurnRole.USER
                "a", "assistant" -> com.rearcue.poc.agent.AgentTurnRole.AGENT
                else -> return@mapNotNull null
            }
            val text = segment.substring(cut + 1).trim()
            if (text.isEmpty()) {
                null
            } else {
                com.rearcue.poc.agent.AgentTurn(role = role, text = text)
            }
        }
    }
    /**
     * 注入姿态读数（DebugCommandReceiver.POSTURE，自动化验收用）：与传感器提交同一条
     * [DashboardEvent.PostureGate] 路径（防抖提交后的事件面）；此后传感器真实提交仍会覆盖。
     */
    fun debugInjectPosture(faceDown: Boolean) {
        postureFaceDown = faceDown
        val applied = dispatch(core.onEvent(DashboardEvent.PostureGate(faceDown)))
        Log.i(LOG_TAG, "debug posture faceDown=$faceDown → ${applied.describeApplied()}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "posture-debug ${if (faceDown) "down" else "up"}" + applied.describe(),
        )
    }

    private companion object {
        /** 伪注入会话键：与真实 feed 的默认键区分，测试/演示互不覆盖。 */
        const val DEBUG_SESSION_ID = AgentApprovePolicy.DEBUG_SESSION_ID

        /** Debug 注入认得的来源（[AgentSources] 的四个）；不在册的值一律当作没给。 */
        val DEBUG_AGENT_SOURCES = setOf(
            AgentSources.ZCODE,
            AgentSources.CODEX,
            AgentSources.CLAUDE,
            AgentSources.DSH,
        )
    }

    /**
     * 效果 → 背屏动作：上屏/更新/退出/降级的决策在 DashboardCore，这里只搬运。
     *
     * 每条效果返回日志短名（[DashboardEffect.label]），进 logcat 与调试页——E7 的验收面。
     * 搬运完再按 core 的退屏宽限截止重排一次到期唤醒（见 [scheduleExitGraceWakeUp]）。
     */
    private fun dispatch(
        effects: List<DashboardEffect>,
        requestRebindSource: String? = null,
    ): List<String> = effects.map { effect ->
        when (effect) {
            is DashboardEffect.LaunchDashboard ->
                if (!rearBackend.project(effect.iconSet.toList(), core.unreadCounts)) {
                    // 上屏没发出（背屏不在/通道失败）：不谎报成功；下一次 Icon Set 变化会经
                    // update() 的自愈重投再试一次（被白名单静默拒绝时只能靠设备实验发现）。
                    Log.w(LOG_TAG, "上屏未发出 iconSet=${effect.iconSet.size}")
                }
            is DashboardEffect.UpdateIconSet ->
                rearBackend.update(effect.iconSet.toList(), core.unreadCounts)
            DashboardEffect.ExitDashboard -> rearBackend.exit()
            // 通道已不可用，没有可停的投送；通知监听与 Icon Set 照常维护，通道回来即重投。
            DashboardEffect.Degrade -> Log.w(LOG_TAG, "Degrade：投送通道不可用，仅维护 Icon Set")
            // Notification Highlight 呼吸指令（spec 0008 / 票 #65）：转发呼吸截止给背屏界面
            // （晚挂载播剩余、过期不播）。锚 `highlight breath start` 由 core 决策处打（本层不重复打）；
            // `highlight breath end` 由背屏动画播完打。词形契约见 DashboardCore.LOG_HIGHLIGHT_CONTRACT，
            // tools/ex 验收链按词形读，byte 不可改。
            is DashboardEffect.HighlightBreath -> HighlightFeed.publishBreath(effect.untilMs)
            // 等待确认强调（spec 0010 / 票 #85）：转发强调截止给背屏界面（晚挂载播剩余、
            // 过期不播）。锚 `agent pulse start` 由 core 打；`agent pulse end` 由背屏动画播完打。
            // 词形契约见 DashboardCore.LOG_AGENT_PULSE_CONTRACT。
            is DashboardEffect.AgentPulse -> AgentFeed.publishPulse(effect.untilMs)
            // 消除所示通知（票 #111 点开即消）：core 在打开 Detail 时连带决定，这里只经
            // 监听服务的 key 级撤销执行；回执（NotificationRemoved）走既有事件链回 core，
            // 由 detailCloseIfShown 的自发消除豁免接住（详情保留供阅读）。执行失败回报
            // SelfCancelFailed 解除布防——外部清除该 key 时详情照旧自动收起。
            is DashboardEffect.CancelNotification -> {
                if (!cancelNotificationByKey(effect.key)) {
                    dispatch(core.onEvent(DashboardEvent.SelfCancelFailed(effect.key)))
                }
            }
            // 可用性横幅（票 #28）：显隐与降级形态的决策在 DashboardCore，这里只落状态供调试页渲染。
            is DashboardEffect.ShowUsabilityBanner -> bannerReasons = effect.reasons
            DashboardEffect.HideUsabilityBanner -> bannerReasons = null
            DashboardEffect.RequestRebind -> requestListenerRebind(requestRebindSource ?: "unknown")
        }
        effect.label
    }.also { scheduleExitGraceWakeUp() }

    /**
     * 退屏宽限到期唤醒（spec 0015 / 票 #146）：core 是同步状态机，宽限到期不会凭空产生
     * 事件；按只读截止投影安排一次性 [kotlinx.coroutines.delay]，到点派发
     * [DashboardEvent.ExitGraceElapsed] 再走统一出口。早到/迟到都由 core 按 nowMs 判定，
     * 晚到的窄窗也由本函数重排；新通知取消宽限时截止变 null，旧定时器在这里被取消。
     */
    private fun scheduleExitGraceWakeUp() {
        exitGraceWakeUpJob?.cancel()
        val deadline = core.exitGraceDeadlineMs ?: return
        val remainingMs = deadline - System.currentTimeMillis()
        exitGraceWakeUpJob = scope.launch {
            if (remainingMs > 0) delay(remainingMs)
            // 先摘掉自己再 dispatch：dispatch 会按最新截止重排，不能把当前协程当旧定时器取消。
            exitGraceWakeUpJob = null
            val applied = dispatch(core.onEvent(DashboardEvent.ExitGraceElapsed))
            refresh(
                listenerConnected = _state.value.listenerConnected,
                lastEvent = "exit-grace" + applied.describe(),
            )
        }
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
     * 记 [CastSource.MANUAL]：豁免门控、不被自动逻辑撤下；通道未就绪时无效果（不谎报在屏）。
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

    /**
     * 调试旁路（spec 0015 / 票 #150 实机验收 fixture）：按包名把一枚「非真实通知」直接投进
     * 可见性路由 [shadeVisibilityGate]——等价于 NLS 回调走到 gate 的那一段，**不**触发
     * Shizuku 可见性探测请求。原因：`cmd notification post` 恒为 `com.android.shell`、本应用
     * `POST_TEST` 恒为 `com.rearcue.poc`，本机造不出 4~7 枚多应用图标的档位场景。
     *
     * 代价（验收脚本据此把这类场景标为注入腿）：注入项不在 SystemUI 在册集合里，下一次成功的
     * Shade-visible 探测会按既有可见性语义把它隐藏（同 key 需先移除再播报才能复活）。
     * release 构建没有调用方，行为不变。
     */
    fun debugInjectFixturePosted(notification: ActiveNotification) {
        shadeVisibilityGate.onPosted(notification)
    }

    /** [debugInjectFixturePosted] 的移除对偶（同一 gate 入口，等价 NLS 的 onNotificationRemoved）。 */
    fun debugInjectFixtureRemoved(notification: ActiveNotification) {
        shadeVisibilityGate.onRemoved(notification)
    }

    /**
     * 消除所示通知（票 #111「点开即消」）的 key 级执行通道：监听服务连接时登记、断开/销毁时
     * 注销（与 [notificationCanceller] 同一生命周期，撤销他人通知是监听服务独有权限）。
     * 返回 true = 系统接受了撤销请求（回执 NotificationRemoved 由既有事件链回 core）。
     */
    @Volatile
    private var notificationKeyCanceller: ((String) -> Boolean)? = null

    fun onKeyCancellerChanged(cancel: ((String) -> Boolean)?) {
        notificationKeyCanceller = cancel
    }

    /** 执行 core 决定的单条撤销；监听未连接时不谎报（详情照常打开，通知留在通知栏）。 */
    private fun cancelNotificationByKey(key: String): Boolean {
        val canceller = notificationKeyCanceller
        if (canceller == null) {
            Log.w(LOG_TAG, "cancel by key 未发出（监听服务未连接）key=$key")
            return false
        }
        val ok = canceller(key)
        Log.i(LOG_TAG, "cancel by key key=$key ok=$ok")
        return ok
    }

    fun onListenerConnected(count: Int) {
        Log.i(LOG_TAG, "listener connected active=$count")
        shadeVisibilityMonitor.request("listener-connected")
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
        shadeVisibilityMonitor.request("listener-disconnected")
    }

    fun onListenerPosted(notification: ActiveNotification) {
        shadeVisibilityGate.onPosted(notification)
        shadeVisibilityMonitor.request("posted")
    }

    fun onListenerRemoved(notification: ActiveNotification) {
        shadeVisibilityGate.onRemoved(notification)
        shadeVisibilityMonitor.request("removed")
    }

    fun onListenerSnapshot(active: List<ActiveNotification>) {
        shadeVisibilityGate.replaceSnapshot(active)
        shadeVisibilityMonitor.request("snapshot")
    }

    /** ranking 变化可能代表 SystemUI 分组/可见性过滤变化：触发一次校准。 */
    fun onListenerRankingUpdate() {
        shadeVisibilityMonitor.request("ranking")
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

    // ---------- 完整历史（spec 0018-7 / 票 #177）：回看当前会话从头到尾 ----------

    /** 桥来源会话取回的全量问答历史（按会话键缓存）；只进显示投影，core 仲裁事实不受影响。 */
    private val fullHistory = HashMap<String, List<AgentTurn>>()

    /** 已经为哪个显示会话取过全量（打开会话触发一次；切走再切回重取，自愈）。 */
    @Volatile
    private var historyFetchedFor: String? = null

    /**
     * 显示投影合入全量历史（票 #177）：实时窗口照旧来自事件流，取回的**更早**条目接到窗口
     * 前面——边界与去重收口在 [AgentTurns.withHistoryPrefix] 纯函数（同 ts 以实时为准，
     * 开放条原地增长不重复出现）。没有缓存原样透传（ZCode 源即此路径：翻到快照边界为止）。
     */
    private fun withFullHistory(state: AgentSessionState?): AgentSessionState? {
        if (state == null) return null
        val full = synchronized(fullHistory) { fullHistory[state.sessionId] } ?: return state
        return state.copy(turns = AgentTurns.withHistoryPrefix(full, state.turns))
    }

    /**
     * 打开会话取一次完整历史（票 #177）：仅桥来源（[AgentTurns.shouldFetchFullHistory]）；
     * 取失败/空答复保持现状、下次切回再试，不自动重试轰炸。回调回来后同点重发刷新显示。
     */
    private fun maybeFetchFullHistory(state: AgentSessionState?) {
        val id = state?.sessionId ?: return
        if (id == historyFetchedFor) return
        historyFetchedFor = id
        if (!AgentTurns.shouldFetchFullHistory(id)) return
        bridgeClient.fetchHistory(id) { turns ->
            if (turns.isNullOrEmpty()) return@fetchHistory
            synchronized(fullHistory) { fullHistory[id] = turns }
            scope.launch {
                refresh(
                    listenerConnected = _state.value.listenerConnected,
                    lastEvent = "bridge history session=$id turns=${turns.size}",
                )
            }
        }
    }

    private fun refresh(listenerConnected: Boolean, lastEvent: String) {
        val previous = _state.value
        val iconSet = core.iconSet.toList()
        // 背屏界面与调试页共用同一份 Icon Set（app → rear 单向依赖）；未读角标计数同点重发
        // （issue #101：角标数字与单/多切换的数据源，唯一事实是 core.unreadCounts 投影）。
        IconSetFeed.publish(iconSet, core.unreadCounts)
        // 充电动画面同点重发（spec 0007 票 #57）：core 的「充电理由 ∧ 在屏」投影是唯一事实，
        // 投送/更新/退出等一切内容产出路径都收口到本方法，每刷必发、不落旧值。
        ChargingFeed.publish(core.chargingOnScreen)
        // 电量读数同点重发（spec 0008 / 票 #67）：core 的 batteryPercent 投影——绿色比例
        // 填充与白色大号数字的数据源，随 BatteryLevel 事件刷新（68→69）。
        ChargingFeed.publishLevel(core.batteryPercent)
        // Detail View 同点重发（spec 0008 / 票 #66）：core.detail 的投影，卡片所示快照；
        // 无 Detail 时发 null（纯图标常态），撤屏/降级路径 core 已随之清、这里不落旧值。
        DetailFeed.publish(core.detail)
        // 内容页与 Agent 状态同点重发（spec 0013 / 票 #132）：图层开关取 core.contentPage
        // （通知页 / Agent 页；WFA 自动插队已在该投影内），投送/更新/退出/回落统一收口。
        // 完整历史（票 #177）：显示投影把取回的更早条目接到实时窗口前面（core 仲裁事实不动）。
        AgentFeed.publish(core.contentPage, withFullHistory(core.agentState))
        // 打开会话取一次完整历史（票 #177）：内部按会话键去重，取失败保持现状不轰炸。
        maybeFetchFullHistory(core.agentState)
        // PC 桥链路状态同点重发（票 #165）：背屏状态点读这一份，主屏两处读 AppState 里的同一值。
        AgentFeed.publishLink(bridgeLinkStatus)
        // 正文档位同点重发（spec 0017 / 票 #169）：背屏字号读这一份，主屏设置页选中态读
        // AppState 里的同一个值——改档即下一拍在屏 Agent 页按新档重排（即时生效）。
        AgentFeed.publishTextSize(core.mirrorTextSize)
        // 角部避让同点重发（spec 0019 / 票 #194）：背屏贴缘/避让读这一份——切换即下一拍
        // 在屏 Agent 会话页与会话列表按新口径重排（即时生效）。
        AgentFeed.publishCornerAvoidance(core.cornerAvoidanceEnabled)
        // 光带亮度倍率同点重发（spec 0021 修订 / 票 #214）：背屏光带读这一份，主屏滑动条读
        // AppState 里的同一个值——拖动即下一拍在屏光带按新倍率点亮（即时生效）。
        AgentFeed.publishGlowBrightness(glowBrightness)
        // 会话选择器同点重发（spec 0016 / 票 #156）：打开态取 core.agentPicker 投影（UI 不自行
        // 开关），条目取同一份列表投影（[AgentStateLogic.projectRoster]）的渲染映射——两屏同源。
        AgentFeed.publishPicker(core.agentPicker, agentPickerRows())
        // 批准浮层投影同点重发（spec 0018-5 / 票 #175）：入口判定全在 [AgentApprovePolicy]
        // （背屏零决策），失败提示与主屏同一份事实（成功即清）。
        AgentFeed.publishApprove(approvePrompt(), agentActionNote)
        _state.value = AppState(
            iconSet = iconSet,
            listenerConnected = listenerConnected,
            visibleNotificationCount = repository.currentNotifications.size,
            lastEvent = lastEvent,
            channelReady = channelReady,
            usabilityBanner = bannerReasons,
            postureFaceDown = postureFaceDown,
            postureGateEnabled = core.postureGateEnabled,
            castSource = core.castSource,
            chargingEnabled = core.chargingAnimationEnabled,
            agentBridgeConfigured = bridgeUrl != null,
            bridgeLinkStatus = bridgeLinkStatus,
            // 桥地址面（票 #171）：当前地址、来源（电脑推/手填/调试）、电脑最后推送时刻、手填探测结果。
            bridgeAddress = bridgeUrl.orEmpty(),
            bridgeAddressSource = bridgeAddressSource,
            bridgePushedAt = bridgePushedAt,
            bridgeAddressProbe = bridgeAddressProbe,
            agentEnabled = agentEnabled,
            // Session Lock 三项投影同点重发：镜像所示会话、当前档显示名、统一桥在册列表。
            agentState = core.agentState,
            sessionLock = core.sessionLock,
            sessionLockName = AgentStateLogic.lockTargetName(core.sessionLock, bridgeRoster(), retainedLockedSession),
            agentRoster = bridgeRoster(),
            // 正文档位（spec 0017 / 票 #169）：设置页选中态读它，与背屏字号同源。
            mirrorTextSize = core.mirrorTextSize,
            // 角部避让（spec 0019 / 票 #194）：设置页开关态读它，与背屏贴缘/避让同源。
            cornerAvoidance = core.cornerAvoidanceEnabled,
            // Agent 提醒两开关（spec 0018-3 / 票 #173）：设置页 Agent 区的展示面。
            agentAlertEnabled = agentAlertEnabled,
            agentAlertVibrate = agentAlertVibrate,
            agentApproveEnabled = agentApproveEnabled,
            // 光带亮度倍率（spec 0021 修订 / 票 #214）：设置页滑动条的展示面，与背屏光带同源。
            glowBrightness = glowBrightness,
            // Remote Approval 三项投影（spec 0018-4）：能力表（入口显隐）、伪会话（验收链）、
            // 失败提示（AC3 一次提示）。
            agentCapabilities = bridgeClient.capabilities(),
            agentDebugSession = lastDebugSession,
            agentActionNote = agentActionNote,
        )
        Log.i(
            LOG_TAG,
            "$lastEvent iconSet ${previous.iconSet} -> $iconSet visible=${_state.value.visibleNotificationCount}",
        )
    }
}

/**
 * 仓库事件 → DashboardCore 事件序列（spec 0008 反转 → 票 #65 收口，票 #66 补内容面）：
 * Posted/Removed 只喂 Icon Set 语义（[DashboardEvent.NotificationPosted]/
 * [DashboardEvent.NotificationRemoved]）；同 key 内容更新（[ActiveNotificationEvent.Updated]）
 * 改喂 [DashboardEvent.NotificationUpdated]——spec 0008 立新真相：Updated 是 Notification Highlight
 * 的触发源（含同 key 更新 ⇒ 呼吸），Icon Set 仍不重计（key 对账在 :notification，票 #50 判例继续成立：
 * 计数事件压根不含 Updated）。原「只喂 FeedPosted 刷横幅」的消费面随横幅退役删除。
 *
 * 票 #66：三类事件携带 notification key 与 title/text 快照（[ActiveNotification] 既有字段，
 * 内存内搬运——NLS extras 读出的隐私边界不越界），core 由此镜像「每 App 最新一条」做 Detail
 * 选择、并对账「所示 key 被清除自动收起」；接线层不做任何选择/对账决策。
 */
internal fun ActiveNotificationEvent.toCoreEvents(): List<DashboardEvent> = when (this) {
    is ActiveNotificationEvent.Posted -> listOf(
        DashboardEvent.NotificationPosted(
            pkg = notification.pkg,
            key = notification.key,
            title = notification.title,
            text = notification.text,
            fromSnapshot = fromSnapshot,
        ),
    )
    is ActiveNotificationEvent.Updated -> listOf(
        DashboardEvent.NotificationUpdated(
            pkg = notification.pkg,
            key = notification.key,
            title = notification.title,
            text = notification.text,
            fromSnapshot = fromSnapshot,
        ),
    )
    is ActiveNotificationEvent.Removed -> listOf(
        DashboardEvent.NotificationRemoved(pkg = notification.pkg, key = notification.key),
    )
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
