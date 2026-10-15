package com.rearcue.poc.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** Remote Codex Conversation 的项目选择（spec 0024）：只暴露电脑端真实可用项目。 */
data class CodexRemoteProject(
    val id: String,
    val name: String,
    val workspace: String,
    val roots: List<String> = listOf(workspace),
    val duplicateIds: Set<String> = emptySet(),
)

/** 当前 provider 的可用模型。名单为空只说明不能手选，不代表这条对话没有当前模型。 */
data class CodexRemoteModel(
    val id: String,
    val displayName: String,
    val isDefault: Boolean,
)

data class CodexRemoteOptions(
    val projects: List<CodexRemoteProject>,
    val models: List<CodexRemoteModel>,
) {
    fun projectForId(id: String?): CodexRemoteProject? =
        projects.firstOrNull { it.id == id || id in it.duplicateIds }
}

/** 一次发起/追问请求；model=null 表示跟着当前对话，不手选。requestId 用于未知态对账。 */
data class CodexRemoteRequest(
    val requestId: String,
    val prompt: String,
    val projectId: String? = null,
    val workspace: String? = null,
    val model: String? = null,
) {
    fun toCreateJson(): String = buildString {
        append("{")
        append("\"requestId\":").append(CodexRemoteCodec.quote(requestId)).append(',')
        append("\"prompt\":").append(CodexRemoteCodec.quote(prompt))
        if (projectId != null) append(",\"projectId\":").append(CodexRemoteCodec.quote(projectId))
        if (workspace != null) append(",\"workspace\":").append(CodexRemoteCodec.quote(workspace))
        if (model != null) append(",\"model\":").append(CodexRemoteCodec.quote(model))
        append("}")
    }

    fun toMessageJson(): String = buildString {
        append("{")
        append("\"requestId\":").append(CodexRemoteCodec.quote(requestId)).append(',')
        append("\"prompt\":").append(CodexRemoteCodec.quote(prompt))
        if (model != null) append(",\"model\":").append(CodexRemoteCodec.quote(model))
        append("}")
    }
}

sealed interface CodexRemoteResult {
    data class Accepted(val threadId: String? = null, val turnId: String? = null, val managedBy: String? = null) : CodexRemoteResult
    data class Rejected(val receipt: String, val reason: String? = null) : CodexRemoteResult
    data class Unknown(val message: String) : CodexRemoteResult
    data class Failed(val message: String) : CodexRemoteResult
}

object CodexRemoteCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun quote(value: String): String = json.encodeToString(kotlinx.serialization.serializer<String>(), value)

    fun parseOptions(body: String): CodexRemoteOptions {
        val root = json.parseToJsonElement(body).jsonObject
        val projects = root["projects"]?.jsonArray.orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val roots = obj["roots"]?.jsonArray.orEmpty()
            val workspace = (roots.firstOrNull() as? JsonObject)?.str("path") ?: return@mapNotNull null
            val paths = roots.mapNotNull { (it as? JsonObject)?.str("path") }.distinctBy(::projectPathKey)
            CodexRemoteProject(id = id, name = obj.str("name") ?: id, workspace = workspace, roots = paths)
        }
        val models = root["models"]?.jsonArray.orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            CodexRemoteModel(
                id = id,
                displayName = obj.str("displayName") ?: id,
                isDefault = obj["isDefault"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        return CodexRemoteOptions(projects = mergeProjectDirectories(projects), models = models)
    }

    private fun mergeProjectDirectories(projects: List<CodexRemoteProject>): List<CodexRemoteProject> {
        val byDirectories = linkedMapOf<Pair<String, Set<String>>, CodexRemoteProject>()
        for (project in projects) {
            // 主目录决定新建位置；只比较主目录会误合并绑定多个目录的项目。
            val key = projectPathKey(project.workspace) to project.roots.map(::projectPathKey).toSet()
            val existing = byDirectories[key]
            // project/list 按最近使用排序，保留首项真实 ID，旧 ID 仍能匹配最近选择。
            byDirectories[key] = existing?.copy(duplicateIds = existing.duplicateIds + project.id) ?: project
        }
        return byDirectories.values.toList()
    }

    private fun projectPathKey(path: String): String {
        val windows = Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path) ||
            path.startsWith("\\\\") || path.startsWith("//")
        val normalized = if (windows) path.replace('\\', '/').lowercase(Locale.ROOT) else path
        return normalized.trimEnd('/').ifEmpty { "/" }
    }

    fun parseResult(body: String, httpSuccess: Boolean): CodexRemoteResult {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return if (httpSuccess) CodexRemoteResult.Failed("invalid bridge response") else CodexRemoteResult.Unknown("invalid bridge response")
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        val receipt = root.str("receipt")
        val threadId = root.str("threadId")
        val turnId = root.str("turnId")
        val reason = root.str("reason")
        val message = root.str("error")
        return when {
            ok && receipt == "accepted" -> CodexRemoteResult.Accepted(threadId, turnId, root.str("managedBy"))
            message == "unauthorized" -> CodexRemoteResult.Failed(
                "桥访问凭据缺失或已失效，请重新复制电脑托盘中的完整桥地址",
            )
            receipt == "failed" -> CodexRemoteResult.Failed(
                if (message?.contains("active writer", ignoreCase = true) == true) {
                    "电脑仍占用这个会话，暂时无法从手机发送"
                } else message ?: "远程操作失败",
            )
            receipt == "unknown" -> CodexRemoteResult.Unknown(message ?: "send status unknown")
            receipt in setOf("bad-request", "forbidden", "unknown-session", "busy", "unsupported") -> CodexRemoteResult.Rejected(receipt ?: "bad-request", reason)
            !httpSuccess -> CodexRemoteResult.Unknown(message ?: "bridge http failure")
            else -> CodexRemoteResult.Failed(message ?: receipt ?: "remote Codex request failed")
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
}

