package com.rearcue.poc.voice

import java.util.ArrayDeque

data class VoiceBroadcastItem(
    val id: Long,
    val kind: VoiceBroadcastKind,
    val text: String,
    val source: VoiceBroadcastSource = VoiceBroadcastSource(),
    val sentences: List<VoiceBroadcastText.SpokenSentence> = VoiceBroadcastText.spokenSentences(text),
    val sessionId: String? = null,
    val eventId: String? = null,
    val requestId: String? = null,
)

/** 进程内 FIFO；不持久化，App 重启后不补播旧结果。 */
class VoiceBroadcastQueue {
    private val pending = ArrayDeque<VoiceBroadcastItem>()
    private var current: VoiceBroadcastItem? = null
    private var nextId = 1L

    val currentItem: VoiceBroadcastItem?
        get() = synchronized(this) { current }

    val currentText: String?
        get() = synchronized(this) { current?.text }

    val pendingCount: Int
        get() = synchronized(this) { pending.size }

    val hasContent: Boolean
        get() = synchronized(this) { current != null || pending.isNotEmpty() }

    @Synchronized
    fun enqueue(
        kind: VoiceBroadcastKind,
        text: String,
        sessionId: String? = null,
        eventId: String? = null,
        requestId: String? = null,
        source: VoiceBroadcastSource = VoiceBroadcastSource(sessionId = sessionId),
        sentences: List<VoiceBroadcastText.SpokenSentence> = VoiceBroadcastText.spokenSentences(text),
    ): VoiceBroadcastItem {
        val item = VoiceBroadcastItem(nextId++, kind, text, source, sentences, sessionId ?: source.sessionId, eventId, requestId)
        pending.addLast(item)
        return item
    }

    @Synchronized
    fun startNext(): VoiceBroadcastItem? {
        if (current != null) return current
        val question = pending.firstOrNull { it.kind == VoiceBroadcastKind.NEEDS_INPUT }
        current = if (question != null) { pending.remove(question); question } else pending.pollFirst()
        return current
    }

    @Synchronized
    fun completeCurrent() {
        current = null
    }

    /** 跳过当前整条；队列其余内容保留。 */
    @Synchronized
    fun skipCurrent(): Boolean {
        if (current == null) return false
        current = null
        return true
    }

    @Synchronized
    fun retainRequests(sessionId: String, requestIds: Set<String>): Boolean {
        pending.removeIf { it.sessionId == sessionId && it.requestId != null && it.requestId !in requestIds }
        return current?.let { it.sessionId == sessionId && it.requestId != null && it.requestId !in requestIds } == true
    }

    @Synchronized
    fun retainSessions(sessionIds: Set<String>): Boolean {
        pending.removeIf { it.sessionId != null && it.sessionId !in sessionIds }
        return current?.let { it.sessionId != null && it.sessionId !in sessionIds } == true
    }

    /** 双击终止：当前和未播队列都清空。 */
    @Synchronized
    fun clear() {
        pending.clear()
        current = null
    }
}
