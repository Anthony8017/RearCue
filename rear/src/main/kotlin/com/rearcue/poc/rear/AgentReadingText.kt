package com.rearcue.poc.rear

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import kotlin.math.ceil

/**
 * Agent 页**正文**（spec 0017 / 票 #169）：**左对齐**问答流——机主提问走右锚提问泡、
 * agent 输出走左侧纯文本，两者共用同一条左缘/右缘版心。
 *
 * 会话标识行**不在这里**：它要带脉冲、点按热区、手势带与链路状态点，四者必须共用同一几何，
 * 全在 [AgentMirrorView] 里画（票 #162 评审的老账）；本件只画正文。
 *
 * 与通知详情的关系（spec 0017 最硬边界）：**不共用**。[DetailText] 仍走 [CenteredReadingText]
 * 的「每行居中 + 标题正文整体居中 + 右距 8px」；本件走 `agentReadingViewport`（右距 16dp）
 * 与左对齐，两页互不渗漏。共用件只剩行框工具（[appendLineBoundsTo]）与几何纯函数。
 *
 * 字号来自 [AgentMirrorParams.reading]（主屏设置页的三档单选），本件零决策照单执行。
 *
 * 滚动语义仍归调用方：[scroll] 由页面级持有（切页不丢回看位置），滚动策略见 [MirrorScrollPolicy]；
 * 空正文用 [emptyScroll] 独立视口，避免其 maxValue=0 把历史回看位置钳回顶部。
 */
@Composable
internal fun AgentReadingText(
    turns: List<AgentTurn>,
    size: MirrorTextSize,
    rules: SafeArea,
    scroll: ScrollState,
    emptyScroll: ScrollState,
    modifier: Modifier = Modifier,
    onBodyTap: (() -> Unit)? = null,
    /**
     * 会话标识行那一条带的高度（px）：正文的垂直居中不许顶进这条带（实机验收：提问泡的实心
     * 底衬压住了会话名）。由 [AgentMirrorLayer] 传入——它就是那条带的实际高度，两处共用一个数。
     */
    headingBandPx: Int = 0,
    /**
     * 会话标识行的左缘内缩（px）：标识行本体由 [AgentMirrorLayer] 画（它要带脉冲/热区/状态点，
     * 四者共用同一几何），本件把正文整列**右移** [bodyInsetPx]（宽度不变），让正文左缘与标识行
     * 文字的左缘落在同一条线上。
     */
    bodyInsetPx: Int = 0,
) {
    val density = LocalDensity.current
    val viewport = rules.agentReadingViewport(density)
    if (viewport.width <= 0 || viewport.height <= 0) return
    // 正文版心：整列**右移** [bodyInsetPx]（宽度不变）——标识行左缘在「点 + 间距」之后，
    // 正文左缘要落在同一条线上，就得整体挪一个内缩量。**不能收右缘**：收右缘会把整列往左挤，
    // 右锚的提问泡跟着左移，看起来像挂在屏幕中间（实机诊断 `col=421`、泡左缘 x=371 就是这么来的）。
    val bodyViewport = remember(viewport, bodyInsetPx) {
        val inset = bodyInsetPx.coerceIn(0, (viewport.width - 1).coerceAtLeast(0))
        viewport.copy(left = viewport.left + inset, right = viewport.right + inset)
    }

    val reading = AgentMirrorParams.reading(size)
    val inherited = LocalTextStyle.current
    val bodyStyle = inherited.merge(
        TextStyle(
            color = RearCueColors.onBackground,
            fontSize = reading.bodySp.sp,
            lineHeight = reading.lineHeightSp.sp,
            textAlign = TextAlign.Start,
        ),
    )
    val codeStyle = inherited.merge(
        TextStyle(
            color = RearCueColors.onBackground,
            fontSize = reading.bodySp.sp,
            lineHeight = reading.codeLineHeightSp.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Start,
        ),
    )
    val inlineCodeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        color = RearCueColors.onBackgroundSecondary,
    )

    val measurer = rememberTextMeasurer()

    // 一次测量（版心宽）→ 定栏宽 → 再一次测量（栏宽）：行框与渲染文本用同一份解析后样式，
    // 栏宽只为「提问泡不超过版心 85%」而收窄，纯文本段落仍可排满版心。
    val layout = remember(measurer, turns, bodyViewport.width, reading, density) {
        measureTurns(
            measurer = measurer,
            turns = turns,
            viewportWidthPx = bodyViewport.width,
            bodyStyle = bodyStyle,
            codeStyle = codeStyle,
            inlineCodeStyle = inlineCodeStyle,
            density = density,
        )
    }

    val contentHeight = layout.items.sumOf { it.heightPx } + layout.gapsPx
    val bounds = remember(layout) {
        buildList {
            var cursor = 0
            layout.items.forEachIndexed { index, item ->
                if (item.heightPx > 0) {
                    item.layout.appendLineBoundsTo(this, top = cursor)
                    cursor += item.heightPx + layout.gapAfter(index)
                }
            }
        }
    }
    val padding = remember(layout, bodyViewport, bounds, headingBandPx) {
        rules.detailTextPadding(
            viewport = bodyViewport,
            textHeight = contentHeight,
            lines = bounds,
            // 正文不许顶进固定的会话标识行那一条带（实机验收：提问泡的底衬压住了会话名）。
            minBeforePx = headingBandPx,
        )
    }

    Box(
        modifier
            .fillMaxSize()
            // 点按兜底（spec 0013 的「Agent 页点正文切回通知页」）：本件外层是个铺满版心的 Box，
            // 它会成为命中目标却**不消费**事件——实机踩过：点按于是穿过它落到外层的内容页切换上，
            // 连会话标识行的点按都被这条吃掉（`area=content-page` 而非 `area=agent-session-line`）。
            // 在这里自己认领这一层：正文留白上的点按归「切内容页」，文字本体上的点按由
            // [AgentParagraph] 的 clickable 先接（子先于父，语义不变）。
            .then(
                if (onBodyTap != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { onBodyTap() }
                    }
                } else {
                    Modifier
                },
            )
            .padding(
                start = with(density) { bodyViewport.left.toDp() },
                top = with(density) { bodyViewport.top.toDp() },
                end = with(density) { (rules.windowWidth - bodyViewport.right).toDp() },
                bottom = with(density) { (rules.windowHeight - bodyViewport.bottom).toDp() },
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(if (layout.items.isEmpty()) emptyScroll else scroll)
                .padding(
                    top = with(density) { padding.before.toDp() },
                    bottom = with(density) { padding.after.toDp() },
                ),
            horizontalAlignment = Alignment.Start,
        ) {
            layout.items.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(Modifier.height(with(density) { layout.gapAfter(index - 1).toDp() }))
                }
                when (item.turn.role) {
                    AgentTurnRole.USER -> PromptBubble(
                        item = item,
                        outerWidthPx = layout.bubbleOuterWidthPx,
                        density = density,
                    )
                    AgentTurnRole.AGENT -> AgentParagraph(item, onBodyTap)
                }
            }
        }
    }
}

