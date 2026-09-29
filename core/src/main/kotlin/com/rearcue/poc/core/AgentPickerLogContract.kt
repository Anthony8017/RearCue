package com.rearcue.poc.core

/**
 * 背屏会话选择器日志锚词形契约（spec 0016 / 票 #156，沿 [ContentPageLogContract] 惯例）。
 *
 * 词形供 tools/ex 实机验收链读取，**byte 不可改**：core 打 open/close（打开与关闭都收口在
 * 状态机），app 接线打 select（选定即锁，走 `setSessionLock` 单入口）。
 * `<reason>` ∈ {toggle, wfa, page, select}——再点标识行 / 等确认插队 / 离开 Agent 页 / 选定条目；
 * `<sessionId>` 选定会话键，选「自动」档时为 `auto`。
 */
object AgentPickerLogContract {
    const val OPEN = "agent picker open"
    const val CLOSE = "agent picker close <reason>"
    const val SELECT = "agent picker select <sessionId>"

    /** 关闭理由：再点标识行 / 点列表外（同一个「关」的用户动作）。 */
    const val REASON_TOGGLE = "toggle"

    /** 关闭理由：有会话进入等待确认——插队显示优先，列表让位。 */
    const val REASON_WFA = "wfa"

    /** 关闭理由：当前内容页不再是 Agent 页（切页/兜底/退屏/重投）。 */
    const val REASON_PAGE = "page"

    /** 关闭理由：在列表里选定了一条（选定即锁 + 关闭 + 回实时跟随）。 */
    const val REASON_SELECT = "select"

    /** 选定「自动」档时的 `<sessionId>` 占位词。 */
    const val SELECT_AUTO = "auto"

    const val CONTRACT = "$OPEN; $CLOSE; $SELECT"

    fun open(): String = OPEN

    fun close(reason: String): String = CLOSE.replace("<reason>", reason)

    fun select(sessionId: String): String = SELECT.replace("<sessionId>", sessionId)
}
