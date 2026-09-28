package com.rearcue.poc.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rearcue.poc.AppContainer
import com.rearcue.poc.AppState
import com.rearcue.poc.R
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.autostart.openAutostartSettings
import com.rearcue.poc.core.CastSource
import com.rearcue.poc.core.UsabilityReason
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueIconSize
import com.rearcue.poc.design.RearCueMotion
import com.rearcue.poc.design.RearCueShape
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTheme
import com.rearcue.poc.design.RearCueTouch
import com.rearcue.poc.design.pressFeedback
import com.rearcue.poc.design.safeAreaPadding
import com.rearcue.poc.notify.cancelTestNotification
import com.rearcue.poc.notify.isListenerEnabled
import com.rearcue.poc.notify.listenerSettingsIntent
import com.rearcue.poc.notify.postTestNotification
import com.rearcue.poc.rear.RearBackendState
import com.rearcue.poc.rear.resolveApp

/**
 * 主屏调试/引导页：实时展示 Icon Set（票 #3 的验收面）+ 背屏投送入口（票 #4）。
 *
 * Icon Set 由 [com.rearcue.poc.core.DashboardCore] 算出（Android 层不做决策）；
 * 本页只读 [AppContainer.state] 与 [com.rearcue.poc.rear.RearDisplayBackend.stateFlow]。
 *
 * 票 #25 翻新：AMOLED 纯黑 + 单一强调色（[RearCueColors] 语义令牌），内容全程落在
 * [safeAreaPadding]（平台 WindowInsets：挖孔/圆角/手势条），触控目标 ≥[RearCueTouch]、
 * 按压反馈 [RearCueMotion.pressFeedbackMs]、空/错误/禁用态齐全；调试旁路语义不变。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边（AMOLED 纯黑铺满到系统栏后），避让交给平台 WindowInsets。
        enableEdgeToEdge()
        setContent {
            val container = (application as RearCueApp).container
            MainScreen(
                state = container.state.collectAsState().value,
                rearState = container.rearBackend.stateFlow.collectAsState().value,
                container = container,
            )
        }
    }
}

/** 状态行语气：健康用强调色、异常用错误色、纯信息用次要色。 */
private enum class StatusTone { OK, ALERT, NEUTRAL }

@Composable
private fun MainScreen(state: AppState, rearState: RearBackendState, container: AppContainer) {
    // 通知使用权是系统设置，不是容器状态：进页面与从设置页返回时各读一次。
    val context = LocalContext.current
    var listenerEnabled by remember(context) { mutableStateOf(isListenerEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 从 MIUI 自启动设置页返回即复查（票 #28）：读数 → 事件 → 横幅效果，零决策搬运。
                container.checkAutostart()
                // 监听授权/连接读数 → 探针；重绑判定与健康横幅仍由 DashboardCore 决定。
                listenerEnabled = container.probeNotificationListener(triggerSource = "on-resume")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    RearCueTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = RearCueColors.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeAreaPadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RearCueSpacing.md),
            ) {
                Header(onOpenSettings = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                })
                // 可用性引导横幅（票 #28）：显隐由 DashboardCore 决定，纵向流式布局不遮挡 Icon Set 与关键状态。
                state.usabilityBanner?.let { UsabilityBannerCard(it) }
                IconSetCard(state)
                SummaryCard(state, listenerEnabled)
                // Agent 镜像区（spec 0010 / 票 #81）：粘贴配对、总开关、连接状态、解除配对。
                AgentSettingsSection(
                    paired = state.agentPaired,
                    enabled = state.agentEnabled,
                    status = state.agentLinkStatus,
                    agentState = state.agentState,
                    onPair = container::pairAgent,
                    onUnpair = container::unpairAgent,
                    onEnabledChange = container::setAgentMirrorEnabled,
                )
                // 开发者选项折叠区（spec 0005 #47）：完整状态明细 + 调试旁路原样收进，默认收起。
                DeveloperOptions(rearState, container)
            }
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = RearCueColors.onBackground,
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        // 设置页唯一入口（主页 → 设置页，路径唯一）。
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.heightIn(min = RearCueTouch.minTarget),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.settings_open_cd),
                tint = RearCueColors.onBackground,
                modifier = Modifier.size(RearCueIconSize.medium),
            )
        }
    }
}

