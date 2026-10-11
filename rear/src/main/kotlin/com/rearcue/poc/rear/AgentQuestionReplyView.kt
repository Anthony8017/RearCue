package com.rearcue.poc.rear

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Shared across page/activity changes; choices persist locally, receipts never fabricate an answer. */
object QuestionReplyFeed {
    private val mutable = MutableStateFlow<Map<String, String>>(emptyMap())
    private val requests = linkedMapOf<String, QuestionReplyRequest>()
    fun lastRequest(sessionId: String, groupId: String) = requests[key(sessionId, groupId)]
    val results = mutable.asStateFlow()
    fun key(sessionId: String, groupId: String) = sessionId + "|" + groupId
    fun receipt(request: QuestionReplyRequest, value: String) {
        mutable.value = mutable.value + (key(request.sessionId, request.groupId) to value)
    }
    fun send(request: QuestionReplyRequest) {
        requests[key(request.sessionId, request.groupId)] = request
        while (requests.size > 128) requests.remove(requests.keys.first())
        receipt(request, if (request.checkOnly) "checking" else "sending")
        RearDashboardHost.emitQuestionReply(request)
    }
    fun reconcile(context: Context, roster: List<AgentSessionState>) {
        val valid = roster.flatMap { session -> session.pendingQuestions.map { QuestionReplyPolicy.draftKey(session.sessionId, it.id) } }.toSet()
        val prefs = context.getSharedPreferences("question-replies", Context.MODE_PRIVATE)
        val stale = prefs.all.keys.filter { it.startsWith("choice:") && it !in valid }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
        val groups = roster.flatMap { session -> session.pendingQuestions.mapNotNull { it.groupId?.let { id -> key(session.sessionId, id) } } }.toSet()
        mutable.value = mutable.value.filter { it.key in groups || it.value in setOf("accepted", "expired", "unknown") }.entries.toList().takeLast(128).associate { it.key to it.value }
    }
}

