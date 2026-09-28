package com.rearcue.poc.core

/**
 * Content Page 日志锚词形契约（spec 0013 / 票 #134）。
 *
 * 词形供 tools/ex 实机验收链读取，**byte 不可改**；page ∈ {notification, agent}，
 * ms 为整数毫秒。core 打 reset/toggle/fallback/wfa enter/wfa exit；rear 打 crossfade
 * start/done（见 [DashboardCore.LOG_CONTENT_PAGE_CONTRACT]）。
 */
object ContentPageLogContract {
    const val PAGE_NOTIFICATION = "notification"
    const val PAGE_AGENT = "agent"

    const val RESET = "content page reset <page>"
    const val TOGGLE = "content page toggle <page>"
    const val FALLBACK = "content page fallback <page>"
    const val WFA_ENTER = "content page wfa enter <page>"
    const val WFA_EXIT = "content page wfa exit <page>"
    const val CROSSFADE_START = "content page crossfade start show=<page>"
    const val CROSSFADE_DONE = "content page crossfade done show=<page> durationMs=<ms>"

    const val CONTRACT = RESET + "; " + TOGGLE + "; " + FALLBACK + "; " + WFA_ENTER + "; " +
        WFA_EXIT + "; " + CROSSFADE_START + "; " + CROSSFADE_DONE

    fun pageName(showAgentPage: Boolean): String =
        if (showAgentPage) PAGE_AGENT else PAGE_NOTIFICATION

    fun crossfadeStart(showAgentPage: Boolean): String =
        "content page crossfade start show=" + pageName(showAgentPage)

    fun crossfadeDone(showAgentPage: Boolean, durationMs: Long): String =
        "content page crossfade done show=" + pageName(showAgentPage) + " durationMs=" + durationMs
}