/** 主页状态摘要（spec 0005 #47）：监听/通道/通知三行，一眼判健康；完整明细在开发者选项里。 */
@Composable
private fun SummaryCard(state: AppState, listenerEnabled: Boolean) {
    SectionCard(title = stringResource(R.string.section_status)) {
        StatusRow(
            icon = if (state.listenerConnected) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
            tone = if (state.listenerConnected) StatusTone.OK else StatusTone.ALERT,
            label = stringResource(R.string.label_listener),
            value = stringResource(
                if (state.listenerConnected) R.string.listener_connected else R.string.listener_disconnected,
            ),
        )
        StatusRow(
            icon = Icons.Outlined.Send,
            tone = if (state.channelReady) StatusTone.OK else StatusTone.ALERT,
            label = stringResource(R.string.label_channel),
            value = stringResource(
                if (state.channelReady) R.string.channel_ready else R.string.channel_unavailable,
            ),
        )
        StatusRow(
            icon = Icons.Outlined.Notifications,
            tone = StatusTone.NEUTRAL,
            label = stringResource(R.string.label_active_notifications),
            value = stringResource(R.string.active_line, state.activeNotificationCount),
        )
    }
}

/**
 * 开发者选项折叠区（spec 0005 #47）：完整状态明细（含通知使用权、最近事件、背屏明细）
 * 与全部 Debug Bypass 原样收进，默认收起——语义不变，只是不再占主视觉。
 */
@Composable
private fun DeveloperOptions(rearState: RearBackendState, container: AppContainer) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RearCueShape.large)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RearCueColors.surface)
            .border(1.dp, RearCueColors.outline, shape)
            .padding(RearCueSpacing.md),
        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RearCueTouch.minTarget)
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.section_dev_options),
                style = MaterialTheme.typography.titleSmall,
                color = RearCueColors.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = RearCueColors.onBackgroundSecondary,
                modifier = Modifier.size(RearCueIconSize.medium),
            )
        }
        if (expanded) {
            StatusCard(container.state.collectAsState().value, rememberListenerEnabled())
            RearCard(rearState)
            DebugActions(container)
        }
    }
}

/** 折叠区明细行的通知使用权读数：展开时即时读一次（系统设置，不是容器状态）。 */
@Composable
private fun rememberListenerEnabled(): Boolean {
    val context = LocalContext.current
    return remember { isListenerEnabled(context) }
}

/**
 * 可用性引导横幅（票 #28）：显隐与降级形态由 DashboardCore 决定（[AppState.usabilityBanner]，
 * null = 隐藏），本组件照单渲染原因文案 + 一键跳转按钮——跳转不依赖检测，任何形态都可点。
 *
 * 布局约束（票 05 验收）：纵向流式占位、不遮挡 Icon Set 与关键状态；触控目标 ≥[RearCueTouch.minTarget]，
 * 全部取票 #25 语义化令牌（[RearCueColors] / [RearCueSpacing] / [RearCueIconSize] / [RearCueShape]）。
 * 降级形态（`AUTOSTART_IN_DOUBT`）只有「仅手动跳转 + 明示文案」，绝不显示「健康」。
 */
@Composable
private fun UsabilityBannerCard(reasons: Set<UsabilityReason>) {
    val context = LocalContext.current
    var jumpFailed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RearCueShape.large)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RearCueColors.surface)
            .border(1.dp, RearCueColors.error, shape)
            .padding(RearCueSpacing.md),
        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = RearCueColors.error,
                modifier = Modifier.size(RearCueIconSize.small),
            )
            Text(
                text = stringResource(R.string.usability_banner_title),
                style = MaterialTheme.typography.titleSmall,
                color = RearCueColors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
        }
        // 原因按词汇序渲染（顺序稳定，便于 ui dump 逐字核对）；文案只陈述事实。
        reasons.sortedBy { it.name }.forEach { reason ->
            Text(
                text = stringResource(
                    when (reason) {
                        UsabilityReason.AUTOSTART_DENIED -> R.string.usability_reason_autostart_denied
                        UsabilityReason.AUTOSTART_IN_DOUBT -> R.string.usability_reason_autostart_in_doubt
                        UsabilityReason.LISTENER_UNHEALTHY -> R.string.usability_reason_listener_unhealthy
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        ActionButton(
            text = stringResource(R.string.usability_jump_autostart_settings),
            icon = Icons.Outlined.Settings,
            filled = true,
            onClick = { jumpFailed = !openAutostartSettings(context) },
        )
        if (jumpFailed) {
            Text(
                text = stringResource(R.string.usability_jump_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = RearCueColors.error,
            )
        }
    }
}

/** 区块卡片：标题（heading 语义）+ 内容，统一形状/描边/留白。 */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(RearCueShape.large)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RearCueColors.surface)
            .border(1.dp, RearCueColors.outline, shape)
            .padding(RearCueSpacing.md),
        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = RearCueColors.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun IconSetCard(state: AppState) {
    SectionCard(title = stringResource(R.string.icon_set_title, state.iconSet.size)) {
        if (state.iconSet.isEmpty()) {
            EmptyState()
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.md),
                verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
            ) {
                state.iconSet.forEach { pkg -> PackageIcon(pkg) }
            }
        }
    }
}

