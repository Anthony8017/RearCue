package com.rearcue.poc.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NotificationRepository 行为测试：只断言「监听回调序列 → 事件序列」，不断言内部状态。
 *
 * 词汇见 CONTEXT.md：Active Notification、Icon Set。
 * notification key 用 Android 真实形状 `<user>|<pkg>|<id>|<tag>|<uid>`。
 */
class NotificationRepositoryTest {

    private val wechat = "com.tencent.mm"
    private val qq = "com.tencent.mobileqq"

    private fun key(pkg: String, id: Int, tag: String? = null, user: Int = 0, uid: Int = 10123) =
        "$user|$pkg|$id|${tag ?: "null"}|$uid"

    private fun active(pkg: String, id: Int, tag: String? = null) =
        ActiveNotification(pkg = pkg, key = key(pkg, id, tag))

    /** 记录事件序列的假订阅者。 */
    private class Recording : ActiveNotificationListener {
        val events = mutableListOf<ActiveNotificationEvent>()

        override fun onEvent(event: ActiveNotificationEvent) {
            events += event
        }
    }

    private fun repositoryWithRecording(): Pair<NotificationRepository, Recording> {
        val repository = NotificationRepository()
        val recording = Recording()
        repository.subscribe(recording)
        return repository to recording
    }

    /** 把事件序列渲染成 `posted:<key>` / `updated:<key>` / `removed:<key>`，便于逐项对账。 */
    private fun List<ActiveNotificationEvent>.rendered(): List<String> =
        mapNotNull { event ->
            when (event) {
                is ActiveNotificationEvent.Posted -> "posted:${event.notification.key}"
                is ActiveNotificationEvent.Updated -> "updated:${event.notification.key}"
                is ActiveNotificationEvent.Removed -> "removed:${event.notification.key}"
                is ActiveNotificationEvent.SnapshotReplaced -> null
            }
        }

    // ---------- 增量：onNotificationPosted ----------

    @Test
    fun `新 key 上报 Post 事件`() {
        val (repository, recording) = repositoryWithRecording()

        repository.onPosted(active(wechat, 1))

        assertEquals(
            listOf("posted:${key(wechat, 1)}"),
            recording.events.rendered(),
        )
    }

    @Test
    fun `同一 key 同内容重复上报不产生事件`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(active(wechat, 1))

        repository.onPosted(active(wechat, 1))

