package com.rearcue.poc.rear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.readingGutterFloorPx
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 通知标题和正文共享一次滚动：短内容整体居中，长内容首尾都能到达。
 * 先用与 Text 相同的样式、宽度和字体解析器量出行框，SafeArea 只对实际碰到圆角的首尾行
 * 增加纵向距离；中间正文保留右侧 8px 的阅读宽度，不为圆角收窄整篇。
 */
@Composable
internal fun DetailText(title: String, body: String, rules: SafeArea, notificationKey: String) {
    val density = LocalDensity.current
    val viewport = rules.detailTextViewport(with(density) { readingGutterFloorPx() })
    if (viewport.width <= 0 || viewport.height <= 0) return

    val inheritedStyle = LocalTextStyle.current
    val titleStyle = inheritedStyle.copy(
        color = RearCueColors.onBackground,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val bodyStyle = inheritedStyle.copy(
        color = RearCueColors.onBackground,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        textAlign = TextAlign.Center,
    )
    val textMeasurer = rememberTextMeasurer()
    val constraints = Constraints.fixedWidth(viewport.width)
    // Text 同样 fillMaxWidth，minWidth/maxWidth 一致，居中行框与最终绘制位置一致。
    val titleLayout = if (title.isNotEmpty()) textMeasurer.measure(title, titleStyle, constraints = constraints) else null
    val bodyLayout = if (body.isNotEmpty()) textMeasurer.measure(body, bodyStyle, constraints = constraints) else null
    val gap = if (titleLayout != null && bodyLayout != null) with(density) { RearCueSpacing.xs.roundToPx() } else 0
    val bodyTop = (titleLayout?.size?.height ?: 0) + gap
    val textHeight = bodyTop + (bodyLayout?.size?.height ?: 0)
    val padding = remember(rules, viewport, titleLayout, bodyLayout, gap) {
        val lines = buildList {
            titleLayout?.appendLineBoundsTo(this, top = 0)
            bodyLayout?.appendLineBoundsTo(this, top = bodyTop)
        }
        rules.detailTextPadding(viewport, textHeight, lines)
    }
    val scroll = rememberScrollState()
    LaunchedEffect(notificationKey, title, body) { scroll.scrollTo(0) }

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
            if (titleLayout != null) Text(title, Modifier.fillMaxWidth(), style = titleStyle)
            if (gap > 0) Spacer(Modifier.height(with(density) { gap.toDp() }))
            if (bodyLayout != null) Text(body, Modifier.fillMaxWidth(), style = bodyStyle)
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
