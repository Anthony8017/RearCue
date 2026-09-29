package com.rearcue.poc.rear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import kotlin.math.roundToInt

/**
 * Agent Mirror 渲染参数（spec 0010 / 票 #84，纯函数——JVM 判例沿 [ChargingWater]）：
 * 状态 × 屏幕几何 → 布局参数（含票 #105 Approval Glow 的光带参数）。渲染层零决策照单执行，
 * 几何语义锁死在单测。
 *
 * grilling #113（去状态词、正文最大化）后原状态标语字号档（statusSp）与动作行截断
 * （actionLine）随状态词/动作行一并删除——本对象只剩正文一档与等确认光带参数。
 *
 * 输入约定：[screenWidthPx]/[screenHeightPx] 是背屏窗口像素（任意方向，函数内部按长短边
 * 归一——小米 17 Pro 背屏 904×572 与旋转态同参）。
 */
object AgentMirrorParams {

    /** 会话输出正文的字号档（sp）：镜像的主体阅读面（票 #86 实机修订：正文区独占剩余高度＋内部滚动，
     * 不再用 maxLines 截断——历史经验：maxLines 档在小屏把核心阅读面推出视口）。 */
    const val REPLY_SP_BASE = 16f

    // —— 阅读版式与正文档位（spec 0017 / 票 #169） ——
    //
    // 档位类型是 [:core] 的 [MirrorTextSize]（用户偏好，设置层与渲染层共用同一类型）；
    // 本对象只负责「档位 → 字号」的映射（见 [reading]）与版式常量。

    /**
     * Agent 页右距屏缘（spec 0017 定案：8dp → 16dp）。
     *
     * **只属于 Agent 页**——Detail View 的右距仍是 `DisplaySafeArea.TEXT_EDGE_GUTTER_PX`，
     * 两页版心由 `SafeArea.detailTextViewport(designGutterPx, rightInsetPx)` 的入参分流，
     * 本常量绝不允许出现在 Detail 的调用链上（spec 0017 最硬边界）。
     */
    val RIGHT_INSET = RearCueSpacing.md

    /** 代码块行距相对正文行距的系数（< 1：正文行距约 1.5em，代码收在 1.15em 左右——紧凑但不挤）。 */
    const val CODE_LINE_HEIGHT_FACTOR = 0.85f

    /** 提问泡最大宽度占版心宽的比例（spec 0017：0.85）。 */
    const val BUBBLE_MAX_WIDTH_RATIO = 0.85f

    /** 提问泡圆角（dp，spec 0017）。 */
    val BUBBLE_CORNER = 12.dp

    /** 提问泡在底衬内的横向内边距（dp）——文字不许贴着底衬边缘。 */
    val BUBBLE_PADDING_HORIZONTAL = 12.dp

    /** 提问泡在底衬内的纵向内边距（dp）。 */
    val BUBBLE_PADDING_VERTICAL = RearCueSpacing.sm

    /** 轮次之间的纵向间距（dp，spec 0017）。 */
    val TURN_GAP = 12.dp

    /**
     * 会话标识行与其下正文之间的净空（dp，票 #169 实机验收修订）。
     *
     * 这个值同时是「正文顶部的预留带高度 = 标识行行高 + 本值」。实机验收抓到过：只留一档 4dp 时，
     * 正文整段垂直居中的结果会让**提问泡的顶边压住标识行**（泡是实心底衬，压上去很显眼）。
     * 取整档留白（24dp）把净空拉开。
     */
    val HEADING_GAP = RearCueSpacing.lg

    /** 提问泡与**紧随其后的回答**之间的间距（dp，spec 0017：这两条是一问一答，挨紧一点）。 */
    val PROMPT_TO_ANSWER_GAP = RearCueSpacing.sm

    /** 代码块相对相邻段的额外上下间距（dp，spec 0017）。 */
    val CODE_BLOCK_GAP = RearCueSpacing.xs

    /**
     * 相邻两轮之间的间距（dp，spec 0017 的间距表）：
     * 「提问 → 紧随其后的回答」用 [PROMPT_TO_ANSWER_GAP]（一问一答挨紧），其余用 [TURN_GAP]。
     * 代码块的上下留白不在这里——它由渲染侧的 `Modifier.padding` 加，见 `AgentReadingText`。
     */
    fun gapBetween(previous: AgentTurn?, next: AgentTurn?): Dp =
        if (previous?.role == AgentTurnRole.USER && next?.role == AgentTurnRole.AGENT) {
            PROMPT_TO_ANSWER_GAP
        } else {
            TURN_GAP
        }

