package com.rearcue.poc.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.agent.CodexRemoteContext
import com.rearcue.poc.agent.CodexRemoteDefaults
import com.rearcue.poc.agent.CodexRemoteEffort
import com.rearcue.poc.agent.CodexRemoteModel
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemotePermission
import com.rearcue.poc.agent.CodexRemoteProject
import com.rearcue.poc.agent.CodexRemoteRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexRemoteCreateDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CodexConversationTestActivity>()

    private fun readyOptions(projects: List<CodexRemoteProject> = listOf(CodexRemoteProject("p1", "RearCue", "C:/ws"))) =
        CodexRemoteOptions(
            projects = projects,
            models = listOf(CodexRemoteModel("provider/model", "Model", true)),
            context = CodexRemoteContext(projects.first().id, projects.first().workspace, "provider/model"),
            defaults = CodexRemoteDefaults("provider/model", "high", "auto"),
            efforts = listOf(CodexRemoteEffort("high", "Thorough"), CodexRemoteEffort("low", "Fast")),
            permissions = listOf(
                CodexRemotePermission("auto", "工作区访问", "可修改项目文件；超出范围时需确认。"),
                CodexRemotePermission("full-access", "完全访问", "可修改电脑文件和执行命令，无需逐项确认。"),
            ),
        )

    private fun load(options: CodexRemoteOptions, projectId: String?, model: String?, callback: (CodexRemoteOptions?) -> Unit) {
        val selected = options.projectForId(projectId)
        callback(options.copy(context = CodexRemoteContext(projectId, selected?.workspace, model ?: options.defaults.model)))
    }

    @Test
    fun lockedDeviceClosesDialogAndReportsInsteadOfSilentlyIgnoringSend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("codex_remote_dialog_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("create-draft", "test prompt").commit()
        val seed = readyOptions()
        var open by mutableStateOf(true)
        var started = 0
        var locked = 0
        var dismissed = 0

        composeRule.setContent {
            if (open) CodexCreateDialog(
                options = seed, prefs = prefs, isDeviceUnlocked = { false },
                onDismiss = { dismissed += 1; open = false }, onLocked = { locked += 1 },
                onOptions = { projectId, model, callback -> load(seed, projectId, model, callback) },
                onStart = { started += 1 },
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("发送").performClick()
        composeRule.onNodeWithText("新建 Codex 对话").assertDoesNotExist()
        assertEquals(0, started)
        assertEquals(1, locked)
        assertEquals(1, dismissed)
    }

    @Test
    fun unlockedDeviceStartsDisplayedSnapshotAndClosesDialogImmediately() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("codex_remote_dialog_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("create-draft", "test prompt").commit()
        val seed = readyOptions()
        var open by mutableStateOf(true)
        var request: CodexRemoteRequest? = null
        var dismissed = 0

        composeRule.setContent {
            if (open) CodexCreateDialog(
                options = seed, prefs = prefs, isDeviceUnlocked = { true },
                onDismiss = { dismissed += 1; open = false }, onLocked = {},
                onOptions = { projectId, model, callback -> load(seed, projectId, model, callback) },
                onStart = { request = it },
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("codex-create-permission").performClick()
        composeRule.onNodeWithText("完全访问").assertIsDisplayed()
        composeRule.onNodeWithText("可修改电脑文件和执行命令，无需逐项确认。").assertIsDisplayed()
        composeRule.onNodeWithText("完全访问").performClick()
        composeRule.onNodeWithText("发送").performClick()
        composeRule.onNodeWithText("新建 Codex 对话").assertDoesNotExist()
        assertEquals("provider/model", request?.model)
        assertEquals("high", request?.effort)
        assertEquals("full-access", request?.permission)
        assertEquals(1, dismissed)
    }

    @Test
    fun duplicateProjectsKeepTheRememberedDirectoryAndShowDistinctDirectoriesBeforeSending() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("codex_remote_dialog_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("create-draft", "test prompt").putString("last-project", "old").commit()
        val projects = listOf(
            CodexRemoteProject("other", "Ant_Nest", "D:/other"),
            CodexRemoteProject("recent", "Ant_Nest", "C:/game"),
            CodexRemoteProject("old", "Ant_Nest", "c:\\GAME\\"),
            CodexRemoteProject("multi", "Ant_Nest", "C:/game", listOf("C:/game", "D:/assets")),
        )
        val options = readyOptions(projects)
        var sentRequest: CodexRemoteRequest? = null

        composeRule.setContent {
            CodexCreateDialog(
                options = options, prefs = prefs, isDeviceUnlocked = { true }, onDismiss = {}, onLocked = {},
                onOptions = { projectId, model, callback -> load(options, projectId, model, callback) },
                onStart = { sentRequest = it },
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Agent：Codex").assertIsDisplayed()
        composeRule.onNodeWithText("C:/game").assertIsDisplayed()
        composeRule.onNodeWithTag("codex-create-project").performClick()
        composeRule.onAllNodes(hasText("Ant_Nest") and hasAnyAncestor(isPopup())).assertCountEquals(3)
        composeRule.onNode(hasText("C:/game\nD:/assets") and hasAnyAncestor(isPopup())).performClick()
        composeRule.onNodeWithText("发送").performClick()

        assertEquals("multi", prefs.getString("last-project", null))
        assertEquals("multi", sentRequest?.projectId)
        assertEquals("C:/game", sentRequest?.workspace)
        assertTrue(sentRequest?.effort != null)
    }
}
