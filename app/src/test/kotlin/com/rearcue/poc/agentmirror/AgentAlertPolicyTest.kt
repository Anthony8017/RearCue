package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent Alert 提醒判定判例（spec 0018-3 / 票 #173）：只测外部行为——
 * 状态跃迁进、提醒种类/是否提醒出；不测簿记实现细节。
 */
class AgentAlertPolicyTest {
    @Test fun `恢复镜像不补旧错误 但当前等待与之后新错误照常提醒`() {
        val tracker = AgentAlertTracker()
        assertNull(tracker.onSessionState("old", AgentStatus.ERROR, now = 1, replay = true))
        assertNull(tracker.onSessionState("old", AgentStatus.ERROR, now = 2))
        assertEquals(AgentAlertKind.WAITING, tracker.onSessionState("waiting", AgentStatus.WAITING_FOR_APPROVAL, now = 3, replay = true))
        tracker.onSessionState("old", AgentStatus.WORKING, now = 4)
        assertEquals(AgentAlertKind.ERROR, tracker.onSessionState("old", AgentStatus.ERROR, now = 5))
    }

    // ---------- 三类触发（kindFor） ----------

    @Test
    fun `进入等确认触发等你确认`() {
        assertEquals(AgentAlertKind.WAITING, AgentAlertPolicy.kindFor(AgentStatus.IDLE, AgentStatus.WAITING_FOR_APPROVAL))
        assertEquals(AgentAlertKind.WAITING, AgentAlertPolicy.kindFor(AgentStatus.WORKING, AgentStatus.WAITING_FOR_APPROVAL))
        // 首次见到即等待：它此刻正需要你，照提醒（重启后重见等待会话同口径）
        assertEquals(AgentAlertKind.WAITING, AgentAlertPolicy.kindFor(null, AgentStatus.WAITING_FOR_APPROVAL))
    }

    @Test
    fun `工作中到空闲触发任务干完`() {
        assertEquals(AgentAlertKind.DONE, AgentAlertPolicy.kindFor(AgentStatus.WORKING, AgentStatus.IDLE))
    }

    @Test
    fun `进入出错触发出错`() {
        assertEquals(AgentAlertKind.ERROR, AgentAlertPolicy.kindFor(AgentStatus.WORKING, AgentStatus.ERROR))
        assertEquals(AgentAlertKind.ERROR, AgentAlertPolicy.kindFor(AgentStatus.IDLE, AgentStatus.ERROR))
        assertEquals(AgentAlertKind.ERROR, AgentAlertPolicy.kindFor(AgentStatus.WAITING_FOR_APPROVAL, AgentStatus.ERROR))
        assertEquals(AgentAlertKind.ERROR, AgentAlertPolicy.kindFor(null, AgentStatus.ERROR))
    }

    // ---------- 三类之外一律不提醒 ----------

    @Test
    fun `工作中实时输出空闲停留一律不提醒`() {
        // 开工（实时输出的每一条都停留在工作态）
        assertNull(AgentAlertPolicy.kindFor(AgentStatus.IDLE, AgentStatus.WORKING))
        assertNull(AgentAlertPolicy.kindFor(AgentStatus.WORKING, AgentStatus.WORKING))
        assertNull(AgentAlertPolicy.kindFor(null, AgentStatus.WORKING))
        // 空闲停留
        assertNull(AgentAlertPolicy.kindFor(AgentStatus.IDLE, AgentStatus.IDLE))
        assertNull(AgentAlertPolicy.kindFor(null, AgentStatus.IDLE)) // 首见空闲不算「干完」——没干过
        // 等待停留（同一等待帧重复到达）
        assertNull(AgentAlertPolicy.kindFor(AgentStatus.WAITING_FOR_APPROVAL, AgentStatus.WAITING_FOR_APPROVAL))
    }

    @Test
    fun `处理完与出错恢复不提醒`() {
        // 处理完等待（自己刚点完，不需要提醒自己）
        assertNull(AgentAlertPolicy.kindFor(AgentStatus.WAITING_FOR_APPROVAL, AgentStatus.IDLE))
        assertNull(AgentAlertPolicy.kindFor(AgentStatus.WAITING_FOR_APPROVAL, AgentStatus.WORKING))
        // 出错恢复
        assertNull(AgentAlertPolicy.kindFor(AgentStatus.ERROR, AgentStatus.IDLE))
        assertNull(AgentAlertPolicy.kindFor(AgentStatus.ERROR, AgentStatus.WORKING))
        assertNull(AgentAlertPolicy.kindFor(AgentStatus.ERROR, AgentStatus.ERROR))
    }

    // ---------- 冷却 ----------

    @Test
    fun `冷却窗常量判例锁死六十秒`() {
        // 数值判例（review 2026-09-30）：改常量必破本判例，与 AgentApproveParamsTest 锁几何数值同惯例。
        assertEquals(60_000L, AgentAlertPolicy.COOLDOWN_MS)
    }

    @Test
    fun `冷却窗内压掉窗外放行`() {
        // 边界判例直接写毫秒值（不引用常量换算），与上面的常量判例一起把 60s 钉死。
        assertTrue(AgentAlertPolicy.inCooldown(lastFiredAt = 1_000L, now = 1_000L))
        assertTrue(AgentAlertPolicy.inCooldown(lastFiredAt = 1_000L, now = 60_999L))
        // 恰到窗边放行（去重按「窗内」算，不吞下一次真事件）
        assertFalse(AgentAlertPolicy.inCooldown(lastFiredAt = 1_000L, now = 61_000L))
        assertFalse(AgentAlertPolicy.inCooldown(lastFiredAt = null, now = 1L))
    }

