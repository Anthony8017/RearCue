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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rearcue.poc.R
import com.rearcue.poc.agent.CodexRemoteModel
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemoteProject

@Composable
internal fun CodexCreateDialog(
    options: CodexRemoteOptions,
    prefs: android.content.SharedPreferences,
    isDeviceUnlocked: () -> Boolean,
    onDismiss: () -> Unit,
    onLocked: () -> Unit,
    onStart: (CodexRemoteProject, CodexRemoteModel?, String) -> Unit,
) {
    val rememberedProjectId = prefs.getString("last-project", null)
    var project by remember {
        mutableStateOf(options.projects.firstOrNull { it.id == rememberedProjectId } ?: options.projects.firstOrNull())
    }
    var manualId by remember { mutableStateOf<String?>(null) }
    val defaultModel = options.models.firstOrNull { it.isDefault }
    var prompt by remember { mutableStateOf(prefs.getString("create-draft", "") ?: "") }
    var projectMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var confirmRetry by remember { mutableStateOf(false) }

    fun submit() {
        if (!isDeviceUnlocked()) {
            onLocked()
            onDismiss()
            return
        }
        project?.let {
            onStart(it, options.models.firstOrNull { candidate -> candidate.id == manualId }, prompt.trim())
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.codex_remote_new)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val selectedProject = project
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { projectMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedProject?.name ?: stringResource(R.string.codex_remote_no_project))
                    }
                    DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                        options.projects.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.name) },
                                onClick = {
                                    project = candidate
                                    prefs.edit().putString("last-project", candidate.id).apply()
                                    projectMenu = false
                                },
                            )
                        }
                    }
                }
                val unknownLabel = stringResource(R.string.codex_remote_model_unknown)
                val modelRows = codexModelRows(
                    manualId, defaultModel?.id, defaultModel?.displayName, options.models, unknownLabel,
                    stringResource(R.string.codex_remote_model_revert_only),
                )
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(codexModelLabel(manualId, defaultModel?.id, defaultModel?.displayName, options.models) ?: unknownLabel)
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        modelRows.forEach { row ->
                            DropdownMenuItem(
                                text = { Text((if (row.checked) "\u2713 " else "") + row.label) },
                                onClick = {
                                    manualId = if (row.clearsManual) null else row.modelId
                                    modelMenu = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = {
                        prompt = it
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
                enabled = project != null && prompt.isNotBlank(),
                onClick = {
                    if (shouldConfirmCodexCreation(
                        prefs.getBoolean("create-unknown", false), prefs.getString("create-attempt-prompt", null), prompt,
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
