package com.rearcue.poc.rear

/**
 * 正文轻格式化（spec 0017 / 票 #169、#248，纯函数——JVM 判例钉在 [AgentMarkdownTest]）：
 * agent 输出的 Markdown 文本 → 「段落 / 代码块」分段结果，**去掉标记符号、保留换行与缩进**。
 *
 * 普通 [parse] 保持 agent 输出的旧口径：链接原文不改写。提问泡走 [parsePrompt]：
 * Skill / 应用 / 文件 / 页面引用与普通 Markdown 链接只留可见标签，隐藏本地路径与 URL，
 * 并给出可分别上色的字符区间。两种入口共用围栏、行内代码与强调标记的解析规则。
 *
 * 解析契约：
 * - 围栏（``` 起头）之间的内容整块进 [Block.Code]：**围栏行不显示、块内不做行内剥离**
 *   （代码里的 `**`、`[$x](path)` 是语法本身，吃掉就改错了源）；未闭合围栏吃到末尾，不吞内容。
 * - 围栏之外按行保留换行与缩进，空行原样留着当段落分隔。
 * - 行内标记（`**`/`__`/`~~`/反引号/标题号）连同符号一起去掉；反引号内的文字记成
 *   [Block.Text.inlineCode] 区间（渲染侧据此上等宽字体），区间是**剥离后**文本里的下标。
 * - [parsePrompt] 的链接标签记成 [Block.Text.inlineLinks] 或 [Block.Text.inlineMentions]；
 *   `[$name](...)`、`[@name](...)`、app/plugin/page/file 协议按内嵌引用标签处理，标签去掉 `$`/`@`。
 * - 单个 `*` / `_` 不当标记（`a * b`、`snake_case` 不该被误剥）。
 */
object AgentMarkdown {

    /** 分段结果：段落或代码块。 */
    sealed interface Block {
        /** 标记符号已剥离后的显示文本；[parsePrompt] 还会把链接改成标签文字。 */
        val text: String

        /**
         * 普通段落。各区间相对 [text] 的 `[start, end)` 半开区间
         * （**不用 `IntRange`**：`IntRange.last` 是闭区间末端，用它当排他末端必错一格）。
         */
        data class Text(
            override val text: String,
            val inlineCode: List<Span> = emptyList(),
            val inlineLinks: List<Span> = emptyList(),
            val inlineMentions: List<Span> = emptyList(),
        ) : Block

        /** 围栏代码块：整块走等宽字体与收紧行距，内容一字不动。 */
        data class Code(override val text: String) : Block
    }

    /** 文本里的字符区间（`[start, end)` 半开，下标相对所属 [Block.Text.text]）。 */
    data class Span(val start: Int, val end: Int) {
        /** 从宿主文本取出本区间覆盖的文字（越界即空串，不抛）。 */
        fun sliceOf(text: String): String =
            if (start in 0..text.length && end in start..text.length) text.substring(start, end) else ""
    }

    /** 围栏起头的判定用**未缩进**的行首三个反引号（同 Markdown 惯例；缩进过的当普通文本）。 */
    private const val FENCE = "```"

    /** 行内代码：反引号包着的连续片段（不跨行——跨行的反引号在 agent 输出里从不是代码）。 */
    private val INLINE_CODE = Regex("`([^`\n]+)`")

    /** Markdown 链接 / 内嵌引用标签：`[label](destination)`，标签与目标都不跨行、目标不嵌右括号。 */
    private val LINK = Regex("""\[([^\]\n]+)]\(([^)\n]+)\)""")

    /** 成对行内标记：加粗、强调、删除线。反引号单独走 [INLINE_CODE]（要记区间）。 */
    private val PAIRED_MARKERS = listOf("**", "__", "~~")

    /** 标题号（行首 1–6 个 `#` 加空格）：符号去掉，文字留成普通正文（不渲染成标题层级）。 */
    private val HEADING = Regex("^#{1,6}\\s+")

    /** 行尾装饰性 `#`（`## 结论 ##` 的那种）。 */
    private val TRAILING_HASHES = Regex("\\s+#+\\s*$")

    /** 文本 → 分段。空输入、全空白输入给空列表（渲染层据此不组内容）。 */
    fun parse(source: String): List<Block> = parse(source, normalizeLinks = false)

    /**
     * 提问泡文本 → 分段。与 [parse] 的差别只有链接：标签代替原文、路径/URL 隐藏，
     * 并保留链接与内嵌引用的独立区间供来源配色使用。
     */
    fun parsePrompt(source: String): List<Block> = parse(source, normalizeLinks = true)

    private fun parse(source: String, normalizeLinks: Boolean): List<Block> {
        if (source.isBlank()) return emptyList()
        val blocks = mutableListOf<Block>()
        val code = mutableListOf<String>()
        val text = mutableListOf<String>()
        var inFence = false

        fun flushCode() {
            if (code.isNotEmpty()) {
                blocks += Block.Code(code.joinToString("\n"))
                code.clear()
            }
        }

        fun flushText() {
            if (text.isNotEmpty()) {
                blocks += stripInline(text.joinToString("\n"), normalizeLinks)
                text.clear()
            }
        }

        for (raw in source.lines()) {
            if (raw.startsWith(FENCE)) {
                if (inFence) {
                    flushCode()
                    inFence = false
                } else {
                    flushText()
                    inFence = true
                }
                continue
            }
            if (inFence) code += raw else text += raw
        }
        // 未闭合围栏：已收到的一并成块（不吞、不丢），后面的行照常当代码读到末尾。
        flushCode()
        flushText()

        return blocks.map { trimBlock(it) }.filterNot { it.text.isBlank() }
    }

