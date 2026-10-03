package com.rearcue.poc.voice

import java.util.ArrayDeque

data class VoiceBroadcastItem(
    val id: Long,
    val kind: VoiceBroadcastKind,
    val text: String,
)

/** 进程内 FIFO；不持久化，App 重启后不补播旧结果。 */
class VoiceBroadcastQueue {
    private val pending = ArrayDeque<VoiceBroadcastItem>()
    private var current: VoiceBroadcastItem? = null
    private var nextId = 1L

    val currentText: String?
        get() = synchronized(this) { current?.text }

    val pendingCount: Int
        get() = synchronized(this) { pending.size }

    @Synchronized
    fun enqueue(kind: VoiceBroadcastKind, text: String): VoiceBroadcastItem {
        val item = VoiceBroadcastItem(nextId++, kind, text)
        pending.addLast(item)
        return item
    }

    @Synchronized
    fun startNext(): VoiceBroadcastItem? {
        if (current != null) return current
        current = pending.pollFirst()
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

    /** 播放/暂停停止与等待确认：当前和未播队列都清空。 */
    @Synchronized
    fun clear() {
        pending.clear()
        current = null
    }
}



