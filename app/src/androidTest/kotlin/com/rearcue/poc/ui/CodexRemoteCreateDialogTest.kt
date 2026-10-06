package com.rearcue.poc.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.agent.CodexRemoteModel
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemoteProject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexRemoteCreateDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CodexConversationTestActivity>()

    @Test
    fun lockedDeviceClosesDialogAndReportsInsteadOfSilentlyIgnoringSend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("codex_remote_dialog_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("create-draft", "test prompt").commit()
        var open by mutableStateOf(true)
        var started = 0
        var locked = 0
        var dismissed = 0

        composeRule.setContent {
            if (open) {
                CodexCreateDialog(
                    options = CodexRemoteOptions(
                        projects = listOf(
                            CodexRemoteProject(id = "p1", name = "RearCue", workspace = "C:/ws"),
                        ),
                        models = listOf(CodexRemoteModel(id = "m1", displayName = "Model", isDefault = true)),
                    ),
                    prefs = prefs,
                    isDeviceUnlocked = { false },
                    onDismiss = {
                        dismissed += 1
                        open = false
                    },
                    onLocked = { locked += 1 },
                    onStart = { _, _, _ -> started += 1 },
                )
            }
        }

        composeRule.onNodeWithText("发送").performClick()

        composeRule.onNodeWithText("新建 Codex 对话").assertDoesNotExist()
        assertEquals(0, started)
        assertEquals(1, locked)
        assertEquals(1, dismissed)
    }

    @Test
    fun unlockedDeviceStartsRequestAndClosesDialogImmediately() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("codex_remote_dialog_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("create-draft", "test prompt").commit()
        var open by mutableStateOf(true)
        var started = 0
        var locked = 0
        var dismissed = 0

        composeRule.setContent {
            if (open) {
                CodexCreateDialog(
                    options = CodexRemoteOptions(
                        projects = listOf(
                            CodexRemoteProject(id = "p1", name = "RearCue", workspace = "C:/ws"),
                        ),
                        models = emptyList(),
                    ),
                    prefs = prefs,
                    isDeviceUnlocked = { true },
                    onDismiss = {
                        dismissed += 1
                        open = false
                    },
                    onLocked = { locked += 1 },
                    onStart = { _, _, _ -> started += 1 },
                )
            }
        }

        composeRule.onNodeWithText("发送").performClick()

        composeRule.onNodeWithText("新建 Codex 对话").assertDoesNotExist()
        assertEquals(1, started)
        assertEquals(0, locked)
        assertEquals(1, dismissed)
    }
}