/** 空态：矢量图标 + 明示文案（不只留一句灰字）。 */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RearCueSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Notifications,
            contentDescription = null,
            tint = RearCueColors.onBackgroundDisabled,
            modifier = Modifier.size(RearCueIconSize.large),
        )
        Text(
            text = stringResource(R.string.icon_set_empty),
            style = MaterialTheme.typography.bodySmall,
            color = RearCueColors.onBackgroundSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一枚图标：Icon Set 中某应用的应用图标 + 应用名；解析不到时退化为包名首字母（错误态不崩）。 */
@Composable
private fun PackageIcon(pkg: String) {
    val context = LocalContext.current
    val size = RearCueIconSize.iconSetMainDisplay
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val app = remember(pkg, sizePx) { context.packageManager.resolveApp(pkg, sizePx) }
    val icon: Painter? = app.icon
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = app.label,
                tint = Color.Unspecified,
                modifier = Modifier.size(size),
            )
        } else {
            val shape = RoundedCornerShape(RearCueShape.medium)
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(shape)
                    .background(RearCueColors.surfaceHighlight)
                    .border(1.dp, RearCueColors.outline, shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = app.label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = RearCueColors.onBackground,
                )
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            color = RearCueColors.onBackgroundSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusCard(state: AppState, listenerEnabled: Boolean) {
    SectionCard(title = stringResource(R.string.section_status_detail)) {
        StatusRow(
            icon = if (state.listenerConnected) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
            tone = if (state.listenerConnected) StatusTone.OK else StatusTone.ALERT,
            label = stringResource(R.string.label_listener),
            value = stringResource(
                if (state.listenerConnected) R.string.listener_connected else R.string.listener_disconnected,
            ),
        )
        StatusRow(
            icon = if (listenerEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
            tone = if (listenerEnabled) StatusTone.OK else StatusTone.ALERT,
            label = stringResource(R.string.label_permission),
            value = stringResource(
                if (listenerEnabled) R.string.listener_enabled else R.string.listener_denied,
            ),
        )
        StatusRow(
            icon = Icons.Outlined.Notifications,
            tone = StatusTone.NEUTRAL,
            label = stringResource(R.string.label_active_notifications),
            value = stringResource(R.string.active_line, state.activeNotificationCount),
        )
        StatusRow(
            icon = Icons.Outlined.Send,
            tone = if (state.channelReady) StatusTone.OK else StatusTone.ALERT,
            label = stringResource(R.string.label_channel),
            value = stringResource(
                if (state.channelReady) R.string.channel_ready else R.string.channel_unavailable,
            ),
        )
        StatusRow(
            icon = if (state.postureFaceDown) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
            tone = if (state.postureFaceDown) StatusTone.NEUTRAL else StatusTone.ALERT,
            label = stringResource(R.string.label_posture),
            value = stringResource(
                if (state.postureFaceDown) R.string.posture_face_down else R.string.posture_face_up,
            ),
        )
        StatusRow(
            icon = Icons.Outlined.Person,
            tone = StatusTone.NEUTRAL,
            label = stringResource(R.string.label_cast_source),
            value = when (state.castSource) {
                CastSource.AUTO -> stringResource(R.string.cast_source_auto)
                CastSource.MANUAL -> stringResource(R.string.cast_source_manual)
                CastSource.CHARGING -> stringResource(R.string.cast_source_charging)
                CastSource.AGENT -> stringResource(R.string.cast_source_agent)
                null -> stringResource(R.string.cast_source_absent)
            },
        )
        StatusRow(
            icon = Icons.Outlined.Info,
            tone = StatusTone.NEUTRAL,
            label = stringResource(R.string.label_last_event),
            value = state.lastEvent,
        )
    }
}

@Composable
private fun StatusRow(icon: ImageVector, tone: StatusTone, label: String, value: String) {
    val toneColor = when (tone) {
        StatusTone.OK -> RearCueColors.accent
        StatusTone.ALERT -> RearCueColors.error
        StatusTone.NEUTRAL -> RearCueColors.onBackgroundSecondary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = toneColor,
            modifier = Modifier.size(RearCueIconSize.small),
        )
        Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = RearCueColors.onBackgroundSecondary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = RearCueColors.onBackground,
            )
        }
    }
}

