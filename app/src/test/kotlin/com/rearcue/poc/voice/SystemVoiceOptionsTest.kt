package com.rearcue.poc.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SystemVoiceOptionsTest {

    private data class FakeVoice(val descriptor: SystemVoiceDescriptor)

    @Test
    fun `同名音色获得唯一 id 和可区分标签`() {
        val voices = listOf(
            FakeVoice(descriptor("zh", "zh-CN", quality = 500, latency = 100)),
            FakeVoice(descriptor("zh", "zh-CN", quality = 400, latency = 200)),
            FakeVoice(descriptor("zh", "zh-TW", quality = 300, latency = 300)),
        )

        val options = buildSystemVoiceChoices(voices, FakeVoice::descriptor).map { it.option }

        assertEquals(3, options.map { it.id }.distinct().size)
        assertEquals(3, options.map { it.label }.distinct().size)
        assertTrue(options.all { it.legacyId == "zh" })
        assertTrue(options.all { it.label.startsWith("zh（") })
    }

    @Test
    fun `同名音色顺序不依赖输入顺序`() {
        val first = FakeVoice(descriptor("zh", "zh-CN", quality = 500, latency = 100))
        val second = FakeVoice(descriptor("zh", "zh-CN", quality = 300, latency = 300))

        val forward = buildSystemVoiceChoices(listOf(first, second), FakeVoice::descriptor).map { it.option }
        val reversed = buildSystemVoiceChoices(listOf(second, first), FakeVoice::descriptor).map { it.option }

        assertEquals(forward, reversed)
    }

    @Test
    fun `完全相同的音色仍能唯一选择`() {
        val voices = List(3) { FakeVoice(descriptor("zh", "zh-CN", quality = 400, latency = 200)) }

        val options = buildSystemVoiceChoices(voices, FakeVoice::descriptor).map { it.option }

        assertEquals(3, options.map { it.id }.distinct().size)
        assertEquals(3, options.map { it.label }.distinct().size)
        assertTrue(options[0].label.endsWith(" · 第1个"))
        assertNotEquals(options[0].id, options[1].id)
    }

    private fun descriptor(
        name: String,
        localeTag: String,
        quality: Int,
        latency: Int,
    ): SystemVoiceDescriptor = SystemVoiceDescriptor(
        name = name,
        localeTag = localeTag,
        quality = quality,
        latency = latency,
        requiresNetwork = false,
        features = emptySet(),
    )
}
