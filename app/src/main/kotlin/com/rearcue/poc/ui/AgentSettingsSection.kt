package com.rearcue.poc.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.R
import com.rearcue.poc.agent.AgentApproveShape
import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.agent.BridgeRelayClient
import com.rearcue.poc.agent.SessionActionKind
import com.rearcue.poc.agent.SessionActionRequest
import com.rearcue.poc.agentmirror.AgentApprovePolicy
import com.rearcue.poc.agentmirror.AgentMirrorSettingsStore
import com.rearcue.poc.agentmirror.AgentStateLogic
import com.rearcue.poc.agentmirror.BridgeAddressProbe
import com.rearcue.poc.agentmirror.BridgeAddressSource
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import com.rearcue.poc.core.GlowBrightness
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.voice.OfflineVoiceStatus
import com.rearcue.poc.voice.VoiceBroadcastRuntimeState
import com.rearcue.poc.voice.VoiceBroadcastSettings
import com.rearcue.poc.voice.VoiceCatalog
import com.rearcue.poc.voice.VoiceEngine
import com.rearcue.poc.voice.VoiceOption
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTouch
import com.rearcue.poc.rear.AgentMirrorParams
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent 镜像区（#234 / ADR 0014）：PC 桥是 ZCode / Codex / Claude / DSH 四源唯一传输；
 * 这里只配置一次桥地址、看桥链路状态并管理统一会话列表，所有会话数据都来自 PC 桥。
 * 桥地址保存前走 [onBridgeAddressSave] 探一次 /health；会话列表锁定与背屏共用同一份偏好。
 */
