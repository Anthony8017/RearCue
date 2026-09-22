package com.rearcue.poc

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEffect
import com.rearcue.poc.core.DashboardEvent
import com.rearcue.poc.notification.ActiveNotification
import com.rearcue.poc.notification.ActiveNotificationEvent
import com.rearcue.poc.notification.ActiveNotificationListener
import com.rearcue.poc.notification.NotificationRepository
import com.rearcue.poc.notify.ensureTestChannel
import com.rearcue.poc.rear.HyperOsRearDisplayBackend
import com.rearcue.poc.rear.IconSetFeed
import com.rearcue.poc.rear.RearDisplayBackend
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
    /** 仓库跟踪的通知总枚数（含非 Allowlist 应用）。 */
    val trackedCount: Int = 0,
    /** 最近一次变化，供调试页与 logcat 展示。 */
    val lastEvent: String = "-",
    /** 投送通道是否就绪（识别到背屏）：就绪后通知事件会自动上/下屏（票 #5）。 */
    val channelReady: Boolean = false,
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
    val core = DashboardCore()

    /** 背屏后端：HyperOS 专有投送操作全在实现里（票 #4）。 */
    val rearBackend: RearDisplayBackend = HyperOsRearDisplayBackend(context)

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** 投送通道是否就绪；只在与上次不同时喂 DashboardCore（避免重复重投）。 */
    private var channelReady = false

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

    /** Shizuku 授权成功 / server 上线：兜底通道可用即按当前 Icon Set 重投一次（幂等）。 */
    private fun onFallbackChanged(available: Boolean) {
        val wasReady = channelReady
        syncChannel()
        when {
            available && channelReady && core.iconSet.isNotEmpty() -> {
                Log.i(LOG_TAG, "兜底通道恢复：重投当前 Icon Set ${core.iconSet}")
                rearBackend.project(core.iconSet)
            }
            !available && wasReady ->
                // 主路径是应用内投送，Shizuku 掉线不影响背屏内容（CONTEXT.md「投送通道」）。
                Log.i(LOG_TAG, "兜底通道掉线：Dashboard 不受影响（应用内投送）")
        }
    }

    /**
     * 背屏信号（锁屏/AOD 抢回/解锁）→ Takeover 事件：DashboardCore 只在「该显示但被顶掉」时重投，
     * 无通知或已退出时这里空转一次（效果列表为空）。
     */
    private fun onRearSignal(action: String) {
        val applied = dispatch(core.onEvent(DashboardEvent.TakeoverDetected))
        Log.i(LOG_TAG, "背屏信号 $action → ${applied.ifEmpty { listOf("无需重投") }.joinToString("+")}")
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = "signal $action" + applied.describe(),
        )
    }

    /**
     * 效果 → 背屏动作：上屏/更新/退出/降级的决策在 DashboardCore，这里只搬运。
     *
     * 每条效果返回日志短名（[DashboardEffect.label]），进 logcat 与调试页——E7 的验收面。
     */
    private fun dispatch(effects: List<DashboardEffect>): List<String> = effects.map { effect ->
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
        }
        effect.label
    }

    // ---------- 手动入口（主屏调试页；直连后端，绕过 DashboardCore 的自动流转） ----------

    /** 调试用：把当前 Icon Set 投到背屏（无通知时投空集，只显示纯黑 + 时间）。 */
    fun projectToRear() {
        Log.i(LOG_TAG, "手动投送背屏 iconSet=${core.iconSet}")
        rearBackend.project(core.iconSet)
    }

    /**
     * 调试用：手动退出背屏 Dashboard（只结束界面，监听照常）。
     *
     * 注意：这是绕过 core 的旁路，core 仍以为 Dashboard 在屏；下一条通知改变 Icon Set 时
     * 会经 `update()` 的自愈重投把它拉回屏上（有通知就该在屏，符合本应用语义）。
     */
    fun exitRear() {
        Log.i(LOG_TAG, "手动退出背屏 Dashboard")
        rearBackend.exit()
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
        refresh(listenerConnected = true, lastEvent = "listener-connected active=$count")
    }
    fun onListenerDisconnected() {
        Log.w(LOG_TAG, "listener disconnected iconSet=${_state.value.iconSet} tracked=${repository.currentNotifications.size}")
        refresh(listenerConnected = false, lastEvent = "listener-disconnected")
    }

    fun onListenerPosted(pkg: String, key: String) {
        repository.onPosted(ActiveNotification(pkg, key))
    }

    fun onListenerRemoved(pkg: String, key: String) {
        repository.onRemoved(ActiveNotification(pkg, key))
    }

    fun onListenerSnapshot(active: List<ActiveNotification>) {
        repository.replaceSnapshot(active)
    }

    // ---------- 状态广播 ----------

    /** 仓库事件 → DashboardCore 事件 → 效果（同一批通知喂两个纯 Kotlin 组件）。 */
    private fun onNotificationEvent(event: ActiveNotificationEvent) {
        val effects = event.toCoreEvent()?.let(core::onEvent).orEmpty()
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
        _state.value = AppState(
            iconSet = iconSet,
            listenerConnected = listenerConnected,
            trackedCount = repository.currentNotifications.size,
            lastEvent = lastEvent,
            channelReady = channelReady,
        )
        Log.i(
            LOG_TAG,
            "$lastEvent iconSet ${previous.iconSet} -> $iconSet tracked=${_state.value.trackedCount}",
        )
    }
}

private fun ActiveNotificationEvent.toCoreEvent(): DashboardEvent? = when (this) {
    is ActiveNotificationEvent.Posted -> DashboardEvent.NotificationPosted(notification.pkg)
    is ActiveNotificationEvent.Removed -> DashboardEvent.NotificationRemoved(notification.pkg)
    is ActiveNotificationEvent.SnapshotReplaced -> null
}

/** 事件摘要：进日志与调试页（E7 的验收面）。 */
private fun ActiveNotificationEvent.describe(): String = when (this) {
    is ActiveNotificationEvent.Posted -> "posted ${notification.pkg}"
    is ActiveNotificationEvent.Removed -> "removed ${notification.pkg}"
    is ActiveNotificationEvent.SnapshotReplaced -> "snapshot ${notifications.size}"
}

/** 效果摘要：投送链路是否自动触发，一眼可查。 */
private fun List<String>.describe(): String =
    if (isEmpty()) "" else " → " + joinToString("+")

class RearCueApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Log.i(LOG_TAG, "app start")
    }
}
