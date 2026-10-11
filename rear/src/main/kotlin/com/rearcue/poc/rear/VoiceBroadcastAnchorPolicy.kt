package com.rearcue.poc.rear

/** Voice Broadcast Follow 的纯文本锚定：把口语句映射回原文显示偏移。 */
object VoiceBroadcastAnchorPolicy {
    data class TextRange(val start: Int, val end: Int)

    fun displayOffset(
        displayText: String,
        sentenceText: String,
        precedingSentenceTexts: List<String> = emptyList(),
    ): Int? = displayRange(displayText, sentenceText, precedingSentenceTexts)?.start

    /** 返回当前句在显示原文中的字符区间；尾部标点也属于整句高亮。 */
    fun displayRange(
        displayText: String,
        sentenceText: String,
        precedingSentenceTexts: List<String> = emptyList(),
    ): TextRange? {
        val sentence = fold(sentenceText)
        if (sentence.isEmpty()) return null
        val folded = StringBuilder()
        val offsets = mutableListOf<Int>()
        displayText.forEachIndexed { index, char ->
            if (Character.isLetterOrDigit(char)) {
                folded.append(char.lowercaseChar())
                offsets += index
            }
        }
        var cursor = 0
        precedingSentenceTexts.forEach { previous ->
            val preceding = fold(previous)
            if (preceding.isNotEmpty()) {
                val start = folded.indexOf(preceding, startIndex = cursor)
                if (start >= 0) cursor = start + preceding.length
            }
        }
        val start = folded.indexOf(sentence, startIndex = cursor)
        if (start < 0) return null
        val startOffset = offsets.getOrNull(start) ?: return null
        val endOffset = offsets.getOrNull(start + sentence.length - 1)?.plus(1) ?: displayText.length
        var end = endOffset
        while (end < displayText.length) {
            val char = displayText[end]
            if (char in TRAILING_SENTENCE_PUNCTUATION ||
                (char.isWhitespace() && char != '\n' && char != '\r')
            ) {
                end++
            } else {
                break
            }
        }
        return TextRange(startOffset, end.coerceAtLeast(startOffset))
    }

    fun fallbackFraction(sentenceIndex: Int, sentenceCount: Int): Float = when {
        sentenceCount <= 1 -> 1f
        else -> sentenceIndex.toFloat() / (sentenceCount - 1)
    }

    private fun fold(text: String): String = buildString {
        val withoutListMarker = text.replace(LIST_MARKER, "")
        withoutListMarker.forEach { char ->
            if (Character.isLetterOrDigit(char)) append(char.lowercaseChar())
        }
    }

    private val LIST_MARKER = Regex("""^\s*(?:[-*+]\s+|\d+[.)、，,：:]\s*)""")
    private const val TRAILING_SENTENCE_PUNCTUATION = "。！？!?；;，,、：:.…"
}
