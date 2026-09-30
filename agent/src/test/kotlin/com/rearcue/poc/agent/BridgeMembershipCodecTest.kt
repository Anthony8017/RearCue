package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeMembershipCodecTest {

    @Test
    fun `桥事件页解析来源在册事实_保持bridge前缀并隔离来源键`() {
        val facts = BridgeEventCodec.parseMembershipPage(
            """
            {"events":[
              {"id":1,"kind":"membership","source":"codex","sourceSessionId":"same","membership":"PRESENT","archiveState":"ACTIVE","reason":"membership-contract","generation":1,"revision":1},
              {"id":2,"kind":"membership","source":"claude","sourceSessionId":"same","membership":"ABSENT","archiveState":"ARCHIVED","reason":"archive","generation":2},
              {"id":3,"sessionId":"ordinary","status":"idle"}
            ],"cursor":3}
            """.trimIndent(),
        )!!
        assertEquals(2, facts.size)
        assertEquals("bridge:codex:same", facts[0].identity.sessionId)
        assertEquals(AgentMembership.PRESENT, facts[0].membership)
        assertEquals("codex", facts[0].identity.source)
        assertEquals("claude", facts[1].identity.source)
        assertEquals(AgentArchiveState.ARCHIVED, facts[1].archiveState)
        assertTrue(facts[1].newerThan(facts[0].generation, facts[0].revision))
    }

    @Test
    fun `快照解析与旧桥兼容_空memberships是合法空事实集`() {
        val facts = BridgeEventCodec.parseMembershipSnapshot(
            """
            {"sessions":[],"memberships":[
              {"source":"dsh","sourceSessionId":"d-1","membership":"ABSENT","archiveState":"UNKNOWN","reason":"source-removed","generation":4,"revision":5}
            ]}
            """.trimIndent(),
        )!!
        assertEquals(1, facts.size)
        assertEquals("bridge:dsh:d-1", facts.single().identity.sessionId)
        assertEquals(AgentMembershipReason.SOURCE_REMOVED, facts.single().reason)
        assertEquals(emptyList(), BridgeEventCodec.parseMembershipSnapshot("""{"sessions":[]}"""))
    }

    @Test
    fun `membership恢复事实携带状态_普通事件页不再把membership当活动`() {
        val facts = BridgeEventCodec.parseMembershipPage(
            """
            {"events":[
              {"id":1,"kind":"membership","source":"codex","sourceSessionId":"restore-1","membership":"PRESENT","archiveState":"ACTIVE","reason":"unarchive","generation":3,"revision":3,"status":"working","workspace":"C:/repo","currentAction":"test"}
            ],"cursor":1}
            """.trimIndent(),
        )!!
        val restored = facts.single()
        assertEquals("bridge:codex:restore-1", restored.identity.sessionId)
        assertEquals("C:/repo", restored.state?.workspace)
        assertEquals(AgentStatus.WORKING, restored.state?.status)
        assertEquals("test", restored.state?.currentAction)

        assertEquals(
            emptyList(),
            BridgeEventCodec.parsePage(
                """
                {"events":[{"id":1,"kind":"membership","sessionId":"restore-1","status":"working"}],"cursor":1}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `坏来源在册事实跳过_不把未知生命周期写进真值`() {
        val parsed = BridgeEventCodec.parseMembershipPage(
            """
            {"events":[
              {"kind":"membership","source":"watson","sourceSessionId":"x","membership":"PRESENT","archiveState":"ACTIVE","generation":1},
              {"kind":"membership","source":"codex","sourceSessionId":"x","membership":"MAYBE","archiveState":"ACTIVE","generation":1},
              {"kind":"membership","source":"codex","sourceSessionId":"x","membership":"PRESENT","archiveState":"ACTIVE","generation":-1}
            ],"cursor":3}
            """.trimIndent(),
        )
        assertEquals(emptyList(), parsed)
        assertNull(BridgeEventCodec.parseMembershipPage("not json"))
    }
}
