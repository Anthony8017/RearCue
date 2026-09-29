package com.rearcue.poc.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rearcue.poc.R
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.agentmirror.AgentLinkStatus
import com.rearcue.poc.agentmirror.AgentStateLogic
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTouch

/**
 * Agent 镜像区（spec 0010 / 票 #81）：总开关（默认开）＋一次性配对＋连接状态行＋解除配对。
 *
 * 配对即粘贴：桌面端「远程控制」弹窗的链接整体粘贴 → [onPair] 落库并起链路；解析失败
 * （非法/残缺链接）就地红字提示、不清输入。凭据不回显——配对后输入框消失，只留状态行。
 * 状态与写入口由调用方注入（同 [ChargingSettingsSection] 口径），本件零决策。
 * 票 #82：已配对时状态行带实时会话面（工作区名 + agent 状态），数据与背屏同源（core 投影）。
 * 票 #104 / spec 0016 #154：ZCode 已配对或 PC 桥已配置时加会话列表（统一投影，点会话即锁定、点自动即解锁）——
 * 选中读 [sessionLock]（core 投影）、点击走 [onSessionLockChange]（= 写入口 `setSessionLock`，
 * 事件进 core + 写盘，与背屏仲裁同一份偏好）；状态行同时显示当前档。
 */
@Composable
fun AgentSettingsSection(
    paired: Boolean,
    bridgeConfigured: Boolean,
    bridgeStatus: BridgeLinkStatus,
    enabled: Boolean,
    status: AgentLinkStatus,
    agentState: AgentSessionState?,
    sessionLock: SessionLockMode,
    roster: List<AgentSessionState>,
    onPair: (String) -> Boolean,
    onUnpair: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSessionLockChange: (SessionLockMode) -> Unit,
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

        if (!paired) {
            var linkText by remember { mutableStateOf("") }
            var invalid by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm)) {
                Text(
                    text = stringResource(R.string.settings_agent_pair_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = RearCueColors.onBackgroundSecondary,
                )
                OutlinedTextField(
                    value = linkText,
                    onValueChange = {
                        linkText = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = invalid,
                    placeholder = { Text(stringResource(R.string.settings_agent_link_placeholder)) },
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.settings_agent_link_invalid)) }
                    } else {
                        null
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm)) {
                    OutlinedButton(onClick = {
                        if (!onPair(linkText)) invalid = true else linkText = ""
                    }) {
                        Text(stringResource(R.string.settings_agent_pair))
                    }
                }
            }
        }
        if (paired || bridgeConfigured) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (!paired && bridgeConfigured) {
                        // 只用 PC 桥时：状态行显示桥的真实链路状态（票 #165）——桥掉线一眼可见，
                        // 与主页概览、背屏状态点读的是同一份事实。
                        bridgeStatusLine(bridgeStatus, agentState, sessionLock, roster)
                    } else {
                        liveStatusLine(status, agentState, sessionLock, roster)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (paired) {
                    OutlinedButton(onClick = onUnpair) {
                        Text(stringResource(R.string.settings_agent_unpair))
                    }
                }
            }
            SessionLockList(
                mode = sessionLock,
                roster = roster,
                onModeChange = onSessionLockChange,
            )
        }
    }
}

/**
 * 会话列表（票 #104 / #154）：顺序、标题、来源、选中态全部取 [AgentStateLogic.projectRoster]；
 * 本件只把行渲染成 [onModeChange] 调用，零决策。
 */
@Composable
private fun SessionLockList(
    mode: SessionLockMode,
    roster: List<AgentSessionState>,
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
                    description = stringResource(R.string.settings_session_lock_auto_desc),
                    sourceLabel = null,
                    status = null,
                    selected = row.selected,
                    onClick = { onModeChange(SessionLockMode.Auto) },
                )
            } else {
                val sessionId = row.sessionId ?: return@forEach
                SessionLockRow(
                    title = row.title,
                    description = null,
                    sourceLabel = row.sourceLabel,
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
    description: String?,
    sourceLabel: String?,
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RearCueColors.onBackground,
                )
                if (sourceLabel != null) {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = RearCueColors.accent,
                    )
                }
            }
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = RearCueColors.onBackgroundSecondary,
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

