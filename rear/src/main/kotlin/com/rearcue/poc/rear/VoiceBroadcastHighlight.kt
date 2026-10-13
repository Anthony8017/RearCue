package com.rearcue.poc.rear

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.rearcue.poc.core.VoiceBroadcastFollow
import com.rearcue.poc.core.VoiceBroadcastVisualTarget
import kotlin.math.roundToInt

/** 长回复里非当前句的亮度；仍可读，但能看出不是正在读的一句。 */
internal object VoiceBroadcastDimPolicy {
    const val BODY = 0.55f
    const val SHORT = 0.78f
    const val FADE_MILLIS = 280
}

/** 当前句已在舒适区内不滚动；贴边或在屏外才滚到阅读区中下部。 */
internal object VoiceBroadcastScrollPolicy {
    const val ANCHOR_FRACTION = 0.55f
    const val EDGE_MARGIN_FRACTION = 0.12f
    const val SCROLL_MILLIS = 360

    fun targetOrNull(
        lineTop: Int,
        lineBottom: Int,
        scrollValue: Int,
        viewportHeight: Int,
        maxScroll: Int,
        obscuredTopPx: Int = 0,
    ): Int? {
        if (viewportHeight <= 0) return null
        val margin = (viewportHeight * EDGE_MARGIN_FRACTION).roundToInt().coerceAtLeast(0)
        val comfortableTop = scrollValue + obscuredTopPx.coerceAtLeast(0) + margin
        val comfortableBottom = scrollValue + viewportHeight - margin
        val bottom = lineBottom.coerceAtLeast(lineTop + 1)
        val room = (comfortableBottom - comfortableTop).coerceAtLeast(1)
        val fits = bottom - lineTop <= room
        if (fits && lineTop >= comfortableTop && bottom <= comfortableBottom) return null
        val anchor = (viewportHeight * ANCHOR_FRACTION).roundToInt()
        val target = (lineTop - anchor).coerceIn(0, maxScroll.coerceAtLeast(0))
        return target.takeIf { it != scrollValue }
    }
}

/** 空行隔开的当前小段；不含前后段落。 */
internal fun voiceBroadcastParagraphRange(
    text: String,
    sentence: VoiceBroadcastAnchorPolicy.TextRange,
): VoiceBroadcastAnchorPolicy.TextRange {
    if (text.isEmpty()) return VoiceBroadcastAnchorPolicy.TextRange(0, 0)
    val lines = ArrayList<Pair<Int, Int>>()
    var cursor = 0
    while (cursor <= text.length) {
        val newline = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
        lines += cursor to newline
        if (newline >= text.length) break
        cursor = newline + 1
    }
    fun blank(line: Pair<Int, Int>) = text.substring(line.first, line.second).isBlank()
    var index = lines.indexOfFirst { (start, end) ->
        val lineEnd = if (end < text.length) end + 1 else text.length
        sentence.start < lineEnd && sentence.end > start
    }
    if (index < 0) index = lines.lastIndex.coerceAtLeast(0)
    if (index >= 0 && blank(lines[index])) {
        val neighbor = (index until lines.size).firstOrNull { !blank(lines[it]) }
            ?: (index downTo 0).firstOrNull { !blank(lines[it]) }
        if (neighbor != null) index = neighbor
    }
    if (index < 0) return VoiceBroadcastAnchorPolicy.TextRange(0, text.length)
    var low = index
    var high = index
    while (low > 0 && !blank(lines[low - 1])) low--
    while (high < lines.lastIndex && !blank(lines[high + 1])) high++
    return VoiceBroadcastAnchorPolicy.TextRange(lines[low].first, lines[high].second)
}

/** 当前句与当前小段之外保持原亮度；小段内其余文字变淡，并在两句之间过渡。 */
internal fun voiceBroadcastGlyphAlpha(
    index: Int,
    fromSentence: VoiceBroadcastAnchorPolicy.TextRange?,
    toSentence: VoiceBroadcastAnchorPolicy.TextRange?,
    fromParagraph: VoiceBroadcastAnchorPolicy.TextRange?,
    toParagraph: VoiceBroadcastAnchorPolicy.TextRange?,
    progress: Float,
    dimFactor: Float,
): Float {
    val blend = progress.coerceIn(0f, 1f)
    fun side(
        sentence: VoiceBroadcastAnchorPolicy.TextRange?,
        paragraph: VoiceBroadcastAnchorPolicy.TextRange?,
    ): Float {
        if (sentence == null || paragraph == null) return 1f
        if (index < paragraph.start || index >= paragraph.end) return 1f
        if (index >= sentence.start && index < sentence.end) return 1f
        return dimFactor
    }
    val start = side(fromSentence, fromParagraph)
    val end = side(toSentence, toParagraph)
    return start + (end - start) * blend
}

/** 可见原文上的当前朗读整句区间；不含句内逐字进度。 */
internal fun voiceBroadcastHighlightRange(
    displayText: String,
    follow: VoiceBroadcastFollow,
    precedingSentenceTexts: List<String> = follow.precedingSentenceTexts,
): VoiceBroadcastAnchorPolicy.TextRange? {
    if (!follow.visualAnchor || displayText.isEmpty()) return null
    return VoiceBroadcastAnchorPolicy.displayRange(
        displayText = displayText,
        sentenceText = follow.sentenceText,
        precedingSentenceTexts = precedingSentenceTexts,
    )
}

