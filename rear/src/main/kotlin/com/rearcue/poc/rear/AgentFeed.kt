package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.core.ContentPage
import com.rearcue.poc.core.GlowBrightness
import com.rearcue.poc.core.MirrorTextSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent Mirror 进程内广播（spec 0010 / 票 #84）：与 [ChargingFeed] 同形——
 * app 层写、[RearDashboardActivity] 读，依赖方向仍是 app → rear（背屏界面不依赖 app 容器）。
 *
 * 主要投影：
 * - [pageSurface]：当前内容页 + 会话选择器打开态/条目**原子快照**（避免切页与列表撤挂出现一帧正文闪现）；
 * - [state]：镜像所示会话（core 的 `agentState` 投影：等确认插队 + 最近活跃，票 #83 仲裁），
 *   null = 无可显示会话（断连/空闲回落已由 core 决定，这里只跟投影走）；
 * - [pulseUntilMs]：等待确认的视觉强调截止（epoch ms，票 #85）——0 = 无进行中的强调。
 *
 * 会话选择器（spec 0016 / 票 #156）的打开态是 core 的 `agentPicker` 投影（UI 不自行开关），
 * 条目是同一份列表投影（[AgentStateLogic.projectRoster]）在 app 层映射成的渲染行。
 *
 * 写方是 app 层 `AppContainer.refresh()`：每次状态刷新按 core 投影重发（不漏发、不落旧值）。
 */
data class AgentPageSurface(
    val contentPage: ContentPage?,
    val pickerOpen: Boolean,
    val pickerRows: List<AgentPickerRow>,
)

object AgentFeed {

    private val _pageSurface = MutableStateFlow(
        AgentPageSurface(contentPage = null, pickerOpen = false, pickerRows = emptyList()),
    )

    val pageSurface: StateFlow<AgentPageSurface> = _pageSurface.asStateFlow()

    private val _state = MutableStateFlow<AgentSessionState?>(null)

    val state: StateFlow<AgentSessionState?> = _state.asStateFlow()

    /**
     * 当前显示会话的两行名（issue #213）：与 [state] 同点发布；断线空窗由 app 层缓存最后显示值，
     * 背屏只照单渲染，不自行回退 sessionId。
     */
    private val _display = MutableStateFlow<AgentSessionDisplay?>(null)

    val display: StateFlow<AgentSessionDisplay?> = _display.asStateFlow()

    private val _pulseUntilMs = MutableStateFlow(0L)

    val pulseUntilMs: StateFlow<Long> = _pulseUntilMs.asStateFlow()

    /**
     * PC 桥链路状态（票 #165）：与主屏设置页 / 主页概览**同一份事实**（app 层 `bridgeLinkStatus`），
     * 背屏只把它画成会话标识行旁的一个**非文字状态点**。
     */
    private val _link = MutableStateFlow(BridgeLinkStatus.DISABLED)

    val link: StateFlow<BridgeLinkStatus> = _link.asStateFlow()

    /**
     * Agent 页正文档位（spec 0017 / 票 #169）：与主屏首页 Agent 卡片的选中态**同一份事实**
     * （app 层 `core.mirrorTextSize`），背屏只把它交给 [AgentMirrorParams.reading] 换字号。
     * 改档即重发 → 在屏 Agent 页按新档重排（即时生效，不需要重新投送）。
     */
    private val _textSize = MutableStateFlow(MirrorTextSize.DEFAULT)

    val textSize: StateFlow<MirrorTextSize> = _textSize.asStateFlow()

    /**
     * 角部避让开关（spec 0019 / 票 #194）：与主屏 Agent 设置区的开关**同一份事实**
     * （app 层 `core.cornerAvoidanceEnabled`）。关＝贴满（默认）；开＝会话页正文逐行
     * 弧区避让、会话列表整列上下内缩。切换即重发 → 在屏 Agent 面即时重排（不需要重新投送）。
     */
    private val _cornerAvoidance = MutableStateFlow(false)

    val cornerAvoidance: StateFlow<Boolean> = _cornerAvoidance.asStateFlow()

    /**
     * 光带亮度倍率（spec 0021 修订 / 票 #214）：与主屏 Agent 设置区滑动条**同一份事实**
     * （app 层持久化偏好，[:core] [com.rearcue.poc.core.GlowBrightness] 范围钳制）。
     * 拖动即重发 → 在屏光带按新倍率点亮（即时生效，不需要重新投送）。
     */
    private val _glowBrightness = MutableStateFlow(GlowBrightness.DEFAULT)

    val glowBrightness: StateFlow<Float> = _glowBrightness.asStateFlow()

    /**
     * 批准浮层投影（spec 0018-5 / 票 #175）：非空＝当前镜像会话在等待确认且可批准（入口
     * 判定全在 app 层批准判定，背屏零决策）；null ＝ 无批准入口（背屏对批准点按不响应）。
     */
    private val _approve = MutableStateFlow<AgentApprovePrompt?>(null)

    val approve: StateFlow<AgentApprovePrompt?> = _approve.asStateFlow()

    /**
     * 批准动作的失败提示（spec 0018-5 AC3）：与主屏提示**同一份事实**（app 层一次提示、
     * 成功即清）；背屏只显示一句、不响不震。
     */
    private val _actionNote = MutableStateFlow<String?>(null)

    val actionNote: StateFlow<String?> = _actionNote.asStateFlow()

    fun publish(
        contentPage: ContentPage?,
        state: AgentSessionState?,
        display: AgentSessionDisplay? = null,
        pickerOpen: Boolean = false,
        pickerRows: List<AgentPickerRow> = emptyList(),
    ) {
        _pageSurface.value = AgentPageSurface(contentPage, pickerOpen, pickerRows)
        _state.value = state
        _display.value = display
    }

    fun publishPulse(untilMs: Long) {
        _pulseUntilMs.value = untilMs
    }

    /** PC 桥链路状态同点重发（票 #165）：背屏状态点的唯一数据源。 */
    fun publishLink(status: BridgeLinkStatus) {
        _link.value = status
    }

    /** 正文档位同点重发（spec 0017 / 票 #169）：背屏字号的唯一数据源。 */
    fun publishTextSize(size: MirrorTextSize) {
        _textSize.value = size
    }

    /** 角部避让同点重发（spec 0019 / 票 #194）：背屏贴缘/避让的唯一数据源。 */
    fun publishCornerAvoidance(enabled: Boolean) {
        _cornerAvoidance.value = enabled
    }

    /** 光带亮度倍率同点重发（spec 0021 修订 / 票 #214）：背屏光带亮度的唯一数据源。 */
    fun publishGlowBrightness(brightness: Float) {
        _glowBrightness.value = brightness
    }

    /** 批准浮层投影同点重发（spec 0018-5 / 票 #175）：入口判定与失败提示一并跟投影走。 */
    fun publishApprove(prompt: AgentApprovePrompt?, actionNote: String?) {
        _approve.value = prompt
        _actionNote.value = actionNote
    }
}
