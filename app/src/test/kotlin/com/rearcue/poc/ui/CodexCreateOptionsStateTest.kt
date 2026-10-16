package com.rearcue.poc.ui

import com.rearcue.poc.agent.CodexRemoteContext
import com.rearcue.poc.agent.CodexRemoteDefaults
import com.rearcue.poc.agent.CodexRemoteEffort
import com.rearcue.poc.agent.CodexRemoteModel
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemotePermission
import com.rearcue.poc.agent.CodexRemoteProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexCreateOptionsStateTest {
    private val projects = listOf(
        CodexRemoteProject("p1", "RearCue", "C:/ws"),
        CodexRemoteProject("p2", "Other", "D:/other"),
    )
    private val models = listOf(
        CodexRemoteModel("provider/a", "Model A", true),
        CodexRemoteModel("provider/b", "Model B", false),
    )

    private fun options(
        project: String = "p1", model: String = "provider/a",
        defaults: CodexRemoteDefaults = CodexRemoteDefaults("provider/a", "high", "auto"),
    ) = CodexRemoteOptions(
        projects, models,
        context = CodexRemoteContext(project, projects.first { it.id == project }.workspace, model),
        defaults = defaults,
        efforts = listOf(CodexRemoteEffort("none", "No reasoning"), CodexRemoteEffort("high", "Thorough")),
        permissions = listOf(
            CodexRemotePermission("auto", "默认权限", "修改文件，命令需确认"),
            CodexRemotePermission("full-access", "完全访问", "可修改电脑文件和执行命令，无需逐项确认。"),
        ),
    )

    private fun ready(): CodexCreateOptionsState {
        val state = CodexCreateOptionsState.open(options(), "p1", "任务草稿")
        return state.loaded(state.pending!!, options())
    }

    @Test fun invalidManualChoicesStayVisibleAndBlockCreationUntilReselected() {
        var state = ready().selectEffort("none").selectPermission("full-access").selectProject("p2")
        state = state.loaded(state.pending!!, options(project = "p2").copy(
            efforts = listOf(CodexRemoteEffort("high", "Thorough")),
            permissions = listOf(CodexRemotePermission("auto", "默认权限", "修改文件，命令需确认")),
        ))
        assertEquals("none", state.effortId)
        assertEquals("full-access", state.permissionId)
        assertTrue(state.effortNeedsReselection)
        assertTrue(state.permissionNeedsReselection)
        assertNull(state.createRequest("invalid"))
        assertEquals("任务草稿", state.prompt)
        state = state.selectEffort("high").selectPermission("auto")
        assertNotNull(state.createRequest("reselected"))
        assertFalse(state.effortNeedsReselection)
        assertFalse(state.permissionNeedsReselection)
    }

    @Test fun evenTheLatestResponseCannotEnableCreationForAnotherProjectOrModel() {
        for (response in listOf(options(project = "p2", model = "provider/b"), options(model = "provider/a"))) {
            var state = ready().selectModel("provider/b")
            state = state.loaded(state.pending!!, response)
            assertTrue(state.readFailed)
            assertNull(state.createRequest("mismatched-context"))
            assertEquals("任务草稿", state.prompt)
        }
    }

    @Test fun aLateReadCannotReplaceTheLatestProjectOrTheDisplayedCombinationAfterCompletion() {
        var state = ready().selectProject("p2")
        val oldRead = state.pending!!
        state = state.selectProject("p1")
        val currentRead = state.pending!!
        state = state.loaded(oldRead, options("p2", "provider/b", CodexRemoteDefaults("provider/b", "none", "full-access")))
        assertEquals(currentRead, state.pending)
        assertNull(state.createRequest("late"))
        state = state.loaded(currentRead, options())
        state = state.loaded(currentRead, options(defaults = CodexRemoteDefaults("provider/a", "none", "full-access")))
        val creation = assertNotNull(state.createRequest("visible"))
        assertEquals("p1", creation.projectId)
        assertEquals("high", creation.effort)
        assertEquals("auto", creation.permission)
    }

    @Test fun changingModelKeepsValidManualEffortAndPermissionInsteadOfReplacingThemWithDefaults() {
        var state = ready().selectEffort("none").selectPermission("full-access").selectModel("provider/b")
        val read = assertNotNull(state.pending)
        assertEquals("provider/b", read.model)
        state = state.loaded(read, options(model = "provider/b"))
        val creation = assertNotNull(state.createRequest("manual"))
        assertEquals("provider/b", state.manualModelId)
        assertEquals("provider/b", creation.model)
        assertEquals("none", creation.effort)
        assertEquals("full-access", creation.permission)
    }

    @Test fun changingProjectUpdatesUntouchedDefaultsAndKeepsTheDraft() {
        var state = ready().selectProject("p2")
        assertFalse(state.canCreate)
        val read = assertNotNull(state.pending)
        assertEquals("p2", read.projectId)
        assertNull(read.model)
        state = state.loaded(read, options("p2", "provider/b", CodexRemoteDefaults("provider/b", "none", "full-access")))
        val creation = assertNotNull(state.createRequest("project-change"))
        assertEquals("p2", creation.projectId)
        assertEquals("provider/b", creation.model)
        assertEquals("none", creation.effort)
        assertEquals("full-access", creation.permission)
        assertEquals("任务草稿", creation.prompt)
    }

    @Test fun openingReadsTheProjectDefaultsAndSubmitsTheDisplayedModelWithoutMarkingItManual() {
        var state = CodexCreateOptionsState.open(options(), "p1", "  创建任务  ")
        assertNull(state.createRequest("create-1"), "opening must read fresh defaults")
        val request = assertNotNull(state.pending)
        assertEquals("p1", request.projectId)
        assertNull(request.model)
        state = state.loaded(request, options())
        assertNull(state.manualModelId)
        val creation = assertNotNull(state.createRequest("create-1"))
        assertEquals("provider/a", creation.model)
        assertEquals("high", creation.effort)
        assertEquals("auto", creation.permission)
        assertEquals("创建任务", creation.prompt)
    }
}
