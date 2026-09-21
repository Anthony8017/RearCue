package com.rearcue.poc.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.rearcue.poc.LOG_TAG
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.notification.ActiveNotification

/**
 * 通知监听胶水：把 NotificationListenerService 回调翻译成 `NotificationRepository` 调用。
 *
 * 这里不做决策，只搬「系统信号 → 仓库事件」；汇聚与去重在 `:notification`（JVM 可测），
 * Dashboard 决策在 `:core`。
 */
class RearNotificationListener : NotificationListenerService() {

    private val container get() = (applicationContext as RearCueApp).container

    override fun onListenerConnected() {
        super.onListenerConnected()
        val active = try {
            activeNotifications?.toList().orEmpty()
        } catch (e: SecurityException) {
            // 断连瞬间系统会拒答；下一次 onListenerConnected 会全量对齐。
            Log.w(LOG_TAG, "getActiveNotifications denied", e)
            emptyList()
        }
        container.onListenerConnected(active.size)
        container.onListenerSnapshot(active.mapNotNull(::toActiveNotification))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        container.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val active = sbn?.let(::toActiveNotification) ?: return
        container.onListenerPosted(active.pkg, active.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val active = sbn?.let(::toActiveNotification) ?: return
        container.onListenerRemoved(active.pkg, active.key)
    }

    private fun toActiveNotification(sbn: StatusBarNotification): ActiveNotification? {
        val pkg = sbn.packageName
        val key = sbn.key
        if (pkg.isNullOrEmpty() || key.isNullOrEmpty()) return null
        return ActiveNotification(pkg = pkg, key = key)
    }
}
