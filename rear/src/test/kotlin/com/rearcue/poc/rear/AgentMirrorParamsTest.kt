package com.rearcue.poc.rear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.design.RearCueColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent Mirror 版式参数判例（#113 AC「版式参数单元测试随布局调整同步」）：
 * 状态字号/动作行截断随状态词删除后，仅剩正文字号一档＋状态光带参数
 * （spec 0021 / 票 #208：五档 Status Glow，Approval Glow 泛化）——钉死档位值，改档必过此例。
 *
 * spec 0017 / 票 #169 追加：正文档位三档（小/中/大）、代码块行距系数、提问泡宽度与泡内可用宽度。
 * spec 0019 / 票 #194 反转：Agent 页右距 16dp（RIGHT_INSET）随版心贴缘归零退役——
 * 贴缘视口的判例移驻 DisplaySafeAreaTest（`flushReadingViewport`）。
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
        assertEquals(656, AgentMirrorParams.bubbleTextWidthPx(columnWidthPx = 800, paddingHorizontalTotalPx = padding))
        // 泡内可用宽度必须严格小于泡外宽：这条不成立就说明没扣内边距。
        assertTrue(
            AgentMirrorParams.bubbleTextWidthPx(800, padding) < AgentMirrorParams.bubbleMaxWidthPx(800),
        )
        // 内边距大到吃满泡宽：不给负宽度（渲染层据此不画泡内容）。
        assertEquals(0, AgentMirrorParams.bubbleTextWidthPx(columnWidthPx = 20, paddingHorizontalTotalPx = 200))
        // 病态几何不抛错。
        assertEquals(0, AgentMirrorParams.bubbleTextWidthPx(columnWidthPx = 0, paddingHorizontalTotalPx = 0))
    }

    @Test
    fun `提问与紧随其后的回答挨得更紧（spec 0017 的间距细分）`() {
        val ask = AgentTurn(AgentTurnRole.USER, "问")
        val answer = AgentTurn(AgentTurnRole.AGENT, "答")
        val anotherAsk = AgentTurn(AgentTurnRole.USER, "再问")

        assertEquals(
            AgentMirrorParams.PROMPT_TO_ANSWER_GAP,
            AgentMirrorParams.gapBetween(ask, answer),
            "一问一答是同一组，挨紧",
        )
        // 其余相邻对一律用常规轮次间距（跨组的「回答 → 下一个提问」也在内）。
        val otherPairs = listOf(answer to anotherAsk, answer to answer, anotherAsk to anotherAsk)
        otherPairs.forEach { (previous, next) ->
            assertEquals(
                AgentMirrorParams.TURN_GAP,
                AgentMirrorParams.gapBetween(previous, next),
                "非「提问→回答」的相邻对应当用常规间距",
            )
        }
        // 单条（没有下一轮）也走常规间距。
        assertEquals(AgentMirrorParams.TURN_GAP, AgentMirrorParams.gapBetween(ask, null))
        assertEquals(AgentMirrorParams.TURN_GAP, AgentMirrorParams.gapBetween(null, answer))
        // 一对问答的间距必须真的比常规紧，否则这条细分是空话。
        assertTrue(AgentMirrorParams.PROMPT_TO_ANSWER_GAP < AgentMirrorParams.TURN_GAP)
    }

    @Test
    fun `会话标识行样式随档联动且与正文同色系`() {
        val small = AgentMirrorParams.headingStyle(TextStyle.Default, MirrorTextSize.SMALL)
        val large = AgentMirrorParams.headingStyle(TextStyle.Default, MirrorTextSize.LARGE)
        assertEquals(11f, small.fontSize.value)
        assertEquals(14f, large.fontSize.value)
        assertEquals(TextAlign.Start, small.textAlign, "标识行与正文同一条左缘")
        assertEquals(RearCueColors.onBackgroundSecondary, small.color)
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

    // —— Status Glow（spec 0021 / 票 #208）：状态 × 链路 × 几何 → 光带规格 ——

    @Test
    fun `五状态加已连接——各档颜色动效亮度钉死`() {
        val working = AgentMirrorParams.statusGlow(AgentStatus.WORKING, BridgeLinkStatus.CONNECTED, 904, 572)!!
        assertEquals(RearCueColors.accent, working.color)
        assertEquals(GlowMotion.FLOWING, working.motion)
        assertEquals(AgentMirrorParams.GLOW_FLOW_CYCLE_MS, working.cycleMs)
        assertEquals(AgentMirrorParams.GLOW_FLOW_ALPHA, working.alphaMin)
        assertEquals(working.alphaMin, working.alphaMax, "流动档恒亮：亮度不起伏，动的是亮段位置")

        val waiting = AgentMirrorParams.statusGlow(
            AgentStatus.WAITING_FOR_APPROVAL, BridgeLinkStatus.CONNECTED, 904, 572,
        )!!
        assertEquals(RearCueColors.waiting, waiting.color, "等待档蓝改琥珀黄（蓝让给工作中，色＋动静双重区分）")
        assertEquals(GlowMotion.BREATHING, waiting.motion)
        assertEquals(AgentMirrorParams.GLOW_CYCLE_MS, waiting.cycleMs, "呼吸周期复用票 #105 既有参数")
        assertEquals(AgentMirrorParams.GLOW_ALPHA_MIN, waiting.alphaMin)
        assertEquals(AgentMirrorParams.GLOW_ALPHA_MAX, waiting.alphaMax)

        val idle = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572)!!
        assertEquals(RearCueColors.idle, idle.color)
        assertEquals(GlowMotion.STILL, idle.motion)
        assertEquals(AgentMirrorParams.GLOW_STILL_ALPHA, idle.alphaMin)
        assertEquals(idle.alphaMin, idle.alphaMax, "静止档恒亮")
        assertEquals(0, idle.cycleMs, "静止档不使用周期")

        val error = AgentMirrorParams.statusGlow(AgentStatus.ERROR, BridgeLinkStatus.CONNECTED, 904, 572)!!
        assertEquals(RearCueColors.error, error.color)
        assertEquals(GlowMotion.STILL, error.motion)
        assertEquals(AgentMirrorParams.GLOW_STILL_ALPHA, error.alphaMin)

        // 亮度阶梯：等待档呼吸上限是全场最亮，其余各档一律低于它。
        listOf(working, idle, error).forEach {
            assertTrue(it.alphaMax < waiting.alphaMax, "常驻档必须低于等待档（等待最亮，spec 0021）")
        }
    }

    @Test
    fun `断链压档——链路未连一律灰档压过会话状态`() {
        listOf(
            AgentStatus.WORKING to BridgeLinkStatus.RETRYING,
            AgentStatus.WAITING_FOR_APPROVAL to BridgeLinkStatus.CONNECTING,
            AgentStatus.IDLE to BridgeLinkStatus.RETRYING,
            AgentStatus.ERROR to BridgeLinkStatus.CONNECTING,
        ).forEach { (status, link) ->
            val glow = AgentMirrorParams.statusGlow(status, link, 904, 572)!!
            assertEquals(
                RearCueColors.onBackgroundSecondary, glow.color,
                "$status＋$link：旧状态不可信，一律次要灰",
            )
            assertEquals(GlowMotion.STILL, glow.motion)
            assertEquals(AgentMirrorParams.GLOW_STILL_ALPHA, glow.alphaMin)
        }
    }

    @Test
    fun `桥未配置不产档`() {
        AgentStatus.entries.forEach { status ->
            assertNull(AgentMirrorParams.statusGlow(status, BridgeLinkStatus.DISABLED, 904, 572))
        }
    }

    @Test
    fun `光带描边随几何缩放并夹紧（沿旧例）`() {
        val small = AgentMirrorParams.statusGlow(
            AgentStatus.WAITING_FOR_APPROVAL, BridgeLinkStatus.CONNECTED, 200, 100,
        )!!
        val large = AgentMirrorParams.statusGlow(AgentStatus.WORKING, BridgeLinkStatus.CONNECTED, 4000, 2000)!!
        assertTrue(small.strokeWidthPx <= large.strokeWidthPx)
        assertTrue(small.strokeWidthPx >= AgentMirrorParams.GLOW_STROKE_MIN_PX)
        assertTrue(large.strokeWidthPx <= AgentMirrorParams.GLOW_STROKE_MAX_PX)
        // 病态几何（采集前 0×0）不抛错，退到夹紧下限。
        val degenerate = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 0, 0)!!
        assertEquals(AgentMirrorParams.GLOW_STROKE_MIN_PX, degenerate.strokeWidthPx)
        // 断链档同样吃几何夹紧。
        val disconnected = AgentMirrorParams.statusGlow(AgentStatus.WORKING, BridgeLinkStatus.RETRYING, 0, 0)!!
        assertEquals(AgentMirrorParams.GLOW_STROKE_MIN_PX, disconnected.strokeWidthPx)
    }

    // —— 流动亮段扫掠 stop 表（spec 0021 / 票 #208 评审收纳：段长与包络数学收口参数层） ——

    @Test
    fun `流动亮段段长与恒亮亮度钉死——流动恒亮同呼吸下限档`() {
        assertEquals(0.35f, AgentMirrorParams.GLOW_FLOW_ARC)
        assertEquals(
            AgentMirrorParams.GLOW_ALPHA_MIN, AgentMirrorParams.GLOW_FLOW_ALPHA,
            "流动恒亮=呼吸下限同档（知识单源）",
        )
        // 票 #214 实机提亮：呼吸下限（＝流动恒亮）0.4→0.5。
        assertEquals(0.5f, AgentMirrorParams.GLOW_FLOW_ALPHA)
    }

    // —— 亮度倍率（spec 0021 修订 / 票 #214：主屏滑动条 → 各档 alpha × 倍率，封顶 1.0） ——

    @Test
    fun `亮度倍率乘各档 alpha 且封顶 1`() {
        // 静止档 0.5 × 0.5 = 0.25：往低调的档。
        val dim = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572, 0.5f)!!
        assertEquals(0.25f, dim.alphaMin)
        assertEquals(0.25f, dim.alphaMax)
        // 呼吸档 2×：下限 1.0 封顶、上限 1.0 封顶——拉满时起伏近乎拍平（已知代价）。
        val blown = AgentMirrorParams.statusGlow(
            AgentStatus.WAITING_FOR_APPROVAL, BridgeLinkStatus.CONNECTED, 904, 572, 2f,
        )!!
        assertEquals(1f, blown.alphaMin)
        assertEquals(1f, blown.alphaMax)
        // 倍率不改档位本色：动效/周期照旧（颜色由档位表出，同入参同色）。
        assertEquals(GlowMotion.BREATHING, blown.motion)
        assertEquals(AgentMirrorParams.GLOW_CYCLE_MS, blown.cycleMs)
    }

    @Test
    fun `亮度倍率越界钳回滑动条范围`() {
        val below = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572, 0.1f)!!
        val min = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572, 0.5f)!!
        assertEquals(min.alphaMin, below.alphaMin)
        val above = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572, 9f)!!
        val max = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572, 2f)!!
        assertEquals(max.alphaMin, above.alphaMin)
    }

    @Test
    fun `票 214 实机口径钉死——静止提亮与描边加宽夹紧`() {
        assertEquals(0.5f, AgentMirrorParams.GLOW_STILL_ALPHA)
        assertEquals(0.028f, AgentMirrorParams.GLOW_STROKE_RATIO)
        assertEquals(8f, AgentMirrorParams.GLOW_STROKE_MIN_PX)
        assertEquals(28f, AgentMirrorParams.GLOW_STROKE_MAX_PX)
        // 572px 短边实机档：0.028×572=16.016px（约 5.7dp@450dpi，票 #214 前为 ~6.9px）。
        assertEquals(
            16.016f,
            AgentMirrorParams.statusGlow(AgentStatus.WORKING, BridgeLinkStatus.CONNECTED, 904, 572)!!.strokeWidthPx,
            0.01f,
        )
    }

    @Test
    fun `流动亮段不跨 0 点——中心满亮、两端渐隐到透明`() {
        val stops = AgentMirrorParams.glowFlowStops(0.5f, Color.Black, AgentMirrorParams.GLOW_FLOW_ALPHA)
        assertEquals(0.5f, stops[2].first, "亮段中心在 phase")
        assertEquals(
            AgentMirrorParams.GLOW_FLOW_ALPHA, stops[2].second.alpha, absoluteTolerance = 5e-3f,
            "亮段中心满亮（0.5×255=127.5 非整，8bit 量化差半步≈0.002，容差盖一个量化步长 1/255）",
        )
        // from=0.325、to=0.675：段外与段端一律透明，亮与不亮的分界干净。
        assertEquals(Color.Transparent, stops[0].second)
        assertEquals(Color.Transparent, stops[1].second)
        assertEquals(Color.Transparent, stops[3].second)
        assertEquals(Color.Transparent, stops[4].second)
    }

    @Test
    fun `流动亮段跨 0 点——两分支 stop 序单调递增且边界同色续接`() {
        val color = Color.Black
        val alpha = AgentMirrorParams.GLOW_FLOW_ALPHA
        // 段长 0.35：phase=0.125 亮段约 [-0.05, 0.30]（from<0 分支）；phase=0.875 亮段约 [0.70, 1.05]（to>1 分支）。
        listOf(
            "from<0 分支" to AgentMirrorParams.glowFlowStops(0.125f, color, alpha),
            "to>1 分支" to AgentMirrorParams.glowFlowStops(0.875f, color, alpha),
        ).forEach { (branch, stops) ->
            assertEquals(5, stops.size, "$branch：五点 stop 表")
            val positions = stops.map { it.first }
            assertEquals(
                positions.sorted(), positions,
                "$branch：stop 位置必须单调递增（sweepGradient 的前提）",
            )
            assertEquals(0f, positions.first(), "$branch：覆盖到环起点")
            assertEquals(1f, positions.last(), "$branch：覆盖到环终点")
            assertEquals(
                stops.first().second, stops.last().second,
                "$branch：1f/0f 边界同色续接——包络连续，不闪缝",
            )
        }
        // 边界亮度是线性包络在 1f/0f 处的取值：alpha × |from|/half = 0.5 × 0.05/0.175 = 1/7 ≈ 0.142857，
        // 两分支对称同值（Color 分量按 8bit 量化，容差盖住量化步长）。
        val before = AgentMirrorParams.glowFlowStops(0.125f, color, alpha)
        val after = AgentMirrorParams.glowFlowStops(0.875f, color, alpha)
        assertEquals(0.142857f, before.first().second.alpha, absoluteTolerance = 5e-3f)
        assertEquals(0.142857f, after.first().second.alpha, absoluteTolerance = 5e-3f)
        assertEquals(
            before.first().second.alpha, after.first().second.alpha, absoluteTolerance = 1e-6f,
            "两分支边界亮度同值（对称）",
        )
        assertEquals(
            alpha, before[1].second.alpha, absoluteTolerance = 5e-3f,
            "亮段中心满亮（8bit 量化容差盖一个步长）",
        )
        assertTrue(
            before.first().second.alpha > 0f && before.first().second.alpha < alpha,
            "边界亮度严格介于熄灭与满亮之间（包络续接而非跳变）",
        )
    }
}