@Composable
internal fun AgentQuestionReplyPanel(state: AgentSessionState, connected: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("question-replies", Context.MODE_PRIVATE) }
    val results by QuestionReplyFeed.results.collectAsState()
    var currentGroup by remember(state.sessionId) { mutableStateOf(state.pendingQuestions.firstOrNull()?.let { it.groupId ?: "unsupported:" + it.id }) }
    val groupIds = state.pendingQuestions.map { it.groupId ?: "unsupported:" + it.id }.distinct()
    val selectedGroup = currentGroup?.takeIf { it in groupIds } ?: groupIds.firstOrNull() ?: currentGroup
    val questions = state.pendingQuestions.filter { (it.groupId ?: "unsupported:" + it.id) == selectedGroup }
    var index by remember(state.sessionId, selectedGroup) { mutableIntStateOf(0) }
    val question = questions.getOrNull(index.coerceAtMost((questions.size - 1).coerceAtLeast(0)))
    var selections by remember(state.sessionId, selectedGroup, questions.map { it.id }) {
        mutableStateOf(questions.associate { q -> q.id to prefs.getStringSet(QuestionReplyPolicy.draftKey(state.sessionId, q.id), emptySet()).orEmpty().toSet().intersect(q.optionValues.toSet()) })
    }
    val status = selectedGroup?.let { results[QuestionReplyFeed.key(state.sessionId, it)] }
    val busy = status == "sending" || status == "checking"
    val validGroup = questions.isNotEmpty() && questions.all { it.canAnswer && it.optionValues.size == it.options.size }
    val note = when (status) {
        "sending" -> "正在提交…"
        "checking" -> "正在核对状态…"
        "accepted" -> "答案已提交"
        "unknown" -> "提交状态未确认，请先核对；不会自动重发"
        "pending" -> "题目仍待答，可手动提交"
        "expired" -> "题目已解除或过期，请返回正文"
        "unsupported" -> "请在电脑回答"
        "unauthorized" -> "桥凭据无效，请重新连接"
        "bad-request" -> "答案或题目已变化，请核对后重选"
        "offline" -> "电脑未连接，选择已保存"
        "failed" -> "提交失败，选择已保存"
        else -> if (!connected) "电脑未连接，选择已保存" else if (!validGroup) "请在电脑回答" else "每题先保存，最后统一提交"
    }
    LaunchedEffect(status) { if (status == "accepted") onClose() }
    LaunchedEffect(questions) {
        selections = selections.mapValues { (id, chosen) -> questions.find { it.id == id }?.optionValues?.takeIf { it.isNotEmpty() }?.let { chosen.intersect(it.toSet()) } ?: chosen }
    }
    Box(modifier.background(Color.Black)) {
    Column(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("返回正文", color = Color.Gray, fontSize = 12.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxWidth().clickable { onClose() }.padding(4.dp))
        if (groupIds.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上一组", color = Color.Gray, modifier = Modifier.clickable(enabled = !busy) {
                    val pos = groupIds.indexOf(selectedGroup); currentGroup = groupIds[(pos - 1 + groupIds.size) % groupIds.size]
                }.padding(4.dp))
                Text("下一组", color = Color.Gray, modifier = Modifier.clickable(enabled = !busy) {
                    val pos = groupIds.indexOf(selectedGroup); currentGroup = groupIds[(pos + 1) % groupIds.size]
                }.padding(4.dp))
            }
        }
        if (status != null || !connected || !validGroup) Text(note, color = if (status == "unknown") Color(0xFFFFBC66) else Color(0xFF3ECF8E), fontSize = 12.sp)
        if (question == null) {
            Text("当前题目已解除", color = Color.Gray)
            val previous = selectedGroup?.let { QuestionReplyFeed.lastRequest(state.sessionId, it) }
            if (status == "unknown" && connected && previous != null) Text("核对状态", color = Color(0xFF3ECF8E),
                modifier = Modifier.clickable { QuestionReplyFeed.send(previous.copy(checkOnly = true)) }.padding(6.dp), fontSize = 12.sp, lineHeight = 14.sp)
            return@Column
        }
        Text("" + (index.coerceAtMost(questions.lastIndex) + 1) + "/" + questions.size + " · " + question.title, color = Color.White, fontSize = 15.sp, lineHeight = 19.sp)
        question.options.forEachIndexed { choiceIndex, label ->
            val value = question.optionValues.getOrNull(choiceIndex)
            val checked = value in selections[question.id].orEmpty()
            Text((if (checked) "● " else "○ ") + label, color = if (checked) Color(0xFF3ECF8E) else Color.White,
                fontSize = 14.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFF161616)).clickable(enabled = !busy && validGroup && value != null && status != "expired" && status != "unknown") {
                    val chosen = if (question.multiple) selections[question.id].orEmpty().let { if (value in it) it - value!! else it + value!! } else setOf(value!!)
                    selections = selections + (question.id to chosen)
                    prefs.edit().putStringSet(QuestionReplyPolicy.draftKey(state.sessionId, question.id), chosen).apply()
                }.padding(8.dp))
        }
    }
        if (validGroup && question != null) {
            Row(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black).padding(start = 8.dp, end = 18.dp, bottom = 16.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (index > 0) Text("上一题", color = Color.Gray, modifier = Modifier.clickable(enabled = !busy) { index-- }.padding(6.dp))
                if (index < questions.lastIndex) {
                    Text("保存 · 下一题", color = Color(0xFF3ECF8E), modifier = Modifier.clickable(enabled = !busy && selections[question.id].orEmpty().isNotEmpty()) { index++ }.padding(6.dp))
                } else {
                    Text(if (status == "unknown") "核对状态" else "提交全部答案", color = Color(0xFF3ECF8E),
                        modifier = Modifier.clickable(enabled = !busy && connected && status != "expired" && (status == "unknown" || QuestionReplyPolicy.canSubmit(questions, selections))) {
                            val first = questions.first()
                            QuestionReplyFeed.send(QuestionReplyRequest(state.sessionId, first.groupId!!, first.turnId!!,
                                selections.mapValues { it.value.toList() }, UUID.randomUUID().toString(), checkOnly = status == "unknown"))
                        }.padding(6.dp))
                }
            }
        }
    }
}
