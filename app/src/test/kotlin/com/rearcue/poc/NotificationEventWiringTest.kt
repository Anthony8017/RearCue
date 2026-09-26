package com.rearcue.poc

import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.core.DashboardEffect
import com.rearcue.poc.core.DashboardEffect.ExitDashboard
import com.rearcue.poc.core.DashboardEffect.HighlightBreath
import com.rearcue.poc.core.DashboardEffect.LaunchDashboard
import com.rearcue.poc.core.DashboardEffect.UpdateIconSet
import com.rearcue.poc.core.DashboardEvent
import com.rearcue.poc.notification.ActiveNotification
import com.rearcue.poc.notification.NotificationRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 仓库事件 → core 事件接线测试（spec 0008 / 票 #65 的新真相面）：
 * 同 key 内容更新（Updated）是 **Notification Highlight 的触发源**——呼吸受同一冷却约束、
 * Icon Set 仍不重计（票 #50 判例继续成立：计数事件压根不含 Updated，集合成员没变）。
 *
 * 分工：key 对账与 Updated 事件面在 :notification（NotificationRepositoryTest 判例不动）；
 * 本文件断言 `toCoreEvents()` 的翻译 + core 侧的消费结果。原 #64 的退役断言
 * 「Updated 不产生任何效果」随票 #65 改写为 Highlight 判例——Updated → NotificationUpdated
 * → 冷却外触发 HighlightBreath。
 */
class NotificationEventWiringTest {

    private val wechat = "com.tencent.mm"
    private val qq = "com.tencent.mobileqq"

    private fun notification(
        key: String = "0|com.tencent.mm|1|null|10210",
        title: String = "标题",
        text: String = "内容",
    ) = ActiveNotification(pkg = wechat, key = key, title = title, text = text)

    /** 仓库 + 接线 + core 串成一条真实链路：事件产出的效果按发生顺序收集（时钟定 t=0，呼吸断言确定）。 */
    private fun wired(core: DashboardCore = DashboardCore(nowMs = { 0L })): Pair<NotificationRepository, List<List<DashboardEffect>>> {
        core.onEvent(DashboardEvent.ProjectionReady)
        val effects = mutableListOf<List<DashboardEffect>>()
        val repository = NotificationRepository()
        repository.subscribe { event -> effects += event.toCoreEvents().flatMap(core::onEvent) }
        return repository to effects
    }

    @Test
    fun `首条通知上屏并触发呼吸`() {
        val (repository, effects) = wired()

        repository.onPosted(notification())

        assertEquals(
            listOf(
                listOf(
                    LaunchDashboard(setOf(wechat)),
                    HighlightBreath(setOf(wechat), DashboardCore.HIGHLIGHT_BREATH_MS),
                ),
            ),
            effects,
        )
    }

    @Test
    fun `Updated 是 Highlight 触发源：冷却外触发呼吸（spec 0008 新真相，改写票 64 退役断言）`() {
        var now = 0L
        val core = DashboardCore(nowMs = { now })
        val (repository, effects) = wired(core)

        repository.onPosted(notification()) // 首条：Launch + 呼吸（冷却起点 t=0）
        now += DashboardCore.HIGHLIGHT_COOLDOWN_MS
        repository.onPosted(notification(title = "更新后", text = "新内容")) // 同 key 内容更新

        assertEquals(
            listOf(
                listOf(
                    LaunchDashboard(setOf(wechat)),
                    HighlightBreath(setOf(wechat), DashboardCore.HIGHLIGHT_BREATH_MS),
                ),
                listOf(
                    HighlightBreath(
                        setOf(wechat),
                        DashboardCore.HIGHLIGHT_COOLDOWN_MS + DashboardCore.HIGHLIGHT_BREATH_MS,
                    ),
                ),
            ),
            effects,
        )
    }

    @Test
    fun `Updated 受同一冷却约束且 Icon Set 不重计（票 50 判例继续成立）`() {
        val (repository, effects) = wired()

        repository.onPosted(notification()) // 首条：Launch + 呼吸
        repository.onPosted(notification(title = "更新后", text = "新内容")) // 同刻更新：冷却中

        assertEquals(
            listOf(
                listOf(
                    LaunchDashboard(setOf(wechat)),
                    HighlightBreath(setOf(wechat), DashboardCore.HIGHLIGHT_BREATH_MS),
                ),
                emptyList<DashboardEffect>(),
            ),
            effects,
        )
    }

    @Test
    fun `Removed 末条通知正常退出（key 对账在 ：notification，core 只收 NotificationRemoved）`() {
        val (repository, effects) = wired()

        repository.onPosted(notification())
        repository.onRemoved(notification())

        assertEquals(
            listOf(
                listOf(
                    LaunchDashboard(setOf(wechat)),
                    HighlightBreath(setOf(wechat), DashboardCore.HIGHLIGHT_BREATH_MS),
                ),
                listOf(ExitDashboard),
            ),
            effects,
        )
    }

    @Test
    fun `第二条不同应用通知只更新 Icon Set 不重复呼吸（冷却内）`() {
        val (repository, effects) = wired()

        repository.onPosted(notification())
        repository.onPosted(
            ActiveNotification(pkg = qq, key = "0|com.tencent.mobileqq|1|null|10211", title = "QQ", text = "另一条"),
        )

        assertEquals(
            listOf(
                listOf(
                    LaunchDashboard(setOf(wechat)),
                    HighlightBreath(setOf(wechat), DashboardCore.HIGHLIGHT_BREATH_MS),
                ),
                listOf(UpdateIconSet(setOf(wechat, qq))),
            ),
            effects,
        )
    }

    @Test
    fun `Detail 打开拿到仓库最新一条的内容快照（事件面携带 key+title+text，票 66）`() {
        val core = DashboardCore(nowMs = { 0L })
        val (repository, _) = wired(core)

        repository.onPosted(notification(key = "0|com.tencent.mm|1|null|10210", title = "旧标题", text = "旧内容"))
        repository.onPosted(ActiveNotification(pkg = wechat, key = "0|com.tencent.mm|2|null|10210", title = "新标题", text = "新内容"))

        core.onEvent(DashboardEvent.DetailToggled(wechat))

        // 端到端：仓库（key 对账）→ toCoreEvents 携带内容 → core 镜像「最新一条」→ Detail 快照。
        assertEquals(
            com.rearcue.poc.core.NotificationDetail(wechat, "0|com.tencent.mm|2|null|10210", "新标题", "新内容"),
            core.detail,
        )
    }

    @Test
    fun `所示 key 被清除自动收起（仓库 Removed 携带 key，端到端）`() {
        val core = DashboardCore(nowMs = { 0L })
        val (repository, _) = wired(core)

        repository.onPosted(notification(key = "0|com.tencent.mm|1|null|10210", title = "标题", text = "内容"))
        core.onEvent(DashboardEvent.DetailToggled(wechat))
        assertEquals(
            com.rearcue.poc.core.NotificationDetail(wechat, "0|com.tencent.mm|1|null|10210", "标题", "内容"),
            core.detail,
        )

        // 清除所示通知：仓库按 key 对账报 Removed → core 对上冻结的 key → 自动收起。
        repository.onRemoved(notification(key = "0|com.tencent.mm|1|null|10210"))
        assertEquals(null, core.detail)
    }
}
