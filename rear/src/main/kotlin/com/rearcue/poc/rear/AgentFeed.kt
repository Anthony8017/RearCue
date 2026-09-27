package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent Mirror 进程内广播（spec 0010 / 票 #84）：与 [ChargingFeed] 同形——
 * app 层写、[RearDashboardActivity] 读，依赖方向仍是 app → rear（背屏界面不依赖 app 容器）。
 *
 * 三条流：
 * - [onScreen]：Agent Mirror 在屏面（core 的 `agentOnScreen` 投影——真 = 本界面的内容层）；
 * - [state]：镜像所示会话（core 的 `agentState` 投影：等确认插队 + 最近活跃，票 #83 仲裁），
 *   null = 无可显示会话（断连/空闲回落已由 core 决定，这里只跟投影走）；
 * - [pulseUntilMs]：等待确认的视觉强调截止（epoch ms，票 #85）——0 = 无进行中的强调。
 *
 * 写方是 app 层 `AppContainer.refresh()`：每次状态刷新按 core 投影重发（不漏发、不落旧值）。
 */
object AgentFeed {

    private val _onScreen = MutableStateFlow(false)

    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    private val _state = MutableStateFlow<AgentSessionState?>(null)

    val state: StateFlow<AgentSessionState?> = _state.asStateFlow()

    private val _pulseUntilMs = MutableStateFlow(0L)

    val pulseUntilMs: StateFlow<Long> = _pulseUntilMs.asStateFlow()

    fun publish(onScreen: Boolean, state: AgentSessionState?) {
        _onScreen.value = onScreen
        _state.value = state
    }

    fun publishPulse(untilMs: Long) {
        _pulseUntilMs.value = untilMs
    }
}