/**
 * 提问泡（CONTEXT.md「Prompt Bubble」）：深灰圆角底衬、**右锚**、文字右对齐。
 *
 * 宽度口径与测量严格一致（本件最容易写错的地方）：泡外宽 = `min(栏宽, 版心 × 0.85)`；
 * 泡内文字**按测量时的同一棵树排**——把该轮的所有段用换行拼成**一个** `Text`（测量就是这么量的）。
 * 拆成多个 `Text` 各占满宽会让换行与测量脱节，实机验收抓到过：泡宽按测量行宽算、文字却按整段
 * 满宽排，泡就盖到了会话标识行上。
 *
 * agent 输出不套任何框——「对齐方向 + 有无底衬」两重区分说话人，不加「你/我」标记。
 */
@Composable
private fun PromptBubble(
    item: MeasuredTurn,
    outerWidthPx: Int,
    density: Density,
) {
    val bubbleText = remember(item.blocks) {
        buildAnnotatedString {
            item.blocks.forEachIndexed { index, block ->
                if (index > 0) append("\n")
                append(block.annotated)
            }
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .width(with(density) { outerWidthPx.coerceAtLeast(0).toDp() })
                .clip(RoundedCornerShape(AgentMirrorParams.BUBBLE_CORNER))
                .background(RearCueColors.surfaceHighlight)
                .padding(
                    horizontal = AgentMirrorParams.BUBBLE_PADDING_HORIZONTAL,
                    vertical = AgentMirrorParams.BUBBLE_PADDING_VERTICAL,
                ),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = bubbleText,
                style = (item.blocks.firstOrNull()?.style ?: TextStyle())
                    .copy(textAlign = TextAlign.End),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** agent 输出段落：左对齐、无底衬；代码块走等宽与收紧行距（不加背景色），上下各留一档间距。 */
@Composable
private fun AgentParagraph(item: MeasuredTurn, onBodyTap: (() -> Unit)?) {
    Column(horizontalAlignment = Alignment.Start) {
        item.blocks.forEach { block ->
            Text(
                text = block.annotated,
                style = block.style,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (block.code) {
                            Modifier.padding(
                                start = RearCueSpacing.sm,
                                top = AgentMirrorParams.CODE_BLOCK_GAP,
                                bottom = AgentMirrorParams.CODE_BLOCK_GAP,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickableOnTap(onBodyTap),
            )
        }
    }
}

/** 渲染层用的一个块：已解析的文本 + 该块的样式（代码块与正文档不同）。 */
internal data class ReadingBlock(
    val annotated: AnnotatedString,
    val style: TextStyle,
    val code: Boolean,
)

/** 测量结果：一轮的高度、块，以及决定泡宽的最大行宽。 */
internal data class MeasuredTurn(
    val turn: AgentTurn,
    val blocks: List<ReadingBlock>,
    val heightPx: Int,
    val maxLineWidthPx: Int,
    val layout: TextLayoutResult,
)

/** 一次测量的全部产出：条目、栏宽与间距。 */
internal data class TurnsLayout(
    val items: List<MeasuredTurn>,
    val columnWidthPx: Int,
    val gapPerItemPx: Int,
    val gapsPx: Int,
    /** 泡内左右内边距之和（px）。 */
    val bubblePaddingPx: Int = 0,
    /** 提问泡的**泡外宽**（px）：测量与渲染共用同一个数，两边不许各算一遍。 */
    val bubbleOuterWidthPx: Int = 0,
    /** 「提问 → 紧随其后的回答」之间的间距（px，spec 0017 的间距细分）。 */
    val promptToAnswerGapPx: Int = gapPerItemPx,
) {
    /**
     * 第 [index] 条与它下一条之间的间距（px）；最后一条之后没有间距。
     * 判据与 [AgentMirrorParams.gapBetween] 同源（一问一答挨紧，其余常规）。
     */
    fun gapAfter(index: Int): Int = when {
        index >= items.size - 1 -> 0
        items[index].turn.role == AgentTurnRole.USER && items[index + 1].turn.role == AgentTurnRole.AGENT ->
            promptToAnswerGapPx
        else -> gapPerItemPx
    }
}

/**
 * 测量一轮问答流（纯计算，几何判例口径见 [AgentMirrorParamsTest]）：
 * 先用版心宽量一遍拿到内容自然宽，据此定栏宽，再按栏宽量最终值。
 *
 * 「栏宽收窄」是给提问泡服务的——agent 段落仍排满版心，所以栏宽只在泡宽上限小于版心时才收。
 */
internal fun measureTurns(
    measurer: TextMeasurer,
    turns: List<AgentTurn>,
    viewportWidthPx: Int,
    bodyStyle: TextStyle,
    codeStyle: TextStyle,
    inlineCodeStyle: SpanStyle,
    density: Density,
): TurnsLayout {
    if (turns.isEmpty() || viewportWidthPx <= 0) {
        return TurnsLayout(emptyList(), viewportWidthPx.coerceAtLeast(0), 0, 0)
    }
    val gapPerItemPx = with(density) { AgentMirrorParams.TURN_GAP.roundToPx() }
    val promptToAnswerGapPx = with(density) { AgentMirrorParams.PROMPT_TO_ANSWER_GAP.roundToPx() }
    val hPadPx = with(density) {
        (AgentMirrorParams.BUBBLE_PADDING_HORIZONTAL * 2).roundToPx()
    }
    val gapPx = { previous: AgentTurn, next: AgentTurn ->
        with(density) { AgentMirrorParams.gapBetween(previous, next).roundToPx() }
    }

    // 第一遍：按**版心**量一遍拿自然宽（提问按「版心 − 泡内边距」量：泡里能排字的只有
    // 外宽减去内边距）。这一遍把每行排到约束宽为止，所以行宽反映「这段至少要多宽才不折行」。
    val bubbleNaturalWidth = AgentMirrorParams.bubbleTextWidthPx(
        columnWidthPx = viewportWidthPx,
        paddingHorizontalTotalPx = hPadPx,
    )
    val natural = turns.map { turn ->
        measureTurn(
            measurer = measurer,
            turn = turn,
            widthPx = if (turn.role == AgentTurnRole.USER) bubbleNaturalWidth else viewportWidthPx,
            bodyStyle = bodyStyle,
            codeStyle = codeStyle,
            inlineCodeStyle = inlineCodeStyle,
        )
    }
    val widestText = turns.zip(natural)
        .filter { (turn, _) -> turn.role == AgentTurnRole.AGENT }
        .maxOfOrNull { (_, item) -> item.maxLineWidthPx } ?: 0
    // 栏宽 = max(正文自然宽, 版心)：**不再乘任何收缩因子**——早先按「泡自然宽 / 0.85」反推栏宽，
    // 等于给栏宽套了两道 0.85，泡因此被收窄两次、右缘缩回屏幕中间（实机诊断 `col=417 bubble=354`
    // 就是这个 0.85 的二连乘）。栏宽要么是正文要的宽度，要么就是整条版心，没有第三种。
    val columnWidthPx = maxOf(widestText, viewportWidthPx).coerceIn(1, viewportWidthPx)
    // 泡外宽按**实测行宽**定（短提问出小泡），上限 = 栏宽 × 0.85。测量与渲染共用这一个数。
    val bubbleOuterWidthPx = natural
        .let { measured ->
            turns.zip(measured)
                .filter { (turn, _) -> turn.role == AgentTurnRole.USER }
                .maxOfOrNull { (_, item) -> item.maxLineWidthPx + hPadPx } ?: 0
        }
        .coerceAtMost(AgentMirrorParams.bubbleMaxWidthPx(columnWidthPx))
        .coerceIn(hPadPx.coerceAtMost(columnWidthPx), columnWidthPx)

    // 第二遍：最终约束 → 行框。约束必须与渲染时 `Text` 的实际约束逐值相同。
    val items = turns.map { turn ->
        measureTurn(
            measurer = measurer,
            turn = turn,
            widthPx = if (turn.role == AgentTurnRole.USER) {
                (bubbleOuterWidthPx - hPadPx).coerceAtLeast(1)
            } else {
                columnWidthPx
            },
            bodyStyle = bodyStyle,
            codeStyle = codeStyle,
            inlineCodeStyle = inlineCodeStyle,
        )
    }
    val gapsPx = items.zipWithNext().sumOf { (previous, next) ->
        with(density) { AgentMirrorParams.gapBetween(previous.turn, next.turn).roundToPx() }
    }
    return TurnsLayout(
        items = items,
        columnWidthPx = columnWidthPx,
        gapPerItemPx = gapPerItemPx,
        gapsPx = gapsPx,
        bubblePaddingPx = hPadPx,
        bubbleOuterWidthPx = bubbleOuterWidthPx,
        promptToAnswerGapPx = promptToAnswerGapPx,
    )
}

/** 量一轮：Markdown 分段 → 逐块标注 → 整轮一次测量（整轮一起量才能精确算总高与行宽）。 */
private fun measureTurn(
    measurer: TextMeasurer,
    turn: AgentTurn,
    widthPx: Int,
    bodyStyle: TextStyle,
    codeStyle: TextStyle,
    inlineCodeStyle: SpanStyle,
): MeasuredTurn {
    val parsed = AgentMarkdown.parse(turn.text)
    val blocks = parsed.map { block ->
        when (block) {
            is AgentMarkdown.Block.Code -> ReadingBlock(
                annotated = AnnotatedString(block.text),
                style = codeStyle,
                code = true,
            )
            is AgentMarkdown.Block.Text -> ReadingBlock(
                annotated = annotate(block, inlineCodeStyle),
                style = bodyStyle,
                code = false,
            )
        }
    }
    val column = buildAnnotatedString {
        blocks.forEachIndexed { index, block ->
            if (index > 0) append("\n")
            append(block.annotated)
        }
    }
    val layout = measurer.measure(column, bodyStyle, constraints = Constraints.fixedWidth(widthPx))
    var maxLineWidth = 0
    for (line in 0 until layout.lineCount) {
        maxLineWidth = maxOf(
            maxLineWidth,
            ceil(layout.getLineRight(line) - layout.getLineLeft(line)).toInt(),
        )
    }
    return MeasuredTurn(
        turn = turn,
        blocks = blocks,
        heightPx = layout.size.height,
        maxLineWidthPx = maxLineWidth,
        layout = layout,
    )
}

/** 段落文本 → 带等宽区间的 [AnnotatedString]（区间来自 [AgentMarkdown.Block.Text.inlineCode]）。 */
private fun annotate(block: AgentMarkdown.Block.Text, inlineCodeStyle: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(block.text)
        block.inlineCode.forEach { span ->
            val start = span.start.coerceIn(0, block.text.length)
            val end = span.end.coerceIn(start, block.text.length)
            if (start < end) addStyle(inlineCodeStyle, start, end)
        }
    }

/**
 * 渲染输入：有问答流用问答流；只有旧的单条 `latestReply` 时回落成「一条 agent 输出」——
 * 桥与 App 版本错配时不黑屏（spec 0017「容错回落」）。
 */
internal fun AgentSessionState.readingTurns(): List<AgentTurn> {
    if (turns.isNotEmpty()) return turns
    val reply = latestReply?.takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(AgentTurn(role = AgentTurnRole.AGENT, text = reply, ts = updatedAt))
}
