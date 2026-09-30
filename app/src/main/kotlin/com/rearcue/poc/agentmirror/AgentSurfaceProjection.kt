package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionState

/**
 * 主屏与背屏的共享投影缝（spec 0023 / 票 #238）。
 *
 * 这是两个真实消费入口的纯函数：
 * - 主屏读 [mainRoster]，作为 `AppState.agentRoster`；
 * - 背屏读 [rearRows]，作为会话选择器的行投影。
 * 两者都从同一个 [AgentArchiveTruth] 出发，但调用不同的入口，避免测试用同一函数自证一致。
 */
object AgentSurfaceProjection {
    data class Input(
        val truth: AgentArchiveTruth,
        val v4: AgentSessionState? = null,
        val indexWaiting: Set<String> = emptySet(),
        val lockMode: com.rearcue.poc.core.DashboardEvent.SessionLockMode =
            com.rearcue.poc.core.DashboardEvent.SessionLockMode.Auto,
    )

    /** 主屏 `AppState.agentRoster` 的真实派生入口。 */
    fun mainRoster(input: Input): List<AgentSessionState> =
        AgentStateLogic.mirrorRoster(input.truth, input.v4, input.indexWaiting)

    /** 背屏选择器行的真实派生入口；只做列表行映射，不重新筛选或排序。 */
    fun rearRows(input: Input): List<AgentListRow> =
        AgentStateLogic.projectRoster(mainRoster(input), input.lockMode)
}
