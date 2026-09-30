package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.SourceCapabilities

/**
 * Remote Approval 的批准入口判定（spec 0018-4 / ADR 0009）：**纯 Kotlin、零 Android 依赖**，
 * 与 [AgentAlertPolicy] 同一判例惯例（JVM 单测锁死）。
 *
 * 口径（票面 AC4/AC5）：
 * - 批准入门只认两件事：会话**正在等确认** ∧ 来源**声明了 approve**（[SourceCapabilities]）；
 *   只提醒来源（没声明 approve，如 codex/zcode）**不显示任何批准入口**。
 * - **调试旁路的伪会话恒可批准**（AC4：伪来源必须声明 approve 以便验收链可跑）——
 *   它的「来源能力」就是这条内置声明。
 * - 自由文字输入永不存在于入口形态：提问类等待＝选项点选（[AgentSessionState.pendingOptions]），
 *   确认类等待＝同意/拒绝，两者之外没有第三种应答面（ADR 0009 红线）。
 */
object AgentApprovePolicy {

    /** 调试旁路伪会话的键（与 [com.rearcue.poc.AppContainer] 的 DEBUG_SESSION_ID 同源）。 */
    const val DEBUG_SESSION_ID = "debug"

    /** 该会话是否调试旁路的伪会话（伪来源恒可批准）。 */
    fun isDebugSession(sessionId: String): Boolean = sessionId == DEBUG_SESSION_ID

    /** 批准入门（唯一判定出口）：等待中 ∧（伪会话 ∨ 来源声明 approve）。 */
    fun canApprove(session: AgentSessionState, capabilities: SourceCapabilities): Boolean {
        if (session.status != AgentStatus.WAITING_FOR_APPROVAL) return false
        return isDebugSession(session.sessionId) || capabilities.can(session.source, SourceCapabilities.APPROVE)
    }

    /** 主屏「待批准」列表：给定在册集里可批准的等待会话（入口显隐的唯一出口）。 */
    fun visibleApprovals(
        roster: List<AgentSessionState>,
        capabilities: SourceCapabilities,
    ): List<AgentSessionState> = roster.filter { canApprove(it, capabilities) }

    /** 提问类等待（有选项＝点选项作答）还是确认类（同意/拒绝）：入口形态由此分流。 */
    fun isQuestion(session: AgentSessionState): Boolean = session.pendingOptions.isNotEmpty()
}