@Composable
fun AgentSettingsSection(
    bridgeConfigured: Boolean,
    bridgeStatus: BridgeLinkStatus,
    bridgeClient: BridgeRelayClient? = null,
    enabled: Boolean,
    agentState: AgentSessionState?,
    /** 当前镜像会话的两行显示投影（issue #213）：状态行内联「标题 · 来源 · 目录」。 */
    agentDisplay: AgentSessionDisplay? = null,
    sessionLock: SessionLockMode,
    /** 锁定会话最后显示的两行（issue #213）：断线空窗沿用。 */
    agentLockedDisplay: AgentSessionDisplay? = null,
    roster: List<AgentSessionState>,
    onEnabledChange: (Boolean) -> Unit,
    onSessionLockChange: (SessionLockMode) -> Unit,
    /** 背屏正文档位（spec 0017 / 票 #169）：core 投影，选中态与背屏字号同一份事实。 */
    textSize: MirrorTextSize = MirrorTextSize.DEFAULT,
    onTextSizeChange: (MirrorTextSize) -> Unit = {},
    /** 光带亮度倍率（spec 0021 修订 / 票 #214）：滑动条值与背屏光带同一份事实；
     *  回调带 persist 旗标——拖动中 false（只生效）、松手 true（落盘）。 */
    glowBrightness: Float = AgentMirrorSettingsStore.GLOW_BRIGHTNESS_DEFAULT,
    onGlowBrightnessChange: (Float, Boolean) -> Unit = { _, _ -> },
    /** 角部避让开关（spec 0019 / 票 #194）：core 投影，与背屏贴缘/避让同一份事实；默认关＝贴满。 */
    cornerAvoidance: Boolean = AgentMirrorSettingsStore.CORNER_AVOIDANCE_DEFAULT,
    onCornerAvoidanceChange: (Boolean) -> Unit = {},
    /** 桥地址当前值（票 #171）：输入框回填面；未配置为空串。 */
    bridgeAddress: String = "",
    /** 桥地址来源与电脑最后推送时刻：这一行说清「谁写的、什么时候写的」。 */
    bridgeSource: BridgeAddressSource? = null,
    bridgePushedAt: Long? = null,
    /** 手填保存前的当场探测结果（探测中 / 探通 / 各种不通）。 */
    bridgeProbe: BridgeAddressProbe = BridgeAddressProbe.Idle,
    onBridgeAddressSave: (String) -> Unit = {},
    onBridgeAddressClear: () -> Unit = {},
        /** Agent 提醒两开关（spec 0018-3 / 票 #173）：默认档与持久化缺键档同源，选中即生效。 */
    alertEnabled: Boolean = AgentMirrorSettingsStore.ALERT_ENABLED_DEFAULT,
    alertVibrate: Boolean = AgentMirrorSettingsStore.ALERT_VIBRATE_DEFAULT,
    onAlertEnabledChange: (Boolean) -> Unit = {},
    onAlertVibrateChange: (Boolean) -> Unit = {},
    /** Voice Broadcast（spec 0022）：总开关、双引擎、音色与全局语速。 */
    voiceSettings: VoiceBroadcastSettings = VoiceBroadcastSettings(),
    voiceRuntime: VoiceBroadcastRuntimeState = VoiceBroadcastRuntimeState(),
    onVoiceEnabledChange: (Boolean, Boolean) -> Unit = { _, _ -> },
    onVoiceEngineChange: (VoiceEngine) -> Unit = {},
    onVoiceSpeedChange: (Float) -> Unit = {},
    onVoicePitchChange: (Float) -> Unit = {},
    onVoiceKokoroVoiceChange: (String) -> Unit = {},
    onVoiceSystemVoiceChange: (String) -> Unit = {},
    /** 待批准列表（spec 0018-4 / 票 #174）：等确认 ∧ 来源声明 approve 的会话才进（判定在
     *  [AgentApprovePolicy]，UI 零决策）；提问类按选项点选，确认类同意/拒绝——无自由文字入口。 */
    approvals: List<AgentSessionState> = emptyList(),
    /** 最近一次批准失败提示（AC3）：一次提示、不重试轰炸；成功即清。 */
    actionNote: String? = null,
    /** 远程批准开关（spec 0018 §五 / review 2026-09-30）：关＝三处批准入口全部不出现。 */
    approveEnabled: Boolean = AgentMirrorSettingsStore.APPROVE_ENABLED_DEFAULT,
    onApproveEnabledChange: (Boolean) -> Unit = {},
    onSendAction: (SessionActionRequest) -> Unit = {},
) {
    SettingsSectionCard(title = stringResource(R.string.settings_agent_title)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_agent_switch),
            description = stringResource(
                if (enabled) R.string.settings_agent_on else R.string.settings_agent_off,
            ),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )

        MirrorTextSizeRow(size = textSize, onSizeChange = onTextSizeChange)

        // 光带亮度（spec 0021 修订 / 票 #214）：倍率滑动条，拖动即时生效、松手落盘；
        // 与正文档位同处 Agent 呈现偏好区。
        GlowBrightnessRow(
            brightness = glowBrightness,
            onBrightnessChange = onGlowBrightnessChange,
        )

        // 角部避让（spec 0019 / 票 #194）：与正文档位同处（版心口径的两个档），
        // 管 Agent 会话页与会话列表两边；默认关＝贴满（角部缺字认了，机主定夺）。
        SettingsSwitchRow(
            title = stringResource(R.string.settings_corner_avoidance),
            description = stringResource(R.string.settings_corner_avoidance_hint),
            checked = cornerAvoidance,
            onCheckedChange = onCornerAvoidanceChange,
        )

        AgentAlertRows(
            enabled = alertEnabled,
            vibrate = alertVibrate,
            onEnabledChange = onAlertEnabledChange,
            onVibrateChange = onAlertVibrateChange,
        )

        VoiceBroadcastRows(
            agentEnabled = enabled,
            settings = voiceSettings,
            runtime = voiceRuntime,
            onEnabledChange = onVoiceEnabledChange,
            onEngineChange = onVoiceEngineChange,
            onSpeedChange = onVoiceSpeedChange,
            onPitchChange = onVoicePitchChange,
            onKokoroVoiceChange = onVoiceKokoroVoiceChange,
            onSystemVoiceChange = onVoiceSystemVoiceChange,
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_approval_switch),
            description = stringResource(R.string.settings_approval_switch_hint),
            checked = approveEnabled,
            onCheckedChange = onApproveEnabledChange,
        )

        ApprovalBlock(
            approvals = approvals,
            actionNote = actionNote,
            onSendAction = onSendAction,
        )

        if (bridgeConfigured) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // PC 桥是唯一链路：桥掉线一眼可见，与主页概览、背屏状态点读同一份事实。
                    text = bridgeStatusLine(bridgeStatus, agentState, agentDisplay, sessionLock, agentLockedDisplay, roster),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            bridgeClient?.let {
                CodexRemotePanel(
                    bridgeClient = it,
                    bridgeStatus = bridgeStatus,
                    roster = roster,
                    currentSession = agentState,
                )
            }
            SessionLockList(
                mode = sessionLock,
                roster = roster,
                textSize = textSize,
                onModeChange = onSessionLockChange,
            )
        }
        // 桥地址手填**不在**上面那个 `paired || bridgeConfigured` 条件里（票 #171 返修）：
        // 清除地址会让 `bridgeConfigured` 变 false，整块随条件一起消失——连"再填一次"的入口都没了。
        // 它是兜底入口，必须常驻：输入框在未配置时正是最该出现的时候。
        BridgeAddressField(
            configured = bridgeConfigured,
            address = bridgeAddress,
            source = bridgeSource,
            pushedAt = bridgePushedAt,
            probe = bridgeProbe,
            onSave = onBridgeAddressSave,
            onClear = onBridgeAddressClear,
        )
    }
}

