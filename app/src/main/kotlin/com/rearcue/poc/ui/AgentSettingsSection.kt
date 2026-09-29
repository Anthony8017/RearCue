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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rearcue.poc.R
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.agentmirror.AgentLinkStatus
import com.rearcue.poc.agentmirror.AgentMirrorSettingsStore
import com.rearcue.poc.agentmirror.AgentStateLogic
import com.rearcue.poc.agentmirror.BridgeAddressProbe
import com.rearcue.poc.agentmirror.BridgeAddressSource
import com.rearcue.poc.core.DashboardEvent.SessionLockMode
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTouch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * 票 #171：增加「PC 桥地址」手填（电脑推送为主、手填兜底）——保存前走 [onBridgeAddressSave]
 * 当场探一次 /health，结果由 [bridgeProbe] 说清是格式错、隧道没应还是连不上；
 * [bridgeSource] / [bridgePushedAt] 让这一行说得出「地址是电脑推来的、推于何时」。
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
    /** 背屏正文档位（spec 0017 / 票 #169）：core 投影，选中态与背屏字号同一份事实。 */
    textSize: MirrorTextSize = MirrorTextSize.DEFAULT,
    onTextSizeChange: (MirrorTextSize) -> Unit = {},
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

        AgentAlertRows(
            enabled = alertEnabled,
            vibrate = alertVibrate,
            onEnabledChange = onAlertEnabledChange,
            onVibrateChange = onAlertVibrateChange,
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
    // 输入态是本件的局部状态（与配对输入框同口径：不把半截输入抬进 AppState）；
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
        AgentStatus.ERROR -> R.string.agent_live_error
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
