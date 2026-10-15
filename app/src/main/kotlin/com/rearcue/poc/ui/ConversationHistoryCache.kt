package com.rearcue.poc.ui

import android.content.SharedPreferences
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class CachedHistoryPage(
    val turns: List<AgentTurn>,
    val nextCursor: String?,
)

/** 有限的、只保存用户实际取到的历史页的本地缓存。 */
internal class ConversationHistoryCache(
    private val preferences: SharedPreferences,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(sessionId: String): CachedHistoryPage? {
        val raw = preferences.getString(pageKey(sessionId), null) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val turns = (root["turns"] as? JsonArray).orEmpty().mapNotNull(::decodeTurn)
            CachedHistoryPage(turns, root.string("nextCursor"))
        }.getOrElse {
            preferences.edit().remove(pageKey(sessionId)).remove(lastKey(sessionId)).apply()
            null
        }
    }

    fun write(sessionId: String, turns: List<AgentTurn>, nextCursor: String?) {
        val bounded = turns.takeLast(MAX_TURNS)
        val body = buildJsonObject {
            put("turns", JsonArray(bounded.map(::encodeTurn)))
            if (nextCursor != null) put("nextCursor", nextCursor)
        }.toString()
        preferences.edit()
            .putString(pageKey(sessionId), body)
            .putLong(lastKey(sessionId), nowMs())
            .apply()
        evictIfNeeded()
    }

    private fun evictIfNeeded() {
        val pages = preferences.all.keys.filter { it.startsWith(PAGE_PREFIX) }
        if (pages.size <= MAX_SESSIONS) return
        val removeCount = pages.size - MAX_SESSIONS
        val editor = preferences.edit()
        pages.sortedBy { preferences.getLong(lastKey(it.removePrefix(PAGE_PREFIX)), 0L) }
            .take(removeCount)
            .forEach { sessionIdKey ->
                val sessionId = sessionIdKey.removePrefix(PAGE_PREFIX)
                editor.remove(sessionIdKey).remove(lastKey(sessionId))
            }
        editor.apply()
    }

    private fun encodeTurn(turn: AgentTurn): JsonObject = buildJsonObject {
        put("role", turn.role.name)
        put("text", turn.text)
        put("ts", turn.ts)
        put("open", turn.open)
        put("kind", turn.kind.wire())
        turn.detail?.let { put("detail", it) }
        turn.entryId?.let { put("entryId", it) }
        turn.toolName?.let { put("toolName", it) }
        turn.command?.let { put("command", it) }
        turn.path?.let { put("path", it) }
        turn.roundId?.let { put("roundId", it) }
        put("roundComplete", turn.roundComplete)
        put("resolved", turn.resolved)
    }

    private fun decodeTurn(element: kotlinx.serialization.json.JsonElement): AgentTurn? {
        val objectValue = element as? JsonObject ?: return null
        val role = when (objectValue.string("role")) {
            "USER" -> AgentTurnRole.USER
            "AGENT" -> AgentTurnRole.AGENT
            else -> return null
        }
        val text = objectValue.string("text") ?: return null
        return AgentTurn(
            role = role,
            text = text,
            ts = objectValue.long("ts") ?: 0L,
            open = objectValue.boolean("open"),
            kind = AgentTurnKind.fromWire(objectValue.string("kind"))
                ?: if (role == AgentTurnRole.USER) AgentTurnKind.PROMPT else AgentTurnKind.ANSWER,
            detail = objectValue.string("detail"),
            entryId = objectValue.string("entryId"),
            toolName = objectValue.string("toolName"),
            command = objectValue.string("command"),
            path = objectValue.string("path"),
            roundId = objectValue.string("roundId"),
            roundComplete = objectValue.boolean("roundComplete"),
            resolved = objectValue.boolean("resolved"),
        )
    }

    private fun pageKey(sessionId: String): String = PAGE_PREFIX + sessionId
    private fun lastKey(sessionId: String): String = LAST_PREFIX + sessionId

    private fun JsonObject.string(key: String): String? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull ?: false

    private companion object {
        const val PAGE_PREFIX = "codex-history-cache-v1:"
        const val LAST_PREFIX = "codex-history-cache-last-v1:"
        const val MAX_SESSIONS = 8
        const val MAX_TURNS = 240
    }
}