/**
 * PC 桥地址（Bridge URL）手填（票 #171）：电脑侧 adb 自动推送仍是主路径，这里是兜底——
 * 无线调试没连上、或人不在电脑边时，从托盘图标抄一次地址就能接上。
 *
 * 交互：输入 → 「保存并测试」先探一次 `/health` 再落库（探不通照样存，只是如实告诉你哪种不通）；
 * 「清除」等价于广播不带 url。**不设确认弹窗**：探测结果本身就是确认，多一层弹窗只添麻烦。
 * 支持文案随来源/探测结果变：电脑推来的说清推于何时（人不在电脑边时，这是"地址是不是被换过"的唯一判据）。
 */
@Composable
private fun BridgeAddressField(
    configured: Boolean,
    address: String,
    source: BridgeAddressSource?,
    pushedAt: Long?,
    probe: BridgeAddressProbe,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    // 输入态是本件的局部状态（不把半截输入抬进 AppState）；
    // 地址一变（电脑推来新域名、或点了清除）就回填成新值——地址作 key，同一个值重复推送不打断输入。
    // 「清除」按钮同时把本地输入清空：清除后地址恰为空串，若只靠 key 变化，框里会留着旧文本。
    var text by remember(address) { mutableStateOf(address) }
    val probing = probe is BridgeAddressProbe.Probing
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm)) {
        Text(
            text = stringResource(R.string.settings_bridge_address_heading),
            style = MaterialTheme.typography.labelMedium,
            color = RearCueColors.onBackgroundSecondary,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !probing,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            placeholder = { Text(stringResource(R.string.settings_bridge_address_placeholder)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onSave(text) }, enabled = !probing) {
                Text(stringResource(R.string.settings_bridge_address_save))
            }
            if (configured) {
                OutlinedButton(
                    onClick = {
                        text = ""
                        onClear()
                    },
                    enabled = !probing,
                ) {
                    Text(stringResource(R.string.settings_bridge_address_clear))
                }
            }
        }
        Text(
            text = bridgeAddressNote(probe, source, pushedAt),
            style = MaterialTheme.typography.bodySmall,
            color = RearCueColors.onBackgroundSecondary,
        )
    }
}

