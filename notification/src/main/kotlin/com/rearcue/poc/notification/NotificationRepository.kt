package com.rearcue.poc.notification

/**
 * 一枚 Active Notification 的纯 Kotlin 标识（见 CONTEXT.md）。
 *
 * [key] 是 NotificationListenerService 视角的稳定唯一键（Android 形状 `<user>|<pkg>|<id>|<tag>|<uid>`），
 * 去重与增删判定都以它为准；[pkg] 冗余保存，供 Icon Set 按应用聚合。
 * [title]/[text] 是内存内的通知内容（spec 0007 Notification Feed，NLS extras 读出、只随事件搬运，
 * 不落盘）——**不参与**存在判定：同 key 内容更新不是「消失又出现」，只以
 * [ActiveNotificationEvent.Updated] 报给订阅者。
 */
data class ActiveNotification(
    val pkg: String,
    val key: String,
    val title: String = "",
    val text: String = "",
)

/** Active Notification 集合的变化事件。 */
sealed interface ActiveNotificationEvent {
    /**
     * 新增一枚。[fromSnapshot] = 重连/重启快照差分补报（[replaceSnapshot] 专用）——
     * 是「重建在册事实」不是「到达」。
     */
    data class Posted(val notification: ActiveNotification, val fromSnapshot: Boolean = false) : ActiveNotificationEvent

    /**
     * 同 key 的内容更新：集合成员没变——不是「消失又出现」，订阅者**不得**据此增减 Icon Set 计数
     * （每 App 一枚的既有语义不动）；接线层把它翻译成
     * [com.rearcue.poc.core.DashboardEvent.NotificationUpdated] 刷新内容镜像。
     * [fromSnapshot] 语义同 [Posted]。
     */
    data class Updated(val notification: ActiveNotification, val fromSnapshot: Boolean = false) : ActiveNotificationEvent

    /** 移除一枚（已不在集合中）。 */
    data class Removed(val notification: ActiveNotification) : ActiveNotificationEvent

    /** 全量快照已对账生效（仅作状态展示，订阅者无需据此改集合）。 */
    data class SnapshotReplaced(val notifications: List<ActiveNotification>) : ActiveNotificationEvent
}

/** 订阅者：只收变化事件，不轮询状态。 */
fun interface ActiveNotificationListener {
    fun onEvent(event: ActiveNotificationEvent)
}

/** 接收通知监听的增删/快照。 */
interface ActiveNotificationSink {
    fun onPosted(notification: ActiveNotification)

    fun onRemoved(notification: ActiveNotification)

    fun replaceSnapshot(notifications: Collection<ActiveNotification>)
}

/**
 * 通知汇聚：唯一事实来源是「按 notification key 去重的集合」。
 *
 * 输入是 NotificationListenerService 的增量回调与全量快照；本类只做去重与快照对账。
 * 输出是给 Dashboard 用的变更事件流。纯 Kotlin，无 Android 框架依赖——JVM 单测 seam。
 *
 * 不做应用级过滤：通知白名单筛选在 DashboardCore，仓库保留收到的全部 Active Notification。
 *
 * 线程模型：所有方法都必须在同一线程调用（Android 侧为监听服务的主线程）。
 */
class NotificationRepository : ActiveNotificationSink {

    /** key -> 当前在册通知（词汇见 CONTEXT.md：不存在「追踪列表」这种对象）。 */
    private val activeByKey = LinkedHashMap<String, ActiveNotification>()

    /** pkg -> 该应用 Active Notification 的 key 集合。 */
    private val keysByPackage = LinkedHashMap<String, MutableSet<String>>()

    private val listeners = mutableListOf<ActiveNotificationListener>()

    /** 当前 Active Notification 集合。 */
    val currentNotifications: Set<ActiveNotification> get() = activeByKey.values.toSet()

    /** 当前存在 Active Notification 的包名集合。 */
    val currentPackages: Set<String> get() = keysByPackage.keys.toSet()

