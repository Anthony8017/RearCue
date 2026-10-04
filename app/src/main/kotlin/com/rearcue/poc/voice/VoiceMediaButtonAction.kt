package com.rearcue.poc.voice

import android.view.KeyEvent

/** Android 媒体键到 Voice Broadcast 手势语义的映射。 */
internal enum class VoiceMediaButtonAction {
    TOGGLE_PAUSE_RESUME,
    STOP_AND_CLEAR,
    SKIP_CURRENT,
    IGNORE,
}

/**
 * 媒体键手势：单击切换暂停/继续，双击终止整批播报。
 * 双击后的第三击在窗口内忽略，避免把已清空内容重新播放。
 */
internal class VoiceMediaButtonGestureResolver(
    private val doubleClickWindowMs: Long = 500L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastToggleAtMs: Long? = null
    private var ignoreToggleUntilMs: Long = Long.MIN_VALUE

    @Synchronized
    fun fromKeyCode(keyCode: Int): VoiceMediaButtonAction = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> VoiceMediaButtonAction.SKIP_CURRENT
        KeyEvent.KEYCODE_MEDIA_STOP -> VoiceMediaButtonAction.STOP_AND_CLEAR
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        -> toggleGesture()
        else -> VoiceMediaButtonAction.IGNORE
    }

    private fun toggleGesture(): VoiceMediaButtonAction {
        val now = clock()
        if (now < ignoreToggleUntilMs) return VoiceMediaButtonAction.IGNORE
        val previous = lastToggleAtMs
        if (previous != null && now >= previous && now - previous <= doubleClickWindowMs) {
            lastToggleAtMs = null
            ignoreToggleUntilMs = now + doubleClickWindowMs
            return VoiceMediaButtonAction.STOP_AND_CLEAR
        }
        lastToggleAtMs = now
        return VoiceMediaButtonAction.TOGGLE_PAUSE_RESUME
    }
}