/** 地址下面那一行话：探测中 → 探测结果 → 来源说明（没探测时）→ 操作提示。 */
@Composable
private fun bridgeAddressNote(
    probe: BridgeAddressProbe,
    source: BridgeAddressSource?,
    pushedAt: Long?,
): String = when (probe) {
    BridgeAddressProbe.Probing -> stringResource(R.string.settings_bridge_address_probing)
    // 探通了给一句明确成功话（否则点了「保存并测试」看不出结果）；
    // 还没探过则说来源，没来源（未配置/刚清除）时提示去哪儿抄地址。
    BridgeAddressProbe.Reachable -> stringResource(R.string.settings_bridge_address_probe_ok)
    BridgeAddressProbe.Idle -> sourceNote(source, pushedAt)
    BridgeAddressProbe.BadFormat -> stringResource(R.string.settings_bridge_address_bad_format)
    is BridgeAddressProbe.HttpStatus -> stringResource(
        R.string.settings_bridge_address_http_error,
        probe.code,
    )
    is BridgeAddressProbe.Unreachable -> stringResource(
        R.string.settings_bridge_address_unreachable,
        probe.reason,
    )
}

/** 来源说明：电脑推来的带时刻（地址被换过的事后判据），手填的提醒会被覆盖。 */
@Composable
private fun sourceNote(source: BridgeAddressSource?, pushedAt: Long?): String = when (source) {
    BridgeAddressSource.PUSHED -> stringResource(
        R.string.settings_bridge_address_source_pushed,
        formatPushedAt(pushedAt),
    )
    BridgeAddressSource.MANUAL -> stringResource(R.string.settings_bridge_address_source_manual)
    BridgeAddressSource.DEBUG_BYPASS -> stringResource(R.string.settings_bridge_address_source_debug_bypass)
    // 未配置（或刚清除）：这是入口最该被看见的时刻，直接说去哪儿抄地址。
    null -> stringResource(R.string.settings_bridge_address_empty)
}

