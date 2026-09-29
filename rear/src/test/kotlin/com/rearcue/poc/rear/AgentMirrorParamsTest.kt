package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent Mirror 版式参数判例（#113 AC「版式参数单元测试随布局调整同步」）：
 * 状态字号/动作行截断随状态词删除后，仅剩正文字号一档＋等确认光带参数（票 #105）——
 * 钉死档位值，改档必过此例。
 */
class AgentMirrorParamsTest {

    @Test
    fun `会话输出正文字号档钉死`() {
        assertEquals(16f, AgentMirrorParams.REPLY_SP_BASE)
    }

    @Test
    fun `固定标识行的预留高度是行高加间距`() {
        // 本机：标识行行高 16sp ≈ 45px、间距 8dp ≈ 23px ⇒ 预留 68px（票 #161 判例）
        assertEquals(68, AgentMirrorParams.headingReservePx(lineHeightPx = 45f, gapPx = 22.5f))
        assertEquals(0, AgentMirrorParams.headingReservePx(lineHeightPx = 0f, gapPx = 0f))
        // 病态输入不抛：负值按 0 收口
        assertEquals(0, AgentMirrorParams.headingReservePx(lineHeightPx = -10f, gapPx = -5f))
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
}
