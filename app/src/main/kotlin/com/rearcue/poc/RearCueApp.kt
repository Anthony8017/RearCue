package com.rearcue.poc

import android.app.Application
import android.content.Context
import android.util.Log
import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEvent
import com.rearcue.poc.notification.ActiveNotification
import com.rearcue.poc.notification.ActiveNotificationEvent
import com.rearcue.poc.notification.ActiveNotificationListener
import com.rearcue.poc.notification.NotificationRepository
import com.rearcue.poc.notify.ensureTestChannel
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
)

/**
 * 进程级接线（POC 期不引 DI 框架）：Android 层只做「系统信号 → 事件 → 效果/状态」的搬运。
 *
 * [repository] 维护按 notification key 去重的 Active Notification 集合（:notification），
 * [core] 决定 Icon Set 与投送效果（:core）。两者吃同一批通知事件，因此不会互相漂移。
 */
class AppContainer(private val context: Context) {

    val repository = NotificationRepository()

    /** 决策核心：Icon Set 由它算，投送效果（票 #4/#5）也由它出。 */
    val core = DashboardCore()

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        repository.subscribe(ActiveNotificationListener(::onNotificationEvent))
        ensureTestChannel(context)
    }

    // ---------- 监听服务入口 ----------

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

    /** 仓库事件 → DashboardCore 事件（同一批通知喂两个纯 Kotlin 组件）。 */
    private fun onNotificationEvent(event: ActiveNotificationEvent) {
        event.toCoreEvent()?.let { core.onEvent(it) }
        refresh(
            listenerConnected = _state.value.listenerConnected,
            lastEvent = when (event) {
                is ActiveNotificationEvent.Posted -> "posted ${event.notification.pkg}"
                is ActiveNotificationEvent.Removed -> "removed ${event.notification.pkg}"
                is ActiveNotificationEvent.SnapshotReplaced -> "snapshot ${event.notifications.size}"
            },
        )
    }

    private fun refresh(listenerConnected: Boolean, lastEvent: String) {
        val previous = _state.value
        _state.value = AppState(
            // DashboardCore 的 activeCounts 是 LinkedHashMap：Icon Set 顺序 = 首次出现顺序，稳定不跳。
            iconSet = core.iconSet.toList(),
            listenerConnected = listenerConnected,
            trackedCount = repository.currentNotifications.size,
            lastEvent = lastEvent,
        )
        Log.i(
            LOG_TAG,
            "$lastEvent iconSet ${previous.iconSet} -> ${_state.value.iconSet} tracked=${_state.value.trackedCount}",
        )
    }
}

private fun ActiveNotificationEvent.toCoreEvent(): DashboardEvent? = when (this) {
    is ActiveNotificationEvent.Posted -> DashboardEvent.NotificationPosted(notification.pkg)
    is ActiveNotificationEvent.Removed -> DashboardEvent.NotificationRemoved(notification.pkg)
    is ActiveNotificationEvent.SnapshotReplaced -> null
}

class RearCueApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Log.i(LOG_TAG, "app start")
    }
}
