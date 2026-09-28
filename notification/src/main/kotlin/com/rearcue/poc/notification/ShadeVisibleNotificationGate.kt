package com.rearcue.poc.notification

/** 一次成功/失败的可见性探测快照：两个集合必须来自同一次探测。 */
data class ShadeVisibilityProbe(
    val visibleKeys: Set<String>?,
    val probedKeys: Set<String>,
)

/**
 * NLS 原始 Active Notification → Dashboard 消费面的可见性路由器。
 *
 * [rawByKey] 保留监听服务看到的全部在册通知；[visibleKeys] 为 null 表示可见性未知，
 * 此时全部放行（fail-open）。一次成功探测后，探测时在册但从可见集合缺席的 key 会被
 * 记为 [hiddenKeys]；探测之后新到、尚未经过探测的 key 仍按未知即可见放行。
 *
 * 纯 Kotlin、无 Android 依赖：可见性判断与 fail-open 边界都由 JVM 判例覆盖。
 */
class ShadeVisibleNotificationGate(
    private val sink: ActiveNotificationSink,
) {

    private val rawByKey = LinkedHashMap<String, ActiveNotification>()

    /** null = 可见性未知；非 null = 最近一次成功探测的完全可见集合。 */
    private var visibleKeys: Set<String>? = null

    /** 最近一次成功探测中「在册但不可见」的 key。 */
    private var hiddenKeys: Set<String> = emptySet()

    val rawKeys: Set<String> get() = rawByKey.keys.toSet()

    val visibleCount: Int get() = rawByKey.count { isVisible(it.key) }

    fun onPosted(notification: ActiveNotification) {
        rawByKey[notification.key] = notification
        if (isVisible(notification.key)) sink.onPosted(notification)
    }

    fun onRemoved(notification: ActiveNotification) {
        rawByKey.remove(notification.key)
        hiddenKeys = hiddenKeys - notification.key
        sink.onRemoved(notification)
    }

    fun replaceSnapshot(notifications: Collection<ActiveNotification>) {
        rawByKey.clear()
        notifications.forEach { rawByKey[it.key] = it }
        hiddenKeys = hiddenKeys.intersect(rawByKey.keys)
        sink.replaceSnapshot(visibleRaw())
    }

    /**
     * 应用一次 SystemUI 探测结果；[ShadeVisibilityProbe.visibleKeys] 为 null 时退回全部可见。
     */
    fun applyVisibility(probe: ShadeVisibilityProbe) {
        visibleKeys = probe.visibleKeys
        hiddenKeys = if (probe.visibleKeys == null) {
            emptySet()
        } else {
            (probe.probedKeys - probe.visibleKeys).intersect(rawByKey.keys)
        }
        sink.replaceSnapshot(visibleRaw())
    }

    private fun isVisible(key: String): Boolean {
        val known = visibleKeys ?: return true
        return key in known || key !in hiddenKeys
    }

    private fun visibleRaw(): List<ActiveNotification> =
        rawByKey.values.filter { isVisible(it.key) }
}
