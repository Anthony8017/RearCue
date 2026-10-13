package com.rearcue.poc.rear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 长按已经发出、还在等系统划掉结果的应用。
 *
 * 这些图标保持缩小，直到要划的通知都有了回执。主屏不读这里。
 */
object IconHoldFeed {
    private val keysByApp = LinkedHashMap<String, MutableSet<String>>()
    private val _holding = MutableStateFlow<Set<String>>(emptySet())

    val holding: StateFlow<Set<String>> = _holding.asStateFlow()

    fun arm(app: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        keysByApp[app] = keys.toMutableSet()
        publish()
    }

    fun resolve(key: String) {
        if (key.isEmpty()) return
        val finished = ArrayList<String>()
        keysByApp.forEach { (app, pending) ->
            if (pending.remove(key) && pending.isEmpty()) finished += app
        }
        finished.forEach { keysByApp.remove(it) }
        if (finished.isNotEmpty()) publish()
    }

    fun pending(app: String): Boolean = app in keysByApp

    internal fun reset() {
        keysByApp.clear()
        publish()
    }

    private fun publish() {
        _holding.value = keysByApp.keys.toSet()
    }
}
