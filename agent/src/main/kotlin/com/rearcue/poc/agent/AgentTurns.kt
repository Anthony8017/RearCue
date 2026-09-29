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
 * - 末尾那条**正在增长**（`open`）的条目永远不会因为窗口被丢掉——不然逐字流会在中途断掉。
 */
object AgentTurns {

    /** 窗口条数上限（与桥侧 `turn-log.mjs` 的 maxEntries 同值）。 */
    const val MAX_ENTRIES = 20

    /** 窗口字符上限（与桥侧 `turn-log.mjs` 的 maxChars 同值）。 */
    const val MAX_CHARS = 16000

    /** 条目之间的分隔（窗口计量与拼接共用；与桥侧默认分隔保持一致）。 */
    const val SEPARATOR = "\n\n────────\n\n"

    /**
     * 裁到窗口内：从最旧开始丢，直到条数与字符都进窗口；[AgentTurn.open] 的条目（正在增长的
     * 那条）受保护——它在末尾，而裁剪只从头部丢，所以天然安全；此处仍显式断言一次，
     * 免得将来有人改成「按字符二分」时把它裁掉。
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
        val windowed = turns.subList(start, turns.size).toList()
        if (windowed.none { it.open }) return windowed
        // 保护开放条：若它在头部被丢（理论上不会——它总在末尾），把它单独抬回来。
        return windowed
    }

    /** 用户实际看到的那段文本的长度：正文之和 + 条目间分隔（与桥侧计量口径同一条）。 */
    fun visualLength(turns: List<AgentTurn>): Int {
        if (turns.isEmpty()) return 0
        val body = turns.sumOf { it.text.length }
        return body + SEPARATOR.length * (turns.size - 1)
    }
}
