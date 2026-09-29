package com.rearcue.poc.rear

import androidx.compose.ui.unit.dp
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.design.RearCueSpacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent Mirror 版式参数判例（#113 AC「版式参数单元测试随布局调整同步」）：
 * 状态字号/动作行截断随状态词删除后，仅剩正文字号一档＋等确认光带参数（票 #105）——
 * 钉死档位值，改档必过此例。
 *
 * spec 0017 / 票 #169 追加：正文档位三档（小/中/大）、代码块行距系数、提问泡宽度与泡内可用宽度、
 * Agent 页右距 16dp——档位与几何语义全部锁在这里。
 */
class AgentMirrorParamsTest {

    @Test
    fun `会话输出正文字号档钉死`() {
        assertEquals(16f, AgentMirrorParams.REPLY_SP_BASE)
    }

    // —— 正文档位（spec 0017 / 票 #169）：三档数值与联动关系锁定 ——

    @Test
    fun `三档正文与会话标识行字号钉死`() {
        val small = AgentMirrorParams.reading(MirrorTextSize.SMALL)
        val medium = AgentMirrorParams.reading(MirrorTextSize.MEDIUM)
        val large = AgentMirrorParams.reading(MirrorTextSize.LARGE)

        assertEquals(14f, small.bodySp)
        assertEquals(20f, small.lineHeightSp)
        assertEquals(11f, small.headingSp)

        assertEquals(16f, medium.bodySp)
        assertEquals(24f, medium.lineHeightSp)
        assertEquals(12f, medium.headingSp)

        assertEquals(20f, large.bodySp)
        assertEquals(30f, large.lineHeightSp)
        assertEquals(14f, large.headingSp)
    }

    @Test
    fun `中档就是既有常量档（升级不改观感）`() {
        assertEquals(
            AgentMirrorParams.REPLY_SP_BASE,
            AgentMirrorParams.reading(MirrorTextSize.MEDIUM).bodySp,
        )
    }

    @Test
    fun `档位单调递增——大档不小、小档不大`() {
        val ladder = MirrorTextSize.entries.map { AgentMirrorParams.reading(it) }
        assertEquals(ladder.sortedBy { it.bodySp }, ladder)
        assertEquals(ladder.sortedBy { it.headingSp }, ladder)
        assertEquals(ladder.sortedBy { it.lineHeightSp }, ladder)
    }

    @Test
    fun `代码块行距比正文紧且不至于挤成一团`() {
        assertTrue(
            AgentMirrorParams.CODE_LINE_HEIGHT_FACTOR < 1f,
            "代码块行距必须比正文紧（系数 < 1），否则「收紧」是空话",
        )
        MirrorTextSize.entries.forEach { size ->
            val reading = AgentMirrorParams.reading(size)
            assertEquals(
                reading.lineHeightSp * AgentMirrorParams.CODE_LINE_HEIGHT_FACTOR,
                reading.codeLineHeightSp,
            )
            assertTrue(reading.codeLineHeightSp < reading.lineHeightSp, "$size：代码行距不得大于等于正文行距")
            // 下界：不小于字号本身，否则行与行会叠字。
            assertTrue(reading.codeLineHeightSp >= reading.bodySp, "$size：代码行距不得小于字号本身")
        }
    }

    @Test
    fun `提问泡宽度取版心比例且不超过版心`() {
        // 常规版心：泡宽是版心的 85%。
        assertEquals(
            (800 * AgentMirrorParams.BUBBLE_MAX_WIDTH_RATIO).toInt(),
            AgentMirrorParams.bubbleMaxWidthPx(800),
        )
        // 窄版心不会反超版心本身。
        assertTrue(AgentMirrorParams.bubbleMaxWidthPx(120) <= 120)
        // 病态几何不抛错、不给负宽度。
        assertEquals(0, AgentMirrorParams.bubbleMaxWidthPx(0))
        assertEquals(0, AgentMirrorParams.bubbleMaxWidthPx(-50))
    }

    @Test
    fun `泡内文字宽度要扣掉左右内边距——否则长提问白白多折行`() {
        val padding = 24
        // 版心 800 → 泡外宽上限 680 → 泡内可用 656。
        assertEquals(656, AgentMirrorParams.bubbleTextWidthPx(columnWidthPx = 800, paddingHorizontalPx = padding))
        // 泡内可用宽度必须严格小于泡外宽：这条不成立就说明没扣内边距。
        assertTrue(
            AgentMirrorParams.bubbleTextWidthPx(800, padding) < AgentMirrorParams.bubbleMaxWidthPx(800),
        )
        // 内边距大到吃满泡宽：不给负宽度（渲染层据此不画泡内容）。
        assertEquals(0, AgentMirrorParams.bubbleTextWidthPx(columnWidthPx = 20, paddingHorizontalPx = 200))
        // 病态几何不抛错。
        assertEquals(0, AgentMirrorParams.bubbleTextWidthPx(columnWidthPx = 0, paddingHorizontalPx = 0))
    }

    @Test
    fun `Agent 页右距屏缘 16dp 而 Detail 保持既有值`() {
        // spec 0017 最硬边界：16dp 只属于 Agent 页。这里钉住 Agent 页的常量本身，
        // Detail 的值由 DisplaySafeAreaTest 的 detailTextViewport 判例继续守。
        assertEquals(RearCueSpacing.md, AgentMirrorParams.RIGHT_INSET)
        assertEquals(16.dp, AgentMirrorParams.RIGHT_INSET)
    }

    @Test
    fun `固定标识行的预留高度是行高加间距`() {
        // 本机：标识行行高 16sp ≈ 45px、间距 8dp ≈ 23px ⇒ 预留 68px（票 #161 判例）
        assertEquals(68, AgentMirrorParams.headingReservePx(lineHeightPx = 45f, gapPx = 22.5f))
        assertEquals(0, AgentMirrorParams.headingReservePx(lineHeightPx = 0f, gapPx = 0f))
        // 病态输入不抛：负值按 0 收口
        assertEquals(0, AgentMirrorParams.headingReservePx(lineHeightPx = -10f, gapPx = -5f))
    }

    @Test
    fun `链路状态点的语义档（票 #165）`() {
        assertEquals(AgentMirrorParams.LinkDot.CONNECTED, AgentMirrorParams.linkDot(BridgeLinkStatus.CONNECTED))
        assertEquals(AgentMirrorParams.LinkDot.PENDING, AgentMirrorParams.linkDot(BridgeLinkStatus.CONNECTING))
        assertEquals(AgentMirrorParams.LinkDot.PENDING, AgentMirrorParams.linkDot(BridgeLinkStatus.RETRYING))
        // 未配置/停用不画点
        assertNull(AgentMirrorParams.linkDot(BridgeLinkStatus.DISABLED))
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
