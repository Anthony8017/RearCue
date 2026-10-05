package com.rearcue.poc.ui

import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.BridgeRelayClient
import com.rearcue.poc.agent.CodexRemoteOptions
import com.rearcue.poc.agent.CodexRemoteRequest
import com.rearcue.poc.agent.CodexRemoteResult
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** 同一对话页的请求面；测试在真实点击与异步回执之间替换传输。 */
internal interface CodexConversationClient {
    fun options(callback: (CodexRemoteOptions?) -> Unit)
    fun history(sessionId: String, callback: (List<AgentTurn>?) -> Unit)
    fun create(request: CodexRemoteRequest, callback: (CodexRemoteResult) -> Unit)
    fun send(sessionId: String, request: CodexRemoteRequest, callback: (CodexRemoteResult) -> Unit)
    fun stop(sessionId: String, requestId: String, callback: (CodexRemoteResult) -> Unit)
    fun delete(sessionId: String, requestId: String, callback: (CodexRemoteResult) -> Unit)
    fun markRead(sessionId: String)
}

internal class BridgeCodexConversationClient(private val bridge: BridgeRelayClient) : CodexConversationClient {
    override fun options(callback: (CodexRemoteOptions?) -> Unit) = bridge.fetchCodexOptions(callback)
    override fun history(sessionId: String, callback: (List<AgentTurn>?) -> Unit) = bridge.fetchHistory(sessionId, callback)
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
