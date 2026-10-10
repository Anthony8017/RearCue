package com.rearcue.poc.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

data class QuestionReplyRequest(
    val sessionId: String,
    val groupId: String,
    val turnId: String,
    val answers: Map<String, List<String>>,
    val requestId: String,
    val checkOnly: Boolean = false,
) {
    fun toJson(rawSessionId: String = sessionId): String {
        val q = CodexRemoteCodec::quote
        val map = answers.entries.joinToString(",") { (id, values) -> q(id) + ":" + values.joinToString(",", "[", "]", transform = q) }
        return "{\"sessionId\":" + q(rawSessionId) + ",\"groupId\":" + q(groupId) +
            ",\"turnId\":" + q(turnId) + ",\"requestId\":" + q(requestId) + ",\"answers\":{" + map + "}}"
    }
}

object QuestionReplyPolicy {
    fun canSubmit(questions: List<AgentUserQuestion>, selections: Map<String, Set<String>>): Boolean =
        questions.isNotEmpty() && questions.all { question ->
            val chosen = selections[question.id].orEmpty()
            question.canAnswer && !question.groupId.isNullOrBlank() && !question.turnId.isNullOrBlank() &&
                question.optionValues.size == question.options.size && chosen.isNotEmpty() &&
                (question.multiple || chosen.size == 1) && question.optionValues.containsAll(chosen)
        } && questions.map { it.groupId }.distinct().size == 1 && questions.map { it.turnId }.distinct().size == 1

    fun draftKey(sessionId: String, questionId: String): String = "choice:" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString((sessionId + "\u0000" + questionId).toByteArray(Charsets.UTF_8))

    fun receipt(body: String): String = runCatching {
        Json.parseToJsonElement(body).jsonObject["receipt"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it in setOf("accepted", "pending", "expired", "unsupported", "unknown", "failed", "bad-request", "unauthorized") } ?: "unknown"
}
