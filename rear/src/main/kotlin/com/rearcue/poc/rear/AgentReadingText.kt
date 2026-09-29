package com.rearcue.poc.rear

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
) {
    val density = LocalDensity.current
    val viewport = rules.agentReadingViewport(density)
    if (viewport.width <= 0 || viewport.height <= 0) return

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
    val layout = remember(measurer, turns, viewport.width, reading, density) {
        measureTurns(
            measurer = measurer,
            turns = turns,
            viewportWidthPx = viewport.width,
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
    val padding = remember(layout, viewport, bounds) {
        rules.detailTextPadding(
            viewport = viewport,
            textHeight = contentHeight,
            lines = bounds,
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
                start = with(density) { viewport.left.toDp() },
                top = with(density) { viewport.top.toDp() },
                end = with(density) { (rules.windowWidth - viewport.right).toDp() },
                bottom = with(density) { (rules.windowHeight - viewport.bottom).toDp() },
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
                        columnWidthPx = layout.columnWidthPx,
                        paddingPx = layout.bubblePaddingPx,
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
 * 宽度口径与测量严格一致（这是本件最容易写错的地方）：泡外宽 = 泡内可用宽度 + 左右内边距，
 * 其中泡内可用宽度由 [AgentMirrorParams.bubbleTextWidthPx] 收口（上限 = 版心 × 0.85 − 内边距）。
 * agent 输出不套任何框——「对齐方向 + 有无底衬」两重区分说话人，不加「你/我」标记。
 */
@Composable
private fun PromptBubble(
    item: MeasuredTurn,
    columnWidthPx: Int,
    paddingPx: Int,
    density: Density,
) {
    val innerCapPx = AgentMirrorParams.bubbleTextWidthPx(columnWidthPx, paddingPx)
    val innerWidthPx = item.maxLineWidthPx.coerceAtMost(innerCapPx)
    val outerWidthPx = (innerWidthPx + paddingPx).coerceAtLeast(0)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .width(with(density) { outerWidthPx.toDp() })
                .clip(RoundedCornerShape(AgentMirrorParams.BUBBLE_CORNER))
                .background(RearCueColors.surfaceHighlight)
                .padding(
                    horizontal = AgentMirrorParams.BUBBLE_PADDING_HORIZONTAL,
                    vertical = AgentMirrorParams.BUBBLE_PADDING_VERTICAL,
                ),
            horizontalAlignment = Alignment.End,
        ) {
            item.blocks.forEach { block ->
                Text(
                    text = block.annotated,
                    style = block.style.copy(textAlign = TextAlign.End),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
    /** 泡内左右内边距之和（px）：泡内可用宽度 = 泡外宽 − 本值（[AgentMirrorParams.bubbleTextWidthPx]）。 */
    val bubblePaddingPx: Int = 0,
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

    // 第一遍：版心宽 → 内容自然宽（用于定栏宽）。提问按「版心 − 泡内边距」量——
    // 泡里能排字的只有外宽减掉内边距，拿版心宽去量会让泡内文字多折一行。
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
    val widestBubble = turns.zip(natural)
        .filter { (turn, _) -> turn.role == AgentTurnRole.USER }
        .maxOfOrNull { (_, item) -> item.maxLineWidthPx + hPadPx } ?: 0
    val widestText = turns.zip(natural)
        .filter { (turn, _) -> turn.role == AgentTurnRole.AGENT }
        .maxOfOrNull { (_, item) -> item.maxLineWidthPx } ?: 0
    // 栏宽：泡宽上限要求「栏宽 ≥ 泡自然宽 / 0.85」；纯文本要求「栏宽 ≥ 它的自然宽」。都不超版心。
    val bubbleDemand = if (widestBubble > 0) {
        ceil(widestBubble / AgentMirrorParams.BUBBLE_MAX_WIDTH_RATIO).toInt()
    } else {
        0
    }
    val columnWidthPx = maxOf(bubbleDemand, widestText).coerceIn(1, viewportWidthPx)

    // 第二遍：栏宽（提问再收进泡内可用宽度）→ 最终行框。行宽既用于圆角避让，
    // 也用于定泡宽——所以这一遍的约束必须与渲染时 `Text` 的实际约束一致。
    val bubbleInnerWidth = AgentMirrorParams.bubbleTextWidthPx(columnWidthPx, hPadPx)
    val items = turns.map { turn ->
        measureTurn(
            measurer = measurer,
            turn = turn,
            widthPx = if (turn.role == AgentTurnRole.USER) {
                bubbleInnerWidth.coerceAtMost(columnWidthPx)
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
