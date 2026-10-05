package com.rearcue.poc.voice

import com.rearcue.poc.core.DashboardEvent
import com.rearcue.poc.core.VoiceBroadcastFollow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceBroadcastRuntimeTest {
    private fun follow(id: Long) = VoiceBroadcastFollow(id, "s$id", null, null, "句子", 0, 1, true)

    @Test fun `真实队列条目间不结束整组而耗尽才发完成`() {
        val queue = VoiceBroadcastQueue()
        queue.enqueue(VoiceBroadcastKind.DONE, "一")
        queue.enqueue(VoiceBroadcastKind.DONE, "二")
        val first = queue.startNext()!!
        val start = VoiceBroadcastRuntimeState(follow = follow(first.id), batchActive = queue.hasContent)
        assertEquals(listOf(DashboardEvent.VoiceBroadcastStarted("s${first.id}")),
            start.dashboardEventsSince(VoiceBroadcastRuntimeState()))
        queue.completeCurrent()
        val gap = start.copy(follow = null, batchActive = queue.hasContent)
        assertTrue(gap.dashboardEventsSince(start).isEmpty())
        val second = queue.startNext()!!
        val next = gap.copy(follow = follow(second.id), batchActive = queue.hasContent)
        assertEquals(listOf(DashboardEvent.VoiceBroadcastStarted("s${second.id}")), next.dashboardEventsSince(gap))
        queue.completeCurrent()
        val end = next.copy(follow = null, batchActive = queue.hasContent)
        assertEquals(listOf(DashboardEvent.VoiceBroadcastFinished), end.dashboardEventsSince(next))
    }

    @Test fun `句子更新和暂停不重复投送清空才结束`() {
        val queue = VoiceBroadcastQueue()
        queue.enqueue(VoiceBroadcastKind.DONE, "一")
        val item = queue.startNext()!!
        val state = VoiceBroadcastRuntimeState(follow = follow(item.id), batchActive = queue.hasContent)
        assertTrue(state.copy(follow = state.follow!!.copy(sentenceIndex = 1)).dashboardEventsSince(state).isEmpty())
        assertTrue(state.copy(note = "暂停").dashboardEventsSince(state).isEmpty())
        queue.clear()
        assertEquals(listOf(DashboardEvent.VoiceBroadcastFinished),
            state.copy(follow = null, batchActive = queue.hasContent).dashboardEventsSince(state))
    }
}
