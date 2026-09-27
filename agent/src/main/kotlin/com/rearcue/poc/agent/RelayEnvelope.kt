package com.rearcue.poc.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * relay envelope：WS text 帧 = `{type:"data", payload, client_ts?, server_ts?}`（E1 schema 固化）。
 * 控制消息（auth_*、error）不带 payload 直进 `type` 字段。
 *
 * server_ts 是 unix 秒、client_ts 是毫秒（capture 实测）。
 */
object RelayEnvelope {
    val json = Json { ignoreUnknownKeys = true }

    private const val TYPE_DATA = "data"
    private const val TYPE_ERROR = "error"

    fun wrapData(payload: JsonObject, clientTsMs: Long): JsonObject = buildJsonObject {
        put("type", TYPE_DATA)
        put("payload", payload)
        put("client_ts", clientTsMs)
    }

    /** 返回 payload；非 data 帧返回 null。 */
    fun unwrapData(text: String): JsonObject? {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((obj["type"] as? JsonPrimitive)?.content != TYPE_DATA) return null
        return (obj["payload"] as? JsonObject)
    }

    /** 服务端 error 帧（`{type:"error", code, ...}`）——E1 实测错误后服务端直接断 TCP。 */
    fun errorOf(text: String): RelayError? {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((obj["type"] as? JsonPrimitive)?.content != TYPE_ERROR) return null
        val code = (obj["code"] as? JsonPrimitive)?.content ?: return RelayError("UNKNOWN")
        return RelayError(code)
    }

    fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    fun primitiveOrNull(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content
}

data class RelayError(
    val code: String,
) {
    /** 是否值得自动重连：AUTH_FAILED 凭据问题（需新配对）、WRONG_PARAM 协议用法错，都不该盲重试。 */
    val retryable: Boolean
        get() = code !in setOf("AUTH_FAILED", "WRONG_PARAM")
}

/** 会话内一层「连接+认证+帧」之后的东西。 */
sealed interface RelayEvent {
    /** 认证完成（pair_status=matched）。 */
    data class Online(val terminalSid: String?) : RelayEvent

    /** 收到一条重组后的逻辑消息（JSON 文本，`{method,params}` 或 result 形态）。 */
    data class LogicalMessage(val text: String) : RelayEvent

    /** 服务端明确拒绝（error 帧）。 */
    data class ServerError(val error: RelayError) : RelayEvent

    /** WS 关闭（close code 语义见 [RelayCloseCodes]）。 */
    data class Closed(val code: Int, val reason: String) : RelayEvent

    /** 传输故障（断网等）。 */
    data class Failed(val error: Throwable) : RelayEvent
}

/** close code 表（zcode.cjs CXi；E1 文档化，4013 待 phase B 实证）。 */
object RelayCloseCodes {
    const val SESSION_NOT_FOUND = 4004
    const val SESSION_CONFLICT = 4009
    const val DESKTOP_DISCONNECTED = 4010
    const val SESSION_EXPIRED = 4011
    const val WORKSPACE_CLOSED = 4012
    const val INVALID_MOBILE_CONNECTION = 4013

    /** 值得自动重连的 close code；4011 链接过期、4013 被顶掉都需要人介入或退避后再试。 */
    fun retryable(code: Int): Boolean = when (code) {
        SESSION_EXPIRED, SESSION_NOT_FOUND, INVALID_MOBILE_CONNECTION -> false
        else -> true
    }
}
