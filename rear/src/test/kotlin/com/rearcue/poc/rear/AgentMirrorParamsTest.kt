package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    // —— Approval Glow（票 #105）：状态 × 几何 → 光带参数 ——

    @Test
    fun `等确认才有光带`() {
        assertNotNull(AgentMirrorParams.approvalGlow(AgentStatus.WAITING_FOR_APPROVAL, 904, 572))
        assertNull(AgentMirrorParams.approvalGlow(AgentStatus.WORKING, 904, 572))
        assertNull(AgentMirrorParams.approvalGlow(AgentStatus.IDLE, 904, 572))
    }

    @Test
    fun `光带持续点亮且亮度周期起伏`() {
        val glow = AgentMirrorParams.approvalGlow(AgentStatus.WAITING_FOR_APPROVAL, 904, 572)!!
        // 持续点亮：任何相位 alpha 都不落到 0；起伏：上限严格高于下限。
        assertTrue(glow.alphaMin > 0f)
        assertTrue(glow.alphaMax <= 1f)
        assertTrue(glow.alphaMin < glow.alphaMax)
        assertTrue(glow.cycleMs > 0)
    }

    @Test
    fun `光带描边随几何缩放并夹紧`() {
        val small = AgentMirrorParams.approvalGlow(AgentStatus.WAITING_FOR_APPROVAL, 200, 100)!!
        val large = AgentMirrorParams.approvalGlow(AgentStatus.WAITING_FOR_APPROVAL, 4000, 2000)!!
        assertTrue(small.strokeWidthPx <= large.strokeWidthPx)
        assertTrue(small.strokeWidthPx >= AgentMirrorParams.GLOW_STROKE_MIN_PX)
        assertTrue(large.strokeWidthPx <= AgentMirrorParams.GLOW_STROKE_MAX_PX)
        // 病态几何（采集前 0×0）不抛错，退到夹紧下限。
        val degenerate = AgentMirrorParams.approvalGlow(AgentStatus.WAITING_FOR_APPROVAL, 0, 0)!!
        assertEquals(AgentMirrorParams.GLOW_STROKE_MIN_PX, degenerate.strokeWidthPx)
    }

    @Test
    fun `动作行封顶一行——多行会把回复区挤到零高（票 88 实机回归锚）`() {
        // spec 0010 story 7：当前动作「一行」。回复区是 weight(1f) 的剩余高度，
        // 动作折行会把它压成 0：状态里有 latestReply 却不落屏（注入短动作可见、真数据长动作消失）。
        assertEquals(1, AgentMirrorParams.ACTION_MAX_LINES)
    }
}