/** 推送时刻：本地时间到分钟（跨天也看得懂，够判「是不是刚被换过」）。 */
private fun formatPushedAt(at: Long?): String =
    if (at == null || at <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

/**
 * 正文档位（spec 0017 / 票 #169）：一行标题 + 三档单选（小 / 中 / 大）。
 *
 * 整行 [selectable]（[Role.RadioButton] 语义、触控目标 ≥[RearCueTouch.minTarget]），
 * 自绘选中圆点（沿 [SessionLockRow] 的语言）；**不做自定义滑杆、不做第四档**（spec 明列）。
 * 选中即回调 [onSizeChange]（写入口负责进 core + 写盘 + 重发背屏），本件零决策。
 */
@Composable
private fun MirrorTextSizeRow(
    size: MirrorTextSize,
    onSizeChange: (MirrorTextSize) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
        Text(
            text = stringResource(R.string.settings_mirror_text_size_heading),
            style = MaterialTheme.typography.labelMedium,
            color = RearCueColors.onBackgroundSecondary,
        )
        Text(
            text = stringResource(R.string.settings_mirror_text_size_hint),
            style = MaterialTheme.typography.labelSmall,
            color = RearCueColors.onBackgroundSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm)) {
            MirrorTextSize.entries.forEach { option ->
                MirrorTextSizeChip(
                    label = stringResource(option.labelRes()),
                    selected = option == size,
                    onClick = { onSizeChange(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 光带亮度倍率滑动条（spec 0021 修订 / 票 #214）：一行标题＋Material3 Slider，
 * 范围/默认读 [:core] [GlowBrightness]（与背屏钳制同一份事实）。拖动中 [onBrightnessChange]
 * 带 persist=false（只生效不写盘），松手（onValueChangeFinished）带 true 落点。
 * 本件零决策（值/范围全注入）。
 */
@Composable
private fun GlowBrightnessRow(
    brightness: Float,
    onBrightnessChange: (Float, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_glow_brightness_heading),
                style = MaterialTheme.typography.labelMedium,
                color = RearCueColors.onBackgroundSecondary,
            )
            Text(
                text = stringResource(R.string.settings_glow_brightness_value, (brightness * 100).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        Text(
            text = stringResource(R.string.settings_glow_brightness_hint),
            style = MaterialTheme.typography.labelSmall,
            color = RearCueColors.onBackgroundSecondary,
        )
        Slider(
            value = brightness,
            onValueChange = { onBrightnessChange(it, false) },
            onValueChangeFinished = { onBrightnessChange(brightness, true) },
            valueRange = GlowBrightness.MIN..GlowBrightness.MAX,
        )
    }
}

/**
 * 待批准区（spec 0018-4 / 票 #174，Remote Approval 主屏入口）：等确认 ∧ 来源声明 approve
 * 的会话才显示（显隐判定在 [AgentApprovePolicy]，本件零决策）。提问类＝选项点选、
 * 确认类＝同意/拒绝；免解锁、点了即生效（机主定夺）。**没有自由文字输入框**（ADR 0009 红线）。
 */
@Composable
private fun ApprovalBlock(
    approvals: List<AgentSessionState>,
    actionNote: String?,
    onSendAction: (SessionActionRequest) -> Unit,
) {
    if (approvals.isEmpty() && actionNote == null) return
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm)) {
        Text(
            text = stringResource(R.string.settings_approval_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        if (approvals.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_approval_hint),
                style = MaterialTheme.typography.bodySmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        approvals.forEach { session ->
            Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
                Text(
                    text = AgentStateLogic.sessionName(session),
                    style = MaterialTheme.typography.bodyMedium,
                )
                session.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = RearCueColors.onBackgroundSecondary,
                    )
                }
                // 按钮组形状分流收口在 AgentApprovePolicy.shapeFor（review 2026-09-30）。
                when (val shape = AgentApprovePolicy.shapeFor(session)) {
                    is AgentApproveShape.Question -> {
                        Text(
                            text = stringResource(R.string.settings_approval_question),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        shape.options.forEach { option ->
                            OutlinedButton(onClick = {
                                onSendAction(SessionActionRequest.of(session.sessionId, SessionActionKind.SELECT, option.id))
                            }) {
                                Text(option.label)
                            }
                        }
                    }
                    AgentApproveShape.Confirm -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm)) {
                            OutlinedButton(onClick = {
                                onSendAction(SessionActionRequest.of(session.sessionId, SessionActionKind.APPROVE))
                            }) {
                                Text(stringResource(R.string.agent_action_approve))
                            }
                            OutlinedButton(onClick = {
                                onSendAction(SessionActionRequest.of(session.sessionId, SessionActionKind.REJECT))
                            }) {
                                Text(stringResource(R.string.agent_action_reject))
                            }
                        }
                    }
                }
            }
        }
        if (actionNote != null) {
            Text(
                text = actionNote,
                style = MaterialTheme.typography.bodySmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
    }
}


/** Voice Broadcast 设置（spec 0022）：一个总开关、双语音引擎、音色与全局语速。 */
@Composable
private fun VoiceBroadcastRows(
    agentEnabled: Boolean,
    settings: VoiceBroadcastSettings,
    runtime: VoiceBroadcastRuntimeState,
    onEnabledChange: (Boolean, Boolean) -> Unit,
    onEngineChange: (VoiceEngine) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onKokoroVoiceChange: (String) -> Unit,
    onSystemVoiceChange: (String) -> Unit,
) {
    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
        Text(
            text = stringResource(R.string.settings_voice_heading),
            style = MaterialTheme.typography.labelMedium,
            color = RearCueColors.onBackgroundSecondary,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_voice_enabled),
            description = stringResource(R.string.settings_voice_enabled_hint),
            checked = settings.enabled,
            enabled = agentEnabled,
            onCheckedChange = { wanted ->
                if (!wanted) {
                    onEnabledChange(false, false)
                } else if (settings.engine == VoiceEngine.OFFLINE &&
                    runtime.offlineStatus != OfflineVoiceStatus.READY
                ) {
                    pendingDownloadAction = { onEnabledChange(true, true) }
                } else {
                    onEnabledChange(true, false)
                }
            },
        )

        Text(
            text = stringResource(R.string.settings_voice_engine_heading),
            style = MaterialTheme.typography.labelSmall,
            color = RearCueColors.onBackgroundSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm)) {
            VoiceEngineChip(
                label = stringResource(R.string.settings_voice_engine_offline),
                selected = settings.engine == VoiceEngine.OFFLINE,
                enabled = agentEnabled,
onClick = {
                    if (settings.enabled &&
                        runtime.offlineStatus != OfflineVoiceStatus.READY
                    ) {
                        pendingDownloadAction = { onEngineChange(VoiceEngine.OFFLINE) }
                    } else {
                        onEngineChange(VoiceEngine.OFFLINE)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            VoiceEngineChip(
                label = stringResource(R.string.settings_voice_engine_system),
                selected = settings.engine == VoiceEngine.SYSTEM,
                enabled = agentEnabled,
                onClick = { onEngineChange(VoiceEngine.SYSTEM) },
                modifier = Modifier.weight(1f),
            )
        }

        if (settings.engine == VoiceEngine.OFFLINE) {
            VoiceOptionMenu(
                label = stringResource(R.string.settings_voice_kokoro_heading),
                options = VoiceCatalog.KOKORO,
                selectedId = settings.kokoroVoiceId,
                enabled = agentEnabled,
                onSelect = onKokoroVoiceChange,
            )
        } else {
            VoiceOptionMenu(
                label = stringResource(R.string.settings_voice_system_heading),
                options = runtime.systemVoices,
                selectedId = settings.systemVoiceId,
                enabled = agentEnabled,
                onSelect = onSystemVoiceChange,
            )
        }

        VoiceSpeedRow(
            speed = settings.speed,
            enabled = agentEnabled,
            onSpeedChange = onSpeedChange,
        )

        val statusText = when (runtime.offlineStatus) {
            OfflineVoiceStatus.NOT_READY -> stringResource(R.string.settings_voice_status_not_ready)
            OfflineVoiceStatus.DOWNLOADING -> stringResource(R.string.settings_voice_status_downloading)
            OfflineVoiceStatus.READY -> stringResource(R.string.settings_voice_status_ready)
            OfflineVoiceStatus.FAILED -> stringResource(R.string.settings_voice_status_failed)
        }
        Text(
            text = runtime.note ?: statusText,
            style = MaterialTheme.typography.bodySmall,
            color = RearCueColors.onBackgroundSecondary,
        )
    }

    pendingDownloadAction?.let { downloadAction ->
        AlertDialog(
            onDismissRequest = { pendingDownloadAction = null },
            title = { Text(stringResource(R.string.settings_voice_download_title)) },
            text = { Text(stringResource(R.string.settings_voice_download_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDownloadAction = null
                    downloadAction()
                }) { Text(stringResource(R.string.settings_voice_download_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownloadAction = null }) {
                    Text(stringResource(R.string.settings_voice_download_cancel))
                }
            },
        )
    }
}

@Composable
private fun VoiceEngineChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = RearCueTouch.minTarget)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) RearCueColors.surfaceHighlight else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) RearCueColors.accent else RearCueColors.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) RearCueColors.accent else RearCueColors.onBackground,
        )
    }
}

