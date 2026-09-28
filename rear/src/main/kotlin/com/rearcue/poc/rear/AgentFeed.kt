package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.core.ContentPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent Mirror 进程内广播（spec 0010 / 票 #84）：与 [ChargingFeed] 同形——
 * app 层写、[RearDashboardActivity] 读，依赖方向仍是 app → rear（背屏界面不依赖 app 容器）。
 *
 * 三条流：
 * - [contentPage]：当前内容页（core 的 [com.rearcue.poc.core.DashboardCore.contentPage] 投影——
 *   null = Dashboard 不在屏；Agent 页时本界面显示 Agent Mirror）；
 * - [state]：镜像所示会话（core 的 `agentState` 投影：等确认插队 + 最近活跃，票 #83 仲裁），
 *   null = 无可显示会话（断连/空闲回落已由 core 决定，这里只跟投影走）；
 * - [pulseUntilMs]：等待确认的视觉强调截止（epoch ms，票 #85）——0 = 无进行中的强调。
 *
 * 写方是 app 层 `AppContainer.refresh()`：每次状态刷新按 core 投影重发（不漏发、不落旧值）。
 */
object AgentFeed {

    private val _contentPage = MutableStateFlow<ContentPage?>(null)

    val contentPage: StateFlow<ContentPage?> = _contentPage.asStateFlow()

    private val _state = MutableStateFlow<AgentSessionState?>(null)

    val state: StateFlow<AgentSessionState?> = _state.asStateFlow()

    private val _pulseUntilMs = MutableStateFlow(0L)

    val pulseUntilMs: StateFlow<Long> = _pulseUntilMs.asStateFlow()

    fun publish(contentPage: ContentPage?, state: AgentSessionState?) {
        _contentPage.value = contentPage
        _state.value = state
    }

    fun publishPulse(untilMs: Long) {
        _pulseUntilMs.value = untilMs
    }
}
