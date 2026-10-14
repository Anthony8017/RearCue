package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusNoteTest {
    private val loading = StatusNoteFrame("s1", "正在载入历史…")
    private val progress = StatusNoteFrame("s1", "载入历史：1 / 2 条")
    private val other = StatusNoteFrame("s2", "正在载入历史…")

    @Test
    fun `出现才淡入`() {
        assertEquals(StatusNoteCommand.Show(loading), statusNoteCommand(null, false, loading))
    }

    @Test
    fun `同一会话换字不重演`() {
        assertEquals(StatusNoteCommand.Replace(progress), statusNoteCommand(loading, true, progress))
        assertNull(statusNoteCommand(progress, true, progress))
    }

    @Test
    fun `换会话先淡出`() {
        assertEquals(StatusNoteCommand.Swap(other), statusNoteCommand(loading, true, other))
    }

    @Test
    fun `淡出未完成时新提示直接进入`() {
        assertEquals(StatusNoteCommand.Show(other), statusNoteCommand(loading, false, other))
    }

    @Test
    fun `没有提示才淡出`() {
        assertEquals(StatusNoteCommand.Hide, statusNoteCommand(loading, true, null))
        assertNull(statusNoteCommand(loading, false, null))
    }
}
