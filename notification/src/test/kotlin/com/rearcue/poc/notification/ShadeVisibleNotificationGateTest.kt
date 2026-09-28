package com.rearcue.poc.notification

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `移除隐藏 key 后再次出现按未知即可见处理`() {
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
    fun `快照保留已知隐藏状态，新增未知 key 仍可进入`() {
        val repository = NotificationRepository()
        val gate = ShadeVisibleNotificationGate(repository)
        gate.onPosted(notification(miSound))
        gate.applyVisibility(ShadeVisibilityProbe(emptySet(), setOf(miSound)))

        gate.replaceSnapshot(listOf(notification(miSound), notification(chatGpt)))

        assertEquals(setOf(chatGpt), repository.currentNotifications.map { it.key }.toSet())
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
}
