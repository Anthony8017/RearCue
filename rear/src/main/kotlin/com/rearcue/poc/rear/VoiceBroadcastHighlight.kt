package com.rearcue.poc.rear

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.rearcue.poc.core.VoiceBroadcastFollow
import com.rearcue.poc.core.VoiceBroadcastVisualTarget
import com.rearcue.poc.design.RearCueColors

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

/** 问题标题、选项、批准摘要等独立元素用精确句定位，不携带其他元素的已读游标。 */
internal fun voiceBroadcastHighlightRangeExact(
    displayText: String,
    follow: VoiceBroadcastFollow,
): VoiceBroadcastAnchorPolicy.TextRange? {
    if (!follow.visualAnchor || displayText.isEmpty()) return null
    return VoiceBroadcastAnchorPolicy.displayRange(
        displayText = displayText,
        sentenceText = follow.sentenceText,
        precedingSentenceTexts = emptyList(),
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

internal fun AnnotatedString.withVoiceBroadcastHighlight(
    range: VoiceBroadcastAnchorPolicy.TextRange?,
): AnnotatedString {
    if (range == null || range.start >= range.end) return this
    return buildAnnotatedString {
        append(this@withVoiceBroadcastHighlight)
        addStyle(
            SpanStyle(background = RearCueColors.voiceBroadcastHighlight),
            range.start,
            range.end,
        )
    }
}

internal fun String.withVoiceBroadcastHighlight(
    range: VoiceBroadcastAnchorPolicy.TextRange?,
): AnnotatedString {
    if (range == null || range.start >= range.end) return AnnotatedString(this)
    return buildAnnotatedString {
        append(this@withVoiceBroadcastHighlight)
        addStyle(
            SpanStyle(background = RearCueColors.voiceBroadcastHighlight),
            range.start,
            range.end,
        )
    }
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
