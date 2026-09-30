package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSources

/**
 * 归档同步消失的确定性时延预算（spec 0023 / 票 #231）。
 *
 * 这里只约束“来源已经改变归档状态”到“手机收到事实”的检测/传输预算；
 * 断线重连另按恢复连接后立即对账处理。ZCode 任务表没有推送归档事实，
 * 因此采用 1 秒轮询；剩余 1 秒留给响应、解析和派生，合计不超过 2 秒。
 */
object AgentArchiveDeliveryBudget {
    const val ZCODE_TASK_TABLE_POLL_MS = 1_000L
    const val ZCODE_DELIVERY_BUDGET_MS = 1_000L
    const val CODEX_SCAN_MS = 800L
    const val CLAUDE_SCAN_MS = 1_000L
    const val DSH_EVENT_MS = 0L
    const val NORMAL_USE_BUDGET_MS = 2_000L

    fun detectionBudgetMs(source: String): Long = when (source) {
        AgentSources.ZCODE -> ZCODE_TASK_TABLE_POLL_MS
        AgentSources.CODEX -> CODEX_SCAN_MS
        AgentSources.CLAUDE -> CLAUDE_SCAN_MS
        AgentSources.DSH -> DSH_EVENT_MS
        else -> NORMAL_USE_BUDGET_MS
    }

    fun deterministicDeliveryMs(source: String): Long =
        detectionBudgetMs(source) + if (source == AgentSources.ZCODE) ZCODE_DELIVERY_BUDGET_MS else 0L

    fun meetsNormalUseBudget(source: String): Boolean =
        deterministicDeliveryMs(source) <= NORMAL_USE_BUDGET_MS
}
