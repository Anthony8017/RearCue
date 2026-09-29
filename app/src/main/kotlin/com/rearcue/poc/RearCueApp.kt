package com.rearcue.poc

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import com.rearcue.poc.agent.BridgeRelayClient
import com.rearcue.poc.agent.PairingLink
import com.rearcue.poc.agent.SessionIndexEntry
import com.rearcue.poc.agent.TaskListParser
import com.rearcue.poc.agent.V4Bridge
import com.rearcue.poc.agentmirror.AgentLinkStore
import com.rearcue.poc.agentmirror.AgentLinkStatus
import com.rearcue.poc.agentmirror.AgentRelayClient
import com.rearcue.poc.agentmirror.AgentStateLogic
import com.rearcue.poc.agentmirror.BridgeLinkStore
import com.rearcue.poc.agentmirror.SessionLockStore
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import com.rearcue.poc.autostart.readAutostartState
import com.rearcue.poc.charging.BatterySignals
import com.rearcue.poc.charging.ChargingSettingsStore
import com.rearcue.poc.charging.PowerSignals
import com.rearcue.poc.core.CastSource
import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEffect
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent
import com.rearcue.poc.core.UsabilityReason
import com.rearcue.poc.notification.ActiveNotification
import com.rearcue.poc.notification.ActiveNotificationEvent
import com.rearcue.poc.notification.ActiveNotificationListener
import com.rearcue.poc.notification.NotificationRepository
import com.rearcue.poc.notification.ShadeVisibleNotificationGate
import com.rearcue.poc.notify.RearNotificationListener
import com.rearcue.poc.notify.ensureTestChannel
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
    /** Agent 镜像：已配对、总开关、链路状态（spec 0010 / 票 #81，设置页 Agent 区的展示面）。 */
    val agentPaired: Boolean = false,
    val agentEnabled: Boolean = AgentLinkStore.ENABLED_DEFAULT,
    val agentLinkStatus: AgentLinkStatus = AgentLinkStatus.UNPAIRED,
    /** 镜像所示会话（spec 0010 / 票 #82）：core 仲裁后的选择，状态行与背屏共源。
     *  票 #104 补投：`refresh()` 此前从未赋值（恒 null），主屏列表与状态行拿不到 core 投影。 */
    val agentState: AgentSessionState? = null,
    /** Session Lock 当前档（票 #104）：core.sessionLock 投影——状态行文案与列表选中同源，
     *  改档仍只走 [setSessionLock] 写入口（读侧零决策）。 */
    val sessionLock: SessionLockMode = SessionLockMode.Auto,
    /** 任务表全量会话（票 #104）：parseAll 原序 × v4 等确认归一后的列表面（[AgentStateLogic]）。 */
    val agentRoster: List<AgentSessionState> = emptyList(),
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

    /** 配对凭据在案（设置页展示面：有 → 状态行+解除配对，无 → 粘贴入口）。 */
    @Volatile
    private var agentPaired = false

    @Volatile
    private var agentEnabled = AgentLinkStore.ENABLED_DEFAULT

    @Volatile
    private var agentLinkStatus = AgentLinkStatus.UNPAIRED

    /** 任务表状态源（票 #86 实测轮询驱动）——与 v4 帧状态合并后进 core。 */
    @Volatile
    private var lastTaskState: AgentSessionState? = null

    /** v4 帧状态源（票 #88：回复原文 + pendingApproval 真检测）。 */
    @Volatile
    private var lastV4State: AgentSessionState? = null

    /**
     * 任务表全量会话（票 #104）：parseAll 结果暂存——主屏会话列表（[AppState.agentRoster]）
     * 的数据源。与 [lastTaskState] 同为控制面帧记账；refresh 时叠 [lastV4State] 归一
     * （任务表无等待语义，等确认只能从 v4 帧来）。
     */
    @Volatile
    private var lastRoster: List<AgentSessionState> = emptyList()

    /**
     * sessions-index 等待视图（票 #103 P0）：全工作区会话的等待确认真读数（任务表没有等待
     * 语义、v4 帧只覆盖单订阅会话——锁档下他会话进等待只有这条链）。传输线程写、主线程读。
     */
    @Volatile
    private var lastIndexEntries: List<SessionIndexEntry> = emptyList()

    /**
     * 上次以「等确认」送进 core 的在册会话键（索引口径）：进出集在 [dispatchAgentMerged]
     * 内成对计算——进＝插队到达、出＝处理完回锁（只在传输线程读写，@Volatile 兜可见性）。
     */
    @Volatile
    private var indexWaitingDispatched: Set<String> = emptySet()

    /**
     * Session Lock 当前档位镜像（票 #103）：与 core 的 sessionLock 投影同写，@Volatile 供
     * V4Bridge 在传输线程经 [lockedTaskId] 取锁定会话键（core 本身单线程记账，不跨线程暴露）。
     */
    @Volatile
    private var sessionLockMode: SessionLockMode = SessionLockMode.Auto

    /**
     * V4 workspace-bridge 编排（票 #88）：控制面开桥 + 二进制通道握手/订阅/帧归一。
     * 回调在传输线程；出站走 [agentClient]（内部 synchronized），状态合并归
     * [dispatchAgentMerged]。锁定档订阅跟随（票 #103）经 [lockedTaskId] 读 [sessionLockMode]。
     */
    private val v4Bridge: V4Bridge = V4Bridge(
        sendControl = { payload -> agentClient.sendControlPayload(payload) },
        sendChannel = { bytes -> agentClient.sendChannelMessage(bytes) },
        onBridgeSession = { id, gen -> agentClient.setBridge(id, gen) },
        onState = { state ->
            lastV4State = state
            dispatchAgentMerged("agent-v4")
        },
        log = { line -> Log.i(LOG_TAG, line) },
        lockedTaskId = { (sessionLockMode as? SessionLockMode.Locked)?.sessionId },
        // 索引等待视图到达（票 #103 P0）：只在等待集进出时补发/刷新（帧本身常驻无变化不刷屏）。
        onIndex = { entries ->
            val before = indexWaitingIds()
            lastIndexEntries = entries
            if (indexWaitingIds() != before) dispatchAgentMerged("agent-index")
        },
    )

    /**
     * 中继客户端（spec 0010）：链路生命周期与退避重连全在 [AgentRelayClient]，本层只把
     * 链路事实翻译成 core 事件、把状态投影进 AppState。会话消息经 [agentFeed]
     * 归一 → [DashboardEvent.AgentSessionUpdated]（票 #82）。
     *
     * 连接事实是**多源 OR**（ADR 0006 两通道）：ZCode 直连与 PC 桥任一在线即「连接在线」，
     * 全部离线才回落——两个客户端各自的 onLinkUp/onLinkDown 更新自己的旗标后重算。
     */
    val agentClient = AgentRelayClient(log = { line -> Log.i(LOG_TAG, line) }).apply {
        onLinkUp = {
            scope.launch {
                zcodeLinkUp = true
                feedConnectionFromSources()
                // 每次上线重建桥会话（票 #88）；任务表轮询（票 #86 实测：workspace-list 响应
                // ~0.7s，随桌面会话实时更新）同时是桥入口——首个响应触发 bridge-open。
                v4Bridge.reset()
                this@apply.sendControlPayload(TaskListParser.listRequest("bootstrap"))
                while (coroutineContext.isActive) {
                    this@apply.sendControlPayload(TaskListParser.listRequest("poll"))
                    delay(10_000)
                }
            }
        }
        onLinkDown = {
            v4Bridge.reset()
            lastV4State = null
            // 索引等待视图随链路失效（数据源没了就不留过期等确认）；断连本身已整体回落，
            // 重连后的首个快照按进出集重新补发。
            lastIndexEntries = emptyList()
            indexWaitingDispatched = emptySet()
            scope.launch {
                zcodeLinkUp = false
                feedConnectionFromSources()
            }
        }
        onStatusChanged = { s: AgentLinkStatus ->
            scope.launch {
                agentLinkStatus = s
                refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "agent link $s")
            }
        }
        onChannelMessage = { bytes -> v4Bridge.onChannel(bytes) }
        onControlMessage = { text ->
            // 控制面帧（票 #86 phase B 实测）：任务表响应 → AgentSessionUpdated（一期镜像状态源）；
            // 全量进 logcat 供验收抓取；workspaceKey 暂存给探针用。
            Log.i(LOG_TAG, "agent control ${text.take(160)}")
            captureWorkspaceKey(text)
            // 同一次 parse-all 喂两条消费面（票 #86 单条状态源 + 票 #103 在册对账）：
            // 取 updatedAt 最大者＝原 parse 口径（[TaskListParser.latest] 单处派生）；
            // 全量会话键交 core 判定锁定是否还在册。先记在册再派状态——索引等待的进出
            // 补发要拿最新名册算交集（票 #103 P0），同一帧内不落后一拍。
            TaskListParser.parseAll(text)?.let { roster ->
                feedAgentRoster(roster)
                TaskListParser.latest(roster)?.let { state ->
                    lastTaskState = state
                    dispatchAgentMerged("agent-task")
                }
            }
            v4Bridge.onControl(text)
        }
    }

    /**
     * PC 桥客户端（ADR 0006 / 票 #116）：对桥的长轮询接入——第二条通道，与 ZCode 直连并存。
     * 会话事实直进 core（桥已归一，无需与任务表/v4 合并）；链路旗标并入多源 OR。
     * 生命周期由 [reconcileBridge] 收口（Agent Mirror 总开关 ∧ 已配置 URL 才起）。
     */
    val bridgeClient = BridgeRelayClient(log = { line -> Log.i(LOG_TAG, line) }).apply {
        onLinkUp = {
            scope.launch {
                bridgeLinkUp = true
                feedConnectionFromSources()
            }
        }
        onLinkDown = {
            scope.launch {
                bridgeLinkUp = false
                feedConnectionFromSources()
            }
        }
        onSession = { state ->
            scope.launch {
                val applied = dispatch(core.onEvent(DashboardEvent.AgentSessionUpdated(state)))
                refresh(
                    listenerConnected = _state.value.listenerConnected,
                    lastEvent = "bridge ${state.status.name.lowercase()}" + applied.describe(),
                )
            }
        }
    }

    /** 多源连接旗标（ADR 0006）：任一通道在线即在线。 */
    @Volatile
    private var zcodeLinkUp = false

    @Volatile
    private var bridgeLinkUp = false

    /** 桥 URL（内存镜像；落盘在 [BridgeLinkStore]）。 */
    @Volatile
    private var bridgeUrl: String? = null

    /** 探针用：从 bootstrap/workspace-list 响应里记下 workspaceKey（activeWorkspaceKey 优先）。 */
    @Volatile
    private var probeWorkspaceKey: String? = null

    private fun captureWorkspaceKey(text: String) {
        runCatching {
            val obj = com.rearcue.poc.agent.RelayEnvelope.parseObject(text) ?: return
            val result = obj["result"] as? kotlinx.serialization.json.JsonObject ?: return
            val key = (result["activeWorkspaceKey"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (result["workspaces"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()
                    ?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("workspacePath") }
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            if (!key.isNullOrBlank()) probeWorkspaceKey = key
        }
    }

    /**
     * 控制面探针（DebugCommandReceiver.AGENT_PROBE，spec 0010 / 票 #86 phase B）：在线状态下
     * 依次发 bootstrap-request → workspace-list-request → workspace-bridge-open → v4 订阅，
     * 响应经 onControlMessage 全量进 logcat——为 T4 订阅接线回填精确 wire 形态。
     */
    fun debugAgentProbe() {
        scope.launch {
            agentClient.sendControlPayload("""{"zcode_type":"bootstrap-request","requestId":"probe-b1"}""")
            delay(4_000)
            agentClient.sendControlPayload("""{"zcode_type":"workspace-list-request","requestId":"probe-w1"}""")
            delay(5_000)
            val key = probeWorkspaceKey
            Log.i(LOG_TAG, "agent probe bridge-open key=$key")
            agentClient.sendControlPayload(
                """{"zcode_type":"workspace-bridge-open","requestId":"probe-o1","bridgeSessionId":"rearcue-bridge-1","workspaceKey":"${key ?: "unknown"}"}""",
            )
            delay(5_000)
            // v4 通道订阅不再走 JSON 逻辑消息（票 #88 wire 实证：桥上是二进制通道协议）——
            // 正常路径由 v4Bridge 自动握手；探针只验控制面。
        }
    }

    /**
     * 任务表状态（票 #86）与 v4 帧状态（票 #88）合并进 core，另按 sessions-index 等待视图
     * 补发（票 #103 P0）：口径抽成纯函数 [AgentStateLogic]（票 #104 与会话列表归一共用同一套
     * 优先级，JVM 单测锁死）——等待批准（v4 pendingApproval / 索引 pendingInteraction 真检测）
     * > 任一来源报进行中 > 空闲；回复原文取 v4、当前动作取任务表标题。会话键不一致（任务
     * 刚切换、v4 尚未重订阅）时先用任务表。
     *
     * 单条口径逐字不变（自动档无回归），只做两件叠加：
     * - 单条在册且索引判等 ⇒ 状态位上调等确认（锁档下插队的到达）；
     * - **进出补发**：单条源只覆盖「任务表最新一条」，锁档把订阅钉在锁会话时他会话 B 的
     *   等待帧到不了 core——索引等待集进（B 首次判等）/出（处理完）各补一条状态，
     *   插队→回锁在真实链路闭合；不在册的会话不进（索引含已归档，交集在 [indexWaitingIds]）。
     */
    private fun dispatchAgentMerged(source: String) {
        val batch = AgentStateLogic.dispatchBatch(
            roster = lastRoster,
            task = AgentStateLogic.merge(lastTaskState, lastV4State),
            v4 = lastV4State,
            indexEntries = lastIndexEntries,
            dispatchedWaiting = indexWaitingDispatched,
        )
        indexWaitingDispatched = batch.waitingDispatched
        if (batch.states.isEmpty()) return
        val status = batch.states.first().status
        scope.launch {
            var applied = emptyList<String>()
            for (state in batch.states) {
                applied = applied + dispatch(core.onEvent(DashboardEvent.AgentSessionUpdated(state)))
            }
            refresh(
                listenerConnected = _state.value.listenerConnected,
                lastEvent = "$source ${status.name.lowercase()}" + applied.describe(),
            )
        }
    }

    /** 索引等待集（索引判等 ∩ 任务表在册）：补发进出与列表三态的同一取值。 */
    private fun indexWaitingIds(): Set<String> = AgentStateLogic.indexWaitingIds(lastIndexEntries, lastRoster)

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
     * 任务表在册对账（票 #103）：parse-all 结果喂 core——**锁定的会话从任务表消失即由
     * DashboardCore 自动清锁退回自动**（决策在状态机，JVM 判例锁死）；清锁发生时同步写盘
     * （偏好持久化跟随：重启不复活已被任务表除名的锁）。无锁定变化时不刷屏（轮询 10s 一次，
     * 刷新由同帧的 [dispatchAgentMerged] 承担）。
     *
     * 票 #104 加列表面：全量暂存 [lastRoster] 供 [AppState.agentRoster] 投影；在册有变但
     * 没触发清锁时单独刷一次——兜住「表空／单条状态源没变」而 [dispatchAgentMerged] 不刷的空窗。
     */
    private fun feedAgentRoster(roster: List<AgentSessionState>) {
        val changed = roster != lastRoster
        lastRoster = roster
        val sessionIds = roster.mapTo(mutableSetOf()) { it.sessionId }
        scope.launch {
            val before = core.sessionLock
            val applied = dispatch(core.onEvent(DashboardEvent.AgentRoster(sessionIds)))
            if (core.sessionLock != before) {
                sessionLockMode = core.sessionLock
                SessionLockStore.save(context, core.sessionLock)
                // 词形卫生：core 的契约锚 `session lock cleared <id>`（byte 不可改）由状态机打，
                // 本层只记「写盘跟随」这一拍——换措辞避免复用契约前缀被验收链误读。
                Log.i(LOG_TAG, "lock auto-cleared: locked session left the task table → auto")
                refresh(
                    listenerConnected = _state.value.listenerConnected,
                    lastEvent = "session-lock cleared" + applied.describe(),
                )
            } else if (changed) {
                refresh(
                    listenerConnected = _state.value.listenerConnected,
                    lastEvent = "agent-roster size=${roster.size}",
                )
            }
        }
    }

    /**
     * 多源连接事实重算（ADR 0006 两通道）：ZCode 直连与 PC 桥任一在线即「在线」，
     * 全部离线才报失联（一个通道掉线不误伤另一个通道在屏的镜像）。
     */
    private fun feedConnectionFromSources() = feedAgentConnection(zcodeLinkUp || bridgeLinkUp)

    /**
     * 桥生命周期收口（ADR 0006 / 票 #116）：Agent Mirror 总开关 ∧ 已配置 URL 才起链路——
     * 与 ZCode 客户端共用总开关（一个开关管整个镜像面），URL 缺失即不起（未配置态零打扰）。
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
     * 桥 URL 写入口（DebugCommandReceiver.BRIDGE_URL；设置页入口后续可挂同一函数）：
     * 落盘 + 即时起停（事件面收口在 [reconcileBridge]，本层零决策）。
     */
    fun setBridgeUrl(url: String?) {
        bridgeUrl = url?.trim()?.takeIf { it.isNotEmpty() }
        reconcileBridge()
        scope.launch { BridgeLinkStore.save(context, bridgeUrl) }
        Log.i(LOG_TAG, "bridge url set has=${bridgeUrl != null}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = if (bridgeUrl != null) "bridge-url set" else "bridge-url cleared",
        )
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
        // Agent Mirror 首读（spec 0010 / 票 #81）：有凭据且开关开 → 起链路（退避重连在 client）；
        // 开关关 → 记停用；未配对 → 状态行保持未配对。
        scope.launch {
            agentEnabled = AgentLinkStore.loadEnabled(context)
            val link = AgentLinkStore.load(context)
            agentPaired = link != null
            if (link != null && agentEnabled) {
                agentClient.start(link)
            } else {
                agentLinkStatus = if (link == null) AgentLinkStatus.UNPAIRED else AgentLinkStatus.DISABLED
                refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "agent idle")
            }
            // PC 桥首读（ADR 0006 / 票 #116）：有 URL 且总开关开 → 起长轮询（断线退避在 client）。
            bridgeUrl = BridgeLinkStore.load(context)
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

    // ---------- Agent Mirror 公共入口（spec 0010 / 票 #81：粘贴即配对、解除、总开关） ----------

    /**
     * 粘贴链接配对：解析合法 → 落盘 + 起链路，返回 true；非法输入返回 false（UI 就地提示，
     * 这里不吐原因——解析细节在 [PairingLink.parse]）。**任何路径不打印链接内容**（凭据红线）。
     */
    fun pairAgent(rawLink: String): Boolean {
        val link = try {
            PairingLink.parse(rawLink)
        } catch (_: IllegalArgumentException) {
            Log.i(LOG_TAG, "agent pair rejected: invalid link")
            return false
        }
        agentPaired = true
        agentEnabled = true
        scope.launch { AgentLinkStore.save(context, link) }
        agentClient.start(link)
        Log.i(LOG_TAG, "agent paired ${AgentLinkStore.describe(link)}")
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "agent-paired")
        return true
    }

    /** 解除配对：凭据清除 + 链路停机（core 收到失联事件后按仲裁回落）。 */
    fun unpairAgent() {
        agentPaired = false
        scope.launch { AgentLinkStore.clear(context) }
        agentClient.stop()
        agentLinkStatus = AgentLinkStatus.UNPAIRED
        Log.i(LOG_TAG, "agent unpaired")
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "agent-unpaired")
    }

    /** 总开关（spec 0010 story 3，默认开）：关 = 断链回落（等价失联）；开且已配对 = 恢复链路。 */
    fun setAgentMirrorEnabled(enabled: Boolean) {
        agentEnabled = enabled
        scope.launch { AgentLinkStore.saveEnabled(context, enabled) }
        if (enabled) {
            scope.launch {
                AgentLinkStore.load(context)?.let { link ->
                    agentClient.start(link)
                    Log.i(LOG_TAG, "agent resumed ${AgentLinkStore.describe(link)}")
                }
            }
        } else {
            agentClient.stop()
            agentLinkStatus = AgentLinkStatus.DISABLED
            Log.i(LOG_TAG, "agent disabled")
        }
        reconcileBridge() // 桥随总开关一起起停（一个开关管整个镜像面）
        refresh(listenerConnected = _state.value.listenerConnected, lastEvent = "agent-enabled=$enabled")
    }

    // ---------- Session Lock（票 #103：存储与写入口都走同一个事件，决策在 core） ----------

    /**
     * 锁定档位存储值对齐：喂 [DashboardEvent.SessionLock]——档位语义（显示谁、等确认插队、
     * 锁会话空闲回落常规内容、在册对账清锁）全在 DashboardCore；与 core 初值相同（首读常态）
     * 时无任何效果。每次换档同步驱动 V4Bridge 的订阅跟随（锁定换向即换订阅，绕过 30s 节流）。
     */
    private fun applySessionLock(mode: SessionLockMode) {
        sessionLockMode = mode
        val applied = dispatch(core.onEvent(DashboardEvent.SessionLock(mode)))
        v4Bridge.onSessionLockChanged()
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
     */
    fun debugInjectAgentState(status: String, workspace: String?, action: String?, reply: String?) {
        val agentStatus = when (status) {
            "working" -> AgentStatus.WORKING
            "waiting" -> AgentStatus.WAITING_FOR_APPROVAL
            "idle" -> AgentStatus.IDLE
            else -> {
                Log.w(LOG_TAG, "debug agent state 忽略未知 status=$status")
                return
            }
        }
        val state = AgentSessionState(
            sessionId = DEBUG_SESSION_ID,
            workspace = workspace,
            status = agentStatus,
            currentAction = action,
            latestReply = reply,
            updatedAt = System.currentTimeMillis(),
        )
        val applied = dispatch(core.onEvent(DashboardEvent.AgentSessionUpdated(state)))
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "agent-debug $status" + applied.describe(),
        )
    }

    /** 注入链路事实（断连回落/恢复演示）：同 [feedAgentConnection] 的真实路径。 */
    fun debugInjectAgentConnection(connected: Boolean) = feedAgentConnection(connected)

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
        const val DEBUG_SESSION_ID = "debug"
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
        AgentFeed.publish(core.contentPage, core.agentState)
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
            agentPaired = agentPaired,
            agentEnabled = agentEnabled,
            agentLinkStatus = agentLinkStatus,
            // Session Lock（票 #104）三项投影同点重发：镜像所示会话、当前档、会话列表——
            // 与背屏 AgentFeed.publish 同一 core 事实，主屏与背屏不各记一份（agentState 此前
            // 从未赋值的缺口在此补上；列表是接线层 parseAll 记账 × v4 归一，core 只收会话键）。
            agentState = core.agentState,
            sessionLock = core.sessionLock,
            agentRoster = AgentStateLogic.normalizeRoster(lastRoster, lastV4State, indexWaitingIds()),
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
