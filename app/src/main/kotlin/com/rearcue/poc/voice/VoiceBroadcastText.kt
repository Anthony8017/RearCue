package com.rearcue.poc.voice

object VoiceBroadcastText {
    const val EMPTY_REPLY = "任务完成，没有文字回复"
    private const val CODE_SKIPPED = "代码内容已略过。"
    private const val TABLE_SKIPPED = "表格内容已略过。"
    private const val LINK_SKIPPED = "链接已略过。"

    data class SpokenSentence(
        val text: String,
        val visualAnchor: Boolean,
        /** 视觉定位保留完整路径，朗读时才缩短为文件名。 */
        val anchorText: String = text,
    )

    fun spokenText(
        kind: VoiceBroadcastKind,
        body: String? = null,
        errorReason: String? = null,
    ): String = when (kind) {
        VoiceBroadcastKind.NEEDS_INPUT -> normalize(body.orEmpty())
        VoiceBroadcastKind.DONE -> body?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalize) ?: EMPTY_REPLY
        VoiceBroadcastKind.ERROR -> {
            val reason = shortErrorReason(errorReason ?: body)
            if (reason.isEmpty()) "任务失败。" else "任务失败。$reason"
        }
    }

    /** 回复正文的自然口语化：文件路径只读文件名，不逐字念代码、表格、URL 和格式符号。 */
    fun normalize(raw: String): String {
        val output = mutableListOf<String>()
        var inCode = false
        var inTable = false
        var codeReported = false
        var tableReported = false

        raw.replace("\r\n", "\n").lineSequence().forEach { original ->
            val line = original.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) {
                if (inCode) {
                    if (!codeReported) {
                        output += CODE_SKIPPED
                        codeReported = true
                    }
                    inCode = false
                } else {
                    inCode = true
                    codeReported = false
                }
                return@forEach
            }
            if (inCode) return@forEach

            if (isTableLine(line)) {
                if (!inTable) {
                    output += TABLE_SKIPPED
                    tableReported = true
                    inTable = true
                }
                return@forEach
            }
            inTable = false
            tableReported = false

            val spoken = markdownToSpeech(line)
                .let(::speechText)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (spoken.isNotEmpty()) output += spoken
        }

        if (inCode && !codeReported) output += CODE_SKIPPED
        return polish(output.joinToString(" "))
    }

    /** 供 TTS 引擎按句切换设置；空输入不产生句子。 */
    fun sentences(raw: String): List<String> {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return emptyList()
        return SPOKEN_SENTENCE.findAll(normalized)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * 按句给出朗读文本与视觉锚：代码/表格的“已略过”只发声不跳转，
     * 后续正文句才是背屏跟随落点。
     */
    fun spokenSentences(raw: String): List<SpokenSentence> {
        if (raw.isBlank()) return emptyList()
        val normalized = raw.replace("\r\n", "\n")
        val output = mutableListOf<SpokenSentence>()
        var inCode = false
        var inTable = false

        fun flushTable() {
            if (!inTable) return
            output += SpokenSentence(TABLE_SKIPPED, visualAnchor = false)
            inTable = false
        }

        normalized.lineSequence().forEach { original ->
            val line = original.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) {
                flushTable()
                if (inCode) {
                    output += SpokenSentence(CODE_SKIPPED, visualAnchor = false)
                    inCode = false
                } else {
                    inCode = true
                }
                return@forEach
            }
            if (inCode) return@forEach

            if (isTableLine(line)) {
                if (!inTable) {
                    inTable = true
                }
                return@forEach
            }
            flushTable()

            val spoken = markdownToSpeech(line)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (spoken.isEmpty()) return@forEach
            SPOKEN_SENTENCE.findAll(spoken).forEach { match ->
                val text = polish(speechText(match.value).trim())
                val anchor = polish(speechText(match.value, shortenPaths = false).trim())
                if (text.isNotEmpty()) output += SpokenSentence(text, visualAnchor = true, anchorText = anchor)
            }
        }

        if (inCode) output += SpokenSentence(CODE_SKIPPED, visualAnchor = false)
        flushTable()
        return output.filter { it.text.isNotEmpty() }
    }

    private fun polish(raw: String): String = raw
        .replace(Regex("[—–―]{2,}"), "，")
        .replace(Regex("[…]+|\\.{3,}"), "，")
        .replace(Regex("，\\s*，+"), "，")
        .replace(Regex("\\s+([，。！？；：])"), "$1")
        .replace(Regex("([，；：])\\s+"), "$1")
        .trim()

    private fun isTableLine(line: String): Boolean {
        if (!line.contains('|')) return false
        return line.startsWith("|") || line.endsWith("|") || line.split('|').size >= 3
    }

    private fun markdownToSpeech(line: String): String {
        var text = line
        text = IMAGE_LINK.replace(text) { match ->
            val label = match.groupValues[1].trim()
            if (label.isEmpty()) "图片已略过。" else "图片：$label。"
        }
        text = LINK.replace(text) { match ->
            val label = match.groupValues[1].trim()
            when {
                label.isEmpty() -> LINK_SKIPPED
                isFilePath(label) -> "`$label`"
                else -> label
            }
        }
        text = BARE_URL.replace(text) { LINK_SKIPPED }
        text = HTML_TAG.replace(text, "")
        text = text.replace(Regex("^#{1,6}\\s*"), "")
        text = text.replace(Regex("^\\s*[-*+]\\s+"), "")
        text = text.replace(Regex("^\\s*\\d+[.)]\\s+"), "")
        text = text.replace(Regex("^>\\s*"), "")
        text = text.replace("**", "").replace("__", "")
        text = text.replace(Regex("\\[([^]]+)]"), "$1")
        return text
    }

    private fun speechText(raw: String, shortenPaths: Boolean = true): String {
        val text = if (shortenPaths) shortenFilePaths(raw) else raw
        return INLINE_CODE.replace(text, "$1").filterNot { it in "（）()［］[]" }
    }

    private fun shortenFilePaths(raw: String): String {
        // 代码和引号界定了完整路径，可包含空格；普通正文只匹配无空格路径。
        val quoted = QUOTED_TEXT.replace(raw) { match ->
            val content = match.groupValues[2]
            if (isFilePath(content)) {
                match.groupValues[1] + fileName(content) + match.groupValues[3]
            } else {
                match.value
            }
        }
        return FILE_PATH.replace(quoted) { fileName(it.value) }
    }

    private fun isFilePath(text: String): Boolean = PATH_START.containsMatchIn(text) &&
        !text.endsWith('/') && !text.endsWith('\\') &&
        (ABSOLUTE_PATH_START.containsMatchIn(text) || FILE_NAME.matches(fileName(text)))

    private fun fileName(path: String): String = PATH_LOCATION.replace(path, "")
        .substringAfterLast('/')
        .substringAfterLast('\\')

    private fun shortErrorReason(raw: String?): String {
        val firstLine = raw
            ?.replace("\r", "\n")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (firstLine.isEmpty()) return ""
        val spoken = normalize(firstLine)
        return if (spoken.length <= 160) spoken else spoken.take(157) + "…"
    }

    private val SPOKEN_SENTENCE = Regex("[^。！？!?]+[。！？!?]?")
    private val IMAGE_LINK = Regex("!\\[([^]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val BARE_URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("<[^>]+>")
    private val INLINE_CODE = Regex("(?<!`)`([^`]*)`(?!`)")
    private val QUOTED_TEXT = Regex("""(`|["'“‘])([^`"'“”‘’]+)(`|["'”’])""")
    private val PATH_START = Regex("""^(?:[A-Za-z]:[/\\]|\\\\|~[/\\]|\.{1,2}[/\\]|/|[^\s:/\\]+[/\\])""")
    private val ABSOLUTE_PATH_START = Regex("""^(?:[A-Za-z]:[/\\]|\\\\|~[/\\]|\.{1,2}[/\\]|/)""")
    private val FILE_NAME = Regex(""".+\.[A-Za-z0-9]+|README|LICENSE|Makefile|Dockerfile""")
    private val PATH_LOCATION = Regex("""(?::\d+(?::\d+)?|#L?\d+(?:[-:]L?\d+)?)$""")
    private const val PATH_PART = """[^\s/\\:*?"'<>|`#,;，。！？；：、（）()\[\]{}“”‘’]+"""
    private val FILE_PATH = Regex(
        // 相对路径需有扩展名，避免把读/写、日期、分数当路径；明确的路径前缀允许无扩展名。
        """(?:(?<![A-Za-z0-9_./\\])[A-Za-z]:[/\\]|(?<![\p{L}\p{N}_./\\:])(?:\\\\|~[/\\]|\.{1,2}[/\\]|/))(?:$PATH_PART[/\\])*$PATH_PART(?:\:\d+(?:\:\d+)?|#L?\d+(?:[-:]L?\d+)?)?""" +
            """|(?<![A-Za-z0-9_./\\])(?:[A-Za-z0-9_@.+-]+[/\\])+$PATH_PART\.[A-Za-z0-9]+(?:\:\d+(?:\:\d+)?|#L?\d+(?:[-:]L?\d+)?)?""",
    )
}
