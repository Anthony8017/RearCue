package com.rearcue.poc.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
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
        container.onKeyCancellerChanged(::cancelByKey)
        val active = activeNotificationsOrNull()
        container.onListenerConnected(active?.size ?: -1)
        if (active != null) {
            container.onListenerSnapshot(active.mapNotNull(::toActiveNotification))
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        container.onCancellerChanged(null)
        container.onKeyCancellerChanged(null)
        container.onListenerDisconnected()
    }

    override fun onDestroy() {
        container.onCancellerChanged(null)
        container.onKeyCancellerChanged(null)
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

    /**
     * 消除所示通知（票 #111 点开即消）：按 notification key 撤销单条。false = 系统不让撤
     * （SecurityException/未连接），调用方按日志处理——不谎报成功，详情照常保留。
     */
    private fun cancelByKey(key: String): Boolean = runCatching { cancelNotification(key) }
        .onFailure { Log.w(LOG_TAG, "cancelNotification failed key=$key", it) }
        .isSuccess

    /** 未连接或系统拒答时返回 null（不能拿空集冒充真相，见票 #3 的收口）。 */
    private fun activeNotificationsOrNull(): List<StatusBarNotification>? = try {
        activeNotifications?.toList()
    } catch (e: SecurityException) {
        Log.w(LOG_TAG, "getActiveNotifications denied, keep active set", e)
        null
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        container.onListenerRankingUpdate()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val active = sbn?.let(::toActiveNotification) ?: return
        container.onListenerPosted(active)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val active = sbn?.let(::toActiveNotification) ?: return
        container.onListenerRemoved(active)
    }

    /**
     * 通知内容只在内存里读（spec 0007 story 15）：`extras` 是 NLS 既有可见字段
     * （零新权限），读出即随事件挂在 [ActiveNotification] 上搬运，不落盘、不外传。
     * 读不到标题/内容时留空串（原横幅按隐私档显示的口径；spec 0008 横幅退役后，
     * 内容留给 Detail View〔票 #66〕消费，本读取口径不变）。
     */
    private fun toActiveNotification(sbn: StatusBarNotification): ActiveNotification? {
        val pkg = sbn.packageName
        val key = sbn.key
        if (pkg.isNullOrEmpty() || key.isNullOrEmpty()) return null
        val extras = sbn.notification.extras
        return ActiveNotification(
            pkg = pkg,
            key = key,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
        )
    }
}
