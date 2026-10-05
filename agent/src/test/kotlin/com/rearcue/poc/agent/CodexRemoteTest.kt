package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexRemoteTest {
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