/**
 * 状态行（票 #104 改：带当前档）：链路状态 · 档位（自动 / 已锁定·会话名）——
 * 锁定档的档位已给会话名，只再补镜像状态（等确认插队时在屏的可能不是锁定会话本身）；
 * 自动档保留票 #82 的实时会话面（工作区名 + 状态）。数据与背屏同源（core 投影）。
 */
@Composable
private fun liveStatusLine(
    status: AgentLinkStatus,
    state: AgentSessionState?,
    lockMode: SessionLockMode,
    roster: List<AgentSessionState>,
): String {
    val link = statusText(status)
    val mode = lockModeText(lockMode, roster)
    if (lockMode is SessionLockMode.Locked) {
        return if (state == null) {
            "$link · $mode"
        } else {
            "$link · $mode · ${sessionStatusText(state.status)}"
        }
    }
    if (state == null) return "$link · $mode"
    val sessionStatus = sessionStatusText(state.status)
    // 会话标签与背屏同源（workspace 目录名 / 尾 4 位兜底）；会话键为空串时才会是空标签。
    val sessionLabel = AgentStateLogic.sessionName(state)
    return if (sessionLabel.isNullOrBlank()) {
        "$link · $mode · $sessionStatus"
    } else {
        "$link · $mode · $sessionLabel · $sessionStatus"
    }
}

/** 当前档文案：自动 / 已锁定·会话名（名字派生在 [AgentStateLogic.lockTargetName]）。 */
@Composable
private fun lockModeText(mode: SessionLockMode, roster: List<AgentSessionState>): String =
    AgentStateLogic.lockTargetName(mode, roster)
        ?.let { stringResource(R.string.settings_session_lock_locked, it) }
        ?: stringResource(R.string.settings_session_lock_auto)

/**
 * 只用 PC 桥时的状态行（票 #165）：桥链路状态（已连接 / 连接中 / 重连中 / 未配置）· 当前档 · 会话三态。
 * 与 [liveStatusLine] 的分工：那条走 ZCode 直连的链路词表；本条的在线词来自桥客户端，
 * 与主页概览、背屏状态点读的是同一份 `BridgeLinkStatus`。
 */
@Composable
private fun bridgeStatusLine(
    bridgeStatus: BridgeLinkStatus,
    state: AgentSessionState?,
    lockMode: SessionLockMode,
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
    val mode = lockModeText(lockMode, roster)
    if (state == null) return "$bridge · $mode"
    val sessionStatus = sessionStatusText(state.status)
    val sessionLabel = AgentStateLogic.sessionName(state)
    return if (sessionLabel.isNullOrBlank()) {
        "$bridge · $mode · $sessionStatus"
    } else {
        "$bridge · $mode · $sessionLabel · $sessionStatus"
    }
}

/** 三态文案：工作中 / 等你确认 / 空闲（与状态行、背屏同一套词）。 */
@Composable
private fun sessionStatusText(status: AgentStatus): String = stringResource(
    when (status) {
        AgentStatus.WORKING -> R.string.agent_live_working
        AgentStatus.WAITING_FOR_APPROVAL -> R.string.agent_live_waiting
        AgentStatus.IDLE -> R.string.agent_live_idle
    },
)

@Composable
private fun statusText(status: AgentLinkStatus): String = stringResource(
    when (status) {
        AgentLinkStatus.UNPAIRED -> R.string.agent_status_unpaired
        AgentLinkStatus.CONNECTING -> R.string.agent_status_connecting
        AgentLinkStatus.CONNECTED -> R.string.agent_status_connected
        AgentLinkStatus.RECONNECTING -> R.string.agent_status_reconnecting
        AgentLinkStatus.DISCONNECTED -> R.string.agent_status_disconnected
        AgentLinkStatus.DISABLED -> R.string.agent_status_disabled
    },
)