    // ---------- 正文退化 ----------

    @Test
    fun `正文是会话名加摘要摘要缺失退化为会话名`() {
        assertEquals("甲 · 想修改 xx 文件", AgentAlertPolicy.contentLine("想修改 xx 文件", "甲"))
        assertEquals("甲", AgentAlertPolicy.contentLine(null, "甲"))
        assertEquals("甲", AgentAlertPolicy.contentLine("   ", "甲"))
    }

    // ---------- 序列行为（簿记走纯判定） ----------

    @Test
    fun `干完连发被冷却压住`() {
        val tracker = AgentAlertTracker()
        tracker.onSessionState("s", AgentStatus.WORKING, now = 0L)
        assertEquals(AgentAlertKind.DONE, tracker.onSessionState("s", AgentStatus.IDLE, now = 100L))
        // 冷却窗内的第二轮干完压掉
        tracker.onSessionState("s", AgentStatus.WORKING, now = 200L)
        assertNull(tracker.onSessionState("s", AgentStatus.IDLE, now = 300L))
        // 窗外放行
        tracker.onSessionState("s", AgentStatus.WORKING, now = 400L)
        assertEquals(
            AgentAlertKind.DONE,
            tracker.onSessionState("s", AgentStatus.IDLE, now = 60_400L),
        )
    }

    @Test
    fun `同类冷却不压异类`() {
        val tracker = AgentAlertTracker()
        tracker.onSessionState("s", AgentStatus.WORKING, now = 0L)
        assertEquals(AgentAlertKind.DONE, tracker.onSessionState("s", AgentStatus.IDLE, now = 10L))
        // 冷却窗内换类照提醒（它需要你，压掉就误事）
        assertEquals(AgentAlertKind.WAITING, tracker.onSessionState("s", AgentStatus.WAITING_FOR_APPROVAL, now = 20L))
    }

    @Test
    fun `多会话并行各自记账`() {
        val tracker = AgentAlertTracker()
        assertEquals(AgentAlertKind.WAITING, tracker.onSessionState("a", AgentStatus.WAITING_FOR_APPROVAL, now = 10L))
        assertEquals(AgentAlertKind.WAITING, tracker.onSessionState("b", AgentStatus.WAITING_FOR_APPROVAL, now = 10L))
        // 同一会话的重复等待帧不重复提醒
        assertNull(tracker.onSessionState("a", AgentStatus.WAITING_FOR_APPROVAL, now = 20L))
    }

    @Test
    fun `同态重复到达不提醒`() {
        val tracker = AgentAlertTracker()
        tracker.onSessionState("s", AgentStatus.WORKING, now = 0L)
        assertNull(tracker.onSessionState("s", AgentStatus.WORKING, now = 100L))
        assertNull(tracker.onSessionState("s", AgentStatus.WORKING, now = 200L))
    }

    @Test
    fun `出错触发与冷却`() {
        val tracker = AgentAlertTracker()
        assertEquals(AgentAlertKind.ERROR, tracker.onSessionState("s", AgentStatus.ERROR, now = 0L))
        assertNull(tracker.onSessionState("s", AgentStatus.ERROR, now = 10L)) // 同态重复
        tracker.onSessionState("s", AgentStatus.IDLE, now = 20L) // 恢复
        assertNull(tracker.onSessionState("s", AgentStatus.ERROR, now = 30L)) // 冷却窗内再错压掉
        tracker.onSessionState("s", AgentStatus.IDLE, now = 40L)
        assertEquals(
            AgentAlertKind.ERROR,
            tracker.onSessionState("s", AgentStatus.ERROR, now = 60_040L),
        )
    }

    @Test
    fun `离册忘账重进按首见判定`() {
        val tracker = AgentAlertTracker()
        tracker.onSessionState("s", AgentStatus.WORKING, now = 0L)
        assertEquals(AgentAlertKind.DONE, tracker.onSessionState("s", AgentStatus.IDLE, now = 10L))
        tracker.forget("s")
        // 重进首见即等待：再提醒（冷却不陈年跨册）
        assertEquals(AgentAlertKind.WAITING, tracker.onSessionState("s", AgentStatus.WAITING_FOR_APPROVAL, now = 20L))
    }

    @Test
    fun `对账收口只留在册`() {
        val tracker = AgentAlertTracker()
        tracker.onSessionState("keep", AgentStatus.WORKING, now = 0L)
        tracker.onSessionState("gone", AgentStatus.WORKING, now = 0L)
        tracker.retain(setOf("keep"))
        // "gone" 离册清账，重进按首见判定（冷却不跨册）
        assertEquals(AgentAlertKind.ERROR, tracker.onSessionState("gone", AgentStatus.ERROR, now = 1L))
        // "keep" 的账还在：工作中到空闲正常按冷却判
        assertEquals(AgentAlertKind.DONE, tracker.onSessionState("keep", AgentStatus.IDLE, now = 2L))
    }

    // ---------- 开关默认档（与持久化缺键档同源） ----------

    @Test
    fun `提醒两开关默认开`() {
        assertTrue(AgentMirrorSettingsStore.ALERT_ENABLED_DEFAULT)
        assertTrue(AgentMirrorSettingsStore.ALERT_VIBRATE_DEFAULT)
    }
}
