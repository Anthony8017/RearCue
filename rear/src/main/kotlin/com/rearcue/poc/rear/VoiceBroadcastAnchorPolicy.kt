package com.rearcue.poc.rear

/** Voice Broadcast Follow 的纯文本锚定：把口语句映射回原文显示偏移。 */
object VoiceBroadcastAnchorPolicy {
    fun displayOffset(displayText: String, sentenceText: String): Int? {
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
        val start = folded.indexOf(sentence)
        if (start < 0) return null
        return offsets.getOrNull(start)
    }

    fun fallbackFraction(sentenceIndex: Int, sentenceCount: Int): Float = when {
        sentenceCount <= 1 -> 1f
        else -> sentenceIndex.toFloat() / (sentenceCount - 1)
    }

    private fun fold(text: String): String = buildString {
        text.forEach { char ->
            if (Character.isLetterOrDigit(char)) append(char.lowercaseChar())
        }
    }
}
