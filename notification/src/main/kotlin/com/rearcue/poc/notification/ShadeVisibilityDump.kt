package com.rearcue.poc.notification

/**
 * SystemUI `NotifCollection` dump 的纯 Kotlin 解析器。
 *
 * 只认「当前可见 key 集合」这一项事实；dump 形状变化时返回 null，让调用方走 fail-open
 * （全部在册即可见），不让解析失败变成漏通知。计数仍对但 key 与 NLS 在册集完全不相交的
 * 漂移由 [ShadeVisibleNotificationGate] 再判未知，避免把全部在册误判成隐藏。
 *
 * 注意：SystemUI 的 `NotifCollection` 是 pre-group 列表，条目块里出现 `filter=` 代表它还会被
 * 后续过滤器剔除（例如 `SummaryFilter` 的自动分组摘要）；这类 key 不能算 Shade-visible。
 */
object ShadeVisibilityDump {

    private const val COLLECTION_HEADER = "NotifCollection unsorted/unfiltered notifications:"
    private const val MISSING_HEADER = "missingNotifications:"
    private val collectionCount = Regex(Regex.escape(COLLECTION_HEADER) + """\s*(\d+)""")
    private val entryStart = Regex("""^\s*\[\d+\]\s+(\S+)""")

    /** 解析成功返回可见 key 集合（空集合法）；缺目标段/截断时返回 null。 */
    fun parse(output: String): Set<String>? {
        val expected = collectionCount.find(output)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        if (!output.contains(COLLECTION_HEADER)) return null
        if (!output.contains(MISSING_HEADER)) return null
        val section = output
            .substringAfter(COLLECTION_HEADER)
            .substringBefore(MISSING_HEADER)
        val lines = section.lineSequence().toList()
        if (lines.count { entryStart.containsMatchIn(it) } != expected) return null
        val keys = linkedSetOf<String>()
        var currentKey: String? = null
        var currentFiltered = false

        fun flush() {
            val key = currentKey
            if (key != null && !currentFiltered) keys += key
            currentKey = null
            currentFiltered = false
        }

        lines.forEach { line ->
            val started = entryStart.find(line)
            if (started != null) {
                flush()
                currentKey = started.groupValues[1]
            } else if (currentKey != null && line.trimStart().startsWith("filter=")) {
                currentFiltered = true
            }
        }
        flush()
        return keys
    }
}
