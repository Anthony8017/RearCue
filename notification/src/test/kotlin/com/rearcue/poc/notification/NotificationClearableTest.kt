package com.rearcue.poc.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NotificationClearableTest {
    @Test
    fun `只改变划得掉标记也上报更新`() {
        val repository = NotificationRepository()
        val events = mutableListOf<ActiveNotificationEvent>()
        repository.subscribe { events += it }
        val key = "0|com.tencent.mm|1|null|10123"
        repository.onPosted(ActiveNotification("com.tencent.mm", key, "标题", "正文", clearable = true))
        repository.onPosted(ActiveNotification("com.tencent.mm", key, "标题", "正文", clearable = false))
        assertEquals(2, events.size)
        val updated = events[1] as ActiveNotificationEvent.Updated
        assertFalse(updated.notification.clearable)
        assertFalse(repository.currentNotifications.single().clearable)
    }
}