@Composable
private fun VoiceOptionMenu(
    label: String,
    options: List<VoiceOption>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.id == selectedId }?.label
        ?: selectedId.ifEmpty { VoiceCatalog.SYSTEM_DEFAULT.label }
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = RearCueColors.onBackgroundSecondary,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(selectedLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            onSelect(option.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoicePitchRow(
    pitch: Float,
    enabled: Boolean,
    onPitchChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_voice_pitch_heading),
                style = MaterialTheme.typography.labelSmall,
                color = RearCueColors.onBackgroundSecondary,
            )
            Text(
                text = stringResource(R.string.settings_voice_pitch_value, pitch),
                style = MaterialTheme.typography.labelSmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        Slider(
            value = pitch,
            onValueChange = onPitchChange,
            enabled = enabled,
            valueRange = VoiceCatalog.MIN_PITCH..VoiceCatalog.MAX_PITCH,
            steps = 14,
        )
    }
}

@Composable
private fun VoiceSpeedRow(
    speed: Float,
    enabled: Boolean,
    onSpeedChange: (Float) -> Unit,

) {
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_voice_speed_heading),
                style = MaterialTheme.typography.labelSmall,
                color = RearCueColors.onBackgroundSecondary,
            )
            Text(
                text = stringResource(R.string.settings_voice_speed_value, speed),
                style = MaterialTheme.typography.labelSmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        Slider(
            value = speed,
            onValueChange = onSpeedChange,
            enabled = enabled,
            valueRange = VoiceCatalog.MIN_SPEED..VoiceCatalog.MAX_SPEED,
            steps = 14,
        )
    }
}

