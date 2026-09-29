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
 * 会话选择器（spec 0016 / 票 #156）另走 [picker] / [pickerRows]：打开态是 core 的
 * `agentPicker` 投影（UI 不自行开关），条目是同一份列表投影（[AgentStateLogic.projectRoster]）
 * 在 app 层映射成的渲染行。
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

    private val _picker = MutableStateFlow(false)

    val picker: StateFlow<Boolean> = _picker.asStateFlow()

    private val _pickerRows = MutableStateFlow<List<AgentPickerRow>>(emptyList())

    val pickerRows: StateFlow<List<AgentPickerRow>> = _pickerRows.asStateFlow()

    fun publish(contentPage: ContentPage?, state: AgentSessionState?) {
        _contentPage.value = contentPage
        _state.value = state
    }

    fun publishPulse(untilMs: Long) {
        _pulseUntilMs.value = untilMs
    }

    /** 选择器投影（打开态 + 条目）：关着时条目仍照发——开列表不必再等一拍刷新。 */
    fun publishPicker(open: Boolean, rows: List<AgentPickerRow>) {
        _picker.value = open
        _pickerRows.value = rows
    }
}
