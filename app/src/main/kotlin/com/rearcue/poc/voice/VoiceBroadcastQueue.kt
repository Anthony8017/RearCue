package com.rearcue.poc.voice

import java.util.ArrayDeque

data class VoiceBroadcastItem(
    val id: Long,
    val kind: VoiceBroadcastKind,
    val text: String,
    val source: VoiceBroadcastSource = VoiceBroadcastSource(),
    val sentences: List<VoiceBroadcastText.SpokenSentence> = VoiceBroadcastText.spokenSentences(text),
    /** 可见原文的句子投影；朗读文本带前缀/编号时仍按这份文本定位。 */
    val visualSentences: List<VoiceBroadcastText.SpokenSentence> =
        source.visualText?.let(VoiceBroadcastText::spokenSentences)?.filter { it.visualAnchor }
            ?: sentences.filter {
                it.visualAnchor &&
                    kind != VoiceBroadcastKind.ERROR &&
                    text != VoiceBroadcastText.EMPTY_REPLY
            },
    val sessionId: String? = null,
    val eventId: String? = null,
    val requestId: String? = null,
    val cueBeforeSpeech: Boolean = false,
    val cueEpoch: Long = 0L,
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
        visualSentences: List<VoiceBroadcastText.SpokenSentence> =
            source.visualText?.let(VoiceBroadcastText::spokenSentences)?.filter { it.visualAnchor }
                ?: sentences.filter {
                    it.visualAnchor &&
                        kind != VoiceBroadcastKind.ERROR &&
                        text != VoiceBroadcastText.EMPTY_REPLY
                },
        cueBeforeSpeech: Boolean = false,
        cueEpoch: Long = 0L,
    ): VoiceBroadcastItem {
        val normalizedSentences = if (source.visualText != null && source.visualText != source.body) {
            val prefixCount = (sentences.size - visualSentences.size).coerceAtLeast(0)
            sentences.mapIndexed { index, sentence ->
                if (index < prefixCount) sentence.copy(visualAnchor = false) else sentence
            }
        } else {
            sentences
        }
        val item = VoiceBroadcastItem(
            nextId++,
            kind,
            text,
            source,
            normalizedSentences,
            visualSentences,
            sessionId ?: source.sessionId,
            eventId,
            requestId,
            cueBeforeSpeech,
            cueEpoch,
        )
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

/** 把音频句序投影成可见句序；略过句沿用上一处可见句，不制造新的高亮。 */
internal fun VoiceBroadcastItem.projectFollow(sentenceIndex: Int): com.rearcue.poc.core.VoiceBroadcastFollow? {
    if (sentences.getOrNull(sentenceIndex) == null) return null
    val visualIndex = sentences.take(sentenceIndex + 1).count { it.visualAnchor } - 1
    val display = visualSentences.getOrNull(visualIndex)
    return com.rearcue.poc.core.VoiceBroadcastFollow(
        itemId = id,
        sessionId = source.sessionId.orEmpty(),
        turnEntryId = source.turnEntryId,
        sourceBody = source.visualText ?: source.body,
        sentenceText = display?.anchorText.orEmpty(),
        sentenceIndex = display?.let { visualIndex } ?: 0,
        sentenceCount = visualSentences.size,
        visualAnchor = display != null,
        precedingSentenceTexts = visualSentences.take(visualIndex.coerceAtLeast(0))
            .map { it.anchorText },
        visualTarget = source.visualTarget,
        requestId = requestId,
        spokenSentenceIndex = sentenceIndex,
    )
}
