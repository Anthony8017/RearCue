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
import com.rearcue.poc.core.GlowBrightness
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
 * （actionLine）随状态词/动作行一并删除——本对象只剩正文一档与状态光带参数
 * （spec 0021 / 票 #208：Approval Glow 泛化为常驻五档 Status Glow）。
 *
 * 输入约定：[screenWidthPx]/[screenHeightPx] 是背屏窗口像素（任意方向，函数内部按长短边
 * 归一——小米 17 Pro 背屏 904×572 与旋转态同参）。
 */
object AgentMirrorParams {

    /** 会话输出正文的字号档（sp）：镜像的主体阅读面（票 #86 实机修订：正文区独占剩余高度＋内部滚动，
     * 不再用 maxLines 截断——历史经验：maxLines 档在小屏把核心阅读面推出视口）。 */
    const val REPLY_SP_BASE = 12f

    // —— 阅读版式与正文档位（spec 0017 / 票 #169） ——

    // 档位类型是 [:core] 的 [MirrorTextSize]（用户偏好，设置层与渲染层共用同一类型）；
    // 本对象只负责「档位 → 字号」的映射（见 [reading]）与版式常量。
    // 版心边距不在这里——spec 0019 起 Agent 线走 [SafeArea.flushReadingViewport]（贴缘，零设计留白），
    // 原 spec 0017 的右距 16dp（RIGHT_INSET）随之归零退役。

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

