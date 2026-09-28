package com.rearcue.poc.rear

import androidx.compose.foundation.ScrollState
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
) {
    val density = LocalDensity.current
    val viewport = rules.detailTextViewport(with(density) { readingGutterFloorPx() })
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
            if (headingLayout != null) Text(heading, headingModifier.fillMaxWidth(), style = resolvedHeadingStyle)
            if (gap > 0) Spacer(Modifier.height(with(density) { gap.toDp() }))
            if (bodyLayout != null) Text(body, Modifier.fillMaxWidth(), style = resolvedBodyStyle)
        }
    }
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
