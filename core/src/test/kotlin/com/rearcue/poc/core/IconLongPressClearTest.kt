package com.rearcue.poc.core

import com.rearcue.poc.core.DashboardEvent.DetailToggled
import com.rearcue.poc.core.DashboardEvent.DismissAppNotifications
import com.rearcue.poc.core.DashboardEvent.NotificationPosted
import com.rearcue.poc.core.DashboardEffect.CancelNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IconLongPressClearTest {
    private val wechat = "com.tencent.mm"
    private val qq = "com.tencent.mobileqq"

    private fun core() = DashboardCore(initialAllowlist = setOf(wechat, qq), nowMs = { 0L })

    private fun post(core: DashboardCore, pkg: String, key: String, clearable: Boolean = true) {
        core.onEvent(NotificationPosted(pkg, key, "标题", "正文", clearable = clearable))
    }

    @Test
    fun `长按只消除该应用划得掉的通知`() {
        val core = core()
        post(core, wechat, "k1", clearable = true)
        post(core, wechat, "k2", clearable = false)
        post(core, qq, "q1", clearable = true)

        assertEquals(listOf(CancelNotification("k1")), core.onEvent(DismissAppNotifications(wechat)))
        assertEquals(setOf(wechat, qq), core.iconSet.toSet())
        assertEquals(2, core.unreadCounts[wechat])
        assertEquals(1, core.unreadCounts[qq])
        assertNull(core.detail)
    }

    @Test
    fun `划得掉的一次全部列出且不打开详情`() {
        val core = core()
        post(core, wechat, "k1")
        post(core, wechat, "k2")

        assertEquals(
            listOf(CancelNotification("k1"), CancelNotification("k2")),
            core.onEvent(DismissAppNotifications(wechat)),
        )
        assertNull(core.detail)
        assertTrue(wechat in core.iconSet)
    }

    @Test
    fun `全部划不掉或应用不在图标里则不撤销`() {
        val core = core()
        post(core, wechat, "k1", clearable = false)

        assertEquals(emptyList(), core.onEvent(DismissAppNotifications(wechat)))
        assertEquals(emptyList(), core.onEvent(DismissAppNotifications(qq)))
        assertEquals(1, core.unreadCounts[wechat])
    }

    @Test
    fun `详情开着时长按无效果`() {
        val core = core()
        post(core, wechat, "k1")
        post(core, wechat, "k2")
        assertEquals(listOf(CancelNotification("k2")), core.onEvent(DetailToggled(wechat)))

        assertEquals(emptyList(), core.onEvent(DismissAppNotifications(wechat)))
        assertEquals(wechat, core.detail?.app)
    }
}