/**
 * Agent 提醒两开关（spec 0018-3 / 票 #173）：提醒总开关＋震动开关（双默认开），
 * 选中即回调（写入口负责写盘与撤已发提醒），本件零决策；**不响铃是既定口径，不给开关**。
 */
@Composable
private fun AgentAlertRows(
    enabled: Boolean,
    vibrate: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
        Text(
            text = stringResource(R.string.settings_agent_alert_heading),
            style = MaterialTheme.typography.labelMedium,
            color = RearCueColors.onBackgroundSecondary,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_agent_alert_enabled),
            description = stringResource(R.string.settings_agent_alert_hint),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_agent_alert_vibrate),
            description = stringResource(R.string.settings_agent_alert_vibrate_hint),
            checked = vibrate,
            onCheckedChange = onVibrateChange,
        )
    }
}

/** 一档的呈现（自绘胶囊：选中＝accent 描边与文字，未选＝outline）——三档平铺一行。 */
@Composable
private fun MirrorTextSizeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = RearCueTouch.minTarget)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) RearCueColors.surfaceHighlight else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) RearCueColors.accent else RearCueColors.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) RearCueColors.accent else RearCueColors.onBackground,
        )
    }
}

/** 档位文案（设置页三档；词语与 CONTEXT.md「Mirror Text Size」一致：小/中/大）。 */
@StringRes
private fun MirrorTextSize.labelRes(): Int = when (this) {
    MirrorTextSize.SMALL -> R.string.settings_mirror_text_size_small
    MirrorTextSize.MEDIUM -> R.string.settings_mirror_text_size_medium
    MirrorTextSize.LARGE -> R.string.settings_mirror_text_size_large
}

/**
 * 会话列表（票 #104 / #154）：顺序、标题、来源、选中态全部取 [AgentStateLogic.projectRoster]；
 * 本件只把行渲染成 [onModeChange] 调用，零决策。
 */
@Composable
private fun SessionLockList(
    mode: SessionLockMode,
    roster: List<AgentSessionState>,
    textSize: MirrorTextSize,
    onModeChange: (SessionLockMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
        Text(
            text = stringResource(R.string.settings_session_lock_heading),
            style = MaterialTheme.typography.labelMedium,
            color = RearCueColors.onBackgroundSecondary,
        )
        AgentStateLogic.projectRoster(roster, mode).forEach { row ->
            if (row.auto) {
                SessionLockRow(
                    title = stringResource(R.string.settings_session_lock_auto),
                    subtitle = stringResource(R.string.settings_session_lock_auto_desc),
                    textSize = textSize,
                    status = null,
                    selected = row.selected,
                    onClick = { onModeChange(SessionLockMode.Auto) },
                )
            } else {
                val sessionId = row.sessionId ?: return@forEach
                SessionLockRow(
                    title = row.title,
                    subtitle = row.subtitle,
                    textSize = textSize,
                    status = row.status?.let { sessionStatusText(it) },
                    selected = row.selected,
                    onClick = { onModeChange(SessionLockMode.Locked(sessionId)) },
                )
            }
        }
        if (roster.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_session_lock_empty),
                style = MaterialTheme.typography.bodySmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
    }
}

