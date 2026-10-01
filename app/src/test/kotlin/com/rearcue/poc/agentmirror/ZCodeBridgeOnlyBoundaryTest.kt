package com.rearcue.poc.agentmirror

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * ZCode bridge-only boundary (#234/#243): the app binary must not retain any direct
 * ZCode transport entry point. This is the user-visible "no fallback path" contract:
 * disconnected stays disconnected until the PC bridge recovers.
 */
class ZCodeBridgeOnlyBoundaryTest {

    @Test
    fun `app binary has no direct ZCode transport entry points`() {
        listOf(
            "com.rearcue.poc.agentmirror.AgentRelayClient",
            "com.rearcue.poc.agentmirror.AgentLinkStore",
            "com.rearcue.poc.agentmirror.AgentLinkStatus",
        ).forEach { className ->
            assertFailsWith<ClassNotFoundException>(className) {
                Class.forName(className)
            }
        }
    }
}
