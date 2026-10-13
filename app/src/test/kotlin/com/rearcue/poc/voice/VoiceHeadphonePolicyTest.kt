package com.rearcue.poc.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceHeadphonePolicyTest {
    @Test fun `播报占着声音时才压住后开始的音乐`() {
        val music = listOf(ExternalPlayback("music", playing = true))
        assertEquals(setOf("music"), externalPlaybackKeysToPause(true, music, emptySet()))
        assertEquals(emptySet(), externalPlaybackKeysToPause(true, music, setOf("music")))
        assertEquals(emptySet(), externalPlaybackKeysToPause(false, music, emptySet()))
        assertEquals(
            emptySet(),
            externalPlaybackKeysToPause(true, listOf(ExternalPlayback("music", playing = false)), emptySet()),
        )
    }

    @Test fun `暂停后其他媒体出声只夺回一次耳机键`() {
        assertTrue(shouldReclaimHeadphoneButton(true, false, true, false))
        assertFalse(shouldReclaimHeadphoneButton(true, false, true, true))
        assertFalse(shouldReclaimHeadphoneButton(false, false, true, false))
        assertFalse(shouldReclaimHeadphoneButton(true, true, true, false))
        assertFalse(shouldReclaimHeadphoneButton(true, false, false, false))
    }
}
