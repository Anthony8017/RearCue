package com.rearcue.poc.ui

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rearcue.poc.R
import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionKeys
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.agent.BridgeRelayClient
import com.rearcue.poc.agent.CodexRemoteModel
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemoteProject
import com.rearcue.poc.agent.CodexRemoteRequest
import com.rearcue.poc.agent.CodexRemoteResult
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Remote Codex Conversation（spec 0024）：手机主屏唯一的主动对话面。
 * 发起/停止/删除都要求手机已解锁；provider/model 选项全部来自 PC 桥当前 app-server。
 */
@Composable
fun CodexRemotePanel(
    bridgeClient: BridgeRelayClient,
    bridgeStatus: BridgeLinkStatus,
    roster: List<AgentSessionState>,
    currentSession: AgentSessionState?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("codex_remote", Context.MODE_PRIVATE) }
    var options by remember { mutableStateOf<CodexRemoteOptions?>(null) }
    var optionError by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AgentSessionState?>(null) }
    val connected = bridgeStatus == BridgeLinkStatus.CONNECTED

    LaunchedEffect(bridgeStatus) {
        if (!connected) return@LaunchedEffect
        val loaded = suspendCancellableCoroutine<CodexRemoteOptions?> { continuation ->
            bridgeClient.fetchCodexOptions { continuation.resume(it) }
        }
        options = loaded
        optionError = if (loaded == null) "无法读取电脑上的可用项目/模型" else null
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.codex_remote_heading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                if (context.isDeviceUnlocked()) createOpen = true else note = context.getString(R.string.codex_remote_locked)
            },
            enabled = connected && options != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.codex_remote_new))
        }

        val codexSessions = roster.filter { it.source == AgentSources.CODEX }
        if (codexSessions.isNotEmpty()) {
            CodexFollowUpBlock(
                bridgeClient = bridgeClient,
                sessions = codexSessions,
                initialSession = currentSession?.takeIf { it.source == AgentSources.CODEX },
                connected = connected,
                models = options?.models.orEmpty(),
                prefs = prefs,
                onNote = { note = it },
                onDeleteRequest = { deleteTarget = it },
            )
        }

        optionError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (createOpen && options != null) {
        CodexCreateDialog(
            options = options!!,
            prefs = prefs,
            isDeviceUnlocked = { context.isDeviceUnlocked() },
            onDismiss = { createOpen = false },
            onLocked = { note = context.getString(R.string.codex_remote_locked) },
            onStart = { project, model, prompt ->
                note = context.getString(R.string.codex_remote_sending)
                scope.launch {
                    val result = bridgeClient.startCodexRemote(
                        CodexRemoteRequest(
                            requestId = "rc-${UUID.randomUUID()}",
                            prompt = prompt,
                            projectId = project.id,
                            workspace = project.workspace,
                            model = model?.id,
                        ),
                    )
                    note = when (result) {
                        is CodexRemoteResult.Accepted -> {
                            prefs.edit().remove("create-draft").apply()
                            context.getString(R.string.codex_remote_started)
                        }
                        is CodexRemoteResult.Rejected -> context.getString(R.string.codex_remote_rejected)
                        is CodexRemoteResult.Unknown -> context.getString(R.string.codex_remote_unknown)
                        is CodexRemoteResult.Failed -> result.message
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.codex_remote_delete_title)) },
            text = { Text(stringResource(R.string.codex_remote_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    if (!context.isDeviceUnlocked()) {
                        note = context.getString(R.string.codex_remote_locked)
                        deleteTarget = null
                        return@TextButton
                    }
                    scope.launch {
                        val result = bridgeClient.deleteCodexRemote(target.sessionId, "rc-${UUID.randomUUID()}")
                        note = when (result) {
                            is CodexRemoteResult.Accepted -> context.getString(R.string.codex_remote_deleted)
                            is CodexRemoteResult.Rejected -> context.getString(R.string.codex_remote_delete_forbidden)
                            is CodexRemoteResult.Unknown -> context.getString(R.string.codex_remote_unknown)
                            is CodexRemoteResult.Failed -> result.message
                        }
                        deleteTarget = null
                    }
                }) { Text(stringResource(R.string.confirm_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CodexFollowUpBlock(
    bridgeClient: BridgeRelayClient,
    sessions: List<AgentSessionState>,
    initialSession: AgentSessionState?,
    connected: Boolean,
    models: List<CodexRemoteModel>,
    prefs: android.content.SharedPreferences,
    onNote: (String) -> Unit,
    onDeleteRequest: (AgentSessionState) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(initialSession ?: sessions.first()) }
    var menuOpen by remember { mutableStateOf(false) }
    var model by remember(selected.sessionId) { mutableStateOf<CodexRemoteModel?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var prompt by remember(selected.sessionId) {
        mutableStateOf(prefs.getString("draft:${selected.sessionId}", "") ?: "")
    }
    val display = AgentSessionDisplay.forRoster(sessions)[selected.sessionId]
    val failedEmpty = selected.status == com.rearcue.poc.agent.AgentStatus.ERROR &&
        selected.turns.none { it.role == com.rearcue.poc.agent.AgentTurnRole.AGENT && it.text.isNotBlank() } &&
        selected.latestReply.isNullOrBlank()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { menuOpen = true },
            enabled = sessions.size > 1,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(display?.inline ?: selected.sessionId.takeLast(4))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            sessions.forEach { session ->
                DropdownMenuItem(
                    text = { Text(AgentSessionDisplay.forRoster(sessions)[session.sessionId]?.inline ?: session.sessionId.takeLast(4)) },
                    onClick = {
                        selected = session
                        menuOpen = false
                    },
                )
            }
        }
        OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Text(model?.displayName ?: stringResource(R.string.codex_remote_model_auto))
        }
        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.codex_remote_model_auto)) },
                onClick = {
                    model = null
                    modelMenu = false
                },
            )
            models.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.displayName) },
                    onClick = {
                        model = candidate
                        modelMenu = false
                    },
                )
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = {
                prompt = it
                prefs.edit().putString("draft:${selected.sessionId}", it).apply()
            },
            label = { Text(stringResource(R.string.codex_remote_followup_hint)) },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!context.isDeviceUnlocked()) {
                        onNote(context.getString(R.string.codex_remote_locked))
                        return@Button
                    }
                    val text = prompt.trim()
                    if (text.isEmpty()) return@Button
                    scope.launch {
                        val result = bridgeClient.sendCodexRemote(
                            selected.sessionId,
                            CodexRemoteRequest(requestId = "rc-${UUID.randomUUID()}", prompt = text, model = model?.id),
                        )
                        onNote(
                            when (result) {
                                is CodexRemoteResult.Accepted -> {
                                    prefs.edit().remove("draft:${selected.sessionId}").apply()
                                    prompt = ""
                                    context.getString(R.string.codex_remote_sent)
                                }
                                is CodexRemoteResult.Rejected -> context.getString(R.string.codex_remote_rejected)
                                is CodexRemoteResult.Unknown -> context.getString(R.string.codex_remote_unknown)
                                is CodexRemoteResult.Failed -> result.message
                            },
                        )
                    }
                },
                enabled = connected && prompt.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.send)) }
            OutlinedButton(
                onClick = {
                    if (!context.isDeviceUnlocked()) {
                        onNote(context.getString(R.string.codex_remote_locked))
                        return@OutlinedButton
                    }
                    scope.launch {
                        val result = bridgeClient.stopCodexRemote(selected.sessionId, "rc-${UUID.randomUUID()}")
                        val message: String = when (result) {
                            is CodexRemoteResult.Accepted -> context.getString(R.string.codex_remote_stopped)
                            is CodexRemoteResult.Unknown -> context.getString(R.string.codex_remote_unknown)
                            else -> context.getString(R.string.codex_remote_failed)
                        }
                        onNote(message)
                    }
                },
                enabled = connected,
            ) { Text(stringResource(R.string.codex_remote_stop)) }
            if (failedEmpty) {
                OutlinedButton(onClick = { onDeleteRequest(selected) }, enabled = connected) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

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
    var model by remember { mutableStateOf<CodexRemoteModel?>(null) }
    var prompt by remember { mutableStateOf(prefs.getString("create-draft", "") ?: "") }
    var projectMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.codex_remote_new)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val selectedProject = project
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
                OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(model?.displayName ?: stringResource(R.string.codex_remote_model_auto))
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.codex_remote_model_auto)) },
                        onClick = {
                            model = null
                            modelMenu = false
                        },
                    )
                    options.models.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.displayName) },
                            onClick = {
                                model = candidate
                                modelMenu = false
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = {
                        prompt = it
                        prefs.edit().putString("create-draft", it).apply()
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
                enabled = project != null && prompt.isNotBlank(),
                onClick = {
                    if (!isDeviceUnlocked()) {
                        onLocked()
                        onDismiss()
                        return@TextButton
                    }
                    project?.let {
                        onStart(it, model, prompt.trim())
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun Context.isDeviceUnlocked(): Boolean =
    getSystemService(KeyguardManager::class.java)?.isDeviceLocked != true

private suspend fun BridgeRelayClient.startCodexRemote(request: CodexRemoteRequest): CodexRemoteResult =
    suspendCancellableCoroutine { continuation ->
        startCodexConversation(request) { continuation.resume(it) }
    }

private suspend fun BridgeRelayClient.sendCodexRemote(sessionId: String, request: CodexRemoteRequest): CodexRemoteResult =
    suspendCancellableCoroutine { continuation ->
        sendCodexMessage(sessionId, request) { continuation.resume(it) }
    }

private suspend fun BridgeRelayClient.stopCodexRemote(sessionId: String, requestId: String): CodexRemoteResult =
    suspendCancellableCoroutine { continuation ->
        stopCodexConversation(sessionId, requestId) { continuation.resume(it) }
    }

private suspend fun BridgeRelayClient.deleteCodexRemote(sessionId: String, requestId: String): CodexRemoteResult =
    suspendCancellableCoroutine { continuation ->
        deleteCodexConversation(sessionId, requestId) { continuation.resume(it) }
    }

