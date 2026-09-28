package com.rearcue.poc.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Content Page 日志锚词形判例（spec 0013 / 票 #134）：契约字符串与 crossfade 生成器
 * 均按 byte 冻结；既有日志契约一并锁定，防止文档收口时误改。
 */
class ContentPageLogContractTest {

    @Test
    fun `内容页完整契约词形冻结`() {
        assertEquals(
            "content page reset <page>; content page toggle <page>; " +
                "content page fallback <page>; content page wfa enter <page>; " +
                "content page wfa exit <page>; content page crossfade start show=<page>; " +
                "content page crossfade done show=<page> durationMs=<ms>",
            DashboardCore.LOG_CONTENT_PAGE_CONTRACT,
        )
        assertEquals(ContentPageLogContract.CONTRACT, DashboardCore.LOG_CONTENT_PAGE_CONTRACT)
    }

    @Test
    fun `crossfade 锚词按 ContentPage 和 durationMs 成型`() {
        assertEquals(
            "content page crossfade start show=notification",
            ContentPageLogContract.crossfadeStart(ContentPage.NOTIFICATION),
        )
        assertEquals(
            "content page crossfade start show=agent",
            ContentPageLogContract.crossfadeStart(ContentPage.AGENT),
        )
        assertEquals(
            "content page crossfade done show=notification durationMs=180",
            ContentPageLogContract.crossfadeDone(ContentPage.NOTIFICATION, durationMs = 180L),
        )
        assertEquals(
            "content page crossfade done show=agent durationMs=201",
            ContentPageLogContract.crossfadeDone(ContentPage.AGENT, durationMs = 201L),
        )
    }

    @Test
    fun `core 锚词按 ContentPage 成型`() {
        assertEquals("content page reset notification", ContentPageLogContract.reset(ContentPage.NOTIFICATION))
        assertEquals("content page toggle agent", ContentPageLogContract.toggle(ContentPage.AGENT))
        assertEquals("content page fallback agent", ContentPageLogContract.fallback(ContentPage.AGENT))
        assertEquals("content page wfa enter notification", ContentPageLogContract.wfaEnter(ContentPage.NOTIFICATION))
        assertEquals("content page wfa exit notification", ContentPageLogContract.wfaExit(ContentPage.NOTIFICATION))
    }

    @Test
    fun `既有日志契约词形不回归`() {
        assertEquals(
            "highlight breath start|end; highlight add <pkg>; highlight remove <pkg>",
            DashboardCore.LOG_HIGHLIGHT_CONTRACT,
        )
        assertEquals(
            "detail open <pkg>; detail close <pkg>",
            DashboardCore.LOG_DETAIL_CONTRACT,
        )
        assertEquals(
            "agent pulse start; agent pulse end",
            DashboardCore.LOG_AGENT_PULSE_CONTRACT,
        )
        assertEquals(
            "session lock cleared <sessionId>",
            DashboardCore.LOG_SESSION_LOCK_CONTRACT,
        )
    }
}
