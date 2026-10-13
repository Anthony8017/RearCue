package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceBroadcastDimPolicyTest {
    private val text = "第一段甲。第一段乙。\n\n第二段甲。第二段乙。\n\n第三段。"

    @Test
    fun `只框住空行隔开的当前小段`() {
        val sentence = VoiceBroadcastAnchorPolicy.displayRange(text, "第二段乙。")!!
        assertEquals(
            VoiceBroadcastAnchorPolicy.TextRange(text.indexOf("第二段甲"), text.indexOf("\n\n第三段")),
            voiceBroadcastParagraphRange(text, sentence),
        )
    }

    @Test
    fun `没有空行时整块算一小段`() {
        val block = "甲。乙。\n丙。"
        val sentence = VoiceBroadcastAnchorPolicy.displayRange(block, "乙。")!!
        assertEquals(VoiceBroadcastAnchorPolicy.TextRange(0, block.length), voiceBroadcastParagraphRange(block, sentence))
    }

    @Test
    fun `小段外保持原亮度_小段内非当前句变淡`() {
        val sentence = VoiceBroadcastAnchorPolicy.displayRange(text, "第二段乙。")!!
        val paragraph = voiceBroadcastParagraphRange(text, sentence)
        assertEquals(1f, voiceBroadcastGlyphAlpha(text.indexOf("第一段甲"), null, sentence, null, paragraph, 1f, VoiceBroadcastDimPolicy.BODY))
        assertEquals(1f, voiceBroadcastGlyphAlpha(text.indexOf("第三段"), null, sentence, null, paragraph, 1f, VoiceBroadcastDimPolicy.BODY))
        assertEquals(VoiceBroadcastDimPolicy.BODY, voiceBroadcastGlyphAlpha(text.indexOf("第二段甲"), null, sentence, null, paragraph, 1f, VoiceBroadcastDimPolicy.BODY))
        assertEquals(1f, voiceBroadcastGlyphAlpha(sentence.start, null, sentence, null, paragraph, 1f, VoiceBroadcastDimPolicy.BODY))
    }

    @Test
    fun `换句时亮度在两句之间过渡`() {
        val previous = VoiceBroadcastAnchorPolicy.displayRange(text, "第二段甲。")!!
        val current = VoiceBroadcastAnchorPolicy.displayRange(text, "第二段乙。")!!
        val paragraph = voiceBroadcastParagraphRange(text, current)
        val midpoint = voiceBroadcastGlyphAlpha(
            index = previous.start,
            fromSentence = previous,
            toSentence = current,
            fromParagraph = paragraph,
            toParagraph = paragraph,
            progress = 0.5f,
            dimFactor = VoiceBroadcastDimPolicy.BODY,
        )
        assertEquals(0.775f, midpoint, 0.001f)
    }

    @Test
    fun `当前句已在舒适区内不重新对位`() {
        assertNull(
            VoiceBroadcastScrollPolicy.targetOrNull(
                lineTop = 700,
                lineBottom = 740,
                scrollValue = 500,
                viewportHeight = 1000,
                maxScroll = 3000,
            ),
        )
    }

    @Test
    fun `贴边或屏外才平滑滚到中下部`() {
        assertEquals(
            1450,
            VoiceBroadcastScrollPolicy.targetOrNull(
                lineTop = 2000,
                lineBottom = 2040,
                scrollValue = 0,
                viewportHeight = 1000,
                maxScroll = 3000,
            ),
        )
    }
}
