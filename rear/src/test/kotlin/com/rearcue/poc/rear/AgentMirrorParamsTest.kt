package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentMirrorParamsTest {

    @Test
    fun `等确认状态标语放大一级`() {
        assertTrue(
            AgentMirrorParams.statusSp(com.rearcue.poc.agent.AgentStatus.WAITING_FOR_APPROVAL) >
                AgentMirrorParams.statusSp(com.rearcue.poc.agent.AgentStatus.WORKING),
        )
        assertEquals(
            AgentMirrorParams.statusSp(com.rearcue.poc.agent.AgentStatus.IDLE),
            AgentMirrorParams.statusSp(com.rearcue.poc.agent.AgentStatus.WORKING),
        )
    }

    @Test
    fun `动作行截断与空值`() {
        assertNull(AgentMirrorParams.actionLine(null))
        assertNull(AgentMirrorParams.actionLine("   "))
        assertEquals("edit A.kt", AgentMirrorParams.actionLine("  edit A.kt  "))
        val long = "x".repeat(100)
        val line = AgentMirrorParams.actionLine(long)!!
        assertEquals(AgentMirrorParams.ACTION_MAX_CHARS, line.length)
        assertTrue(line.endsWith("…"))
        assertFalse(line.contains("…" + "x"))
    }

    @Test
    fun `动作行封顶一行——多行会把回复区挤到零高（票 88 实机回归锚）`() {
        // spec 0010 story 7：当前动作「一行」。回复区是 weight(1f) 的剩余高度，
        // 动作折行会把它压成 0：状态里有 latestReply 却不落屏（注入短动作可见、真数据长动作消失）。
        assertEquals(1, AgentMirrorParams.ACTION_MAX_LINES)
    }
}
