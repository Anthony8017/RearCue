package com.rearcue.poc.notify

import android.content.ComponentName
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
        container.onCancellerChanged(::cancelAllOf)
        val active = activeNotificationsOrNull()
        container.onListenerConnected(active?.size ?: -1)
        if (active != null) {
            container.onListenerSnapshot(active.mapNotNull(::toActiveNotification))
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        container.onCancellerChanged(null)
        container.onListenerDisconnected()
        // 被解绑/杀进程后让系统重新绑定，避免在册集合长期停在旧快照上。
        requestRebind(ComponentName(this, RearNotificationListener::class.java))
    }

    override fun onDestroy() {
        container.onCancellerChanged(null)
        super.onDestroy()
    }

    /**
     * 调试旁路（票 #7）：撤销某包的全部 Active Notification。
     *
     * `cmd notification` 只有 post 没有 cancel，PC 脚本要能清掉自己发的通知（含
     * `com.android.shell`）只能借监听服务的权限——这是它存在的唯一理由，不参与产品链路。
     * 返回撤销枚数；`-1` = 系统不让列/不让撤（监听未连接或权限被收回），调用方按日志处理。
     */
    private fun cancelAllOf(pkg: String): Int {
        val active = activeNotificationsOrNull() ?: return -1
        var cancelled = 0
        active.filter { it.packageName == pkg }.forEach { sbn ->
            runCatching { cancelNotification(sbn.key) }
                .onSuccess { cancelled++ }
                .onFailure { Log.w(LOG_TAG, "cancelNotification failed key=${sbn.key}", it) }
        }
        return cancelled
    }

    /** 未连接或系统拒答时返回 null（不能拿空集冒充真相，见票 #3 的收口）。 */
    private fun activeNotificationsOrNull(): List<StatusBarNotification>? = try {
        activeNotifications?.toList()
    } catch (e: SecurityException) {
        Log.w(LOG_TAG, "getActiveNotifications denied, keep active set", e)
        null
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
