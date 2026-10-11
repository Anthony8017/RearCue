package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentStatus

/**
 * Agent Alert 提醒判定（spec 0018-3 / CONTEXT.md「Agent Alert」）：会话状态变化 →
 * 提醒种类（等你确认 / 任务干完 / 出错）或不提醒。**纯 Kotlin、零 Android 依赖**——
 * 落位在会话状态归一/仲裁缝（与 [AgentStateLogic] 同一判例惯例，JVM 单测锁死）。
 *
 * 判定口径（机主 2026-09-30 定案）：
 * - 仅三类触发：**等你确认**（进入等待）、**任务干完**（工作中 → 空闲）、**出错**（进入出错）；
 * - 工作中、实时输出（同态重复到达）、空闲停留一律不提醒；
 * - 同一会话同类提醒**去重＋冷却**（[COOLDOWN_MS]），多回合连发/多会话并行不轰炸。
 */
enum class AgentAlertKind {
    /** 等你确认：进入 Waiting-for-Approval。 */
    WAITING,

    /** 任务干完：工作中 → 空闲（回合结束）。 */
    DONE,

    /** 出错：来源报会话级失败/崩溃（[AgentStatus.ERROR]）。 */
    ERROR,
}

/** 提醒判定的纯函数面（全部无状态；簿记在 [AgentAlertTracker]）。 */
object AgentAlertPolicy {

    /**
     * 同一会话同类提醒的冷却窗：窗内的重复触发压掉（去重）。取 60s——
     * 30s 冷却作基础防抖，回合级「干完」连发更密故加倍；
     * 判例锁死这个数（改数必须改判例）。
     */
    const val COOLDOWN_MS = 60_000L

    /**
     * 状态跃迁 → 提醒种类（唯一判定出口）：
     * - 任何非等待 → 等待 ⇒ [AgentAlertKind.WAITING]（含首次见到即等待——它此刻正需要你）；
     * - 工作中 → 空闲 ⇒ [AgentAlertKind.DONE]（首见空闲不算「干完」，没干过）；
     * - 任何非出错 → 出错 ⇒ [AgentAlertKind.ERROR]；
     * 其余跃迁（工作中/实时输出/空闲停留、出错恢复、处理完等待）一律 null。
     */
    fun kindFor(prev: AgentStatus?, next: AgentStatus): AgentAlertKind? = when {
        next == AgentStatus.WAITING_FOR_APPROVAL && prev != AgentStatus.WAITING_FOR_APPROVAL ->
            AgentAlertKind.WAITING
        prev == AgentStatus.WORKING && next == AgentStatus.IDLE ->
            AgentAlertKind.DONE
        next == AgentStatus.ERROR && prev != AgentStatus.ERROR ->
            AgentAlertKind.ERROR
        else -> null
    }

    /** 冷却判定：距同会话同类上次提醒不足 [COOLDOWN_MS] 即压掉；null = 从未发过。 */
    fun inCooldown(lastFiredAt: Long?, now: Long): Boolean =
        lastFiredAt != null && now - lastFiredAt < COOLDOWN_MS

    /**
     * 通知正文（会话标识 ＋ 一句摘要）：摘要缺失/空白**退化为会话名**——
     * 事件类型在通知标题，整体即「会话名＋事件类型」（AC 的退化口径）。
     * 任何情况下不带输出原文（锁屏可见性取舍，机主定夺）。
     */
    fun contentLine(summary: String?, name: String): String {
        val trimmed = summary?.trim()?.takeIf { it.isNotEmpty() } ?: return name
        return "$name · $trimmed"
    }
}

/**
 * 提醒的序列簿记（spec 0018-3）：逐条会话状态进、提醒种类出。**所有判定走
 * [AgentAlertPolicy] 纯函数**，本类只记两笔账（各会话上一状态、各会话同类上次提醒时刻）；
 * 重启即清账——重见「等待中」的会话会再提醒一次（它仍需要你，符合产品口径）。
 */
class AgentAlertTracker {

    private val lastStatus = mutableMapOf<String, AgentStatus>()
    private val lastFiredAt = mutableMapOf<String, MutableMap<AgentAlertKind, Long>>()
    private val lastQuestions = mutableMapOf<String, Set<String>>()

    /** 按题目身份去重；普通工作增量不重新叫人，全部解除时撤回旧问题通知。 */
    fun onQuestions(sessionId: String, ids: Set<String>): QuestionAlertChange {
        val previous = lastQuestions.put(sessionId, ids).orEmpty()
        return when {
            (ids - previous).isNotEmpty() -> QuestionAlertChange.NEW
            previous.isNotEmpty() && ids.isEmpty() -> QuestionAlertChange.CLEARED
            else -> QuestionAlertChange.NONE
        }
    }

    /**
     * 一条会话状态到达（同态重复到达也进）：返回该触发的提醒种类，null = 不提醒。
     * 冷却压掉时**不返回**种类，也不重复计时（冷却从最近一次实发时刻起算）。
     */
    fun onSessionState(sessionId: String, status: AgentStatus, now: Long, replay: Boolean = false): AgentAlertKind? {
        val prev = lastStatus.put(sessionId, status)
        val kind = AgentAlertPolicy.kindFor(prev, status) ?: return null
        if (replay && kind != AgentAlertKind.WAITING) return null
        val fired = lastFiredAt.getOrPut(sessionId) { mutableMapOf() }
        if (AgentAlertPolicy.inCooldown(fired[kind], now)) return null
        fired[kind] = now
        return kind
    }

    /** 会话离册（清锁对账）时忘掉它的账：重进按首见判定，不留陈年冷却。 */
    fun forget(sessionId: String) {
        lastQuestions.remove(sessionId)
        lastStatus.remove(sessionId)
        lastFiredAt.remove(sessionId)
    }

    /** 离册清账的对账口径：只留还在册的会话（与 [forget] 同语义，按在册集整批收口）。 */
    fun retain(sessionIds: Set<String>) {
        lastQuestions.keys.retainAll(sessionIds)
        lastStatus.keys.retainAll(sessionIds)
        lastFiredAt.keys.retainAll(sessionIds)
    }
}

enum class QuestionAlertChange { NEW, CLEARED, NONE }
