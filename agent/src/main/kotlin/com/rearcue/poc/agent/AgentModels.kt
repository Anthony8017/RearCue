package com.rearcue.poc.agent

/**
 * Agent Mirror 的会话状态模型（spec 0010 / CONTEXT.md「Agent Mirror」「Waiting-for-Approval」）。
 *
 * 纯 Kotlin：决策在 [:core] 的 DashboardCore，本模块只产出事实；渲染在 [:rear]。
 */

enum class AgentStatus {
    /** 回合进行中（工具运行或流式输出）。 */
    WORKING,

    /** agent 停下等待用户批准/输入——背屏永远优先插队的状态。 */
    WAITING_FOR_APPROVAL,

    /** 无进行中回合且无等待。 */
    IDLE,
}

/** 单个会话的镜像事实。多会话并存时的选择（最近活跃、等确认插队）是 [:core] 的仲裁。 */
data class AgentSessionState(
    val sessionId: String,
    val workspace: String? = null,
    val status: AgentStatus = AgentStatus.IDLE,
    /** 最近运行中工具的单行摘要，如「edit src/App.kt」。 */
    val currentAction: String? = null,
    /** 最新一条助手回复原文（不打码，spec 定案）。 */
    val latestReply: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * 会话行（Conversation V4 snapshot/delta 的归一化中间形态）。
 *
 * 行模型按 ZCode part 结构固化（spec 0010 调研：type = text|reasoning|tool|step-start|step-finish；
 * tool 的 state.status = inputStreaming|pendingApproval|running|success|error|cancelled）。
 * 精确 wire 字段待 T1 phase B 实测回填——[AgentRowCodec] 是唯一调整点。
 */
data class AgentRow(
    val rowId: Long,
    val type: String,
    val role: String? = null,
    val text: String? = null,
    val toolName: String? = null,
    val toolStatus: String? = null,
    val toolInputSummary: String? = null,
)

/** 当前动作摘要的上限：背屏一行，超长截断。 */
const val ACTION_SUMMARY_MAX_CHARS = 120