    /**
     * 一个档位解析出的全部字号（渲染层照单执行）。字号一律 sp；
     * 间距/圆角那些是 dp，归本对象的独立常量，不进本数据类以免单位混淆。
     */
    data class Reading(
        /** 正文（agent 输出与提问泡内文字）字号（sp）。 */
        val bodySp: Float,
        /** 正文字距（sp）。 */
        val lineHeightSp: Float,
        /** 会话标识行字号（sp）——随档联动（票 #169 Q12）。 */
        val headingSp: Float,
        /** 会话标识行行距（sp）。 */
        val headingLineHeightSp: Float,
        /** 代码块行距（sp）＝ [lineHeightSp] × [CODE_LINE_HEIGHT_FACTOR]（由 [reading] 派生，不手填）。 */
        val codeLineHeightSp: Float = 0f,
    )

    /**
     * 档位 → 全套字号（spec 0017 表）：小 14/20/11、中 16/24/12（＝原常量档）、大 20/30/14。
     *
     * 纯函数：主屏设置写档、背屏读档，两侧都过这里，数值只此一处。
     */
    fun reading(size: MirrorTextSize): Reading = when (size) {
        MirrorTextSize.SMALL -> Reading(bodySp = 14f, lineHeightSp = 20f, headingSp = 11f, headingLineHeightSp = 15f)
        MirrorTextSize.MEDIUM -> Reading(bodySp = 16f, lineHeightSp = 24f, headingSp = 12f, headingLineHeightSp = 16f)
        MirrorTextSize.LARGE -> Reading(bodySp = 20f, lineHeightSp = 30f, headingSp = 14f, headingLineHeightSp = 19f)
    }.let { it.copy(codeLineHeightSp = it.lineHeightSp * CODE_LINE_HEIGHT_FACTOR) }

    /**
     * 提问泡最大宽度（px，spec 0017）：版心宽 × [BUBBLE_MAX_WIDTH_RATIO]。
     * 病态几何（未采集的 0 宽）不抛错、不给负值——退到 0 即「画不出泡」。
     */
    fun bubbleMaxWidthPx(viewportWidthPx: Int): Int =
        (viewportWidthPx.coerceAtLeast(0) * BUBBLE_MAX_WIDTH_RATIO).toInt()

    /**
     * 会话标识行样式（票 #161 / spec 0017）：小字、次要色、左对齐，字号随档联动。
     * 绘制在 [AgentMirrorView]（标识行的**脉冲、点按热区、手势带、链路状态点共用同一几何**，
     * 谁也别想只改一半），正文渲染件不画它。
     */
    fun headingStyle(
        inherited: TextStyle,
        size: MirrorTextSize,
        color: Color = RearCueColors.onBackgroundSecondary,
    ): TextStyle = inherited.merge(
        TextStyle(
            color = color,
            fontSize = reading(size).headingSp.sp,
            lineHeight = reading(size).headingLineHeightSp.sp,
            textAlign = TextAlign.Start,
        ),
    )

    /**
     * 提问泡**泡内文字**的排布宽度（px）：栏宽减去泡内左右内边距**之和**，再夹到泡宽上限之内。
     *
     * [paddingHorizontalTotalPx] 是左右两侧内边距**加起来**的值（渲染侧按
     * `BUBBLE_PADDING_HORIZONTAL × 2` 折算）——名字里带 `Total` 就是为了别让人误传单侧。
     *
     * 这是本页最容易写错的一条算术（评审实证）：泡的外宽上限是「版心 × 0.85」，但泡里能排字的
     * 只有 `外宽 − 内边距之和`——若拿外宽去量文字，文字会按更宽的约束折行，再塞进窄一圈的泡里，
     * 结果是**长提问白白多折一行**（外层 `width` 会把它夹回来，不溢出但难看）。
     */
    fun bubbleTextWidthPx(columnWidthPx: Int, paddingHorizontalTotalPx: Int): Int {
        val outer = bubbleMaxWidthPx(columnWidthPx)
        val inner = outer - paddingHorizontalTotalPx.coerceAtLeast(0)
        return inner.coerceAtLeast(0)
    }

