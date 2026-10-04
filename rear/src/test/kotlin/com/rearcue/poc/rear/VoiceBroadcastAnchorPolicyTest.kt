package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceBroadcastAnchorPolicyTest {

    @Test
    fun `口语句映射回剥离标记后的显示偏移`() {
        val display = "前文\n第二段内容\n后文"
        assertEquals(3, VoiceBroadcastAnchorPolicy.displayOffset(display, "第二段内容"))
    }

    @Test
    fun `找不到原文锚时返回空_由调用方按句比例回退`() {
        assertNull(VoiceBroadcastAnchorPolicy.displayOffset("原文", "代码内容已略过。"))
    }

    @Test
    fun `句比例首尾正确`() {
        assertEquals(0f, VoiceBroadcastAnchorPolicy.fallbackFraction(0, 3))
        assertEquals(0.5f, VoiceBroadcastAnchorPolicy.fallbackFraction(1, 3))
        assertEquals(1f, VoiceBroadcastAnchorPolicy.fallbackFraction(2, 3))
        assertEquals(1f, VoiceBroadcastAnchorPolicy.fallbackFraction(0, 1))
    }
}
