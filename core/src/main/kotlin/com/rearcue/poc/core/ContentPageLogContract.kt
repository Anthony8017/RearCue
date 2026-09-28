package com.rearcue.poc.core

/**
 * Content Page 日志锚词形契约（spec 0013 / 票 #134）。
 *
 * 词形供 tools/ex 实机验收链读取，**byte 不可改**；page ∈ {notification, agent}，
 * ms 为整数毫秒。core 打 reset/toggle/fallback/wfa enter/wfa exit；rear 打 crossfade
 * start/done（见 [DashboardCore.LOG_CONTENT_PAGE_CONTRACT]）。
 *
 * 评审修复：页面名与模板渲染统一吃 [ContentPage]，core/rear 不再各维护一份布尔映射。
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

    fun pageName(page: ContentPage): String = when (page) {
        ContentPage.NOTIFICATION -> PAGE_NOTIFICATION
        ContentPage.AGENT -> PAGE_AGENT
    }

    private fun render(template: String, page: ContentPage): String =
        template.replace("<page>", pageName(page))

    fun reset(page: ContentPage): String = render(RESET, page)

    fun toggle(page: ContentPage): String = render(TOGGLE, page)

    fun fallback(page: ContentPage): String = render(FALLBACK, page)

    fun wfaEnter(page: ContentPage): String = render(WFA_ENTER, page)

    fun wfaExit(page: ContentPage): String = render(WFA_EXIT, page)

    fun crossfadeStart(page: ContentPage): String = render(CROSSFADE_START, page)

    fun crossfadeDone(page: ContentPage, durationMs: Long): String =
        render(CROSSFADE_DONE, page).replace("<ms>", durationMs.toString())
}
