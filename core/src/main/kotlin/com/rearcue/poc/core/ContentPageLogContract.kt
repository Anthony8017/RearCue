package com.rearcue.poc.core

/**
 * Content Page 日志锚词形契约（spec 0013 / 票 #134）。
 *
 * 词形供 tools/ex 实机验收链读取，**byte 不可改**；page ∈ {notification, agent}，
 * ms 为整数毫秒。core 打 reset/toggle/fallback/wfa enter/wfa exit/recover；rear 打 crossfade
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

    /** 链路恢复回 Agent 页（票 #163）：断→通边沿且 Agent 有内容时打，page 恒为 agent。 */
    const val RECOVER = "content page recover <page>"

    /**
     * 切页被拒（票 #171 返修）：点按收到了，但目标页**没有内容**所以不切——
     * `content page toggle-rejected <page>`，page 是那个被拒的目标页。
     *
     * 为什么要单独一条锚：这条拒绝路径过去是**完全静默**的。机主点背屏空白处没反应时，
     * 日志里只有 `rear-tap received` + `content-page-toggle`，看不出"是点按丢了"还是
     * "切页被规则挡了"（2026-09-30 实测：Agent 页空的时候点半天没反应，就是这条）。
     * 词形与既有锚同构，tools/ex 验收链可按前缀读；**不改变任何行为**，只是让拒绝可观测。
     */
    const val TOGGLE_REJECTED = "content page toggle-rejected <page>"
    const val CROSSFADE_START = "content page crossfade start show=<page>"
    const val CROSSFADE_DONE = "content page crossfade done show=<page> durationMs=<ms>"

    const val CONTRACT = RESET + "; " + TOGGLE + "; " + FALLBACK + "; " + WFA_ENTER + "; " +
        WFA_EXIT + "; " + RECOVER + "; " + TOGGLE_REJECTED + "; " + CROSSFADE_START + "; " +
        CROSSFADE_DONE

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

    fun recover(page: ContentPage): String = render(RECOVER, page)

    fun toggleRejected(page: ContentPage): String = render(TOGGLE_REJECTED, page)

    fun crossfadeStart(page: ContentPage): String = render(CROSSFADE_START, page)

    fun crossfadeDone(page: ContentPage, durationMs: Long): String =
        render(CROSSFADE_DONE, page).replace("<ms>", durationMs.toString())
}
