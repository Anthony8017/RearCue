package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.SessionLockMode

/**
 * 会话列表与 Session Lock 当前档的纯逻辑（票 #104）：只吃数据回数据、零 Android 依赖——
 * JVM 单测直接跑（[com.rearcue.poc.RearCueApp] 里 `refresh` 等接线要 Context/Log，测不了）。
 *
 * 三个面：
 * - [merge]／[normalizeRoster]：状态归一——任务表无「等待确认」语义，等确认真检测只在 v4 帧；
 * - [sessionName]／[lockTargetName]／[selectedSessionId]：当前档派生——状态行文案与列表选中。
 *
 * 接线口径（票 #104）：列表数据 = `TaskListParser.parseAll` 原序，叠 [normalizeRoster]；
 * 写入口仍是 T1 的 [com.rearcue.poc.RearCueApp.setSessionLock]（事件进 core + 写盘）。
 */
object AgentStateLogic {

    /**
     * 单条状态归一（任务表 × v4 帧合并；原 `dispatchAgentMerged` 的内联口径抽出共用）：
     * 同一会话时 **等确认 > 任一来源工作中 > 空闲**；回复原文取 v4、当前动作取任务表标题；
     * 会话键不一致（任务刚切换、v4 尚未重订阅）时先用任务表；单边为 null 取另一边。
     */
    fun merge(task: AgentSessionState?, v4: AgentSessionState?): AgentSessionState? = when {
        task == null -> v4
        v4 == null -> task
        task.sessionId != v4.sessionId -> task
        else -> AgentSessionState(
            sessionId = task.sessionId,
            workspace = task.workspace,
            status = when {
                v4.status == AgentStatus.WAITING_FOR_APPROVAL -> AgentStatus.WAITING_FOR_APPROVAL
                task.status == AgentStatus.WORKING || v4.status == AgentStatus.WORKING -> AgentStatus.WORKING
                else -> AgentStatus.IDLE
            },
            currentAction = task.currentAction ?: v4.currentAction,
            latestReply = v4.latestReply,
            updatedAt = maxOf(task.updatedAt, v4.updatedAt),
        )
    }

    /**
     * 列表状态归一（票 #104）：任务表全量（`TaskListParser.parseAll` 原序、不重排）叠加
     * v4 帧的等确认真检测——v4 只订一个会话，故仅同会话那条可能被升级，其余逐字保留；
     * v4 无读数（未订阅／断连）即任务表原样。
     */
    fun normalizeRoster(roster: List<AgentSessionState>, v4: AgentSessionState?): List<AgentSessionState> {
        if (v4 == null) return roster
        return roster.map { entry ->
            if (entry.sessionId == v4.sessionId) merge(entry, v4) ?: entry else entry
        }
    }

    /**
     * 列表行会话名：工作区名优先，空/缺回退会话键（永不空串，行永远有标题可读）。
     * 与状态行 `链路 · workspace` 的取词同源。
     */
    fun sessionName(session: AgentSessionState): String =
        session.workspace?.takeIf { it.isNotBlank() } ?: session.sessionId

    /**
     * 当前档派生（票 #104 状态行）：null = 自动档；非空 = 锁定档要展示的会话名——
     * 在册取工作区名（缺名回退会话键），不在册回退会话键（存储首读到在册对账清锁之间的
     * 空窗仍显示「锁了谁」）。文案前缀（「已锁定·」）由 UI 拼，这里只出名字。
     */
    fun lockTargetName(mode: SessionLockMode, roster: List<AgentSessionState>): String? {
        val id = (mode as? SessionLockMode.Locked)?.sessionId ?: return null
        return roster.find { it.sessionId == id }?.let { sessionName(it) } ?: id
    }

    /** 列表选中派生：自动档回 null，锁定档回锁定会话键（各行选中判据）。 */
    fun selectedSessionId(mode: SessionLockMode): String? =
        (mode as? SessionLockMode.Locked)?.sessionId
}
