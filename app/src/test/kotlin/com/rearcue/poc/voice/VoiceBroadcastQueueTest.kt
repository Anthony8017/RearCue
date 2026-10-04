package com.rearcue.poc.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceBroadcastQueueTest {

    @Test
    fun `多个结果先进先出`() {
        val queue = VoiceBroadcastQueue()
        queue.enqueue(VoiceBroadcastKind.DONE, "一")
        queue.enqueue(VoiceBroadcastKind.ERROR, "二")
        assertEquals("一", queue.startNext()?.text)
        queue.completeCurrent()
        assertEquals("二", queue.startNext()?.text)
    }

    @Test
    fun `下一首跳过当前整条但保留后续`() {
        val queue = VoiceBroadcastQueue()
        queue.enqueue(VoiceBroadcastKind.DONE, "一", source = VoiceBroadcastSource(sessionId = "a"))
        queue.enqueue(VoiceBroadcastKind.DONE, "二", source = VoiceBroadcastSource(sessionId = "b"))
        queue.startNext()
        assertTrue(queue.skipCurrent())
        val next = queue.startNext()
        assertEquals("二", next?.text)
        assertEquals("b", next?.source?.sessionId)
    }

    @Test
    fun `双击停止清空全部`() {
        val queue = VoiceBroadcastQueue()
        queue.enqueue(VoiceBroadcastKind.DONE, "一")
        queue.enqueue(VoiceBroadcastKind.DONE, "二")
        queue.startNext()
        queue.clear()
        assertNull(queue.startNext())
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `问题不打断当前整条但优先于其他结果并继续后续`() {
        val queue = VoiceBroadcastQueue()
        queue.enqueue(VoiceBroadcastKind.DONE, "当前")
        queue.startNext()
        queue.enqueue(VoiceBroadcastKind.DONE, "后续")
        queue.enqueue(VoiceBroadcastKind.NEEDS_INPUT, "问题", "s", requestId = "q")
        assertEquals("当前", queue.startNext()?.text)
        queue.completeCurrent()
        assertEquals("问题", queue.startNext()?.text)
        queue.completeCurrent()
        assertEquals("后续", queue.startNext()?.text)
    }

    @Test
    fun `已答请求和归档会话定向移除不清其他结果`() {
        val queue = VoiceBroadcastQueue()
        queue.enqueue(VoiceBroadcastKind.NEEDS_INPUT, "已答", "s", requestId = "q")
        queue.enqueue(VoiceBroadcastKind.DONE, "归档", "gone")
        queue.enqueue(VoiceBroadcastKind.DONE, "保留", "s")
        queue.retainRequests("s", emptySet())
        queue.retainSessions(setOf("s"))
        assertEquals("保留", queue.startNext()?.text)
        queue.completeCurrent()
        assertNull(queue.startNext())
    }
}
