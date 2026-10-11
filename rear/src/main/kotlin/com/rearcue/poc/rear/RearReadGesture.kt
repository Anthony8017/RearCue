package com.rearcue.poc.rear

/**
 * 背屏把哪一下算作看过当前对话（ADR 0026）。
 * 只判断手势种类；会话是哪一条、切页有没有成功，由调用方拿动作前的画面决定。
 */
object RearReadGesture {
    enum class Kind {
        /** 从列表点进某一条。 */
        PICK_SESSION,
        /** 点「自动」档。落到哪条都不算看过。 */
        PICK_AUTO,
        /** 点标题，或点空白让当前对话盖上来。 */
        SESSION_LINE,
        /** 对话正盖住列表时点正文。 */
        BODY_TAP,
        /** 对话正盖住列表时用手滑动正文。 */
        BODY_SCROLL,
        /** 对话正盖住列表时切到通知页。 */
        LEAVE_TO_NOTIFICATION,
        /** 只滑列表。 */
        LIST_SCROLL,
        /** 列表上点「自动」右侧，直接去通知页。 */
        NOTIFICATION_SHORTCUT,
        /** 手机自己翻页、播报、自动滚动。 */
        AUTO_REVEAL,
    }

    fun marks(kind: Kind, conversationVisible: Boolean): Boolean = when (kind) {
        Kind.PICK_SESSION, Kind.SESSION_LINE -> true
        Kind.BODY_TAP, Kind.BODY_SCROLL, Kind.LEAVE_TO_NOTIFICATION -> conversationVisible
        Kind.PICK_AUTO, Kind.LIST_SCROLL, Kind.NOTIFICATION_SHORTCUT, Kind.AUTO_REVEAL -> false
    }
}
