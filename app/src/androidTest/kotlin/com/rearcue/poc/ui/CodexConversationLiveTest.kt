package com.rearcue.poc.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.R
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.agent.AgentSessionKeys
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Opt-in only: pass an idle, disposable diagnosticThreadId; sends exactly one real prompt. */
@RunWith(AndroidJUnit4::class)
class CodexConversationLiveTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun desktopFollowupUsesRealPhoneTransportAndPersistsDraft() {
        val threadId = InstrumentationRegistry.getArguments().getString("diagnosticThreadId")
        assumeTrue("Live bridge test requires a disposable diagnosticThreadId", threadId != null)
        require(threadId!!.matches(Regex("[0-9a-f-]{36}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as RearCueApp).container
        val id = AgentSessionKeys.bridge(AgentSources.CODEX, threadId)
        val prefs = context.getSharedPreferences("codex_remote", Context.MODE_PRIVATE)
        val client = BridgeCodexConversationClient(container.bridgeClient)
        // Resume verification after a user leaves the page: never send the diagnostic again.
        InstrumentationRegistry.getArguments().getString("verifyMarker")?.let { existingMarker ->
            require(existingMarker.matches(Regex("REARCUE_PHONE_[0-9a-f]{8}")))
            verifyDelivered(client, prefs, id, existingMarker)
            return
        }
        val previousSelection = prefs.getString("last-session", null)
        val intent = CodexConversationActivity.intentFor(context, id)
        val marker = "REARCUE_PHONE_" + UUID.randomUUID().toString().take(8)
        val prompt = "Reply only $marker. Do not call tools or read or modify files."
        var scenario: ActivityScenario<CodexConversationActivity>? = null
        try {
            scenario = ActivityScenario.launch(intent)
            try {
                // A cold app first drains bounded event pages before the roster snapshot arrives.
                rule.waitUntil(90_000) {
                    prefs.getString("last-session", null) == id &&
                        container.state.value.agentRoster.any { it.sessionId == id && it.status == AgentStatus.IDLE }
                }
            } catch (error: Throwable) {
                val state = container.state.value
                throw AssertionError("Live page readiness: bridge=${state.bridgeLinkStatus}, " +
                    "selected=${prefs.getString("last-session", null)}, " +
                    "roster=${state.agentRoster.map { it.sessionId to it.status }}", error)
            }
            assertEquals("", prefs.getString("draft:$id", ""))
            assertFalse(prefs.getBoolean("unknown:$id", false))
            rule.onNodeWithTag("codex-session-selector").performClick()
            rule.onNodeWithTag("codex-session-$id").assertIsDisplayed().performClick()
            rule.onNodeWithTag("codex-model-selector").performClick()
            rule.onAllNodesWithText(context.getString(R.string.codex_remote_model_auto))[1]
                .assertIsDisplayed().performClick()
            rule.onNodeWithTag("codex-input").performTextInput(prompt)
            scenario.close()
            scenario = ActivityScenario.launch(intent)
            rule.waitUntil(30_000) { prefs.getString("last-session", null) == id }
            rule.onNodeWithTag("codex-input").assertTextContains(prompt)
            rule.onNodeWithTag("codex-send").assertIsEnabled().performClick()
            rule.waitUntil(45_000) {
                prefs.getBoolean("desktop-managed:$id", false) &&
                    !prefs.getBoolean("unknown:$id", true) &&
                    prefs.getString("draft:$id", null) == ""
            }
            // Receipt must survive the owner switching apps while the desktop is answering.
            verifyDelivered(client, prefs, id, marker)
        } finally {
            scenario?.close()
            if (prefs.getString("last-session", null) == id) {
                prefs.edit().apply {
                    if (previousSelection == null) remove("last-session") else putString("last-session", previousSelection)
                }.commit()
            }
        }
    }

    private fun verifyDelivered(client: CodexConversationClient, prefs: SharedPreferences, id: String, marker: String) {
        assertEquals("已发送", prefs.getString("receipt:$id", null))
        assertEquals("", prefs.getString("draft:$id", null))
        assertFalse(prefs.getBoolean("unknown:$id", true))
        assertEquals(true, prefs.getBoolean("desktop-managed:$id", false))
        runBlocking {
            withTimeout(45_000) {
                while (true) {
                    val history = awaitCodexResult<List<AgentTurn>?> { client.history(id, it) }
                    if (history?.any { it.text.trim() == marker } == true) break
                    delay(1_000)
                }
            }
        }
    }
}