@Composable
private fun RearCard(state: RearBackendState) {
    SectionCard(title = stringResource(R.string.section_rear)) {
        StatusRow(
            icon = if (state.projected) Icons.Outlined.CheckCircle else Icons.Outlined.Close,
            tone = if (state.projected) StatusTone.OK else StatusTone.NEUTRAL,
            label = stringResource(R.string.label_projection_state),
            value = stringResource(
                if (state.projected) R.string.rear_projected else R.string.rear_not_projected,
            ),
        )
        StatusRow(
            icon = Icons.Outlined.Info,
            tone = StatusTone.NEUTRAL,
            label = stringResource(R.string.label_rear_display),
            value = state.rearDisplayId?.toString() ?: "-",
        )
        StatusRow(
            icon = Icons.Outlined.Info,
            tone = StatusTone.NEUTRAL,
            label = stringResource(R.string.label_rear_detail),
            value = state.lastDetail,
        )
    }
}

/**
 * 调试动作。上/下屏在票 #5 之后是自动的（通知事件驱动），这里的「投送到背屏 / 退出」是
 * 绕过自动流转的手动旁路：只用于自查投送链路（无通知时投空集可看纯黑 + 时间）。
 * 票 #25 只翻新外观：动作与分支逐条不变。「退出」**不设**未投送禁用态（#30 review 回退，
 * 票 02 AC「既有调试功能语义不变」）：它是调试旁路，未投送时点击 = 挂起退出请求；
 * 空/禁用态的展示由**非旁路控件**承担（背屏卡「投送状态」行，design_review 状态完备意图保留）。
 */
@Composable
private fun DebugActions(container: AppContainer) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 拒绝时不发通知：系统会静默丢弃，不如什么都不做。
        if (granted) postTestNotification(context)
    }
    SectionCard(title = stringResource(R.string.section_debug_actions)) {
        ActionButton(
            text = stringResource(R.string.action_open_listener_settings),
            icon = Icons.Outlined.Settings,
            filled = false,
            onClick = { context.openListenerSettings() },
        )
        ActionButton(
            text = stringResource(R.string.action_post_test_notification),
            icon = Icons.Outlined.Notifications,
            filled = true,
            onClick = {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    postTestNotification(context)
                } else {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        ActionButton(
            text = stringResource(R.string.action_cancel_test_notification),
            icon = Icons.Outlined.Delete,
            filled = false,
            onClick = { cancelTestNotification(context) },
        )
        ActionButton(
            text = stringResource(R.string.action_project_to_rear),
            icon = Icons.Outlined.Send,
            filled = true,
            onClick = {
                if (container.rearBackend.permissionRequired()) {
                    container.rearBackend.requestPermission()
                } else {
                    container.projectToRear()
                }
            },
        )
        ActionButton(
            text = stringResource(R.string.action_exit_rear),
            icon = Icons.Outlined.ExitToApp,
            filled = false,
            onClick = { container.exitRear() },
        )
    }
}

/** 动作按钮（调试旁路与引导横幅共用，中性名，#30 review）：全宽、触控目标 ≥48dp、
 *  按压反馈（缩放 [RearCueMotion.pressFeedbackMs] + ripple）。不设禁用态（#30 review 回退）：
 *  控件不承担状态展示，状态完备由状态卡/背屏卡负责。 */
@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(RearCueShape.medium)
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = RearCueTouch.minTarget)
        .pressFeedback(interactionSource)
    if (filled) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = RearCueColors.accent,
                contentColor = RearCueColors.onAccent,
            ),
            interactionSource = interactionSource,
        ) {
            ActionButtonContent(text, icon)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = RearCueColors.onBackground,
            ),
            interactionSource = interactionSource,
        ) {
            ActionButtonContent(text, icon)
        }
    }
}

@Composable
private fun RowScope.ActionButtonContent(text: String, icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(RearCueIconSize.medium),
    )
    Spacer(Modifier.width(RearCueSpacing.sm))
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}

/** 通知使用权设置页；个别 ROM 没有 detail 页时退回列表页。 */
private fun Context.openListenerSettings() {
    runCatching { startActivity(listenerSettingsIntent(this)) }
        .onFailure {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
}
