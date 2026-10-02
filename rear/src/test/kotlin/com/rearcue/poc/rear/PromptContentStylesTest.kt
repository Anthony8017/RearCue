package com.rearcue.poc.rear

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/** 提问泡内来源配色映射（票 #248）：来源身份固定，未知来源不猜品牌。 */
class PromptContentStylesTest {

    @Test
    fun `四个来源各用固定近似色`() {
        assertEquals(Color(0xFF19C37D), PromptContentStyles.forSource("codex").mention)
        assertEquals(Color(0xFFD97757), PromptContentStyles.forSource("CLAUDE").link)
        assertEquals(Color(0xFF4D9FFF), PromptContentStyles.forSource("zcode").mention)
        assertEquals(Color(0xFF4D6BFE), PromptContentStyles.forSource("dsh").mention)
    }

    @Test
    fun `未知或缺省来源退回中性配色`() {
        val neutral = PromptContentStyles.neutral
        assertEquals(neutral, PromptContentStyles.forSource(null))
        assertEquals(neutral, PromptContentStyles.forSource("unknown"))
    }
}
