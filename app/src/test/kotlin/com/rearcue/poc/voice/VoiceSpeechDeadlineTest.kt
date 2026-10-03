package com.rearcue.poc.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class VoiceSpeechDeadlineTest {

    private class HangingEngine : SpeechSynthesizer {
        var stopped = false

        override suspend fun speak(text: String, voiceId: String, speed: Float): Boolean =
            awaitCancellation()

        override fun stop() {
            stopped = true
        }
    }

    @Test
    fun `引擎回调丢失时必须限时返回并停止引擎`() = runBlocking {
        val engine = HangingEngine()

        val result = withTimeout(500L) {
            VoiceSpeechDeadline.speak(
                engine = engine,
                text = "一句话",
                voiceId = "",
                speed = 1f,
                timeoutMs = 50L,
            )
        }

        assertEquals(false, result)
        assertTrue(engine.stopped)
    }
}