/** 一行档位：整行 [selectable]（[Role.RadioButton] 语义、触控目标 ≥[RearCueTouch.minTarget]）。 */
@Composable
private fun SessionLockRow(
    title: String,
    subtitle: String?,
    textSize: MirrorTextSize,
    status: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RearCueTouch.minTarget)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            val typography = AgentMirrorParams.settingsSessionListTypography(textSize)
            Text(
                text = title,
                color = RearCueColors.onBackground,
                fontSize = typography.titleSp.sp,
                lineHeight = typography.titleLineHeightSp.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = RearCueColors.onBackgroundSecondary,
                    fontSize = typography.subtitleSp.sp,
                    lineHeight = typography.subtitleLineHeightSp.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        SelectDot(selected = selected)
    }
}

/**
 * 选中圆点（纯显示，触控与语义由整行承担——同 [SettingsSwitchRow] 的滑块判例）：
 * 自绘而非取图标——material 图标 core 集无 RadioButton，且单色令牌轨道与开关件同语言。
 */
@Composable
private fun SelectDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) RearCueColors.accent else RearCueColors.surfaceHighlight)
            .border(1.dp, if (selected) RearCueColors.accent else RearCueColors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(RearCueColors.onAccent),
            )
        }
    }
}

/** 当前档文案：自动 / 已锁定·两行内联名（派生/缓存见 [AgentStateLogic.lockTargetDisplay]）。 */
@Composable
private fun lockModeText(
    mode: SessionLockMode,
    lockedDisplay: AgentSessionDisplay?,
    roster: List<AgentSessionState>,
): String {
    val display = lockedDisplay ?: AgentStateLogic.lockTargetDisplay(mode, roster)
        ?: return stringResource(R.string.settings_session_lock_auto)
    return stringResource(R.string.settings_session_lock_locked, display.inline)
}

/** PC 桥状态行：桥链路状态 · 当前档 · 会话三态/两行内联会话名（issue #213）。 */
@Composable
private fun bridgeStatusLine(
    bridgeStatus: BridgeLinkStatus,
    state: AgentSessionState?,
    display: AgentSessionDisplay?,
    lockMode: SessionLockMode,
    lockedDisplay: AgentSessionDisplay?,
    roster: List<AgentSessionState>,
): String {
    val bridge = stringResource(
        when (bridgeStatus) {
            BridgeLinkStatus.DISABLED -> R.string.agent_bridge_status_disabled
            BridgeLinkStatus.CONNECTING -> R.string.agent_bridge_status_connecting
            BridgeLinkStatus.CONNECTED -> R.string.agent_bridge_status_connected
            BridgeLinkStatus.RETRYING -> R.string.agent_bridge_status_retrying
        },
    )
    val mode = lockModeText(lockMode, lockedDisplay, roster)
    if (state == null) return "$bridge · $mode"
    val sessionStatus = sessionStatusText(state.status)
    val sessionLabel = (display ?: AgentStateLogic.sessionDisplay(state, roster)).inline
    return if (sessionLabel.isBlank()) {
        "$bridge · $mode · $sessionStatus"
    } else {
        "$bridge · $mode · $sessionLabel · $sessionStatus"
    }
}

/** 三态文案：工作中 / 等你确认 / 空闲 / 出错（与状态行、背屏同一套词）。 */
@Composable
private fun sessionStatusText(status: AgentStatus): String = stringResource(
    when (status) {
        AgentStatus.WORKING -> R.string.agent_live_working
        AgentStatus.WAITING_FOR_APPROVAL -> R.string.agent_live_waiting
        AgentStatus.IDLE -> R.string.agent_live_idle
        AgentStatus.ERROR -> R.string.agent_live_error
    },
)
