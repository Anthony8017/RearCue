package com.rearcue.poc.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShadeVisibleNotificationGateTest {

    private val miSound = "0|com.miui.misound|887|null|10184"
    private val chatGpt = "0|com.openai.chatgpt|1|null|10370"

    private fun notification(key: String, title: String = "", text: String = "") =
        ActiveNotification(pkg = key.substringAfter("|").substringBefore("|"), key = key, title = title, text = text)

    private class Recording : ActiveNotificationListener {
        val events = mutableListOf<ActiveNotificationEvent>()
        override fun onEvent(event: ActiveNotificationEvent) {
            events += event
        }
    }

    @Test
    fun `可见性未知时全部放行`() {
        val repository = NotificationRepository()
        val recording = Recording()
        repository.subscribe(recording)
        val gate = ShadeVisibleNotificationGate(repository)

        gate.onPosted(notification(miSound))

        assertEquals(setOf(miSound), repository.currentNotifications.map { it.key }.toSet())
        assertEquals(1, recording.events.size)
    }

    @Test
    fun `一次探测后剔除在册但不可见的 key`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))
        gate.onPosted(notification(chatGpt))

        gate.applyVisibility(ShadeVisibilityProbe(setOf(chatGpt), setOf(miSound, chatGpt)))

        assertEquals(setOf(chatGpt), repository.currentNotifications.map { it.key }.toSet())
    }

    @Test
    fun `探测后新到的 key 在下次探测前仍可见`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.applyVisibility(ShadeVisibilityProbe(emptySet(), emptySet()))

        gate.onPosted(notification(chatGpt))

        assertEquals(setOf(chatGpt), repository.currentNotifications.map { it.key }.toSet())
    }

    @Test
    fun `已判隐藏的通知更新内容不会重新进入背屏`() {
        val repository = NotificationRepository()
        val recording = Recording()
        repository.subscribe(recording)
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound, title = "旧"))
        gate.applyVisibility(ShadeVisibilityProbe(emptySet(), setOf(miSound)))
        recording.events.clear()

        gate.onPosted(notification(miSound, title = "新"))

        assertEquals(emptySet(), repository.currentNotifications)
        assertEquals(emptyList(), recording.events)
    }

    @Test
    fun `隐藏通知后来变为可见时补发最新快照`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound, title = "旧"))
        gate.applyVisibility(ShadeVisibilityProbe(emptySet(), setOf(miSound)))
        gate.onPosted(notification(miSound, title = "新"))

        gate.applyVisibility(ShadeVisibilityProbe(setOf(miSound), setOf(miSound)))

        assertEquals("新", repository.currentNotifications.single().title)
        assertEquals(setOf("com.miui.misound"), repository.currentPackages)
    }

    @Test
    fun `探测不可用时退回全部在册可见`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))
        gate.onPosted(notification(chatGpt))
        gate.applyVisibility(ShadeVisibilityProbe(setOf(chatGpt), setOf(miSound, chatGpt)))

        gate.applyVisibility(ShadeVisibilityProbe(null, emptySet()))

        assertEquals(setOf(miSound, chatGpt), repository.currentNotifications.map { it.key }.toSet())
    }

    @Test
    fun `同 App 一条可见一条隐藏时只保留可见条目（角标口径）`() {
        val visible = "0|com.tencent.mm|1|null|10210"
        val hidden = "0|com.tencent.mm|2|null|10210"
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(visible))
        gate.onPosted(notification(hidden))

        gate.applyVisibility(ShadeVisibilityProbe(setOf(visible), setOf(visible, hidden)))

        assertEquals(setOf(visible), repository.currentNotifications.map { it.key }.toSet())
        assertEquals(setOf("com.tencent.mm"), repository.currentPackages)
    }

    @Test
    fun `移除后同 key 再到达按未知即可见（不记忆隐藏态）`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))
        gate.applyVisibility(ShadeVisibilityProbe(emptySet(), setOf(miSound)))

        gate.onRemoved(notification(miSound))
        gate.onPosted(notification(miSound, title = "repost"))

        assertEquals(setOf(miSound), repository.currentNotifications.map { it.key }.toSet())
        assertEquals("repost", repository.currentNotifications.single().title)
    }

    @Test
    fun `快照对账后隐藏 key 不漂回可见集合`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))
        gate.onPosted(notification(chatGpt))
        gate.applyVisibility(ShadeVisibilityProbe(setOf(chatGpt), setOf(miSound, chatGpt)))

        gate.replaceSnapshot(listOf(notification(miSound), notification(chatGpt)))

        assertEquals(setOf(chatGpt), repository.currentNotifications.map { it.key }.toSet())
        assertEquals(setOf(miSound, chatGpt), gate.rawKeys)
    }

    @Test
    fun `快照保留已知隐藏状态，新增未知 key 仍可进入`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))
        gate.applyVisibility(ShadeVisibilityProbe(emptySet(), setOf(miSound)))

        gate.replaceSnapshot(listOf(notification(miSound), notification(chatGpt)))

        assertEquals(setOf(chatGpt), repository.currentNotifications.map { it.key }.toSet())
    }

    @Test
    fun `快照里消失的 key 不残留隐藏记忆`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))
        gate.applyVisibility(ShadeVisibilityProbe(emptySet(), setOf(miSound)))

        gate.replaceSnapshot(emptyList())
        gate.onPosted(notification(miSound))

        assertEquals(setOf(miSound), repository.currentNotifications.map { it.key }.toSet())
    }

    @Test
    fun `探测 key 与在册 key 完全不相交时按未知 fail-open`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))
        gate.onPosted(notification(chatGpt))

        val effective = gate.applyVisibility(
            ShadeVisibilityProbe(
                visibleKeys = setOf("0|com.other|1|null|1000"),
                probedKeys = setOf(miSound, chatGpt),
            ),
        )

        assertNull(effective)
        assertEquals(setOf(miSound, chatGpt), repository.currentNotifications.map { it.key }.toSet())
    }

    @Test
    fun `可见集为空时不触发失配 fail-open`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))

        val effective = gate.applyVisibility(ShadeVisibilityProbe(emptySet(), setOf(miSound)))

        assertEquals(emptySet<String>(), effective)
        assertEquals(emptySet(), repository.currentNotifications)
    }

    @Test
    fun `visibleCount 跟随路由结果与 fail-open`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))
        gate.onPosted(notification(chatGpt))
        assertEquals(2, gate.visibleCount)

        gate.applyVisibility(ShadeVisibilityProbe(setOf(chatGpt), setOf(miSound, chatGpt)))
        assertEquals(1, gate.visibleCount)

        gate.applyVisibility(ShadeVisibilityProbe(null, setOf(miSound, chatGpt)))
        assertEquals(2, gate.visibleCount)

        gate.applyVisibility(
            ShadeVisibilityProbe(setOf("0|com.other|1|null|1000"), setOf(miSound, chatGpt)),
        )
        assertEquals(2, gate.visibleCount)
    }
}
