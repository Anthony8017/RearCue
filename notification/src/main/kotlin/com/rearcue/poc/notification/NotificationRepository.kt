package com.rearcue.poc.notification

/**
 * 一枚 Active Notification 的纯 Kotlin 标识（见 CONTEXT.md）。
 *
 * [key] 是 NotificationListenerService 视角的稳定唯一键（Android 形状 `<user>|<pkg>|<id>|<tag>|<uid>`），
 * 去重与增删判定都以它为准；[pkg] 冗余保存，供 Icon Set 按应用聚合。
 */
data class ActiveNotification(
    val pkg: String,
    val key: String,
)

/** Active Notification 集合的变化事件。 */
sealed interface ActiveNotificationEvent {
    /** 新增一枚。 */
    data class Posted(val notification: ActiveNotification) : ActiveNotificationEvent

    /** 移除一枚（已不在集合中）。 */
    data class Removed(val notification: ActiveNotification) : ActiveNotificationEvent

    /** 全量快照已对账生效（仅作状态展示，订阅者无需据此改集合）。 */
    data class SnapshotReplaced(val notifications: List<ActiveNotification>) : ActiveNotificationEvent
}

/** 订阅者：只收变化事件，不轮询状态。 */
fun interface ActiveNotificationListener {
    fun onEvent(event: ActiveNotificationEvent)
}

/**
 * Active Notification 汇聚：唯一事实来源是「按 notification key 去重的集合」。
 *
 * 输入是 NotificationListenerService 的三类回调（增量 Post/Removed + 连接时全量快照）；
 * 输出是给 Dashboard 用的变更事件流。纯 Kotlin，无 Android 框架依赖——JVM 单测 seam。
 *
 * 不做 Allowlist 过滤：过滤发生在 Icon Set 层（见 CONTEXT.md「Allowlist App」），
 * 这样 Allowlist 变更时既有通知能立刻上屏，不需要重新全量扫描。
 *
 * 线程模型：所有方法都必须在同一线程调用（Android 侧为监听服务的主线程）。
 */
class NotificationRepository {

    /** key -> 跟踪中的通知。 */
    private val tracked = LinkedHashMap<String, ActiveNotification>()

    /** pkg -> 该应用的 key 集合，供 Icon Set 按应用判断「有/无」。 */
    private val keysByPackage = LinkedHashMap<String, MutableSet<String>>()

    private val listeners = mutableListOf<ActiveNotificationListener>()

    /** 跟踪中的全部 Active Notification。 */
    val currentKeys: Set<ActiveNotification> get() = tracked.values.toSet()

    /** 当前存在 Active Notification 的包名集合（未按 Allowlist 过滤）。 */
    val currentPackages: Set<String> get() = keysByPackage.keys.toSet()

    /** 注册订阅者；后续事件立即推送，不回放当前集合（用 [currentKeys] 取初值）。 */
    fun subscribe(listener: ActiveNotificationListener) {
        listeners += listener
    }

    fun unsubscribe(listener: ActiveNotificationListener) {
        listeners -= listener
    }

    /** 增量：onNotificationPosted。同一 key 重复上报视为幂等。 */
    fun onPosted(notification: ActiveNotification) {
        if (record(notification)) {
            notify(ActiveNotificationEvent.Posted(notification))
        }
    }

    /** 增量：onNotificationRemoved。未跟踪的 key 视为幂等。 */
    fun onRemoved(notification: ActiveNotification) {
        forget(notification.key)?.let { notify(ActiveNotificationEvent.Removed(it)) }
    }

    /**
     * 全量：onListenerConnected 的 `getActiveNotifications()` 对账。
     *
     * 监听服务重建、断线重连、Shizuku 恢复后都用它对齐，避免跟踪集与系统真实集合漂移。
     * 已有 key 视为不变（不重复上报），快照里消失的 key 上报 Removed，新 key 上报 Posted。
     * 空快照是合法的（连接瞬间系统可能返回空），会清空既有跟踪集。
     */
    fun replaceSnapshot(notifications: Collection<ActiveNotification>) {
        val incoming = notifications.distinctBy { it.key }
        val incomingByKey = incoming.associateBy { it.key }

        tracked.values
            .filter { stale -> incomingByKey[stale.key] != stale }
            .forEach { stale ->
                forget(stale.key)?.let { notify(ActiveNotificationEvent.Removed(it)) }
            }

        incoming.forEach { notification ->
            if (record(notification)) {
                notify(ActiveNotificationEvent.Posted(notification))
            }
        }

        notify(ActiveNotificationEvent.SnapshotReplaced(incoming))
    }

    /** 记录一枚通知；已跟踪则返回 false。 */
    private fun record(notification: ActiveNotification): Boolean {
        if (tracked.containsKey(notification.key)) return false
        tracked[notification.key] = notification
        keysByPackage.getOrPut(notification.pkg) { LinkedHashSet() } += notification.key
        return true
    }

    /** 停止跟踪一枚通知，返回被移除的记录；未跟踪返回 null。 */
    private fun forget(key: String): ActiveNotification? {
        val removed = tracked.remove(key) ?: return null
        keysByPackage[removed.pkg]?.let { keys ->
            keys -= removed.key
            if (keys.isEmpty()) keysByPackage.remove(removed.pkg)
        }
        return removed
    }

    private fun notify(event: ActiveNotificationEvent) {
        listeners.toList().forEach { it.onEvent(event) }
    }
}