    /** 块首块尾的空白不留（含内容自带的首尾空格），块内换行与空行原样保留。 */
    private fun trimBlock(block: Block): Block = when (block) {
        is Block.Code -> Block.Code(block.text.trim('\n'))
        is Block.Text -> {
            // 各区间是从**未 trim** 的文本算出来的：先按 trim 掉的字符数把区间左移，
            // 再让越界/落空的区间消失（不让样式套到别的字上）。
            val raw = block.text
            val cut = raw.indexOfFirst { !it.isWhitespace() }
            if (cut < 0) {
                Block.Text(text = "")
            } else {
                val trimmed = raw.trim()
                fun shifted(spans: List<Span>): List<Span> = spans
                    .map { Span(it.start - cut, it.end - cut) }
                    .filter { it.start >= 0 && it.end <= trimmed.length && it.start < it.end }

                Block.Text(
                    text = trimmed,
                    inlineCode = shifted(block.inlineCode),
                    inlineLinks = shifted(block.inlineLinks),
                    inlineMentions = shifted(block.inlineMentions),
                )
            }
        }
    }

    /**
     * 剥掉一段文本里的行内标记，并给出等宽/链接/引用标签在**剥离后**文本里的区间。
     *
     * 三件容易写错的事（都有判例守着）：
     * 1. 区间必须是**剥离之后**的坐标，所以本函数**边剥边记**：游标 `text.length` 天然就是当前位置。
     * 2. 反引号片段里的字**不再当标记或链接处理**（代码里的 `**`、`[$x](path)` 是语法本身）。
     * 3. 链接标签里的强调标记也要剥掉，但标签本身只留可见名称，目标路径/URL 永不进入显示文本。
     */
    private fun stripMarkers(segment: String): String {
        var s = segment
        for (marker in PAIRED_MARKERS) {
            s = s.replace(marker, "")
        }
        return s
    }
    private fun stripInline(line: String, normalizeLinks: Boolean): Block.Text {
        val text = StringBuilder()
        val codeSpans = mutableListOf<Span>()
        val linkSpans = mutableListOf<Span>()
        val mentionSpans = mutableListOf<Span>()
        var cursor = 0
        // 标题号与行尾装饰井号是**行级**的事：段首认一次、行尾认一次，与反引号片段切几刀无关。
        val heading = HEADING.containsMatchIn(line)
        val withoutTrailingHashes = if (TRAILING_HASHES.containsMatchIn(line)) {
            line.replaceFirst(TRAILING_HASHES, "")
        } else {
            line
        }

        fun stripPlain(segment: String): String {
            var s = segment
            for (marker in PAIRED_MARKERS) {
                s = s.replace(marker, "")
            }
            return s
        }

        fun appendPlain(segment: String) {
            if (!normalizeLinks) {
                text.append(stripPlain(segment))
                return
            }
            var offset = 0
            while (true) {
                val match = LINK.find(segment, offset) ?: break
                text.append(stripPlain(segment.substring(offset, match.range.first)))
                val rawLabel = match.groupValues[1]
                val destination = match.groupValues[2]
                val mention = isMention(rawLabel, destination)
                val label = displayLabel(rawLabel, mention)
                if (label.isNotEmpty()) {
                    val start = text.length
                    text.append(label)
                    val span = Span(start, text.length)
                    if (mention) mentionSpans += span else linkSpans += span
                }
                offset = match.range.last + 1
            }
            text.append(stripPlain(segment.substring(offset)))
        }

        var first = true
        val source = withoutTrailingHashes
        while (true) {
            val start = INLINE_CODE.find(source, cursor) ?: break
            var plain = source.substring(cursor, start.range.first)
            if (first && heading) plain = plain.replaceFirst(HEADING, "")
            first = false
            appendPlain(plain)
            val content = start.groupValues[1]
            val spanStart = text.length
            text.append(content)
            codeSpans += Span(start = spanStart, end = text.length)
            cursor = start.range.last + 1
        }
        var tail = source.substring(cursor)
        if (first && heading) tail = tail.replaceFirst(HEADING, "")
        appendPlain(tail)

        return Block.Text(
            text = text.toString(),
            inlineCode = codeSpans,
            inlineLinks = linkSpans,
            inlineMentions = mentionSpans,
        )
    }

    /**
     * 内嵌引用标签的识别只认**结构明确**的形态；看不准的 `[文字](目标)` 仍按普通链接显示，
     * 不把用户真实输入误吞成系统标签。
     */
    private fun isMention(rawLabel: String, destination: String): Boolean {
        val label = rawLabel.trim()
        val target = destination.trim()
        val structuredTarget = target.startsWith("app://") ||
            target.startsWith("plugin://") ||
            target.startsWith("page://") ||
            target.startsWith("file://") ||
            target.startsWith("project-file:") ||
            target.startsWith("library-file:") ||
            target.contains(".skills-manager") ||
            Regex("""[\\/]skills[\\/]""").containsMatchIn(target)
        val localTarget = Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(target) ||
            target.startsWith("~/") ||
            target.startsWith("/")
        return structuredTarget ||
            ((label.startsWith("$") || label.startsWith("@")) && localTarget)
    }
    private fun displayLabel(rawLabel: String, mention: Boolean): String {
        val label = stripMarkers(rawLabel).trim()
        if (!mention) return label
        return label.removePrefix("$").removePrefix("@").trim()
    }
}
