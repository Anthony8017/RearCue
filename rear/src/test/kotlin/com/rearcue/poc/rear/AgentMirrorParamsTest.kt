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
}
