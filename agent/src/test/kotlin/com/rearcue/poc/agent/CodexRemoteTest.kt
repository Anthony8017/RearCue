package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexRemoteTest {
    @Test
    fun `partial read failures keep usable lists and unknown defaults without guessing`() {
        val options = CodexRemoteCodec.parseOptions(
            """
            {"ok":true,"projects":[],"models":[{"id":"m","isDefault":true}],
             "context":{"projectId":"p1","workspace":"C:/ws","model":"m"},
             "defaults":{"model":null,"effort":null,"permission":null},
             "efforts":[{"id":"none"}],"permissions":[{"id":"custom","label":"自定义","description":"电脑规则"}],
             "errors":[{"field":"defaults.model","code":"read-failed"},{"field":"defaults.permission","code":"not-allowed"}]}
            """.trimIndent(),
        )
        assertEquals(CodexRemoteDefaults(), options.defaults)
        assertEquals(listOf("none"), options.efforts.map { it.id })
        assertEquals(listOf("custom"), options.permissions.map { it.id })
        assertEquals(
            listOf(CodexRemoteOptionError("defaults.model", "read-failed"), CodexRemoteOptionError("defaults.permission", "not-allowed")),
            options.errors,
        )
    }

    @Test
    fun `followups never carry cached creation effort or permission even on a reused request`() {
        assertEquals(
            """{"requestId":"next","prompt":"continue","model":"new-model"}""",
            CodexRemoteRequest("next", "continue", projectId = "p1", model = "new-model", effort = "high", permission = "full-access").toMessageJson(),
        )
    }

    @Test
    fun `context options preserve actual model efforts and the permitted profile labels`() {
        val options = CodexRemoteCodec.parseOptions(
            """
            {"ok":true,"projects":[{"id":"p1","name":"RearCue","roots":[{"path":"C:/ws"}]}],
             "models":[{"id":"provider/model-a","displayName":"Model A","isDefault":true}],
             "context":{"projectId":"p1","workspace":"C:/ws","model":"provider/model-a"},
             "defaults":{"model":"provider/model-a","effort":"none","permission":"profile:team"},
             "efforts":[{"id":"none","description":"No reasoning"},{"id":"high","description":"Thorough"}],
             "permissions":[{"id":"profile:team","label":"团队权限","description":"需确认执行命令",
               "settings":{"permissions":"team","approvalPolicy":"on-request"}}],"errors":[]}
            """.trimIndent(),
        )
        assertEquals(CodexRemoteContext("p1", "C:/ws", "provider/model-a"), options.context)
        assertEquals(CodexRemoteDefaults("provider/model-a", "none", "profile:team"), options.defaults)
        assertEquals(listOf("none", "high"), options.efforts.map { it.id })
        assertEquals("Thorough", options.efforts.last().description)
        assertEquals(CodexRemotePermission("profile:team", "团队权限", "需确认执行命令"), options.permissions.single())
    }

    @Test
    fun `creation submits the displayed conversation settings without interpreting permission settings`() {
        val body = CodexRemoteRequest(
            "r-settings", "read \"this\"", projectId = "p1",
            model = "provider/model-a", effort = "none", permission = "profile:team",
        ).toCreateJson()
        assertEquals(
            """{"requestId":"r-settings","prompt":"read \"this\"","projectId":"p1","model":"provider/model-a","effort":"none","permission":"profile:team"}""",
            body,
        )
    }

    @Test
    fun `desktop delivery receipt exposes which side can stop its turn`() {
        assertEquals(
            CodexRemoteResult.Accepted("t1", "turn1", "desktop"),
            CodexRemoteCodec.parseResult("""{"ok":true,"receipt":"accepted","threadId":"t1","turnId":"turn1","managedBy":"desktop"}""", true),
        )
        assertEquals(
            CodexRemoteResult.Rejected("unsupported", "no-managed-turn"),
            CodexRemoteCodec.parseResult("""{"ok":false,"receipt":"unsupported","reason":"no-managed-turn"}""", false),
        )
    }

    @Test
    fun `writer conflict has a clear failure instead of an unknown send status`() {
        assertEquals(
            CodexRemoteResult.Failed("电脑仍占用这个会话，暂时无法从手机发送"),
            CodexRemoteCodec.parseResult(
                """{"ok":false,"receipt":"failed","error":"thread-store conflict: thread t1 already has an active writer"}""",
                false,
            ),
        )
    }

    @Test
    fun `definite failed receipt stays failed on HTTP 500`() {
        assertEquals(
            CodexRemoteResult.Failed("model unavailable"),
            CodexRemoteCodec.parseResult("""{"ok":false,"receipt":"failed","error":"model unavailable"}""", false),
        )
    }

    @Test
    fun `unauthorized create is a definite failure with credential recovery guidance`() {
        assertEquals(
            CodexRemoteResult.Failed("桥访问凭据缺失或已失效，请重新复制电脑托盘中的完整桥地址"),
            CodexRemoteCodec.parseResult("""{"ok":false,"error":"unauthorized"}""", false),
        )
    }

    @Test
    fun `bridge fragment carries write token without changing base url`() {
        val endpoint = BridgeEndpoint.parse("https://example.test/base/#token=abc%2F123&x=1")
        assertEquals("https://example.test/base", endpoint.baseUrl)
        assertEquals("abc/123", endpoint.accessToken)
    }

    @Test
    fun `options only expose real projects and current models`() {
        val options = CodexRemoteCodec.parseOptions(
            """
            {"ok":true,"projects":[{"id":"p1","name":"RearCue","roots":[{"path":"C:\\ws"}]}],
             "models":[{"id":"m1","displayName":"Model One","isDefault":true}]}
            """.trimIndent(),
        )
        assertEquals("C:\\ws", options.projects.single().workspace)
        assertEquals("Model One", options.models.single().displayName)
        assertTrue(options.models.single().isDefault)
    }

    @Test
    fun `duplicate Windows directories keep recent order and resolve an older remembered project`() {
        val options = CodexRemoteCodec.parseOptions(
            """
            {"projects":[
              {"id":"other","name":"Other","roots":[{"path":"D:/other"}]},
              {"id":"recent","name":"RearCue","roots":[{"path":"C:/ws"}]},
              {"id":"old","name":"RearCue","roots":[{"path":"c:\\WS\\"}]}
            ],"models":[]}
            """.trimIndent(),
        )

        assertEquals(listOf("other", "recent"), options.projects.map { it.id })
        assertEquals("recent", options.projectForId("old")?.id)
        assertEquals("C:/ws", options.projectForId("old")?.workspace)
        assertEquals(listOf("C:/ws"), options.projectForId("old")?.roots)
        assertEquals(null, options.projectForId("unknown"))
        assertEquals(null, options.projectForId(null))
    }

    @Test
    fun `overlapping directories and different primary directories remain separate`() {
        val options = CodexRemoteCodec.parseOptions(
            """
            {"projects":[
              {"id":"single","name":"Ant_Nest","roots":[{"path":"C:/game"}]},
              {"id":"multi","name":"Ant_Nest","roots":[{"path":"C:/game"},{"path":"D:/assets"},{"path":"E:/docs"}]},
              {"id":"old-multi","name":"Ant_Nest","roots":[{"path":"c:\\GAME"},{"path":"E:/docs/"},{"path":"D:/assets"}]},
              {"id":"different-primary","name":"Ant_Nest","roots":[{"path":"D:/assets"},{"path":"C:/game"},{"path":"E:/docs"}]},
              {"id":"different-roots","name":"Ant_Nest","roots":[{"path":"C:/game"},{"path":"D:/assets"}]}
            ],"models":[]}
            """.trimIndent(),
        )

        assertEquals(listOf("single", "multi", "different-primary", "different-roots"), options.projects.map { it.id })
        assertEquals("multi", options.projectForId("old-multi")?.id)
        assertEquals(listOf("C:/game", "D:/assets", "E:/docs"), options.projectForId("old-multi")?.roots)
        assertEquals("D:/assets", options.projectForId("different-primary")?.workspace)
    }

    @Test
    fun `same names in different directories and case sensitive paths remain separate`() {
        val options = CodexRemoteCodec.parseOptions(
            """
            {"projects":[
              {"id":"c","name":"Project","roots":[{"path":"C:/project"}]},
              {"id":"d","name":"Project","roots":[{"path":"D:/project"}]},
              {"id":"upper","name":"Project","roots":[{"path":"/work/Project"}]},
              {"id":"lower","name":"Project","roots":[{"path":"/work/project"}]}
            ],"models":[]}
            """.trimIndent(),
        )

        assertEquals(listOf("c", "d", "upper", "lower"), options.projects.map { it.id })
    }

    @Test
    fun `UNC directory duplicates merge without changing the path used for creation`() {
        val options = CodexRemoteCodec.parseOptions(
            """
            {"projects":[
              {"id":"recent","name":"Shared","roots":[{"path":"\\\\Server\\Share\\Repo\\"}]},
              {"id":"old","name":"Shared","roots":[{"path":"//server/share/repo"}]}
            ],"models":[]}
            """.trimIndent(),
        )

        assertEquals(1, options.projects.size)
        assertEquals("recent", options.projectForId("old")?.id)
        assertEquals("\\\\Server\\Share\\Repo\\", options.projects.single().workspace)
    }

    @Test
    fun `remote results distinguish unknown from user rejection`() {
        assertEquals(
            CodexRemoteResult.Unknown("timeout"),
            CodexRemoteCodec.parseResult("""{"ok":false,"receipt":"unknown","error":"timeout"}""", true),
        )
        assertEquals(
            CodexRemoteResult.Rejected("forbidden", "only-failed-empty-remote-sessions"),
            CodexRemoteCodec.parseResult(
                """{"ok":false,"receipt":"forbidden","reason":"only-failed-empty-remote-sessions"}""",
                true,
            ),
        )
        val accepted = CodexRemoteCodec.parseResult(
            """{"ok":true,"receipt":"accepted","threadId":"t1","turnId":"turn1"}""",
            true,
        )
        assertTrue(accepted is CodexRemoteResult.Accepted)
        assertEquals("t1", (accepted as CodexRemoteResult.Accepted).threadId)
        assertFalse(CodexRemoteRequest("r1", "hello").toCreateJson().contains("model"))
    }
}
