package com.rearcue.poc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import com.rearcue.poc.R
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agentmirror.AgentLinkStatus
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing

/**
 * Agent 镜像区（spec 0010 / 票 #81）：总开关（默认开）＋一次性配对＋连接状态行＋解除配对。
 *
 * 配对即粘贴：桌面端「远程控制」弹窗的链接整体粘贴 → [onPair] 落库并起链路；解析失败
 * （非法/残缺链接）就地红字提示、不清输入。凭据不回显——配对后输入框消失，只留状态行。
 * 状态与写入口由调用方注入（同 [ChargingSettingsSection] 口径），本件零决策。
 * 票 #82：已配对时状态行带实时会话面（工作区名 + agent 状态），数据与背屏同源（core 投影）。
 */
@Composable
fun AgentSettingsSection(
    paired: Boolean,
    enabled: Boolean,
    status: AgentLinkStatus,
    agentState: AgentSessionState?,
    onPair: (String) -> Boolean,
    onUnpair: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
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
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = liveStatusLine(status, agentState),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onUnpair) {
                    Text(stringResource(R.string.settings_agent_unpair))
                }
            }
        }
    }
}

/** 状态行：链路状态为主；有实时会话时追加「工作区 · 状态」（票 #82，与背屏同源）。 */
@Composable
private fun liveStatusLine(status: AgentLinkStatus, state: AgentSessionState?): String {
    val link = statusText(status)
    if (state == null) return link
    val sessionStatus = stringResource(
        when (state.status) {
            AgentStatus.WORKING -> R.string.agent_live_working
            AgentStatus.WAITING_FOR_APPROVAL -> R.string.agent_live_waiting
            AgentStatus.IDLE -> R.string.agent_live_idle
        },
    )
    val workspace = state.workspace
    return if (workspace.isNullOrBlank()) "$link · $sessionStatus" else "$link · $workspace · $sessionStatus"
}

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
