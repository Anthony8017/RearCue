package com.rearcue.poc

import android.app.Application
import android.content.Context
import android.util.Log
import com.rearcue.poc.core.PocAllowlist
import com.rearcue.poc.notification.ActiveNotification
import com.rearcue.poc.notification.ActiveNotificationEvent
import com.rearcue.poc.notification.ActiveNotificationListener
import com.rearcue.poc.notification.NotificationRepository
import com.rearcue.poc.notify.ensureTestChannel
import kotlinx.coroutines.flow.MutableStateFlow

/** 日志 TAG：设备实验（`adb logcat -s RearCue`）的观测面。 */
const val LOG_TAG = "RearCue"

/**
 * 进程级接线（POC 期不引 DI 框架）。
 *
 * [repository] 是 Active Notification 的唯一事实来源（见 CONTEXT.md）：
 * NotificationListenerService 喂事件、MainActivity 读状态，两侧共享本容器。
 */
class AppContainer(private val context: Context) {

    val repository = NotificationRepository()

    /** Icon Set：当前存在 Active Notification 的 Allowlist App，按首次出现顺序。 */
    val iconSet = MutableStateFlow<List<String>>(emptyList())

    /** 监听服务是否已连接（未授权通知使用权时为 false）。 */
    val listenerConnected = MutableStateFlow(false)

    /** 仓库跟踪的通知总枚数（含非 Allowlist 应用）。 */
    val trackedCount = MutableStateFlow(0)

    /** 最近一次变化的原因，供调试页与 logcat 展示。 */
    val lastCause = MutableStateFlow("-")

    init {
        repository.subscribe(ActiveNotificationListener(::onEvent))
        ensureTestChannel(context)
    }

    // ---------- 监听服务入口 ----------

    fun onListenerConnected(count: Int) {
        listenerConnected.value = true
        lastCause.value = "listener-connected"
        Log.i(LOG_TAG, "listener connected active=$count")
        refresh()
    }

    fun onListenerDisconnected() {
        listenerConnected.value = false
        lastCause.value = "listener-disconnected"
        Log.w(LOG_TAG, "listener disconnected iconSet=${iconSet.value} tracked=${trackedCount.value}")
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

    private fun onEvent(event: ActiveNotificationEvent) {
        val before = iconSet.value
        lastCause.value = when (event) {
            is ActiveNotificationEvent.Posted -> "posted ${event.notification.pkg}"
            is ActiveNotificationEvent.Removed -> "removed ${event.notification.pkg}"
            is ActiveNotificationEvent.SnapshotReplaced -> "snapshot ${event.notifications.size}"
        }
        refresh()
        Log.i(
            LOG_TAG,
            "${lastCause.value} iconSet $before -> ${iconSet.value} tracked=${repository.currentKeys.size}",
        )
    }

    private fun refresh() {
        trackedCount.value = repository.currentKeys.size
        iconSet.value = repository.currentPackages.filter { it in PocAllowlist.APPS }
    }
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
