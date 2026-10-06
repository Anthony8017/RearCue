package com.rearcue.poc.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.agent.AgentSessionKeys
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.agent.CodexRemoteModel
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemoteProject
import com.rearcue.poc.agent.CodexRemoteRequest
import com.rearcue.poc.agent.CodexRemoteResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexConversationScreenTest {
    @get:Rule val rule = createAndroidComposeRule<CodexConversationTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("codex_conversation_screen_test", Context.MODE_PRIVATE)
    private val a = AgentSessionKeys.bridge(AgentSources.CODEX, "a")
    private val b = AgentSessionKeys.bridge(AgentSources.CODEX, "b")

    private fun session(id: String, title: String, status: AgentStatus = AgentStatus.IDLE) =
        AgentSessionState(id, source = AgentSources.CODEX, title = title, status = status)

    private fun show(client: FakeClient, sessions: List<AgentSessionState> = listOf(session(a, "对话 A"), session(b, "对话 B"))) {
        prefs.edit().clear().commit()
        rule.setContent {
            CodexConversationScreen(client, BridgeLinkStatus.CONNECTED, sessions, a, prefs, { true }, {})
        }
    }

    @Test fun sendImmediatelyShowsPendingAndDefiniteFailureKeepsDraft() {
        val client = FakeClient()
        show(client)
        rule.onNodeWithTag("codex-input").performTextInput("继续检查")
        rule.onNodeWithTag("codex-send").performClick()
        rule.onNodeWithTag("codex-send").assertIsNotEnabled()
        rule.onNodeWithTag("codex-note").assertTextContains("正在发送…")
        rule.runOnIdle {
            assertEquals(1, client.sent.size)
            client.respond(CodexRemoteResult.Failed("电脑仍占用这个会话，暂时无法从手机发送"))
        }
        rule.onNodeWithTag("codex-input").assertTextContains("继续检查")
        rule.onNodeWithTag("codex-note").assertTextContains("电脑仍占用", substring = true)
        rule.onNodeWithTag("codex-send").assertIsEnabled()
    }

    @Test fun switchingConversationDuringSendCannotEraseTheNewConversationDraft() {
        val client = FakeClient()
        show(client)
        rule.onNodeWithTag("codex-input").performTextInput("A 的追问")
        rule.onNodeWithTag("codex-send").performClick()
        rule.onNodeWithTag("codex-session-selector").performClick()
        rule.onNodeWithTag("codex-session-$b").performClick()
        rule.onNodeWithTag("codex-input").performTextInput("B 的草稿")
        rule.runOnIdle { client.respond(CodexRemoteResult.Accepted("a", "turn-a")) }
        rule.onNodeWithTag("codex-input").assertTextContains("B 的草稿")
        rule.runOnIdle {
            assertEquals("", prefs.getString("draft:$a", null))
            assertEquals("B 的草稿", prefs.getString("draft:$b", null))
        }
    }

    @Test fun freshWorkingStateBlocksSendingButAllowsWritingDraft() {
        val client = FakeClient()
        prefs.edit().clear().commit()
        var sessions by mutableStateOf(listOf(session(a, "对话 A")))
        rule.setContent { CodexConversationScreen(client, BridgeLinkStatus.CONNECTED, sessions, a, prefs, { true }, {}) }
        rule.onNodeWithTag("codex-input").performTextInput("下一条")
        rule.runOnIdle { sessions = listOf(session(a, "对话 A", AgentStatus.WORKING)) }
        rule.onNodeWithTag("codex-send").assertIsNotEnabled()
        rule.onNodeWithTag("codex-input").performTextInput("草稿")
        rule.onNodeWithTag("codex-input").assertTextContains("下一条草稿")
        rule.runOnIdle { sessions = listOf(session(a, "对话 A")) }
        rule.onNodeWithTag("codex-send").assertIsEnabled()
    }

    @Test fun firstVisitWithoutRecentSessionAutomaticallySelectsTheFirstConversation() {
        val client = FakeClient()
        prefs.edit().clear().commit()
        rule.setContent {
            CodexConversationScreen(client, BridgeLinkStatus.CONNECTED, listOf(session(a, "对话 A")), null, prefs, { true }, {})
        }
        rule.onNodeWithText("对话 A").assertIsDisplayed()
        rule.onNodeWithTag("codex-input").assertIsEnabled()
    }

    @Test fun completeHistoryIncludesAnswersAndProcessCanBeExpanded() {
        val client = FakeClient()
        show(client)
        rule.onNodeWithText("历史提问").assertIsDisplayed()
        rule.onNodeWithText("历史回答").assertIsDisplayed()
        rule.onNodeWithText("中间过程").assertDoesNotExist()
        rule.onNodeWithText("▸ 过程 · 进度说明 · 1 项").performClick()
        rule.onNodeWithText("中间过程").assertIsDisplayed()
    }

    @Test fun unknownReceiptRequiresManualConfirmationBeforeResending() {
        val client = FakeClient()
        show(client)
        rule.onNodeWithTag("codex-input").performTextInput("追问")
        rule.onNodeWithTag("codex-send").performClick()
        rule.runOnIdle { client.respond(CodexRemoteResult.Unknown("timeout")) }
        rule.onNodeWithTag("codex-send").performClick()
        rule.onNodeWithText("再次发送？").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, client.sent.size) }
        rule.onNodeWithText("确认发送").performClick()
        rule.runOnIdle { assertEquals(2, client.sent.size) }
    }

    @Test fun acceptedReceiptStillUpdatesDraftAfterLeavingThePage() {
        val client = FakeClient()
        prefs.edit().clear().commit()
        var visible by mutableStateOf(true)
        rule.setContent {
            if (visible) CodexConversationScreen(client, BridgeLinkStatus.CONNECTED, listOf(session(a, "对话 A")), a, prefs, { true }, {})
        }
        rule.onNodeWithTag("codex-input").performTextInput("追问")
        rule.onNodeWithTag("codex-send").performClick()
        rule.runOnIdle { visible = false }
        rule.runOnIdle {
            client.respond(CodexRemoteResult.Accepted("a"))
            assertEquals("", prefs.getString("draft:$a", null))
            assertEquals(false, prefs.getBoolean("unknown:$a", true))
        }
    }

    @Test fun writingNextDraftWhileWaitingForReceiptKeepsIt() {
        val client = FakeClient()
        show(client)
        rule.onNodeWithTag("codex-input").performTextInput("旧稿")
        rule.onNodeWithTag("codex-send").performClick()
        rule.onNodeWithTag("codex-input").performTextInput("新内容")
        rule.runOnIdle { client.respond(CodexRemoteResult.Accepted("a")) }
        rule.onNodeWithTag("codex-input").assertTextContains("旧稿新内容")
    }

    private class FakeClient : CodexConversationClient {
        val sent = mutableListOf<Pair<String, CodexRemoteRequest>>()
        private var callback: ((CodexRemoteResult) -> Unit)? = null
        override fun options(callback: (CodexRemoteOptions?) -> Unit) = callback(CodexRemoteOptions(
            listOf(CodexRemoteProject("p", "RearCue", "C:/ws")), listOf(CodexRemoteModel("model", "可用模型", true)),
        ))
        override fun history(sessionId: String, callback: (List<AgentTurn>?) -> Unit) = callback(listOf(
            AgentTurn(AgentTurnRole.USER, "历史提问", ts = 1, kind = AgentTurnKind.PROMPT, entryId = "u", roundId = "r", roundComplete = true),
            AgentTurn(AgentTurnRole.AGENT, "中间过程", ts = 2, kind = AgentTurnKind.PROGRESS, entryId = "p", roundId = "r", roundComplete = true),
            AgentTurn(AgentTurnRole.AGENT, "历史回答", ts = 3, entryId = "f", roundId = "r", roundComplete = true),
        ))
        override fun send(sessionId: String, request: CodexRemoteRequest, callback: (CodexRemoteResult) -> Unit) {
            sent += sessionId to request; this.callback = callback
        }
        fun respond(result: CodexRemoteResult) { callback?.invoke(result); callback = null }
        override fun create(request: CodexRemoteRequest, callback: (CodexRemoteResult) -> Unit) = callback(CodexRemoteResult.Accepted("new"))
        override fun stop(sessionId: String, requestId: String, callback: (CodexRemoteResult) -> Unit) = callback(CodexRemoteResult.Accepted())
        override fun delete(sessionId: String, requestId: String, callback: (CodexRemoteResult) -> Unit) = callback(CodexRemoteResult.Rejected("forbidden"))
        override fun markRead(sessionId: String) = Unit
    }
}