        assertEquals(1, recording.events.size)
        assertEquals(setOf(active(wechat, 1)), repository.currentNotifications)
    }

    @Test
    fun `同 key 内容更新上报 Updated 而非 Post，在册成员不变`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(
            ActiveNotification(pkg = wechat, key = key(wechat, 1), title = "旧", text = "旧内容"),
        )

        repository.onPosted(
            ActiveNotification(pkg = wechat, key = key(wechat, 1), title = "新", text = "新内容"),
        )

        // 恰好一条 Post + 一条 Updated：没有第二条 Post（Icon Set 计数/次序的仓库侧前提，spec 0007 story 1/4）
        assertEquals(
            listOf("posted:${key(wechat, 1)}", "updated:${key(wechat, 1)}"),
            recording.events.rendered(),
        )
        assertEquals(setOf(wechat), repository.currentPackages)
        assertEquals(
            setOf(ActiveNotification(pkg = wechat, key = key(wechat, 1), title = "新", text = "新内容")),
            repository.currentNotifications,
        )
    }

    @Test
    fun `同 key 单改标题或单改内容都算更新`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(ActiveNotification(pkg = wechat, key = key(wechat, 1), title = "旧", text = "旧"))
        recording.events.clear()

        repository.onPosted(ActiveNotification(pkg = wechat, key = key(wechat, 1), title = "新", text = "旧"))
        repository.onPosted(ActiveNotification(pkg = wechat, key = key(wechat, 1), title = "新", text = "更"))

        assertEquals(2, recording.events.size)
        assertTrue(recording.events.all { it is ActiveNotificationEvent.Updated })
    }

    @Test
    fun `同应用不同 key 各算一枚通知`() {
        val (repository, recording) = repositoryWithRecording()

        repository.onPosted(active(wechat, 1))
        repository.onPosted(active(wechat, 2))

        assertEquals(2, recording.events.size)
        assertEquals(setOf(wechat), repository.currentPackages)
    }

    @Test
    fun `同一 id 不同 tag 是不同通知`() {
        val (repository, recording) = repositoryWithRecording()

        repository.onPosted(active(wechat, 1, tag = "chat-a"))
        repository.onPosted(active(wechat, 1, tag = "chat-b"))

        assertEquals(2, recording.events.size)
        assertEquals(setOf(wechat), repository.currentPackages)
    }

    // ---------- 增量：onNotificationRemoved ----------

    @Test
    fun `跟踪中的 key 移除上报 Removed 事件`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(active(wechat, 1))
        recording.events.clear()

        repository.onRemoved(active(wechat, 1))

        assertEquals(
            listOf("removed:${key(wechat, 1)}"),
            recording.events.rendered(),
        )
    }

    @Test
    fun `未跟踪的 key 移除无事件`() {
        val (repository, recording) = repositoryWithRecording()

        repository.onRemoved(active(wechat, 1))

        assertEquals(emptyList(), recording.events)
    }

    @Test
    fun `重复移除同一 key 只上报一次`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(active(wechat, 1))
        recording.events.clear()

        repository.onRemoved(active(wechat, 1))
        repository.onRemoved(active(wechat, 1))

        assertEquals(1, recording.events.size)
    }

    @Test
    fun `部分移除后应用仍在跟踪集`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(active(wechat, 1))
        repository.onPosted(active(wechat, 2))
        recording.events.clear()

        repository.onRemoved(active(wechat, 1))

        assertEquals(setOf(wechat), repository.currentPackages)
        assertEquals(1, recording.events.size)
    }

    @Test
    fun `末条移除后应用离开跟踪集`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(active(wechat, 1))
        recording.events.clear()

        repository.onRemoved(active(wechat, 1))

        assertEquals(emptySet(), repository.currentPackages)
    }

    @Test
    fun `任意应用同样被跟踪（通知不过滤）`() {
        val (repository, recording) = repositoryWithRecording()

        repository.onPosted(active("com.stranger.app", 9))

        assertEquals(setOf("com.stranger.app"), repository.currentPackages)
        assertEquals(1, recording.events.size)
    }

    // ---------- 全量：onListenerConnected 对账 ----------

    @Test
    fun `首次全量快照把每枚通知上报为 Post`() {
        val (repository, recording) = repositoryWithRecording()

        repository.replaceSnapshot(listOf(active(wechat, 1), active(qq, 2)))

        assertEquals(
            listOf("posted:${key(wechat, 1)}", "posted:${key(qq, 2)}"),
            recording.events.rendered(),
        )
        assertEquals(setOf(wechat, qq), repository.currentPackages)
    }

    @Test
    fun `重连快照保留既有 key 时不重复上报`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(active(wechat, 1))
        recording.events.clear()

        repository.replaceSnapshot(listOf(active(wechat, 1)))

        assertEquals(emptyList(), recording.events.rendered())
    }

    @Test
    fun `重连快照同 key 内容变化不算消失又出现（对账只认 key，只报更新）`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(
            ActiveNotification(pkg = wechat, key = key(wechat, 1), title = "旧", text = "旧内容"),
        )
        recording.events.clear()

        repository.replaceSnapshot(
            listOf(ActiveNotification(pkg = wechat, key = key(wechat, 1), title = "新", text = "新内容")),
        )

        // 没有 Removed/Posted（图标不抖一轮），内容变化以 Updated 报出（横幅该刷新）
        assertEquals(listOf("updated:${key(wechat, 1)}"), recording.events.rendered())
        assertEquals(
            setOf(ActiveNotification(pkg = wechat, key = key(wechat, 1), title = "新", text = "新内容")),
            repository.currentNotifications,
        )
    }

    @Test
    fun `重连快照中消失的 key 上报 Removed`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(active(wechat, 1))
        repository.onPosted(active(qq, 2))
        recording.events.clear()

        repository.replaceSnapshot(listOf(active(qq, 2)))

        assertEquals(
            listOf("removed:${key(wechat, 1)}"),
            recording.events.rendered(),
        )
    }

    @Test
    fun `重连快照同时新增与消失只上报差异`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(active(wechat, 1))
        recording.events.clear()

        repository.replaceSnapshot(listOf(active(qq, 2)))

        assertEquals(
            listOf("removed:${key(wechat, 1)}", "posted:${key(qq, 2)}"),
            recording.events.rendered(),
        )
    }

    @Test
    fun `空快照清空跟踪集并逐枚上报 Removed`() {
        val (repository, recording) = repositoryWithRecording()
        repository.onPosted(active(wechat, 1))
        repository.onPosted(active(qq, 2))
        recording.events.clear()

        repository.replaceSnapshot(emptyList())

        assertEquals(
            listOf("removed:${key(wechat, 1)}", "removed:${key(qq, 2)}"),
            recording.events.rendered(),
        )
        assertEquals(emptySet(), repository.currentPackages)
    }

    @Test
    fun `快照始终上报 SnapshotReplaced 供状态展示`() {
        val (repository, recording) = repositoryWithRecording()

        repository.replaceSnapshot(listOf(active(wechat, 1)))

        val snapshot = recording.events
            .filterIsInstance<ActiveNotificationEvent.SnapshotReplaced>()
        assertEquals(1, snapshot.size)
        assertEquals(listOf(active(wechat, 1)), snapshot.single().notifications)
    }

    @Test
    fun `快照内重复 key 只跟踪一枚`() {
        val (repository, recording) = repositoryWithRecording()

        repository.replaceSnapshot(listOf(active(wechat, 1), active(wechat, 1)))

        assertEquals(setOf(wechat), repository.currentPackages)
        assertEquals(
            1,
            recording.events.count { it is ActiveNotificationEvent.Posted },
        )
    }

    // ---------- 变更广播顺序 ----------

    @Test
    fun `先上报 Post 再上报 Removed，订阅者看到的顺序即回调顺序`() {
        val (repository, recording) = repositoryWithRecording()

        repository.onPosted(active(wechat, 1))
        repository.onRemoved(active(wechat, 1))

        assertTrue(recording.events[0] is ActiveNotificationEvent.Posted)
        assertTrue(recording.events[1] is ActiveNotificationEvent.Removed)
    }

    @Test
    fun `多个订阅者都收到同一事件`() {
        val repository = NotificationRepository()
        val first = Recording()
        val second = Recording()
        repository.subscribe(first)
        repository.subscribe(second)

        repository.onPosted(active(wechat, 1))

        assertEquals(first.events, second.events)
    }

    @Test
    fun `currentPackages 初始为空集`() {
        assertEquals(emptySet(), NotificationRepository().currentPackages)
    }
}
