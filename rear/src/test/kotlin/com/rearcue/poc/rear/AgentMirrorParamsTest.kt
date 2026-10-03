package com.rearcue.poc.rear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.core.GlowBrightness
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
        assertEquals(12f, AgentMirrorParams.REPLY_SP_BASE)
    }

    // —— 正文档位（spec 0017 / 票 #169）：三档数值与联动关系锁定 ——

    @Test
    fun `三档正文与会话标识行字号钉死`() {
        val small = AgentMirrorParams.reading(MirrorTextSize.SMALL)
        val medium = AgentMirrorParams.reading(MirrorTextSize.MEDIUM)
        val large = AgentMirrorParams.reading(MirrorTextSize.LARGE)

        assertEquals(11f, small.bodySp)
        assertEquals(15f, small.lineHeightSp)
        assertEquals(14f, small.headingSp)
        assertEquals(20f, small.headingLineHeightSp)

        assertEquals(12f, medium.bodySp)
        assertEquals(16f, medium.lineHeightSp)
        assertEquals(16f, medium.headingSp)
        assertEquals(24f, medium.headingLineHeightSp)

        assertEquals(14f, large.bodySp)
        assertEquals(19f, large.lineHeightSp)
        assertEquals(20f, large.headingSp)
        assertEquals(30f, large.headingLineHeightSp)
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
        assertEquals(14f, small.fontSize.value)
        assertEquals(20f, large.fontSize.value)
        assertEquals(TextAlign.Start, small.textAlign, "标识行与正文同一条左缘")
        assertEquals(RearCueColors.onBackgroundSecondary, small.color)

        // 副行不参与标题/正文角色对换，仍保持 10/11/13sp。
        val sizes = listOf(MirrorTextSize.SMALL, MirrorTextSize.MEDIUM, MirrorTextSize.LARGE)
        sizes.forEach { size ->
            val reading = AgentMirrorParams.reading(size)
            assertEquals(
                when (size) {
                    MirrorTextSize.SMALL -> 10f
                    MirrorTextSize.MEDIUM -> 11f
                    MirrorTextSize.LARGE -> 13f
                },
                reading.headingSubtitleSp,
            )
        }
    }

    @Test
    fun `主屏设置页会话列表字号不随会话页角色对换`() {
        val small = AgentMirrorParams.settingsSessionListTypography(MirrorTextSize.SMALL)
        val medium = AgentMirrorParams.settingsSessionListTypography(MirrorTextSize.MEDIUM)
        val large = AgentMirrorParams.settingsSessionListTypography(MirrorTextSize.LARGE)

        assertEquals(11f, small.titleSp)
        assertEquals(10f, small.subtitleSp)
        assertEquals(12f, medium.titleSp)
        assertEquals(11f, medium.subtitleSp)
        assertEquals(14f, large.titleSp)
        assertEquals(13f, large.subtitleSp)
    }

    @Test
    fun `固定标识行的预留高度是行高加间距`() {
        // 本机：标识行行高 16sp ≈ 45px、间距 8dp ≈ 23px ⇒ 预留 68px（票 #161 判例）
        assertEquals(68, AgentMirrorParams.headingReservePx(lineHeightPx = 45f, gapPx = 22.5f))
        assertEquals(0, AgentMirrorParams.headingReservePx(lineHeightPx = 0f, gapPx = 0f))
        // 两行标识行把副行行高一并预留；无副行保持原单行口径。
        assertEquals(108, AgentMirrorParams.headingReservePx(lineHeightPx = 45f, gapPx = 22.5f, subtitleLineHeightPx = 40f))
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
        assertEquals(AgentMirrorParams.GLOW_CYCLE_MS, waiting.cycleMs, "呼吸周期（票 #230：1.2s/周期）")
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

        // 亮度阶梯（票 #230 改判）：工作中提亮 ×2 后峰值与等待档齐平（1.0），靠色相＋动效区分；
        // 静止档（空闲/出错）仍低于两者峰值。
        assertEquals(1f, working.alphaMax, "工作中恒亮提到满幅（票 #230）")
        assertEquals(waiting.alphaMax, working.alphaMax, 1e-4f)
        listOf(idle, error).forEach {
            assertTrue(it.alphaMax < waiting.alphaMax, "静止档必须低于峰值档")
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
    fun `流动亮段段长与恒亮亮度钉死——票 230 加长提亮`() {
        // 票 #230 实机：亮斑 ×1.5（0.35→0.525）、恒亮 ×2（0.5→1.0），与呼吸下限脱钩。
        assertEquals(0.525f, AgentMirrorParams.GLOW_FLOW_ARC)
        assertEquals(1.0f, AgentMirrorParams.GLOW_FLOW_ALPHA)
    }

    @Test
    fun `呼吸起伏参数钉死——票 230 快频低暗位 5 次后收尾`() {
        assertEquals(1200, AgentMirrorParams.GLOW_CYCLE_MS, "1.2s/周期")
        assertEquals(0.1f, AgentMirrorParams.GLOW_ALPHA_MIN, "暗位低亮")
        assertEquals(1f, AgentMirrorParams.GLOW_ALPHA_MAX)
        assertEquals(5, AgentMirrorParams.GLOW_BREATH_CYCLES, "起伏 5 次后恒定低亮")
        assertEquals(600, AgentMirrorParams.GLOW_BREATH_SETTLE_MS, "触屏打断/收尾渐落时长")
        assertTrue(
            AgentMirrorParams.GLOW_ALPHA_MIN < AgentMirrorParams.GLOW_FLOW_ALPHA,
            "呼吸暗位低于流动恒亮（各档不再同源）",
        )
    }

    // —— 亮度倍率（spec 0021 修订 / 票 #214：主屏滑动条 → 各档 alpha × 倍率，封顶 1.0） ——

    @Test
    fun `亮度倍率乘各档 alpha 且封顶 1`() {
        // 静止档 0.5 × 0.5 = 0.25：往低调的档。
        val dim = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572, 0.5f)!!
        assertEquals(0.25f, dim.alphaMin)
        assertEquals(0.25f, dim.alphaMax)
        // 呼吸档 2×（票 #230 暗位 0.1）：下限 0.2、上限 1.0 封顶。
        val blown = AgentMirrorParams.statusGlow(
            AgentStatus.WAITING_FOR_APPROVAL, BridgeLinkStatus.CONNECTED, 904, 572, 2f,
        )!!
        assertEquals(0.2f, blown.alphaMin, 1e-4f)
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
        // 票 #216 上限 2×→10×（1000%）：9× 已在范围内不钳，100× 才钳到 10×。
        val inRange = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572, 9f)!!
        val max = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572, 10f)!!
        assertEquals(max.alphaMin, inRange.alphaMin)
        val above = AgentMirrorParams.statusGlow(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED, 904, 572, 100f)!!
        assertEquals(max.alphaMin, above.alphaMin)
        assertEquals(10f, GlowBrightness.MAX, "上限 1000%（票 #216）")
    }

    // —— 屏内光晕深度（票 #220：AGSL 距离场 wash 的衰减距离，撤 #218 模糊半径口径） ——

    @Test
    fun `光晕深度——随倍率增长且夹紧防糊屏`() {
        val dim = AgentMirrorParams.glowHaloDepthPx(1f, 16f, 572)
        val bright = AgentMirrorParams.glowHaloDepthPx(5f, 16f, 572)
        assertTrue(dim < bright, "倍率越高光晕越深（>100% 增量走面积）")
        // 深度＝描边×2.5×倍率：16×2.5×1=40；拉满 16×2.5×10=400 夹到短边 35%=200.2。
        assertEquals(40f, dim, 0.01f)
        assertEquals(572f * 0.35f, AgentMirrorParams.glowHaloDepthPx(10f, 16f, 572), 0.01f, "拉满夹紧短边 35% 深度")
        // 亮度低于 100% 时光晕同步收浅。
        assertTrue(AgentMirrorParams.glowHaloDepthPx(0.5f, 16f, 572) < dim)
    }

    @Test
    fun `statusGlow 附带光晕深度——病态几何归零纯描边`() {
        val spec = AgentMirrorParams.statusGlow(AgentStatus.WORKING, BridgeLinkStatus.CONNECTED, 904, 572, 3f)!!
        assertTrue(spec.haloDepthPx > 0f, "正常几何：有光晕")
        val degenerate = AgentMirrorParams.statusGlow(AgentStatus.WORKING, BridgeLinkStatus.CONNECTED, 0, 0, 3f)!!
        assertEquals(0f, degenerate.haloDepthPx, "病态几何：深度 0，渲染层不画 wash")
    }

    // —— 内缘圆角（票 #274：淡出轮廓独立圆角，深光晕四角不顶直角尖） ——

    @Test
    fun `内缘圆角半径——顶格恒同屏幕圆角`() {
        // 票 #280 顶格（比例 1.0）：浅/深光晕内缘圆角都＝屏幕圆角——角落内缘与玻璃弧同弯。
        assertEquals(97f, AgentMirrorParams.glowHaloInnerRadiusPx(97, 10f), 0.01f)
        assertEquals(97f, AgentMirrorParams.glowHaloInnerRadiusPx(97, 153f), 0.01f)
        assertEquals(1f, AgentMirrorParams.GLOW_HALO_INNER_CORNER_MIN_RATIO)
        // 病态：无圆角/无深度退化 0（渲染层 innerRadius=0 走尖角内矩形，直边口径不变）。
        assertEquals(0f, AgentMirrorParams.glowHaloInnerRadiusPx(0, 153f))
        assertEquals(0f, AgentMirrorParams.glowHaloInnerRadiusPx(97, 0f))
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
    fun `会话状态点与状态光带五档同色_断链压档_停用不画`() {
        assertEquals(RearCueColors.accent, AgentMirrorParams.statusColor(AgentStatus.WORKING, BridgeLinkStatus.CONNECTED))
        assertEquals(RearCueColors.waiting, AgentMirrorParams.statusColor(AgentStatus.WAITING_FOR_APPROVAL, BridgeLinkStatus.CONNECTED))
        assertEquals(RearCueColors.idle, AgentMirrorParams.statusColor(AgentStatus.IDLE, BridgeLinkStatus.CONNECTED))
        assertEquals(RearCueColors.error, AgentMirrorParams.statusColor(AgentStatus.ERROR, BridgeLinkStatus.CONNECTED))
        assertEquals(
            RearCueColors.onBackgroundSecondary,
            AgentMirrorParams.statusColor(AgentStatus.WORKING, BridgeLinkStatus.RETRYING),
        )
        assertEquals(
            RearCueColors.onBackgroundSecondary,
            AgentMirrorParams.statusColor(AgentStatus.WAITING_FOR_APPROVAL, BridgeLinkStatus.CONNECTING),
        )
        assertNull(AgentMirrorParams.statusColor(AgentStatus.WORKING, BridgeLinkStatus.DISABLED))
    }
}
