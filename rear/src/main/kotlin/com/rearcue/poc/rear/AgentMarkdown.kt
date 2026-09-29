package com.rearcue.poc.rear

/**
 * 正文轻格式化（spec 0017 / 票 #169，纯函数——JVM 判例钉在 [AgentMarkdownTest]）：
 * agent 输出的 Markdown 文本 → 「段落 / 代码块」分段结果，**去掉标记符号、保留换行与缩进**。
 *
 * 范围刻意收窄（spec 0017「去符号、留结构」档）：不渲染链接、不重排表格、不引入 Markdown 库。
 * 解析契约：
 * - 围栏（``` 起头）之间的内容整块进 [Block.Code]：**围栏行不显示、块内不做行内剥离**
 *   （代码里的 `**` 是语法本身，吃掉就改错了源）；未闭合围栏吃到末尾，不吞内容。
 * - 围栏之外按行保留换行与缩进，空行原样留着当段落分隔。
 * - 行内标记（`**`/`__`/`~~`/反引号/标题号）连同符号一起去掉；反引号内的文字记成
 *   [Block.Text.inlineCode] 区间（渲染侧据此上等宽字体），区间是**剥离后**文本里的下标。
 * - 单个 `*` / `_` 不当标记（`a * b`、`snake_case` 不该被误剥）。
 */
object AgentMarkdown {

    /** 分段结果：段落或代码块。 */
    sealed interface Block {
        /** 原始文本（标记符号已剥离）。 */
        val text: String

        /**
         * 普通段落。[inlineCode] 是要走等宽字体的字符区间——相对 [text] 的 `[start, end)`
         * 半开区间（**不用 `IntRange`**：`IntRange.last` 是闭区间末端，用它当排他末端必错一格，
         * 实测踩过，见 [AgentMarkdownTest] 的区间判例）。
         */
        data class Text(
            override val text: String,
            val inlineCode: List<Span> = emptyList(),
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

    /** 成对行内标记：加粗、强调、删除线。反引号单独走 [INLINE_CODE]（要记区间）。 */
    private val PAIRED_MARKERS = listOf("**", "__", "~~")

    /** 标题号（行首 1–6 个 `#` 加空格）：符号去掉，文字留成普通正文（不渲染成标题层级）。 */
    private val HEADING = Regex("^#{1,6}\\s+")

    /** 行尾装饰性 `#`（`## 结论 ##` 的那种）。 */
    private val TRAILING_HASHES = Regex("\\s+#+\\s*$")

    /**
     * 文本 → 分段。空输入、全空白输入给空列表（渲染层据此不组内容）。
     *
     * 连续的非围栏行聚成**一个**段落（段内换行与空行原样留着），围栏之间聚成一个代码块——
     * 逐行切段会把一段话拆成十几块，行距也就跟着散了。
     */
    fun parse(source: String): List<Block> {
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
                blocks += stripInline(text.joinToString("\n"))
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

        return blocks.map(::trimBlock).filterNot { it.text.isBlank() }
    }

    /** 块首块尾的空白不留（含内容自带的首尾空格），块内换行与空行原样保留。 */
    private fun trimBlock(block: Block): Block = when (block) {
        is Block.Code -> Block.Code(block.text.trim('\n'))
        is Block.Text -> {
            // 行内区间是从**未 trim** 的文本算出来的：先按 trim 掉的字符数把区间左移，
            // 再让越界/落空的区间消失（不让等宽区间套到别的字上）。
            val raw = block.text
            val cut = raw.indexOfFirst { !it.isWhitespace() }
            if (cut < 0) {
                Block.Text(text = "")
            } else {
                val trimmed = raw.trim()
                val fixed = block.inlineCode
                    .map { Span(it.start - cut, it.end - cut) }
                    .filter { it.start >= 0 && it.end <= trimmed.length && it.start < it.end }
                Block.Text(text = trimmed, inlineCode = fixed)
            }
        }
    }

    /**
     * 剥掉一段文本里的行内标记，并给出反引号片段在**剥离后**文本里的区间。
     *
     * 两件容易写错的事（都有判例守着，评审抓过一次）：
     * 1. 区间必须是**剥离之后**的坐标——`**A** 起 \`one\`` 里那个反引号片段，在 `**` 被删掉后
     *    整体左移两位；先算区间后剥符号会把等宽套到别的字上（越界时更糟：区间被丢掉、
     *    等宽效果静默消失）。所以本函数**边剥边记**：游标 `text.length` 天然就是剥离后的位置。
     * 2. 反引号片段里的字**不再当标记处理**（代码里的 `**` 是语法本身）；
     *    相应地 `**` 这类成对标记不跨过反引号片段匹配。
     */
    private fun stripInline(line: String): Block.Text {
        val text = StringBuilder()
        val spans = mutableListOf<Span>()
        var cursor = 0
        // 标题号与行尾装饰井号是**行级**的事：段首认一次、行尾认一次，与反引号片段切几刀无关。
        val heading = HEADING.containsMatchIn(line)
        val trailing = TRAILING_HASHES.containsMatchIn(line)

        /** 剥一段**非代码**文字里的成对标记（代码里的 `**` 是语法本身，不归这里管）。 */
        fun stripPlain(segment: String): String {
            var s = segment
            for (marker in PAIRED_MARKERS) {
                s = s.replace(marker, "")
            }
            return s
        }

        var first = true
        while (true) {
            val start = INLINE_CODE.find(line, cursor) ?: break
            var plain = line.substring(cursor, start.range.first)
            if (first && heading) plain = plain.replaceFirst(HEADING, "")
            first = false
            text.append(stripPlain(plain))
            val content = start.groupValues[1]
            val spanStart = text.length
            text.append(content)
            spans += Span(start = spanStart, end = text.length)
            cursor = start.range.last + 1
        }
        var tail = line.substring(cursor)
        if (first && heading) tail = tail.replaceFirst(HEADING, "")
        text.append(stripPlain(tail))

        var working = text.toString()
        if (trailing) working = working.replaceFirst(TRAILING_HASHES, "")
        return Block.Text(text = working, inlineCode = spans)
    }
}
