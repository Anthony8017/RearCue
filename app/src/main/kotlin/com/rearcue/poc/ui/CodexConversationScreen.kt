package com.rearcue.poc.ui

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rearcue.poc.R
import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionKeys
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.AgentTurns
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemoteRequest
import com.rearcue.poc.agent.CodexRemoteResult
import com.rearcue.poc.agent.SessionReadState
import com.rearcue.poc.design.RearCueTheme
import com.rearcue.poc.design.safeAreaPadding
import com.rearcue.poc.rear.AgentProcessPresentation
import java.util.UUID
import kotlinx.coroutines.launch

private data class CodexSubmission(val sessionId: String, val draft: String, val model: String?, val revision: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexConversationScreen(
    client: CodexConversationClient,
    bridgeStatus: BridgeLinkStatus,
    sessions: List<AgentSessionState>,
    initialSessionId: String?,
    preferences: SharedPreferences,
    isDeviceUnlocked: () -> Boolean,
    onBack: () -> Unit,
    foreground: Boolean = true,
    explicitSessionId: String? = null,
    explicitSessionRevision: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val codexSessions = sessions.filter { it.source == AgentSources.CODEX }
    val connected = bridgeStatus == BridgeLinkStatus.CONNECTED
    var explicitHandled by remember(explicitSessionId, explicitSessionRevision) { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf(explicitSessionId ?: preferences.getString("last-session", null) ?: initialSessionId) }
    var createdId by rememberSaveable { mutableStateOf<String?>(null) }
    var completedCreateThread by remember { mutableStateOf(preferences.getString("create-thread-id", null)) }
    var allowCreateRecovery by remember(explicitSessionId, explicitSessionRevision) { mutableStateOf(explicitSessionId == null) }
    var options by remember { mutableStateOf<CodexRemoteOptions?>(null) }
    var optionError by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var choosingSession by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<CodexSubmission?>(null) }
    var globalNote by remember { mutableStateOf(
        if (preferences.getBoolean("create-unknown", false)) context.getString(R.string.codex_remote_unknown)
        else preferences.getString("create-receipt", null),
    ) }
    var historyRevision by remember { mutableStateOf(0) }
    val drafts = remember { mutableStateMapOf<String, String>() }
    val models = remember { mutableStateMapOf<String, String?>() }
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    val notes = remember { mutableStateMapOf<String, String>() }
    val unknown = remember { mutableStateMapOf<String, Boolean>() }
    val desktopManaged = remember { mutableStateMapOf<String, Boolean>() }
    val histories = remember { mutableStateMapOf<String, List<AgentTurn>>() }
    val historyErrors = remember { mutableStateMapOf<String, Boolean>() }
    val processChoices = remember { mutableStateMapOf<String, Boolean>() }

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key != null) {
                val id = key.substringAfter(':')
                when {
                    key == "create-receipt" -> globalNote = prefs.getString(key, null)
                    key == "create-thread-id" -> completedCreateThread = prefs.getString(key, null)
                    key.startsWith("draft:") -> drafts[id] = prefs.getString(key, "").orEmpty()
                    key.startsWith("unknown:") -> unknown[id] = prefs.getBoolean(key, false)
                    key.startsWith("receipt:") -> prefs.getString(key, null)?.let { notes[id] = it }
                    key.startsWith("desktop-managed:") -> desktopManaged[id] = prefs.getBoolean(key, false)
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(codexSessions.map { it.sessionId }) {
        if (codexSessions.any { it.sessionId == createdId }) createdId = null
        if (codexSessions.none { it.sessionId == selectedId } && (createdId == null || selectedId != createdId)) {
            selectedId = preferredCodexSessionId(
                preferences.getString("last-session", null), initialSessionId, codexSessions.map { it.sessionId },
            )
        }
    }
    LaunchedEffect(completedCreateThread, allowCreateRecovery) {
        completedCreateThread?.let { threadId ->
            if (allowCreateRecovery) {
                selectedId = AgentSessionKeys.bridge(AgentSources.CODEX, threadId)
                createdId = selectedId
            }
            preferences.edit().remove("create-thread-id").apply()
        }
    }
    LaunchedEffect(explicitSessionId, explicitSessionRevision, codexSessions.map { it.sessionId }) {
        if (!explicitHandled) explicitSessionId?.takeIf { id -> codexSessions.any { it.sessionId == id } }?.let {
            selectedId = it
            explicitHandled = true
        }
    }
    LaunchedEffect(selectedId) {
        selectedId?.let { id ->
            if (id !in drafts) drafts[id] = preferences.getString("draft:$id", "").orEmpty()
            unknown[id] = preferences.getBoolean("unknown:$id", false)
            desktopManaged[id] = preferences.getBoolean("desktop-managed:$id", false)
            if (unknown[id] == true && pending[id] != true) notes[id] = context.getString(R.string.codex_remote_unknown)
            else preferences.getString("receipt:$id", null)?.let { notes[id] = it }
            preferences.edit().putString("last-session", id).apply()
        }
    }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        options = awaitCodexResult(client::options)
        optionError = options == null
    }
    val session = codexSessions.firstOrNull { it.sessionId == selectedId }
        ?: createdId?.takeIf { it == selectedId }?.let {
            AgentSessionState(it, source = AgentSources.CODEX, status = AgentStatus.WORKING)
        }
    val draft = drafts[selectedId].orEmpty()
    val busy = session?.status == AgentStatus.WORKING || session?.status == AgentStatus.WAITING_FOR_APPROVAL
    val sending = pending[selectedId] == true
    val display = AgentSessionDisplay.forRoster(codexSessions)[selectedId]
    val turns = AgentTurns.withHistoryPrefix(histories[selectedId].orEmpty(), session?.turns.orEmpty())
    val process = AgentProcessPresentation.project(
        selectedId.orEmpty(), turns, session?.status ?: AgentStatus.IDLE,
        processChoices.toMap(), session?.pendingQuestions?.isNotEmpty() == true,
    )
    LaunchedEffect(selectedId, connected, session?.status, session?.turns?.firstOrNull()?.entryId, historyRevision) {
        val id = selectedId ?: return@LaunchedEffect
        if (!connected || session == null) return@LaunchedEffect
        val history = awaitCodexResult<List<AgentTurn>?> { client.history(id, it) }
        historyErrors[id] = history == null
        if (history != null) histories[id] = history
    }

    fun saveDraft(id: String, text: String) {
        drafts[id] = text
        preferences.edit().putString("draft:$id", text)
            .putInt("draft-revision:$id", preferences.getInt("draft-revision:$id", 0) + 1).apply()
    }
    fun resultNote(result: CodexRemoteResult, accepted: Int): String = when (result) {
        is CodexRemoteResult.Accepted -> context.getString(accepted)
        is CodexRemoteResult.Unknown -> context.getString(R.string.codex_remote_unknown)
        is CodexRemoteResult.Failed -> result.message
        is CodexRemoteResult.Rejected -> when (result.reason ?: result.receipt) {
            "model-unavailable" -> "所选模型已不可用，请重新选择模型"
            "unknown-session" -> "这个会话已不在电脑列表中，请重新选择"
            "busy", "desktop-occupied" -> "电脑正在使用这个会话，暂时无法从手机发送"
            "no-managed-turn" -> "当前没有可从手机停止的回合；电脑端回合请在电脑上停止"
            else -> context.getString(R.string.codex_remote_rejected)
        }
    }
    fun send(submission: CodexSubmission) {
        val current = codexSessions.firstOrNull { it.sessionId == submission.sessionId } ?: return
        if (!connected || pending[submission.sessionId] == true ||
            current.status == AgentStatus.WORKING || current.status == AgentStatus.WAITING_FOR_APPROVAL
        ) return
        if (!isDeviceUnlocked()) {
            notes[submission.sessionId] = context.getString(R.string.codex_remote_locked)
            return
        }
        pending[submission.sessionId] = true
        globalNote = null
        notes[submission.sessionId] = context.getString(R.string.codex_remote_sending)
        preferences.edit().putBoolean("unknown:${submission.sessionId}", true).apply()
        scope.launch {
            try {
                val result = awaitCodexResult<CodexRemoteResult> {
                    client.send(submission.sessionId, CodexRemoteRequest(
                        requestId = "rc-${UUID.randomUUID()}", prompt = submission.draft.trim(), model = submission.model,
                    )) { receipt -> runOnCodexUiThread {
                        val id = submission.sessionId
                        val edit = preferences.edit()
                            .putBoolean("unknown:$id", receipt is CodexRemoteResult.Unknown)
                            .putString("receipt:$id", resultNote(receipt, R.string.codex_remote_sent))
                        if (receipt is CodexRemoteResult.Accepted) {
                            edit.putBoolean("desktop-managed:$id", receipt.managedBy == "desktop")
                            if (preferences.getInt("draft-revision:$id", 0) == submission.revision &&
                                preferences.getString("draft:$id", "") == submission.draft
                            ) edit.putString("draft:$id", "").putInt("draft-revision:$id", submission.revision + 1)
                        }
                        // 页面退出后仍保存回执；下一次进入不会把已发出的旧稿当未发送。
                        edit.apply()
                        it(receipt)
                    } }
                }
                notes[submission.sessionId] = resultNote(result, R.string.codex_remote_sent)
                unknown[submission.sessionId] = result is CodexRemoteResult.Unknown
                if (result is CodexRemoteResult.Accepted) desktopManaged[submission.sessionId] = result.managedBy == "desktop"
            } finally { pending.remove(submission.sessionId) }
        }
    }

    val list = rememberLazyListState()
    var followLatest by remember(selectedId) { mutableStateOf(true) }
    val atLatest by remember { derivedStateOf {
        list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1
    } }
    LaunchedEffect(list, selectedId) {
        snapshotFlow { list.isScrollInProgress to atLatest }.collect { (scrolling, atEnd) ->
            if (scrolling) followLatest = atEnd
        }
    }
    LaunchedEffect(selectedId, process.turns.size, process.turns.lastOrNull()?.text, process.turns.lastOrNull()?.detail) {
        if (followLatest && process.turns.isNotEmpty()) list.scrollToItem(process.turns.lastIndex)
    }
    LaunchedEffect(selectedId, foreground, session?.readState, atLatest) {
        if (foreground && atLatest && session?.readState == SessionReadState.UNREAD) {
            selectedId?.let(client::markRead)
        }
    }

    RearCueTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeAreaPadding().imePadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.settings_back_cd))
                    }
                    TextButton(
                        onClick = { choosingSession = true }, enabled = codexSessions.isNotEmpty(),
                        modifier = Modifier.weight(1f).testTag("codex-session-selector"),
                    ) {
                        Text(display?.title ?: stringResource(R.string.codex_conversation_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { createOpen = true }, enabled = connected && !creating && options?.projects?.isNotEmpty() == true) {
                        Text(stringResource(R.string.codex_conversation_new))
                    }
                }
                if (!connected) Text(
                    stringResource(R.string.codex_conversation_offline), Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                if (optionError && connected) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("无法读取可用项目/模型", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch { options = awaitCodexResult(client::options); optionError = options == null } }) { Text("重试") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (process.turns.isEmpty()) Text(
                        if (session == null) stringResource(R.string.codex_conversation_empty) else "等待会话内容…",
                        Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("codex-history"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(process.turns, key = { index, turn -> "${turn.entryId ?: turn.ts}:$index" }) { _, turn ->
                            val group = process.headers[turn.entryId]
                            if (group != null) {
                                TextButton(onClick = { processChoices[group.choiceKey] = !group.expanded }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(turn.text) }
                            } else CodexTurn(turn)
                        }
                    }
                    if (!followLatest && process.turns.isNotEmpty()) Button(
                        onClick = { followLatest = true; scope.launch { list.animateScrollToItem(process.turns.lastIndex) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) { Text("回到最新") }
                }
                if (historyErrors[selectedId] == true) TextButton(onClick = { historyRevision++ }) { Text("历史读取失败，点击重试") }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (globalNote ?: notes[selectedId])?.let { Text(it, Modifier.testTag("codex-note"), style = MaterialTheme.typography.bodySmall) }
                    if (busy && !sending) Text(
                        if (session?.status == AgentStatus.WAITING_FOR_APPROVAL) "等待确认；可写草稿，处理后继续发送" else "正在回答；可写草稿，结束后继续发送",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TextButton(onClick = { choosingModel = true }, modifier = Modifier.testTag("codex-model-selector")) {
                                Text(options?.models?.firstOrNull { it.id == models[selectedId] }?.displayName ?: stringResource(R.string.codex_remote_model_auto))
                            }
                            DropdownMenu(expanded = choosingModel, onDismissRequest = { choosingModel = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.codex_remote_model_auto)) }, onClick = { selectedId?.let { models[it] = null }; choosingModel = false })
                                options?.models.orEmpty().forEach { model ->
                                    DropdownMenuItem(text = { Text(model.displayName) }, onClick = { selectedId?.let { models[it] = model.id }; choosingModel = false })
                                }
                            }
                        }
                        if (session?.status == AgentStatus.ERROR && turns.none {
                            it.role == AgentTurnRole.AGENT && it.kind == AgentTurnKind.ANSWER && it.text.isNotBlank()
                        } && session.latestReply.isNullOrBlank()) {
                            TextButton(onClick = { deleteTarget = selectedId }, enabled = connected && !sending) { Text(stringResource(R.string.delete)) }
                        }
                        if (busy && desktopManaged[selectedId] == true) Text("请在电脑端停止", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            val id = selectedId ?: return@OutlinedButton
                            globalNote = null
                            if (!isDeviceUnlocked()) { notes[id] = context.getString(R.string.codex_remote_locked); return@OutlinedButton }
                            scope.launch {
                                val result = awaitCodexResult<CodexRemoteResult> { client.stop(id, "rc-${UUID.randomUUID()}", it) }
                                notes[id] = resultNote(result, R.string.codex_remote_stopped)
                                if (result is CodexRemoteResult.Rejected && result.reason == "no-managed-turn") desktopManaged[id] = true
                            }
                        }, enabled = connected && busy && !sending && desktopManaged[selectedId] != true) { Text(stringResource(R.string.codex_remote_stop)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = draft, onValueChange = { selectedId?.let { id -> saveDraft(id, it) } },
                            label = { Text(stringResource(R.string.codex_remote_followup_hint)) }, minLines = 1, maxLines = 5,
                            modifier = Modifier.weight(1f).testTag("codex-input"), enabled = session != null,
                        )
                        Button(onClick = {
                            val id = selectedId ?: return@Button
                            val submission = CodexSubmission(id, draft, models[id], preferences.getInt("draft-revision:$id", 0))
                            if (unknown[id] == true) retry = submission else send(submission)
                        }, enabled = connected && session != null && !busy && !sending && draft.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("codex-send")) {
                            Text(stringResource(if (sending) R.string.codex_remote_sending else R.string.send))
                        }
                    }
                }
            }
        }
        if (choosingSession) ModalBottomSheet(onDismissRequest = { choosingSession = false }) {
            Text("选择 Codex 会话", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                itemsIndexed(codexSessions) { _, candidate ->
                    val name = AgentSessionDisplay.forRoster(codexSessions)[candidate.sessionId]
                    Column(Modifier.fillMaxWidth().clickable {
                        selectedId = candidate.sessionId; explicitHandled = true; allowCreateRecovery = false
                        globalNote = null; choosingSession = false
                    }.padding(16.dp).testTag("codex-session-${candidate.sessionId}")) {
                        Text(name?.title ?: AgentSessionDisplay.forState(candidate).title)
                        name?.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if (createOpen && options != null) CodexCreateDialog(
            options = options!!, prefs = preferences, isDeviceUnlocked = isDeviceUnlocked,
            onDismiss = { createOpen = false }, onLocked = { globalNote = context.getString(R.string.codex_remote_locked) },
            onStart = { project, model, prompt ->
                if (!creating && connected) {
                    allowCreateRecovery = true
                    val requestId = "rc-${UUID.randomUUID()}"
                    val submittedDraft = preferences.getString("create-draft", "").orEmpty()
                    val submittedRevision = preferences.getInt("create-draft-revision", 0)
                    preferences.edit().putString("create-request-id", requestId)
                        .putString("create-attempt-prompt", prompt).putBoolean("create-unknown", true)
                        .putString("create-receipt", context.getString(R.string.codex_remote_sending)).apply()
                    explicitHandled = true
                    creating = true; globalNote = context.getString(R.string.codex_remote_sending)
                    scope.launch {
                        try {
                            val result = awaitCodexResult<CodexRemoteResult> { resume -> client.create(CodexRemoteRequest(
                                requestId = requestId, prompt = prompt, projectId = project.id, workspace = project.workspace, model = model?.id,
                            )) { receipt -> runOnCodexUiThread {
                                if (preferences.getString("create-request-id", null) == requestId) {
                                    val edit = preferences.edit().putBoolean("create-unknown", receipt is CodexRemoteResult.Unknown)
                                        .putString("create-receipt", resultNote(receipt, R.string.codex_remote_started))
                                    if (receipt is CodexRemoteResult.Accepted && receipt.threadId != null) {
                                        receipt.threadId?.let { edit.putString("create-thread-id", it) }
                                        if (canClearCodexCreationDraft(submittedDraft, submittedRevision,
                                            preferences.getString("create-draft", null), preferences.getInt("create-draft-revision", 0))) {
                                            edit.remove("create-draft").putInt("create-draft-revision", submittedRevision + 1)
                                        }
                                    }
                                    edit.apply()
                                }
                                resume(receipt)
                            } } }
                            globalNote = resultNote(result, R.string.codex_remote_started)
                        } finally { creating = false }
                    }
                }
            },
        )
        retry?.let { submission -> AlertDialog(
            onDismissRequest = { retry = null }, title = { Text("再次发送？") },
            text = { Text("上次发送状态未知。请先查看会话内容，重复发送可能产生两次任务。") },
            confirmButton = { TextButton(onClick = { retry = null; send(submission) }) { Text("确认发送") } },
            dismissButton = { TextButton(onClick = { retry = null }) { Text(stringResource(R.string.cancel)) } },
        ) }
        deleteTarget?.let { id -> AlertDialog(
            onDismissRequest = { deleteTarget = null }, title = { Text(stringResource(R.string.codex_remote_delete_title)) },
            text = { Text(stringResource(R.string.codex_remote_delete_body)) },
            confirmButton = { TextButton(onClick = {
                deleteTarget = null
                if (!isDeviceUnlocked()) { notes[id] = context.getString(R.string.codex_remote_locked) }
                else scope.launch {
                    val result = awaitCodexResult<CodexRemoteResult> { client.delete(id, "rc-${UUID.randomUUID()}", it) }
                    notes[id] = resultNote(result, R.string.codex_remote_deleted)
                }
            }) { Text(stringResource(R.string.confirm_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        ) }
    }
}

@Composable
private fun CodexTurn(turn: AgentTurn) {
    val user = turn.role == AgentTurnRole.USER
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = if (user) Modifier.fillMaxWidth(0.85f) else Modifier.fillMaxWidth(),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            shape = MaterialTheme.shapes.medium,
        ) {
            SelectionContainer {
                Column(Modifier.padding(if (user) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(turn.text, style = MaterialTheme.typography.bodyLarge,
                        color = if (turn.kind == AgentTurnKind.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    turn.detail?.takeIf { it.isNotBlank() && it != turn.text }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (turn.kind == AgentTurnKind.TOOL || turn.kind == AgentTurnKind.TOOL_RESULT) FontFamily.Monospace else null)
                    }
                }
            }
        }
    }
}
