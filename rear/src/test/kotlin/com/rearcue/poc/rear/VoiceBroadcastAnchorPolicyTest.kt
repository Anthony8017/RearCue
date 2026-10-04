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
    fun `重复句按朗读顺序定位到后一次出现`() {
        val display = "共同句。\n另一句。\n共同句。"
        assertEquals(
            display.lastIndexOf("共同句"),
            VoiceBroadcastAnchorPolicy.displayOffset(
                display,
                "共同句。",
                listOf("共同句。", "另一句。"),
            ),
        )
    }

    @Test
    fun `返回播报页时用完整历史重建重复句位置`() {
        val display = "继续。\n中间内容。\n继续。\n最后内容。\n继续。"
        assertEquals(
            display.lastIndexOf("继续"),
            VoiceBroadcastAnchorPolicy.displayOffset(
                display,
                "继续。",
                listOf("继续。", "中间内容。", "继续。", "最后内容。"),
            ),
        )
    }

    @Test
    fun `不存在的口语提示不妨碍后续正文定位`() {
        val display = "开头。\n重复。\n重复。"
        assertEquals(
            display.lastIndexOf("重复"),
            VoiceBroadcastAnchorPolicy.displayOffset(
                display,
                "重复。",
                listOf("开头。", "链接已略过。", "重复。"),
            ),
        )
    }

    @Test
    fun `当前句只在已读位置出现时不倒退匹配`() {
        assertNull(
            VoiceBroadcastAnchorPolicy.displayOffset(
                "前句。\n后句。",
                "前句。",
                listOf("前句。", "后句。"),
            ),
        )
    }

    @Test
    fun `句比例首尾正确`() {
        assertEquals(0f, VoiceBroadcastAnchorPolicy.fallbackFraction(0, 3))
        assertEquals(0.5f, VoiceBroadcastAnchorPolicy.fallbackFraction(1, 3))
        assertEquals(1f, VoiceBroadcastAnchorPolicy.fallbackFraction(2, 3))
        assertEquals(1f, VoiceBroadcastAnchorPolicy.fallbackFraction(0, 1))
    }
}
