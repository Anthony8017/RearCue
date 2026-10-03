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
        queue.enqueue(VoiceBroadcastKind.DONE, "一")
        queue.enqueue(VoiceBroadcastKind.DONE, "二")
        queue.startNext()
        assertTrue(queue.skipCurrent())
        assertEquals("二", queue.startNext()?.text)
    }

    @Test
    fun `停止和等待确认清空全部`() {
        val queue = VoiceBroadcastQueue()
        queue.enqueue(VoiceBroadcastKind.DONE, "一")
        queue.enqueue(VoiceBroadcastKind.DONE, "二")
        queue.startNext()
        queue.clear()
        assertNull(queue.startNext())
        assertEquals(0, queue.pendingCount)
    }
}
