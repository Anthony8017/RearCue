package com.rearcue.poc.voice

import android.media.AudioManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceAudioFocusPolicyTest {
    @Test
    fun `navigation duck pauses until audio focus returns`() {
        val interrupted = interruptedAfterAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, false)
        assertTrue(interrupted)
        assertFalse(interruptedAfterAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN, interrupted))
    }

    @Test
    fun `focus loss interrupts and unknown changes preserve state`() {
        assertTrue(interruptedAfterAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, false))
        assertTrue(interruptedAfterAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, false))
        assertTrue(interruptedAfterAudioFocusChange(0, true))
        assertFalse(interruptedAfterAudioFocusChange(0, false))
    }
}