    /** 注册订阅者；后续事件立即推送，不回放当前集合（用 [currentNotifications] 取初值）。 */
    fun subscribe(listener: ActiveNotificationListener) {
        listeners += listener
    }

    fun unsubscribe(listener: ActiveNotificationListener) {
        listeners -= listener
    }

    /** 增量：onNotificationPosted。同 key 同内容不产生事件；同 key 内容变了上报 [ActiveNotificationEvent.Updated]（见 [record]）。 */
    override fun onPosted(notification: ActiveNotification) {
        when (record(notification)) {
            RecordOutcome.ENROLLED -> notify(ActiveNotificationEvent.Posted(notification))
            RecordOutcome.CONTENT_CHANGED -> notify(ActiveNotificationEvent.Updated(notification))
            RecordOutcome.UNCHANGED -> Unit
        }
    }

    /** 增量：onNotificationRemoved。不在册的 key 视为幂等。 */
    override fun onRemoved(notification: ActiveNotification) {
        forget(notification.key)?.let { notify(ActiveNotificationEvent.Removed(it)) }
    }

    /**
     * 全量：Active Notification 快照对账（监听连接/重连时投喂）。
     *
     * 监听服务重建、断线重连时用它对齐仓库视图。
     * 对账只认 [ActiveNotification.key]（内容更新不算「消失又出现」——否则重连快照会把
     * 在屏的图标抖掉一轮）：快照里消失的 key 上报 Removed，新 key 上报 Posted，
     * 既有 key 就地刷新在册内容——内容变了补一条 [ActiveNotificationEvent.Updated]
     * （横幅该刷新，图标计数不动），没变不产生事件。
     * 空快照是合法的（连接瞬间系统可能返回空），会清空在册集合。
     */
    override fun replaceSnapshot(notifications: Collection<ActiveNotification>) {
        val incoming = notifications.distinctBy { it.key }
        val incomingByKey = incoming.associateBy { it.key }

        activeByKey.values
            .filter { stale -> incomingByKey[stale.key] == null }
            .forEach { stale ->
                forget(stale.key)?.let { notify(ActiveNotificationEvent.Removed(it)) }
            }

        incoming.forEach { notification ->
            when (record(notification)) {
                // 快照差分补报一律带 fromSnapshot：重建不是到达。
                RecordOutcome.ENROLLED -> notify(ActiveNotificationEvent.Posted(notification, fromSnapshot = true))
                RecordOutcome.CONTENT_CHANGED -> notify(ActiveNotificationEvent.Updated(notification, fromSnapshot = true))
                RecordOutcome.UNCHANGED -> Unit
            }
        }

        notify(ActiveNotificationEvent.SnapshotReplaced(incoming))
    }

    /** [record] 的落点：新入册 / 同 key 内容变了（集合成员没变）/ 同 key 同内容（无事件）。 */
    private enum class RecordOutcome { ENROLLED, CONTENT_CHANGED, UNCHANGED }

    /**
     * 记录一枚通知：不存在 → 入册（新增）；已存在 → 就地刷新在册内容，[ActiveNotification.title]/
     * [ActiveNotification.text] 变了才报内容更新，没变就是无事件（幂等）。
     *
     * 存在判定仍只认 [ActiveNotification.key]——内容字段只决定「要不要重发横幅」，
     * 决不参与「在不在册」（否则重连快照会把在屏的图标抖掉一轮）。
     */
    private fun record(notification: ActiveNotification): RecordOutcome {
        val previous = activeByKey[notification.key]
        activeByKey[notification.key] = notification
        if (previous == null) {
            keysByPackage.getOrPut(notification.pkg) { LinkedHashSet() } += notification.key
            return RecordOutcome.ENROLLED
        }
        val contentChanged = previous.title != notification.title || previous.text != notification.text
        return if (contentChanged) RecordOutcome.CONTENT_CHANGED else RecordOutcome.UNCHANGED
    }

    /** 从在册集合移除一枚，返回被移除的记录；不在册返回 null。 */
    private fun forget(key: String): ActiveNotification? {
        val removed = activeByKey.remove(key) ?: return null
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
