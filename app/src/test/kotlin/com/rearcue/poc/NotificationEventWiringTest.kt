package com.rearcue.poc

import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEffect
import com.rearcue.poc.core.DashboardEffect.ExitDashboard
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import com.rearcue.poc.core.DashboardEffect.UpdateIconSet
import com.rearcue.poc.core.DashboardEvent
import com.rearcue.poc.notification.ActiveNotification
import com.rearcue.poc.notification.NotificationRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 仓库事件 → core 事件接线测试：同 key 内容更新（Updated）只刷新内容镜像，
 * 不改变 Icon Set、不产生额外视觉效果。
 *
 * 分工：key 对账与 Updated 事件面在 :notification（NotificationRepositoryTest 判例不动）；
 * 本文件断言 `toCoreEvents()` 的翻译 + core 侧的消费结果。
 */
class NotificationEventWiringTest {

    private val wechat = "com.tencent.mm"
    private val qq = "com.tencent.mobileqq"

    private fun notification(
        key: String = "0|com.tencent.mm|1|null|10210",
        title: String = "标题",
        text: String = "内容",
    ) = ActiveNotification(pkg = wechat, key = key, title = title, text = text)

    /** 仓库 + 接线 + core 串成一条真实链路：事件产出的效果按发生顺序收集。 */
    private fun wired(core: DashboardCore = DashboardCore()): Pair<NotificationRepository, List<List<DashboardEffect>>> {
        core.onEvent(DashboardEvent.ProjectionReady)
        val effects = mutableListOf<List<DashboardEffect>>()
        val repository = NotificationRepository()
        repository.subscribe { event -> effects += event.toCoreEvents().flatMap(core::onEvent) }
        return repository to effects
    }

    @Test
    fun `首条通知上屏`() {
        val (repository, effects) = wired()

        repository.onPosted(notification())

        assertEquals(listOf(listOf(LaunchDashboard(setOf(wechat)))), effects)
    }

    @Test
    fun `Updated 只刷新内容镜像且 Icon Set 不重计`() {
        val core = DashboardCore()
        val (repository, effects) = wired(core)

        repository.onPosted(notification())
        repository.onPosted(notification(title = "更新后", text = "新内容"))

        assertEquals(
            listOf(
                listOf(LaunchDashboard(setOf(wechat))),
                emptyList<DashboardEffect>(),
            ),
            effects,
        )
        assertEquals(listOf(wechat), core.iconSet)
    }

    @Test
    fun `Removed 末条通知进入退屏宽限`() {
        val (repository, effects) = wired()

        repository.onPosted(notification())
        repository.onRemoved(notification())

        assertEquals(
            listOf(
                listOf(LaunchDashboard(setOf(wechat))),
                listOf(UpdateIconSet(emptySet())),
            ),
            effects,
        )
    }

    @Test
    fun `第二条不同应用通知只更新 Icon Set`() {
        val (repository, effects) = wired()

        repository.onPosted(notification())
        repository.onPosted(
            ActiveNotification(pkg = qq, key = "0|com.tencent.mobileqq|1|null|10211", title = "QQ", text = "另一条"),
        )

        assertEquals(
            listOf(
                listOf(LaunchDashboard(setOf(wechat))),
                listOf(UpdateIconSet(setOf(wechat, qq))),
            ),
            effects,
        )
    }

    @Test
    fun `Detail 打开拿到仓库最新一条的内容快照`() {
        val core = DashboardCore()
        val (repository, _) = wired(core)

        repository.onPosted(notification(key = "0|com.tencent.mm|1|null|10210", title = "旧标题", text = "旧内容"))
        repository.onPosted(ActiveNotification(pkg = wechat, key = "0|com.tencent.mm|2|null|10210", title = "新标题", text = "新内容"))

        core.onEvent(DashboardEvent.DetailToggled(wechat))

        assertEquals(
            com.rearcue.poc.core.NotificationDetail(wechat, "0|com.tencent.mm|2|null|10210", "新标题", "新内容"),
            core.detail,
        )
    }

    @Test
    fun `点开即消端到端：回执豁免保留详情`() {
        val core = DashboardCore()
        val (repository, _) = wired(core)

        repository.onPosted(notification(key = "0|com.tencent.mm|1|null|10210", title = "标题", text = "内容"))
        assertEquals(
            listOf(DashboardEffect.CancelNotification("0|com.tencent.mm|1|null|10210")),
            core.onEvent(DashboardEvent.DetailToggled(wechat)),
        )
        assertEquals(
            com.rearcue.poc.core.NotificationDetail(wechat, "0|com.tencent.mm|1|null|10210", "标题", "内容"),
            core.detail,
        )

        repository.onRemoved(notification(key = "0|com.tencent.mm|1|null|10210"))
        assertEquals(
            com.rearcue.poc.core.NotificationDetail(wechat, "0|com.tencent.mm|1|null|10210", "标题", "内容"),
            core.detail,
        )

        assertEquals(listOf(ExitDashboard), core.onEvent(DashboardEvent.DetailToggled(wechat)))
    }

    @Test
    fun `重连快照差分只重建在册事实`() {
        val core = DashboardCore()
        val (repository, effects) = wired(core)

        repository.replaceSnapshot(listOf(notification(title = "快照标题", text = "快照内容")))
        repository.replaceSnapshot(listOf(notification(title = "变了", text = "内容变了")))
        repository.replaceSnapshot(listOf(notification())) // 同 key 同内容：完全无事件

        assertEquals(listOf(LaunchDashboard(setOf(wechat))), effects[0])
        assertEquals(emptyList<DashboardEffect>(), effects[1])
        assertEquals(6, effects.size) // 三次快照 = 3×内容事件批 + 3×SnapshotReplaced 空批

        repository.onPosted(ActiveNotification(pkg = qq, key = "0|com.tencent.mobileqq|1|null|10211", title = "QQ", text = "另一条"))
        assertEquals(listOf(listOf(UpdateIconSet(setOf(wechat, qq)))), effects.drop(6))
    }
}
