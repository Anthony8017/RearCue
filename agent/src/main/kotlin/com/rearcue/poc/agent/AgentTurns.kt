package com.rearcue.poc.agent

/**
 * 问答流的窗口与排序（spec 0017 / 票 #169，纯函数）。
 *
 * 两条链路（PC 桥、ZCode 中继）都要给手机端一份「最近这一段的问答流」，窗口口径必须一致：
 * **最多 [MAX_ENTRIES] 条、合计最多 [MAX_CHARS] 字（含条目之间的分隔），超限从最旧丢**。
 * 这就是 spec 0010 时代 `tail-util.createTailHistory` 的窗口值，只是现在**提问与回答共用**
 * 同一个窗口，而不是「只留助手文本、提问丢掉」。
 *
 * 三条硬规矩（都有判例）：
 * - 只丢整条，**不截断单条正文**（截断会把 agent 的代码块拦腰砍掉）；
 * - **至少留一条**，哪怕它自己就超窗口（否则屏上会突然全空）；
 * - 末尾那条**正在增长**（[AgentTurn.open]）的条目永远不会因为窗口被丢掉——裁剪只从头部
 *   开始丢，而它总在末尾，所以这条是**结构性保证**，不需要另写一个分支去「保护」它。
 */
object AgentTurns {

    /** 窗口条数上限（与桥侧 `turn-log.mjs` 的 maxEntries 同值）。 */
    const val MAX_ENTRIES = 20

    /** 窗口字符上限（与桥侧 `turn-log.mjs` 的 maxChars 同值）。 */
    const val MAX_CHARS = 16000

    /** 条目之间的分隔长度（窗口按「用户实际看到的那段文本」计量：正文和 + 条目间分隔）。 */
    const val SEPARATOR = "\n\n────────\n\n"

    /**
     * 裁到窗口内：从最旧开始丢，直到条数与字符都进窗口。
     *
     * 裁剪只动头部，所以 [AgentTurn.open] 的条目（总在末尾）天然安全；此处的循环条件是
     * `start < turns.size - 1`——**至少留一条**与「不丢末尾那条」在这里是同一件事。
     */
    fun window(
        turns: List<AgentTurn>,
        maxEntries: Int = MAX_ENTRIES,
        maxChars: Int = MAX_CHARS,
    ): List<AgentTurn> {
        if (turns.isEmpty()) return emptyList()
        var start = 0
        while (start < turns.size - 1) {
            val tail = turns.subList(start, turns.size)
            val fits = tail.size <= maxEntries && visualLength(tail) <= maxChars
            if (fits) break
            start++
        }
        return turns.subList(start, turns.size).toList()
    }

    /** 用户实际看到的那段文本的长度：正文之和 + 条目间分隔（与桥侧计量口径同一条）。 */
    fun visualLength(turns: List<AgentTurn>): Int {
        if (turns.isEmpty()) return 0
        val body = turns.sumOf { it.text.length }
        return body + SEPARATOR.length * (turns.size - 1)
    }
}