    /**
     * 会话标识行固定渲染时，正文内容顶部要预留的高度（px，票 #161）：
     * 标识行行高 + 一档间距——正文首行（含滚动中半截的那行）与标识行留出清晰间隔。
     * 纯函数（`lineHeightPx`/`gapPx` 由渲染侧按 density 折算传入），判例钉在 [AgentMirrorParamsTest]。
     */
    fun headingReservePx(lineHeightPx: Float, gapPx: Float): Int =
        (lineHeightPx.coerceAtLeast(0f) + gapPx.coerceAtLeast(0f)).roundToInt()

    /**
     * 链路状态点的语义档（票 #165，纯判据不碰色值）：[LinkDot.CONNECTED] 已连接（accent 实心点）、
     * [LinkDot.PENDING] 连接中/重连中（次要灰点）、null 未配置/停用（不画点）。渲染层照此选令牌。
     */
    enum class LinkDot { CONNECTED, PENDING }

    /** 链路状态 → 状态点语义档（票 #165）：判据收口在此，判例钉在 [AgentMirrorParamsTest]。 */
    fun linkDot(status: BridgeLinkStatus): LinkDot? = when (status) {
        BridgeLinkStatus.CONNECTED -> LinkDot.CONNECTED
        BridgeLinkStatus.CONNECTING, BridgeLinkStatus.RETRYING -> LinkDot.PENDING
        BridgeLinkStatus.DISABLED -> null
    }

    // —— Approval Glow（CONTEXT.md「Approval Glow」/ 票 #105）纯函数参数 ——

    /** 明暗起伏周期（ms）：一个「亮→暗→亮」完整来回。 */
    const val GLOW_CYCLE_MS = 2400

    /** 起伏亮度下限（alpha）：>0 ——「持续点亮」，任何相位都不熄灭。 */
    const val GLOW_ALPHA_MIN = 0.4f

    /** 起伏亮度上限（alpha ≤ 1）。 */
    const val GLOW_ALPHA_MAX = 1f

    /** 描边宽度取短边的比例（背屏尺寸各异，比例档比像素档稳）。 */
    const val GLOW_STROKE_RATIO = 0.012f

    /** 描边宽度夹紧下限（px）。 */
    const val GLOW_STROKE_MIN_PX = 4f

    /** 描边宽度夹紧上限（px）。 */
    const val GLOW_STROKE_MAX_PX = 12f

    /**
     * 状态 × 屏幕几何 → Approval Glow 光带参数（票 #105，照本对象纯函数惯例）：
     * 仅 Waiting-for-Approval 返回非 null（工作中/空闲不亮、状态离开 WAITING 即灭），
     * 其余状态一律 null——「是否亮」的判定收口在此，渲染层照单执行不自行决策。
     * 几何只决定描边宽度（短边比例折算并夹紧）；色值归设计令牌、圆角随屏运行时读取，
     * 都不进本函数。病态几何（未采集的 0×0）不抛错，退到夹紧下限。
     */
    fun approvalGlow(
        status: AgentStatus,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): ApprovalGlow? {
        if (status != AgentStatus.WAITING_FOR_APPROVAL) return null
        val shortEdge = minOf(screenWidthPx, screenHeightPx).coerceAtLeast(0)
        return ApprovalGlow(
            strokeWidthPx = (shortEdge * GLOW_STROKE_RATIO).coerceIn(GLOW_STROKE_MIN_PX, GLOW_STROKE_MAX_PX),
            alphaMin = GLOW_ALPHA_MIN,
            alphaMax = GLOW_ALPHA_MAX,
            cycleMs = GLOW_CYCLE_MS,
        )
    }
}

/**
 * Approval Glow 光带参数（票 #105，[AgentMirrorParams.approvalGlow] 的输出）：
 * 等确认存续期背屏边缘环绕灯带的渲染输入——持续点亮（[alphaMin] > 0）＋亮度周期性
 * 起伏（[cycleMs]）。不发声、不震动；与会话标识行 3 秒脉冲（票 #85）分工共存，
 * 谁都不改谁的语义。
 */
data class ApprovalGlow(
    /** 环带描边宽度（px，短边比例折算后夹紧）。 */
    val strokeWidthPx: Float,
    /** 起伏亮度下限（alpha，>0——任何相位都亮着）。 */
    val alphaMin: Float,
    /** 起伏亮度上限（alpha ≤ 1）。 */
    val alphaMax: Float,
    /** 一个明暗起伏周期（ms）。 */
    val cycleMs: Int,
)
