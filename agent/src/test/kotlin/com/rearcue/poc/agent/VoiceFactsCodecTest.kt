package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceFactsCodecTest {
    @Test
    fun `explicit results and pending input decode without status guessing`() {
        val body = """{"events":[{"id":3,"source":"codex","sessionId":"s","status":"working",
          "voiceEvent":{"id":"turn:done","kind":"done","text":"本轮","createdAt":12},
          "pendingRequests":[{"id":"call:0","kind":"question","text":"问题。选项"}]}]}"""
        val state = BridgeEventCodec.toSessionState(BridgeEventCodec.parsePage(body)!!.single())!!
        assertEquals("本轮", state.voiceEvent?.text)
        assertEquals("call:0", state.pendingRequests.single().id)
        assertTrue(state.voiceEligible)
    }

    @Test
    fun `replay cannot enqueue historical questions but snapshot retains currently pending questions`() {
        val body = """{"events":[{"id":3,"sessionId":"s","status":"working","voiceReplay":true,
          "pendingRequests":[{"id":"old:0","kind":"question","text":"旧问题"}]}]}"""
        val state = BridgeEventCodec.toSessionState(BridgeEventCodec.parsePage(body)!!.single())!!
        assertFalse(state.voiceEligible)
        val snapshot = """{"cursor":4,"sessions":[{"sessionId":"s","status":"working",
          "pendingQuestions":[{"id":"live:0","title":"当前问题","options":["甲","乙"]}]}]}"""
        val current = BridgeEventCodec.parseSnapshot(snapshot)!!.single()
        assertTrue(current.voiceEligible)
        assertEquals("当前问题。甲。乙", current.pendingRequests.single().text)
    }
}
