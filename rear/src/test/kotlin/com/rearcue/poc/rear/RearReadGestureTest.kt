package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RearReadGestureTest {
    @Test
    fun `点进一条或开关列表都算看过当前对话`() {
        assertTrue(RearReadGesture.marks(RearReadGesture.Kind.PICK_SESSION, conversationVisible = false))
        assertTrue(RearReadGesture.marks(RearReadGesture.Kind.SESSION_LINE, conversationVisible = false))
        assertTrue(RearReadGesture.marks(RearReadGesture.Kind.SESSION_LINE, conversationVisible = true))
    }

    @Test
    fun `对话盖住列表时点正文滑正文或切到通知才算看过`() {
        RearReadGesture.Kind.entries.filter {
            it == RearReadGesture.Kind.BODY_TAP ||
                it == RearReadGesture.Kind.BODY_SCROLL ||
                it == RearReadGesture.Kind.LEAVE_TO_NOTIFICATION
        }.forEach { kind ->
            assertTrue(RearReadGesture.marks(kind, conversationVisible = true), kind.name)
            assertFalse(RearReadGesture.marks(kind, conversationVisible = false), kind.name)
        }
    }

    @Test
    fun `自动档滑列表通知捷径和自动翻页都不算看过`() {
        listOf(
            RearReadGesture.Kind.PICK_AUTO,
            RearReadGesture.Kind.LIST_SCROLL,
            RearReadGesture.Kind.NOTIFICATION_SHORTCUT,
            RearReadGesture.Kind.AUTO_REVEAL,
        ).forEach { kind ->
            assertFalse(RearReadGesture.marks(kind, conversationVisible = true), kind.name)
            assertFalse(RearReadGesture.marks(kind, conversationVisible = false), kind.name)
        }
    }
}