internal fun VoiceBroadcastAnchorPolicy.TextRange.clipToBlock(
    blockStart: Int,
    blockLength: Int,
): VoiceBroadcastAnchorPolicy.TextRange? {
    val start = maxOf(this.start, blockStart)
    val end = minOf(this.end, blockStart + blockLength)
    return if (start < end) VoiceBroadcastAnchorPolicy.TextRange(start - blockStart, end - blockStart) else null
}

private class VoiceDimFade {
    var from: VoiceBroadcastAnchorPolicy.TextRange? = null
    var to: VoiceBroadcastAnchorPolicy.TextRange? = null
    var primed = false
    val progress = Animatable(1f)
}

@Composable
internal fun AnnotatedString.rememberVoiceBroadcastDim(
    range: VoiceBroadcastAnchorPolicy.TextRange?,
    baseColor: Color,
    dimFactor: Float,
): AnnotatedString {
    val fade = remember { VoiceDimFade() }
    LaunchedEffect(range) {
        if (fade.primed && range == fade.to) return@LaunchedEffect
        fade.from = if (fade.primed) fade.to else null
        fade.to = range
        fade.primed = true
        fade.progress.snapTo(0f)
        fade.progress.animateTo(
            1f,
            tween(VoiceBroadcastDimPolicy.FADE_MILLIS, easing = FastOutSlowInEasing),
        )
    }
    return withVoiceBroadcastDim(
        from = fade.from,
        to = fade.to,
        progress = fade.progress.value,
        baseColor = baseColor,
        dimFactor = dimFactor,
    )
}

@Composable
internal fun animateVoiceBroadcastLineAlpha(activeLine: Boolean, followVisible: Boolean): Float {
    val alpha by animateFloatAsState(
        targetValue = if (!followVisible || activeLine) 1f else VoiceBroadcastDimPolicy.SHORT,
        animationSpec = tween(VoiceBroadcastDimPolicy.FADE_MILLIS, easing = FastOutSlowInEasing),
        label = "voice-line-alpha",
    )
    return alpha
}

internal fun AnnotatedString.withVoiceBroadcastDim(
    from: VoiceBroadcastAnchorPolicy.TextRange?,
    to: VoiceBroadcastAnchorPolicy.TextRange?,
    progress: Float,
    baseColor: Color,
    dimFactor: Float,
): AnnotatedString {
    if (isEmpty() || baseColor == Color.Unspecified) return this
    val fromParagraph = from?.let { voiceBroadcastParagraphRange(text, it) }
    val toParagraph = to?.let { voiceBroadcastParagraphRange(text, it) }
    val cuts = sortedSetOf(0, length)
    listOf(from, to, fromParagraph, toParagraph).forEach { range ->
        if (range != null) {
            cuts += range.start.coerceIn(0, length)
            cuts += range.end.coerceIn(0, length)
        }
    }
    val points = cuts.toList()
    val segments = ArrayList<Triple<Int, Int, Float>>()
    for (index in 0 until points.lastIndex) {
        val start = points[index]
        val end = points[index + 1]
        if (start >= end) continue
        val alpha = voiceBroadcastGlyphAlpha(
            index = start,
            fromSentence = from,
            toSentence = to,
            fromParagraph = fromParagraph,
            toParagraph = toParagraph,
            progress = progress,
            dimFactor = dimFactor,
        )
        if (alpha < 0.999f) segments += Triple(start, end, alpha)
    }
    if (segments.isEmpty()) return this
    return buildAnnotatedString {
        append(this@withVoiceBroadcastDim)
        segments.forEach { (start, end, alpha) ->
            paintAlpha(this@withVoiceBroadcastDim, start, end, alpha, baseColor)
        }
    }
}

private fun AnnotatedString.Builder.paintAlpha(
    source: AnnotatedString,
    start: Int,
    end: Int,
    factor: Float,
    baseColor: Color,
) {
    var cursor = start
    while (cursor < end) {
        val color = source.colorAt(cursor) ?: baseColor
        var next = cursor + 1
        while (next < end && (source.colorAt(next) ?: baseColor) == color) next++
        addStyle(
            SpanStyle(color = color.copy(alpha = (color.alpha * factor).coerceIn(0f, 1f))),
            cursor,
            next,
        )
        cursor = next
    }
}

private fun AnnotatedString.colorAt(index: Int): Color? {
    var color: Color? = null
    for (span in spanStyles) {
        if (index >= span.start && index < span.end && span.item.color != Color.Unspecified) {
            color = span.item.color
        }
    }
    return color
}

internal fun VoiceBroadcastFollow.matchesVisualTarget(
    target: VoiceBroadcastVisualTarget,
    sessionId: String,
    requestId: String? = null,
    turnEntryId: String? = null,
): Boolean =
    visualTarget == target &&
        this.sessionId == sessionId &&
        (requestId == null || this.requestId == requestId || this.turnEntryId == requestId) &&
        (turnEntryId == null || this.turnEntryId == turnEntryId)