    /**
     * 会话状态点直径（dp）：正文标题行只保留这一颗状态点，不再另画 PC 桥链路点。
     * 空态没有会话状态，不画点；有内容时与列表状态表达共用同一套状态语义。
     */
    val STATUS_DOT_DP = 8

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
        /** Agent 会话页会话标识行主行字号（sp）——随档联动，与正文字号角色对换。 */
        val headingSp: Float,
        /** 会话标识行主行行距（sp）。 */
        val headingLineHeightSp: Float,
        /**
         * 会话列表条目副行字号（sp）——恒比主行小一号（issue #213），保持 10/11/13sp、
         * 不随主行对换。副行不上正文页标识行（2026-10-03 机主定夺）；
         * 本字段仍被背屏会话列表条目消费。
         */
        val headingSubtitleSp: Float,
        /** 会话列表条目副行行距（sp）。 */
        val headingSubtitleLineHeightSp: Float,
        /** 代码块行距（sp）＝ [lineHeightSp] × [CODE_LINE_HEIGHT_FACTOR]（由 [reading] 派生，不手填）。 */
        val codeLineHeightSp: Float = 0f,
    )

    /**
     * 档位 → Agent 会话页全套字号/行距：小 11/15（正文）＋14/20（标题）、中 12/16＋16/24、大 14/19＋20/30。
     *
     * 纯函数：主屏设置写档、背屏读档，两侧都过这里，数值只此一处。
     */
    fun reading(size: MirrorTextSize): Reading = when (size) {
        MirrorTextSize.SMALL -> Reading(
            bodySp = 11f,
            lineHeightSp = 15f,
            headingSp = 14f,
            headingLineHeightSp = 20f,
            headingSubtitleSp = 10f,
            headingSubtitleLineHeightSp = 14f,
        )
        MirrorTextSize.MEDIUM -> Reading(
            bodySp = 12f,
            lineHeightSp = 16f,
            headingSp = 16f,
            headingLineHeightSp = 24f,
            headingSubtitleSp = 11f,
            headingSubtitleLineHeightSp = 15f,
        )
        MirrorTextSize.LARGE -> Reading(
            bodySp = 14f,
            lineHeightSp = 19f,
            headingSp = 20f,
            headingLineHeightSp = 30f,
            headingSubtitleSp = 13f,
            headingSubtitleLineHeightSp = 18f,
        )
    }.let { it.copy(codeLineHeightSp = it.lineHeightSp * CODE_LINE_HEIGHT_FACTOR) }

    /** 主屏设置页会话列表的独立排版档；不随 Agent 会话页标题/正文角色对换而改变。 */
    data class SettingsSessionListTypography(
        val titleSp: Float,
        val titleLineHeightSp: Float,
        val subtitleSp: Float,
        val subtitleLineHeightSp: Float,
    )

    /** 主屏设置页会话列表保持既有 11/12/14sp 主行与 10/11/13sp 副行。 */
    fun settingsSessionListTypography(size: MirrorTextSize): SettingsSessionListTypography {
        val reading = reading(size)
        return SettingsSessionListTypography(
            titleSp = when (size) {
                MirrorTextSize.SMALL -> 11f
                MirrorTextSize.MEDIUM -> 12f
                MirrorTextSize.LARGE -> 14f
            },
            titleLineHeightSp = when (size) {
                MirrorTextSize.SMALL -> 15f
                MirrorTextSize.MEDIUM -> 16f
                MirrorTextSize.LARGE -> 19f
            },
            subtitleSp = reading.headingSubtitleSp,
            subtitleLineHeightSp = reading.headingSubtitleLineHeightSp,
        )
    }

    /**
     * 提问泡最大宽度（px，spec 0017）：版心宽 × [BUBBLE_MAX_WIDTH_RATIO]。
     * 病态几何（未采集的 0 宽）不抛错、不给负值——退到 0 即「画不出泡」。
     */
    fun bubbleMaxWidthPx(viewportWidthPx: Int): Int =
        (viewportWidthPx.coerceAtLeast(0) * BUBBLE_MAX_WIDTH_RATIO).toInt()

    /**
     * 会话标识行样式（票 #161 / spec 0017）：小字、次要色、左对齐，字号随档联动。
     * 绘制在 [AgentMirrorView]（标识行的**脉冲、点按热区、手势带、会话状态点共用同一几何**，
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
     * 一个提问泡的**泡外宽**（px，2026-10-03 机主定夺「泡宽各自贴内容」）：按**本泡自身**文字
     * 实测宽伸缩（短问短泡），封顶 [bubbleMaxWidthPx]、地板＝泡内水平内边距之和（空提问只剩
     * 衬里）、再夹进栏宽。反转旧「同屏泡共用最宽值」——旧口径下一个长提问会把同屏所有短泡
     * 一并撑到顶格。测量与渲染共用此出口（每泡一个数，不另算第二遍）。
     * 纯函数，判例钉在 [AgentMirrorParamsTest]。
     */
    fun bubbleOuterWidthPx(
        naturalTextWidthPx: Int,
        paddingHorizontalTotalPx: Int,
        columnWidthPx: Int,
    ): Int {
        val width = columnWidthPx.coerceAtLeast(0)
        val cap = bubbleMaxWidthPx(width)
        val floor = minOf(paddingHorizontalTotalPx.coerceAtLeast(0), width)
        return (naturalTextWidthPx.coerceAtLeast(0) + paddingHorizontalTotalPx.coerceAtLeast(0))
            .coerceAtLeast(floor)
            .coerceAtMost(maxOf(cap, floor))
            .coerceAtMost(width)
    }

    /**
     * 会话标识行固定渲染时，正文内容顶部要预留的高度（px，票 #161）：
     * 主行行高 + 一档间距。正文页标识行恒单行（2026-10-03 机主定夺：副行不上正文页），
     * 副行行高不再进本口径。纯函数（各值由渲染侧按 density 折算传入），
     * 判例钉在 [AgentMirrorParamsTest]。
     */
    fun headingReservePx(
        lineHeightPx: Float,
        gapPx: Float,
    ): Int = (
        lineHeightPx.coerceAtLeast(0f) +
            gapPx.coerceAtLeast(0f)
        ).roundToInt()

    /**
     * 会话状态点的语义档（2026-10-03 机主定夺）：正文标题行与状态光带读同一套会话状态；
     * 工作中蓝、等待确认琥珀黄、空闲绿、出错红。PC 桥未连时旧状态不可信，统一压成
     * [SessionStatusDot.DISCONNECTED] 灰点。这里不再表达“链路已连接＝蓝点”。
     */
    enum class SessionStatusDot(val color: Color) {
        WORKING(RearCueColors.accent),
        WAITING(RearCueColors.waiting),
        IDLE(RearCueColors.idle),
        ERROR(RearCueColors.error),
        DISCONNECTED(RearCueColors.onBackgroundSecondary),
    }

    /** 会话状态 × 链路 → 标题行状态点语义档；判据收口在此，判例钉在 [AgentMirrorParamsTest]。 */
    fun statusDot(status: AgentStatus, link: BridgeLinkStatus): SessionStatusDot =
        if (link != BridgeLinkStatus.CONNECTED) {
            SessionStatusDot.DISCONNECTED
        } else {
            when (status) {
                AgentStatus.WORKING -> SessionStatusDot.WORKING
                AgentStatus.WAITING_FOR_APPROVAL -> SessionStatusDot.WAITING
                AgentStatus.IDLE -> SessionStatusDot.IDLE
                AgentStatus.ERROR -> SessionStatusDot.ERROR
            }
        }

    // —— Status Glow（CONTEXT.md「Status Glow（状态光带）」/ spec 0021 / 票 #208）纯函数参数 ——

    /** 呼吸明暗起伏周期（ms）：一个「亮→暗→亮」完整来回（票 #230 实机定稿：2400→1200）。 */
    const val GLOW_CYCLE_MS = 1200

    /** 呼吸暗位低亮（alpha，票 #230：0.5→0.1）：>0——任何相位/收尾都不熄灭；也是触屏打断后的落点。 */
    const val GLOW_ALPHA_MIN = 0.1f

    /** 呼吸亮度上限（alpha ≤ 1）：全场最亮档——「该我了」是五种状态里最抢眼的信号。 */
    const val GLOW_ALPHA_MAX = 1f

    /** 呼吸起伏次数（票 #230）：满 5 个周期后渐变至 [GLOW_ALPHA_MIN] 恒定低亮；触屏随时打断提前落。 */
    const val GLOW_BREATH_CYCLES = 5

    /** 触屏打断/收尾渐落时长（ms，票 #230「渐变至低亮」——半周期同档手感）。 */
    const val GLOW_BREATH_SETTLE_MS = 600

    /** 流动周期（ms）：工作中档亮段沿环带走完一圈——「感觉得到在动但不吸睛」初档，实机验收定稿。 */
    const val GLOW_FLOW_CYCLE_MS = 8000

    /** 流动档恒亮 alpha（票 #230 实机提亮 ×2：0.5→1.0；与呼吸下限脱钩——呼吸暗位 0.1 不再同档）。 */
    const val GLOW_FLOW_ALPHA = 1f

    /**
     * 流动亮段占整环的比例（spec 0021 / 票 #208）：段长画法参数——亮段中心最亮、两端渐隐，
     * 段长由参数给定（[GLOW_FLOW_ARC]），渲染层（票 #220 AGSL 着色器）照单读取，不自行定段长。
     * 票 #230 实机加长 ×1.5：0.35→0.525（段内过渡改平滑见着色器 smoothstep）。
     */
    const val GLOW_FLOW_ARC = 0.525f

    /** 静止档恒亮 alpha（空闲/出错/断链——常驻档低亮，能瞥见即可；票 #214 实机提亮：0.3→0.5）。 */
    const val GLOW_STILL_ALPHA = 0.5f

    /** 描边宽度取短边的比例（背屏尺寸各异，比例档比像素档稳；票 #214 实机加宽：0.012→0.028）。 */
    const val GLOW_STROKE_RATIO = 0.028f

    /** 描边宽度夹紧下限（px，票 #214：4→8）。 */
    const val GLOW_STROKE_MIN_PX = 8f

    /** 描边宽度夹紧上限（px，票 #214：12→28）。 */
    const val GLOW_STROKE_MAX_PX = 28f

    // —— 屏内光晕（票 #216 起、票 #220 定稿 AGSL 距离场：边缘最亮向内连续衰减，无分层无稀释） ——

    /** 1× 亮度时光晕总深度＝核心深度档（距场 wash 从屏缘向内亮 depth px 后归零）。 */
    const val GLOW_HALO_DEPTH_FACTOR = 2.5f

    /** 光晕深度上限＝短边比例：>100% 亮度的增量走深度，夹紧防糊屏吞正文（票 #216）。 */
    const val GLOW_HALO_DEPTH_MAX_RATIO = 0.35f

    /** 距离场衰减指数（票 #220）：alpha ∝ (1−dist/depth)^exp——>1 越聚拢屏缘，观感「贴边亮」。 */
    const val GLOW_HALO_FALLOFF_EXP = 1.6f

    /**
     * 内缘圆角下限＝屏幕圆角半径×本比例（票 #274；票 #276/#278/#280 追调圆：0.3→0.6→0.8→1.0
     * 顶格）：淡出深度超过屏幕圆角半径时，内缩偏移的圆角半径（r−depth）变负、内缘在四角退化
     * 为直角尖——下限保证内缘永远有可见圆弧。1.0＝内缘圆角恒同屏幕圆角，角落内缘与玻璃弧同弯。
     */
    const val GLOW_HALO_INNER_CORNER_MIN_RATIO = 1f

    /** 状态点颜色：与状态光带共用五档色，点本身保持静止。DISABLED 不画点。 */
    fun statusColor(status: AgentStatus, link: BridgeLinkStatus): Color? =
        glowTier(status, link)?.color

    private fun glowTier(status: AgentStatus, link: BridgeLinkStatus): GlowTier? = when (link) {
        // 断链压档优先于会话状态：连接中/重连中亮的是「链路不可信」，不是旧会话状态。
        BridgeLinkStatus.CONNECTING, BridgeLinkStatus.RETRYING -> GlowTier.DISCONNECTED
        BridgeLinkStatus.CONNECTED -> when (status) {
            AgentStatus.WORKING -> GlowTier.WORKING
            AgentStatus.WAITING_FOR_APPROVAL -> GlowTier.WAITING
            AgentStatus.IDLE -> GlowTier.IDLE
            AgentStatus.ERROR -> GlowTier.ERROR
        }
        BridgeLinkStatus.DISABLED -> null
    }

    /**
     * 状态 × 链路 × 屏幕几何 → Status Glow 光带规格（spec 0021 / 票 #208，照本对象纯函数惯例）：
     * 五档——工作中（accent 蓝·缓慢流动）/等待确认（琥珀黄·呼吸，全场最亮）/空闲（绿·静止
     * 低亮）/出错（error 红·静止）/断链（次要灰·静止低亮，**压过一切会话状态档**——旧状态
     * 不可信就不显示）。链路「已连」以外（连接中/重连中）一律灰档；桥未配置（DISABLED）
     * 不产档。「是否亮、亮什么色、怎么动」的判定收口在此，渲染层照单执行不自行决策。
     * 几何只决定描边宽度（短边比例折算并夹紧）；色值归设计令牌（档位色随规格出参带回）、
     * 圆角随屏运行时读取，都不进本函数。病态几何（未采集的 0×0）不抛错，退到夹紧下限。
     *
     * [brightness] 是主屏滑动条的亮度倍率（spec 0021 修订 / 票 #214，范围钳制在
     * [GlowBrightness]）：乘各档 alpha 后封顶 1.0——倍率不改档位/颜色/动效，只改亮度；
     * 顶端（2×）时呼吸档下限被封顶、起伏近乎拍平，是拉满的已知代价。缺省 1×＝基础亮度。
     */
    fun statusGlow(
        status: AgentStatus,
        link: BridgeLinkStatus,
        screenWidthPx: Int,
        screenHeightPx: Int,
        brightness: Float = GlowBrightness.DEFAULT,
    ): StatusGlow? {
        val tier = glowTier(status, link) ?: return null
        val m = GlowBrightness.coerce(brightness)
        val shortEdge = minOf(screenWidthPx, screenHeightPx).coerceAtLeast(0)
        val stroke = (shortEdge * GLOW_STROKE_RATIO).coerceIn(GLOW_STROKE_MIN_PX, GLOW_STROKE_MAX_PX)
        return StatusGlow(
            color = tier.color,
            motion = tier.motion,
            strokeWidthPx = stroke,
            alphaMin = (tier.alphaMin * m).coerceAtMost(1f),
            alphaMax = (tier.alphaMax * m).coerceAtMost(1f),
            cycleMs = tier.cycleMs,
            haloDepthPx = glowHaloDepthPx(brightness, stroke, shortEdge),
        )
    }

    /**
     * 屏内光晕的深度（票 #220，AGSL 距离场 wash 的衰减距离）：自屏缘向内亮 depth px 后
     * 连续归零（幂衰减 [GLOW_HALO_FALLOFF_EXP]，无分层无稀释——撤 #218 高斯模糊：
     * 模糊摊薄能量、只剩粗条）。深度随亮度倍率增长（>100% 的「更亮」来自更大的发光
     * 面积），夹紧短边 [GLOW_HALO_DEPTH_MAX_RATIO] 防糊屏；病态几何退化 0＝无光晕。
     */
    fun glowHaloDepthPx(brightness: Float, strokeWidthPx: Float, shortEdgePx: Int): Float {
        val m = GlowBrightness.coerce(brightness)
        if (shortEdgePx <= 0 || strokeWidthPx <= 0f) return 0f
        return (strokeWidthPx * GLOW_HALO_DEPTH_FACTOR * m)
            .coerceAtMost(shortEdgePx * GLOW_HALO_DEPTH_MAX_RATIO)
    }

    /**
     * 光晕**内缘**（淡出轮廓）的圆角半径（票 #274）：浅光晕＝外缘圆角的平行偏移
     * （cornerRadius−depth）；深光晕（depth 追平/超过屏幕圆角）不再让内缘顶成直角尖，
     * 夹到下限 [GLOW_HALO_INNER_CORNER_MIN_RATIO]×cornerRadius。病态几何（无圆角/无深度）退化 0。
     */
    fun glowHaloInnerRadiusPx(cornerRadiusPx: Int, depthPx: Float): Float {
        val r = cornerRadiusPx.coerceAtLeast(0).toFloat()
        if (r <= 0f || depthPx <= 0f) return 0f
        return (r - depthPx).coerceAtLeast(r * GLOW_HALO_INNER_CORNER_MIN_RATIO)
    }

    /** 五档规格表（spec 0021 定案）：颜色/动效型/亮度/周期全收口此表，[statusGlow] 只做链路仲裁与几何折算。 */
    private enum class GlowTier(
        val color: Color,
        val motion: GlowMotion,
        val alphaMin: Float,
        val alphaMax: Float,
        val cycleMs: Int,
    ) {
        /** 工作中：accent 蓝，缓慢流动（恒亮，亮段沿环带缓移）。 */
        WORKING(RearCueColors.accent, GlowMotion.FLOWING, GLOW_FLOW_ALPHA, GLOW_FLOW_ALPHA, GLOW_FLOW_CYCLE_MS),

        /** 等待确认：琥珀黄，呼吸（复用既有呼吸参数，全场最亮）。 */
        WAITING(RearCueColors.waiting, GlowMotion.BREATHING, GLOW_ALPHA_MIN, GLOW_ALPHA_MAX, GLOW_CYCLE_MS),

        /** 空闲：绿，静止低亮（「没事，不用管」）。 */
        IDLE(RearCueColors.idle, GlowMotion.STILL, GLOW_STILL_ALPHA, GLOW_STILL_ALPHA, 0),

        /** 出错：error 红，静止（亮度阶梯的常驻低亮档——红相本身已是「该去看」信号）。 */
        ERROR(RearCueColors.error, GlowMotion.STILL, GLOW_STILL_ALPHA, GLOW_STILL_ALPHA, 0),

        /** 断链（连接中/重连中）：次要灰，静止低亮，压过一切会话状态档。 */
        DISCONNECTED(RearCueColors.onBackgroundSecondary, GlowMotion.STILL, GLOW_STILL_ALPHA, GLOW_STILL_ALPHA, 0),
    }
}

