package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskListParserMembershipTest {

    private fun payload(vararg tasks: String): String =
        """{"result":{"tasks":[${tasks.joinToString(",")}]}}"""

    private fun task(
        id: String,
        archived: Boolean? = null,
        status: String = "running",
        updatedAt: Long = 1L,
    ): String = buildString {
        append("{\"taskId\":\"$id\",\"displayStatus\":\"$status\",\"updatedAt\":$updatedAt,\"title\":\"$id\"")
        if (archived != null) append(""","archived":$archived""")
        append("}")
    }

    @Test
    fun `任务表是归档真值_ACTIVE到ARCHIVED再到ACTIVE逐代产生来源事实`() {
        val first = TaskListParser.parseMembership(payload(task("z1")), generation = 1)!!.single()
        assertEquals(AgentSources.ZCODE, first.source)
        assertEquals("z1", first.sourceSessionId)
        assertEquals("bridge:zcode:z1", first.identity.sessionId, "四来源统一桥键空间")
        assertEquals(AgentMembership.PRESENT, first.membership)
        assertEquals(AgentArchiveState.ACTIVE, first.archiveState)

        val archived = TaskListParser.parseMembership(
            payload(task("z1", archived = true)),
            generation = 2,
        )!!.single()
        assertEquals(AgentMembership.ABSENT, archived.membership)
        assertEquals(AgentArchiveState.ARCHIVED, archived.archiveState)
        assertEquals(AgentMembershipReason.ARCHIVE, archived.reason)

        val restored = TaskListParser.parseMembership(
            payload(task("z1", archived = false)),
            generation = 3,
        )!!.single()
        assertEquals(AgentMembership.PRESENT, restored.membership)
        assertEquals(AgentArchiveState.ACTIVE, restored.archiveState)
        assertTrue(restored.newerThan(archived.generation, archived.revision))
    }

    @Test
    fun `完整任务表缺行是ACTIVE到ABSENT_不冒充归档`() {
        val facts = TaskListParser.parseMembership(
            payload(),
            generation = 2,
            previouslySeen = setOf("z1", "z2"),
            revision = 7,
        )!!
        assertEquals(setOf("z1", "z2"), facts.map { it.sourceSessionId }.toSet())
        facts.forEach {
            assertEquals(AgentMembership.ABSENT, it.membership)
            assertEquals(AgentArchiveState.UNKNOWN, it.archiveState)
            assertEquals(AgentMembershipReason.SOURCE_REMOVED, it.reason)
            assertEquals(2L, it.generation)
            assertEquals(7L, it.revision)
        }
    }

    @Test
    fun `parseAll继续排除归档任务_非任务响应与空表仍可区分`() {
        assertEquals(
            listOf("z2"),
            TaskListParser.parseAll(
                payload(task("z1", archived = true), task("z2", archived = false)),
            )!!.map { it.sessionId },
        )
        assertNull(TaskListParser.parseMembership("""{"zcode_type":"rpc-frame"}""", generation = 1))
        assertEquals(
            emptyList(),
            TaskListParser.parseMembership(payload(), generation = 1),
        )
    }
}
