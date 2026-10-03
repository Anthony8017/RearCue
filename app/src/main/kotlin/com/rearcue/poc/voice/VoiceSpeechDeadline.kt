package com.rearcue.poc.voice

import kotlinx.coroutines.withTimeoutOrNull

/** 语音引擎单句执行的超时保护面：引擎回调丢失不得卡死整条 Voice Broadcast 队列。 */
object VoiceSpeechDeadline {
    suspend fun speak(
        engine: SpeechSynthesizer,
        text: String,
        voiceId: String,
        speed: Float,
        timeoutMs: Long,
    ): Boolean {
        val result = withTimeoutOrNull(timeoutMs) {
            engine.speak(text, voiceId, speed)
        }
        if (result != true) engine.stop()
        return result == true
    }
}