/** 状态光带动效型（spec 0021 / 票 #208）：渲染层照此选择动效实现，不自行决策。 */
enum class GlowMotion {
    /** 呼吸：亮度在 alphaMin..alphaMax 间周期起伏（等待确认档）。 */
    BREATHING,

    /** 缓慢流动：亮段沿环带缓移，一个 cycleMs 走完一圈（工作中档）。 */
    FLOWING,

    /** 静止：恒定 alphaMin（＝alphaMax）常亮（空闲/出错/断链档）。 */
    STILL,
}

/**
 * Status Glow 状态光带参数（spec 0021 / 票 #208，[AgentMirrorParams.statusGlow] 的输出）：
 * Agent 页常驻背屏边缘环绕灯带的渲染输入——五档（工作中/等待确认/空闲/出错/断链，
 * 语义见 spec 0021 与 ADR 0012）。渲染层零决策照单执行：[color] 档位色（设计令牌，
 * 出参带回）、[motion] 动效型、[alphaMin]/[alphaMax] 亮度区间（呼吸档在区间内起伏；
 * 流动/静止档恒定，alphaMin＝alphaMax）、[cycleMs] 周期（呼吸＝一个明暗来回；流动＝
 * 亮段走完一圈；静止＝0 不使用）、[strokeWidthPx] 描边宽、[haloDepthPx] 屏内光晕深度
 * （票 #220——AGSL 距离场连续渐隐层，0＝无光晕退回纯描边）。不发声、不震动；
 * 断链档（灰）压过一切会话状态档的仲裁在 [AgentMirrorParams.statusGlow]，本类只收结果。
 */
data class StatusGlow(
    /** 档位色（设计令牌：accent/琥珀黄/idle 绿/error/次要灰）。 */
    val color: Color,
    /** 动效型（[GlowMotion]）。 */
    val motion: GlowMotion,
    /** 环带描边宽度（px，短边比例折算后夹紧）。 */
    val strokeWidthPx: Float,
    /** 亮度下限（alpha，>0——任何相位/任何位置都亮着）。 */
    val alphaMin: Float,
    /** 亮度上限（alpha ≤ 1；非呼吸档＝alphaMin）。 */
    val alphaMax: Float,
    /** 动效周期（ms；静止档为 0 不使用）。 */
    val cycleMs: Int,
    /** 屏内光晕深度（px，票 #220；AGSL 距离场连续渐隐，随亮度倍率增长、封顶防糊屏）。 */
    val haloDepthPx: Float = 0f,
)
