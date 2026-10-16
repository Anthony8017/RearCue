package com.rearcue.poc.ui

import android.os.Handler
import android.os.Looper
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.BridgeRelayClient
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemoteRequest
import com.rearcue.poc.agent.CodexRemoteResult
import com.rearcue.poc.agent.BridgeEventCodec
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** 同一对话页的请求面；测试在真实点击与异步回执之间替换传输。 */
internal interface CodexConversationClient {
    fun options(callback: (CodexRemoteOptions?) -> Unit)
    fun options(projectId: String?, model: String?, callback: (CodexRemoteOptions?) -> Unit) = options(callback)
    fun history(sessionId: String, callback: (List<AgentTurn>?) -> Unit)
    fun historyPage(sessionId: String, cursor: String?, callback: (BridgeEventCodec.HistoryPage?) -> Unit) {
        history(sessionId) { turns ->
            callback(turns?.let { BridgeEventCodec.HistoryPage(sessionId = sessionId, turns = it) })
        }
    }
    fun create(request: CodexRemoteRequest, callback: (CodexRemoteResult) -> Unit)
    fun send(sessionId: String, request: CodexRemoteRequest, callback: (CodexRemoteResult) -> Unit)
    fun stop(sessionId: String, requestId: String, callback: (CodexRemoteResult) -> Unit)
    fun delete(sessionId: String, requestId: String, callback: (CodexRemoteResult) -> Unit)
    fun markRead(sessionId: String)
}

internal class BridgeCodexConversationClient(private val bridge: BridgeRelayClient) : CodexConversationClient {
    override fun options(callback: (CodexRemoteOptions?) -> Unit) = bridge.fetchCodexOptions(callback)
    override fun options(projectId: String?, model: String?, callback: (CodexRemoteOptions?) -> Unit) =
        bridge.fetchCodexOptions(projectId, model, callback)
    override fun history(sessionId: String, callback: (List<AgentTurn>?) -> Unit) = bridge.fetchHistory(sessionId, callback)
    override fun historyPage(sessionId: String, cursor: String?, callback: (BridgeEventCodec.HistoryPage?) -> Unit) =
        bridge.fetchHistoryPage(sessionId, cursor, callback)
    override fun create(request: CodexRemoteRequest, callback: (CodexRemoteResult) -> Unit) =
        bridge.startCodexConversation(request, callback)
    override fun send(sessionId: String, request: CodexRemoteRequest, callback: (CodexRemoteResult) -> Unit) =
        bridge.sendCodexMessage(sessionId, request, callback)
    override fun stop(sessionId: String, requestId: String, callback: (CodexRemoteResult) -> Unit) =
        bridge.stopCodexConversation(sessionId, requestId, callback)
    override fun delete(sessionId: String, requestId: String, callback: (CodexRemoteResult) -> Unit) =
        bridge.deleteCodexConversation(sessionId, requestId, callback)
    override fun markRead(sessionId: String) = bridge.markSessionRead(sessionId)
}

internal suspend fun <T> awaitCodexResult(request: ((T) -> Unit) -> Unit): T =
    suspendCancellableCoroutine { continuation ->
        request { result -> if (continuation.isActive) continuation.resume(result) }
    }

/** Serialize receipt checks and preference writes with UI draft edits, even after page disposal. */
internal fun runOnCodexUiThread(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action()
    else Handler(Looper.getMainLooper()).post { action() }
}
