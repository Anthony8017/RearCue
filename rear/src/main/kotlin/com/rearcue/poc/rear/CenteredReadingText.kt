package com.rearcue.poc.rear

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.readingGutterFloorPx
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 通知详情与 Agent Mirror 共用的阅读布局：每行居中，标题/会话名与正文作为整体优先垂直居中。
 * 测量与绘制使用同一解析后的样式、宽度、字体解析器；只按实际行框避让圆角，不收窄整篇。
 * 滚动策略由调用方提供：通知从开头进入，Agent 跟随最新输出或保留历史回看位置。
 *
 * [onTap] 非空时给正文本身挂点按（spec 0013 / 票 #133：Agent 页点正文切回通知页）——滚动容器
 * 消费拖动，clickable 只在原地抬起时触发，拖动滚动不误触；无 indication、无系统反馈，保持
 * 「不响不震」。留空（Detail 卡片）时点按语义完全不变。
 *
 * 会话标识行的点按自票 #161 起不在这里：Agent 页把标识行固定渲染在屏顶（`AgentMirrorLayer`），
 * 本件只承担正文；[heading] 非空时仍与正文作为整体垂直居中（Detail 卡片的口径）。
 *
 * [topReservePx] > 0 时正文视口整体下移这一段（票 #161）：调用方（Agent 页）把会话标识行固定
 * 渲染在屏顶，正文只在其余区域滚动/居中——滚动中的正文不会从标识行底下穿过。
 * 该预留带上的拖动手势由调用方转发（见 `AgentMirrorLayer` 的手势转发带），不留手势死区。
 * 留 0 时布局逐字不变（Detail 卡片）。
 */
@Composable
internal fun CenteredReadingText(
    heading: String,
    body: String,
    headingStyle: TextStyle,
    bodyStyle: TextStyle,
    rules: SafeArea,
    scroll: ScrollState,
    headingModifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    topReservePx: Int = 0,
) {
    val density = LocalDensity.current
    val reserved = topReservePx.coerceAtLeast(0)
    // 预留带**不在**滚动内容里：正文视口整体下移，滚动的正文永远不会从固定的标识行底下穿过
    // （票 #162 评审）；预留带上的拖动手势由调用方用 [Modifier.scrollable] 转发给同一个滚动状态。
    val viewport = rules.readingViewport(density)
        .let { if (reserved > 0) it.copy(top = it.top + reserved) else it }
    if (viewport.width <= 0 || viewport.height <= 0) return

    val inheritedStyle = LocalTextStyle.current
    val resolvedHeadingStyle = inheritedStyle.merge(headingStyle).copy(textAlign = TextAlign.Center)
    val resolvedBodyStyle = inheritedStyle.merge(bodyStyle).copy(textAlign = TextAlign.Center)
    val textMeasurer = rememberTextMeasurer()
    val constraints = Constraints.fixedWidth(viewport.width)
    val headingLayout = if (heading.isNotEmpty()) {
        textMeasurer.measure(heading, resolvedHeadingStyle, constraints = constraints)
    } else null
    val bodyLayout = if (body.isNotEmpty()) {
        textMeasurer.measure(body, resolvedBodyStyle, constraints = constraints)
    } else null
    val gap = if (headingLayout != null && bodyLayout != null) with(density) { RearCueSpacing.xs.roundToPx() } else 0
    val bodyTop = (headingLayout?.size?.height ?: 0) + gap
    val textHeight = bodyTop + (bodyLayout?.size?.height ?: 0)
    // 顶部预留（票 #161）已由上面的视口下移承担；内容自身的首尾留白照旧。
    val padding = remember(rules, viewport, headingLayout, bodyLayout, gap) {
        val lines = buildList {
            headingLayout?.appendLineBoundsTo(this, top = 0)
            bodyLayout?.appendLineBoundsTo(this, top = bodyTop)
        }
        rules.detailTextPadding(viewport, textHeight, lines)
    }

    Box(
        Modifier.fillMaxSize().padding(
            start = with(density) { viewport.left.toDp() },
            top = with(density) { viewport.top.toDp() },
            end = with(density) { (rules.windowWidth - viewport.right).toDp() },
            bottom = with(density) { (rules.windowHeight - viewport.bottom).toDp() },
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(
                top = with(density) { padding.before.toDp() },
                bottom = with(density) { padding.after.toDp() },
            ),
        ) {
            // 点按只挂在文字本体上（行内实际宽度），四周空白仍透到外层的内容页切换。
            if (headingLayout != null) {
                Text(heading, headingModifier.fillMaxWidth(), style = resolvedHeadingStyle)
            }
            if (gap > 0) Spacer(Modifier.height(with(density) { gap.toDp() }))
            if (bodyLayout != null) {
                Text(body, Modifier.clickableOnTap(onTap).fillMaxWidth(), style = resolvedBodyStyle)
            }
        }
    }
}

/**
 * 点按语义（spec 0013 / 票 #133）：[onTap] 为空时原样返回（Detail 卡片）；非空时文字本体
 * 可点、无涟漪/无反馈；拖动由外层滚动容器消费，clickable 内置的拖动取消保证不误触。
 * 票 #161 起也由固定的会话标识行复用（本件与 AgentMirrorView 同包）。
 */
@Composable
internal fun Modifier.clickableOnTap(onTap: (() -> Unit)?): Modifier =
    if (onTap == null) {
        this
    } else {
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onTap,
        )
    }

/** 使用完整行高作保守边界，包含字体升部/降部；不拿整篇最大宽度代替每行实际横跨。 */
private fun TextLayoutResult.appendLineBoundsTo(destination: MutableList<PxRect>, top: Int) {
    for (line in 0 until lineCount) {
        destination += PxRect(
            left = floor(getLineLeft(line)).toInt(),
            top = top + floor(getLineTop(line)).toInt(),
            right = ceil(getLineRight(line)).toInt(),
            bottom = top + ceil(getLineBottom(line)).toInt(),
        )
    }
}
