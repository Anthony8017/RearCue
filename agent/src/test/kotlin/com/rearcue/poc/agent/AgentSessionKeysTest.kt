package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionKeysTest {

    @Test
    fun `桥键来源作用域可逆_Codex与Claude同原始id不冲突`() {
        val codex = AgentSessionKeys.bridge(AgentSources.CODEX, "same")
        val claude = AgentSessionKeys.bridge(AgentSources.CLAUDE, "same")

        assertEquals("bridge:codex:same", codex)
        assertEquals("bridge:claude:same", claude)
        assertEquals("same", AgentSessionKeys.bridgeSourceSessionId(codex))
        assertEquals("same", AgentSessionKeys.bridgeSourceSessionId(claude))
        assertEquals(AgentSources.CODEX, AgentSessionKeys.bridgeSource(codex))
        assertEquals(AgentSources.CLAUDE, AgentSessionKeys.bridgeSource(claude))
    }

    @Test
    fun `旧bridge键兼容取原始id_非桥键不误认`() {
        assertEquals("legacy-id", AgentSessionKeys.bridgeSourceSessionId("bridge:legacy-id"))
        assertNull(AgentSessionKeys.bridgeSourceSessionId("zcode-id"))
        assertNull(AgentSessionKeys.bridgeSource("bridge:legacy-id"))
    }
}
