package com.rearcue.poc.voice

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceMediaButtonGestureResolverTest {

    @Test
    fun `单击暂停超过双击窗口后再次单击继续`() {
        var now = 0L
        val resolver = VoiceMediaButtonGestureResolver(doubleClickWindowMs = 500L) { now }

        assertEquals(
            VoiceMediaButtonAction.TOGGLE_PAUSE_RESUME,
            resolver.fromKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        )
        now = 600L
        assertEquals(
            VoiceMediaButtonAction.TOGGLE_PAUSE_RESUME,
            resolver.fromKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        )
    }

    @Test
    fun `双击立即清空且第三击忽略`() {
        var now = 0L
        val resolver = VoiceMediaButtonGestureResolver(doubleClickWindowMs = 500L) { now }

        assertEquals(
            VoiceMediaButtonAction.TOGGLE_PAUSE_RESUME,
            resolver.fromKeyCode(KeyEvent.KEYCODE_HEADSETHOOK),
        )
        now = 250L
        assertEquals(
            VoiceMediaButtonAction.STOP_AND_CLEAR,
            resolver.fromKeyCode(KeyEvent.KEYCODE_HEADSETHOOK),
        )
        now = 300L
        assertEquals(
            VoiceMediaButtonAction.IGNORE,
            resolver.fromKeyCode(KeyEvent.KEYCODE_HEADSETHOOK),
        )
    }

    @Test
    fun `下一首和停止保持独立语义`() {
        val resolver = VoiceMediaButtonGestureResolver(doubleClickWindowMs = 500L) { 0L }

        assertEquals(
            VoiceMediaButtonAction.SKIP_CURRENT,
            resolver.fromKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT),
        )
        assertEquals(
            VoiceMediaButtonAction.STOP_AND_CLEAR,
            resolver.fromKeyCode(KeyEvent.KEYCODE_MEDIA_STOP),
        )
        assertEquals(
            VoiceMediaButtonAction.IGNORE,
            resolver.fromKeyCode(KeyEvent.KEYCODE_VOLUME_UP),
        )
    }
}
