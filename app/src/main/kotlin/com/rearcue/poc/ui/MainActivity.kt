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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import com.rearcue.poc.core.PocAllowlist
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
            if (event == Lifecycle.Event.ON_RESUME) listenerEnabled = isListenerEnabled(context)
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
                Header()
                IconSetCard(state)
                StatusCard(state, listenerEnabled)
                RearCard(rearState)
                DebugActions(container, rearState)
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
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

/** 一枚图标：Allowlist App 的应用图标 + 应用名；解析不到时退化为包名首字母（错误态不崩）。 */
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
            icon = if (listenerEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
            tone = if (listenerEnabled) StatusTone.OK else StatusTone.ALERT,
            label = stringResource(R.string.label_permission),
            value = stringResource(
                if (listenerEnabled) R.string.listener_enabled else R.string.listener_denied,
            ),
        )
        StatusRow(
            icon = Icons.Outlined.List,
            tone = StatusTone.NEUTRAL,
            label = stringResource(R.string.label_allowlist),
            value = PocAllowlist.APPS.joinToString(" "),
        )
        StatusRow(
            icon = Icons.Outlined.Notifications,
            tone = StatusTone.NEUTRAL,
            label = stringResource(R.string.label_active_notifications),
            value = stringResource(R.string.tracked_line, state.trackedCount),
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
 * 票 #25 只翻新外观：动作与分支逐条不变，仅「退出」在未投送时呈禁用态（无可退出的 Dashboard）。
 */
@Composable
private fun DebugActions(container: AppContainer, rearState: RearBackendState) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 拒绝时不发通知：系统会静默丢弃，不如什么都不做。
        if (granted) postTestNotification(context)
    }
    SectionCard(title = stringResource(R.string.section_debug_actions)) {
        DebugButton(
            text = stringResource(R.string.action_open_listener_settings),
            icon = Icons.Outlined.Settings,
            filled = false,
            onClick = { context.openListenerSettings() },
        )
        DebugButton(
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
        DebugButton(
            text = stringResource(R.string.action_cancel_test_notification),
            icon = Icons.Outlined.Delete,
            filled = false,
            onClick = { cancelTestNotification(context) },
        )
        DebugButton(
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
        DebugButton(
            text = stringResource(R.string.action_exit_rear),
            icon = Icons.Outlined.ExitToApp,
            filled = false,
            enabled = rearState.projected,
            onClick = { container.exitRear() },
        )
        if (!rearState.projected) {
            Text(
                text = stringResource(R.string.action_exit_rear_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
    }
}

/** 调试动作按钮：全宽、触控目标 ≥48dp、按压反馈（缩放 [RearCueMotion.pressFeedbackMs] + ripple）。 */
@Composable
private fun DebugButton(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    enabled: Boolean = true,
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
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = RearCueColors.accent,
                contentColor = RearCueColors.onAccent,
                disabledContainerColor = RearCueColors.surfaceHighlight,
                disabledContentColor = RearCueColors.onBackgroundDisabled,
            ),
            interactionSource = interactionSource,
        ) {
            DebugButtonContent(text, icon)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = RearCueColors.onBackground,
                disabledContentColor = RearCueColors.onBackgroundDisabled,
            ),
            interactionSource = interactionSource,
        ) {
            DebugButtonContent(text, icon)
        }
    }
}

@Composable
private fun RowScope.DebugButtonContent(text: String, icon: ImageVector) {
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
