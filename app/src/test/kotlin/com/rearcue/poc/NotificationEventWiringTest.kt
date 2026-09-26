package com.rearcue.poc

import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEffect
import com.rearcue.poc.core.DashboardEffect.ExitDashboard
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import com.rearcue.poc.core.DashboardEvent
import com.rearcue.poc.notification.ActiveNotification
import com.rearcue.poc.notification.NotificationRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 仓库事件 → core 事件接线测试（spec 0008 反转的退役断言面）：
 * 横幅退役后，同一枚通知只喂 Icon Set 语义；同 key 内容更新（Updated）**不产生任何效果**。
 *
 * 分工：key 对账与 Updated 事件面保留在 :notification（NotificationRepositoryTest 判例不动）；
 * 本文件断言 core 侧的消费面收敛——Updated 到 core 为止静默，直到 Notification Highlight
 * （票 #65）接管消费。原「同 key 内容更新不重计 Icon Set」（票 #50）的判例由此继续成立：
 * Updated 压根不到 core，计数无从重计。
 * （原 core 内 FeedPosted 面的判例已随 spec 0008 删除，留痕见 DashboardCoreTest 横幅节。）
 */
class NotificationEventWiringTest {

    private val wechat = "com.tencent.mm"

    private fun notification(
        key: String = "0|com.tencent.mm|1|null|10210",
        title: String = "标题",
        text: String = "内容",
    ) = ActiveNotification(pkg = wechat, key = key, title = title, text = text)

    /** 仓库 + 接线 + core 串成一条真实链路：事件产出的效果按发生顺序收集。 */
    private fun wired(): Pair<NotificationRepository, List<List<DashboardEffect>>> {
        val core = DashboardCore()
        core.onEvent(DashboardEvent.ProjectionReady)
        val effects = mutableListOf<List<DashboardEffect>>()
        val repository = NotificationRepository()
        repository.subscribe { event -> effects += event.toCoreEvents().flatMap(core::onEvent) }
        return repository to effects
    }

    @Test
    fun `Updated 不产生任何效果（横幅退役断言，spec 0008 反转）`() {
        val (repository, effects) = wired()

        repository.onPosted(notification())
        assertEquals(listOf(LaunchDashboard(setOf(wechat))), effects.single())

        // 同 key 内容更新：仓库仍上报 Updated（事件面保留），core 侧零效果（无横幅可刷）。
        repository.onPosted(notification(title = "更新后", text = "新内容"))
        assertEquals(
            listOf(listOf(LaunchDashboard(setOf(wechat))), emptyList<DashboardEffect>()),
            effects,
        )
    }

    @Test
    fun `Removed 末条通知正常退出（key 对账在 ：notification，core 只收 NotificationRemoved）`() {
        val (repository, effects) = wired()

        repository.onPosted(notification())
        repository.onRemoved(notification())

        assertEquals(
            listOf(listOf(LaunchDashboard(setOf(wechat))), listOf(ExitDashboard)),
            effects,
        )
    }
}
