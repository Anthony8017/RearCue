package com.rearcue.poc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rearcue.poc.R
import com.rearcue.poc.agent.CodexRemoteEffort
import com.rearcue.poc.agent.CodexRemoteModel
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemotePermission
import com.rearcue.poc.agent.CodexRemoteProject
import com.rearcue.poc.agent.CodexRemoteRequest
import java.util.UUID

@Composable
internal fun CodexCreateDialog(
    options: CodexRemoteOptions,
    prefs: android.content.SharedPreferences,
    isDeviceUnlocked: () -> Boolean,
    onDismiss: () -> Unit,
    onLocked: () -> Unit,
    onOptions: (String?, String?, (CodexRemoteOptions?) -> Unit) -> Unit,
    onStart: (CodexRemoteRequest) -> Unit,
    submitting: Boolean = false,
    refreshToken: Int = 0,
    creationNote: String? = null,
) {
    val rememberedProjectId = prefs.getString("last-project", null)
    val initialPrompt = remember { prefs.getString("create-draft", "") ?: "" }
    var state by remember(options) {
        mutableStateOf(CodexCreateOptionsState.open(options, rememberedProjectId, initialPrompt))
    }
    val showProjectPaths = remember(state.options.projects) {
        val nameCounts = state.options.projects.groupingBy { it.name }.eachCount()
        state.options.projects.filter { it.roots.size > 1 || nameCounts.getValue(it.name) > 1 }.map { it.id }.toSet()
    }
    var projectMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    var confirmRetry by remember { mutableStateOf(false) }

    LaunchedEffect(refreshToken) {
        if (refreshToken > 0) state = state.retry()
    }

    LaunchedEffect(state.pending) {
        val request = state.pending ?: return@LaunchedEffect
        onOptions(request.projectId, request.model) { response ->
            runOnCodexUiThread { state = state.loaded(request, response) }
        }
    }

    fun submit() {
        if (!isDeviceUnlocked()) {
            onLocked()
            onDismiss()
            return
        }
        if (!submitting) state.createRequest("rc-${UUID.randomUUID()}")?.let(onStart)
    }

    val selectedProject = state.project
    val defaultModel = state.options.models.firstOrNull { it.id == state.options.defaults.model }
    val unknownLabel = stringResource(R.string.codex_remote_model_unknown)
    val modelRows = codexModelRows(
        state.manualModelId, defaultModel?.id, defaultModel?.displayName, state.options.models, unknownLabel,
        stringResource(R.string.codex_remote_model_revert_only),
    )
    val effortLabel = when {
        state.effort != null -> state.effort!!.id
        state.effortNeedsReselection -> "需要重新选择"
        state.pending != null -> "读取中…"
        else -> "未选择"
    }
    val permissionLabel = when {
        state.permission != null -> state.permission!!.label
        state.permissionNeedsReselection -> "需要重新选择"
        state.pending != null -> "读取中…"
        else -> "未选择"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.codex_remote_new)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.codex_remote_source), style = MaterialTheme.typography.labelLarge)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { projectMenu = true },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().testTag("codex-create-project"),
                    ) {
                        if (selectedProject == null) Text(stringResource(R.string.codex_remote_no_project))
                        else CodexProjectLabel(selectedProject, selectedProject.id in showProjectPaths)
                    }
                    DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                        state.options.projects.forEach { candidate ->
                            DropdownMenuItem(
                                text = { CodexProjectLabel(candidate, candidate.id in showProjectPaths) },
                                onClick = {
                                    state = state.selectProject(candidate.id)
                                    prefs.edit().putString("last-project", candidate.id).apply()
                                    projectMenu = false
                                },
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { modelMenu = true },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().testTag("codex-create-model"),
                    ) {
                        Text(codexModelLabel(
                            state.manualModelId, defaultModel?.id, defaultModel?.displayName, state.options.models,
                        ) ?: unknownLabel)
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        modelRows.forEach { row ->
                            DropdownMenuItem(
                                text = { Text((if (row.checked) "✓ " else "") + row.label) },
                                onClick = {
                                    state = state.selectModel(if (row.clearsManual) null else row.modelId)
                                    modelMenu = false
                                },
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { effortMenu = true },
                        enabled = state.pending == null && !submitting,
                        modifier = Modifier.fillMaxWidth().testTag("codex-create-effort"),
                    ) { Text("思考强度：$effortLabel") }
                    DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                        state.options.efforts.forEach { effort ->
                            EffortMenuItem(effort, state.effortId == effort.id) {
                                state = state.selectEffort(effort.id)
                                effortMenu = false
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { permissionMenu = true },
                        enabled = state.pending == null && !submitting,
                        modifier = Modifier.fillMaxWidth().testTag("codex-create-permission"),
                    ) { Text("执行权限：$permissionLabel") }
                    DropdownMenu(expanded = permissionMenu, onDismissRequest = { permissionMenu = false }) {
                        state.options.permissions.forEach { permission ->
                            PermissionMenuItem(permission, state.permissionId == permission.id) {
                                state = state.selectPermission(permission.id)
                                permissionMenu = false
                            }
                        }
                    }
                }
                creationNote?.let { Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("codex-create-note"),
                ) }
                if (state.readFailed) Text(
                    "选项读取失败，请点击重试。",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("codex-create-options-error"),
                )
                if (state.options.errors.isNotEmpty()) Text(
                    state.options.errors.joinToString("；") { "${it.field}: ${it.code}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("codex-create-options-detail"),
                )
                if (state.pending != null || state.readFailed || state.options.errors.isNotEmpty()) {
                    TextButton(
                        onClick = { state = state.retry() },
                        modifier = Modifier.testTag("codex-create-options-retry"),
                    ) { Text("重试读取选项") }
                }
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = {
                        state = state.copy(prompt = it)
                        prefs.edit().putString("create-draft", it)
                            .putInt("create-draft-revision", prefs.getInt("create-draft-revision", 0) + 1).apply()
                    },
                    label = { Text(stringResource(R.string.codex_remote_prompt_hint)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("codex-create-send"),
                enabled = state.canCreate && !submitting,
                onClick = {
                    if (shouldConfirmCodexCreation(
                        prefs.getBoolean("create-unknown", false), prefs.getString("create-attempt-prompt", null), state.prompt,
                    )) confirmRetry = true else submit()
                },
            ) { Text(stringResource(R.string.send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    if (confirmRetry) AlertDialog(
        onDismissRequest = { confirmRetry = false }, title = { Text("再次新建？") },
        text = { Text("上次新建状态未知。请先查看电脑会话，重复发送可能创建两次任务。") },
        confirmButton = { TextButton(onClick = { confirmRetry = false; submit() }) { Text("确认发送") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SelectionMenuItem(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text((if (selected) "✓ " else "") + label)
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun EffortMenuItem(effort: CodexRemoteEffort, selected: Boolean, onClick: () -> Unit) =
    SelectionMenuItem(effort.id, effort.description, selected, onClick)

@Composable
private fun PermissionMenuItem(permission: CodexRemotePermission, selected: Boolean, onClick: () -> Unit) =
    SelectionMenuItem(permission.label, permission.description, selected, onClick)

@Composable
private fun CodexProjectLabel(project: CodexRemoteProject, showPaths: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Text(project.name)
        if (showPaths) Text(
            project.roots.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
