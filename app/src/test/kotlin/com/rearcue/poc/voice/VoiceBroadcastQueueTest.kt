package com.rearcue.poc.voice

import com.rearcue.poc.core.VoiceBroadcastVisualTarget
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

    @Test
    fun `待答问题保留会话名发音但只高亮可见问题句`() {
        val queue = VoiceBroadcastQueue()
        val item = queue.enqueue(
            kind = VoiceBroadcastKind.NEEDS_INPUT,
            text = "会话名。标题。甲。乙",
            sessionId = "s",
            requestId = "q",
            source = VoiceBroadcastSource(
                sessionId = "s",
                turnEntryId = "q",
                body = "标题。甲。乙",
                visualText = "标题\n- 甲\n- 乙",
                visualTarget = VoiceBroadcastVisualTarget.QUESTION,
            ),
        )
        assertEquals(listOf(false, true, true, true), item.sentences.map { it.visualAnchor })
        assertEquals(listOf("标题", "甲", "乙"), item.visualSentences.map { it.anchorText })
    }

    @Test
    fun `代码表格略过句沿用上一处可见高亮`() {
        val queue = VoiceBroadcastQueue()
        val body = "前句。\n```kotlin\nval x = 1\n```\n后句。"
        val item = queue.enqueue(
            kind = VoiceBroadcastKind.DONE,
            text = body,
            source = VoiceBroadcastSource(
                sessionId = "s",
                body = body,
                visualText = body,
            ),
        )
        assertEquals("前句。", item.projectFollow(0)?.sentenceText)
        val skipped = item.projectFollow(1)
        assertEquals("前句。", skipped?.sentenceText)
        assertEquals(1, skipped?.spokenSentenceIndex)
        assertEquals("后句。", item.projectFollow(2)?.sentenceText)
    }

    @Test
    fun `失败合成提示不产生可见高亮`() {
        val queue = VoiceBroadcastQueue()
        val item = queue.enqueue(VoiceBroadcastKind.ERROR, text = "任务失败。磁盘已满")
        assertEquals(false, item.projectFollow(0)?.visualAnchor)
        assertTrue(item.visualSentences.isEmpty())

        val emptyReply = queue.enqueue(VoiceBroadcastKind.DONE, text = VoiceBroadcastText.EMPTY_REPLY)
        assertEquals(false, emptyReply.projectFollow(0)?.visualAnchor)
        assertTrue(emptyReply.visualSentences.isEmpty())
    }
}
