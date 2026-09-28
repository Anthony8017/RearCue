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
 * **例外是锁屏态过滤器**（见 [IGNORED_FILTERS]）：`filter=KeyguardCoordinator` 只是「锁屏这层
 * 没往下发」，不是用户按掉；按 CONTEXT 的 Shade-visible 定义（**解锁状态下**下拉栏会列出的
 * 通知）它照常算可见。
 */
object ShadeVisibilityDump {

    private const val COLLECTION_HEADER = "NotifCollection unsorted/unfiltered notifications:"
    private const val MISSING_HEADER = "missingNotifications:"
    private val collectionCount = Regex(Regex.escape(COLLECTION_HEADER) + """\s*(\d+)""")
    private val entryStart = Regex("""^\s*\[\d+\]\s+(\S+)""")

    /**
     * 锁屏态（state）过滤器：SystemUI 在 keyguard 锁定时给用户通知打
     * `filter=KeyguardCoordinator`，解锁后这层就没了。它表达的是「**现在**锁着，挡一下」，
     * 不是「这条通知在下拉栏里不存在」——CONTEXT 的 Shade-visible 明确是**解锁状态下**下拉栏
     * 会列出的集合，所以这类条目必须照常算可见。
     *
     * 2026-09-29 实机踏坑记录：不豁免它时，锁屏下每条新通知都会被判定「下拉栏不可见」，
     * 背屏刚显示的图标 ~300ms 后被自己的可见性链删掉（`removed <pkg>`），机主看到的就是
     * 「锁屏时来消息，背屏图标不更新」。真正该剔除的仍是 `SummaryFilter` 这类内容过滤器。
     */
    private val IGNORED_FILTERS = listOf("KeyguardCoordinator")

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
                val filter = line.trimStart().removePrefix("filter=").trim()
                if (IGNORED_FILTERS.none { filter.startsWith(it) }) currentFiltered = true
            }
        }
        flush()
        return keys
    }
}
