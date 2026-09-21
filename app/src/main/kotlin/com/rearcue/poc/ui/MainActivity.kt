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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import com.rearcue.poc.notify.cancelTestNotification
import com.rearcue.poc.notify.isListenerEnabled
import com.rearcue.poc.notify.listenerSettingsIntent
import com.rearcue.poc.notify.postTestNotification

private val IconSize = 48.dp

/**
 * 主屏调试页：实时展示 Icon Set（票 #3 的验收面），并提供通知授权入口与测试通知按钮。
 *
 * Icon Set 由 [com.rearcue.poc.core.DashboardCore] 算出（Android 层不做决策）；
 * 本页只读 [AppContainer.state]。背屏投送（票 #4/#5）尚未接入。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val container = (application as RearCueApp).container
            MainScreen(state = container.state.collectAsState().value)
        }
    }
}

@Composable
private fun MainScreen(state: AppState) {
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            IconSetCard(state)
            StatusCard(state, listenerEnabled)
            DebugActions()
        }
    }
}

@Composable
private fun IconSetCard(state: AppState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.icon_set_title, state.iconSet.size),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.iconSet.isEmpty()) {
                Text(
                    text = stringResource(R.string.icon_set_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.iconSet.forEach { pkg -> PackageIcon(pkg) }
                }
            }
        }
    }
}

/** 一枚图标：Allowlist App 的应用图标 + 应用名；解析不到时退化为包名首字母。 */
@Composable
private fun PackageIcon(pkg: String) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val app = remember(pkg, sizePx) { context.packageManager.resolveApp(pkg, sizePx) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (app.icon != null) {
            Icon(
                painter = app.icon,
                contentDescription = app.label,
                tint = Color.Unspecified,
                modifier = Modifier.size(IconSize),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(IconSize)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = app.label.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusCard(state: AppState, listenerEnabled: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.listenerConnected) {
                        R.string.listener_connected
                    } else {
                        R.string.listener_disconnected
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    if (listenerEnabled) R.string.listener_enabled else R.string.listener_denied,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.allowlist_line, PocAllowlist.APPS.joinToString(" ")),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.tracked_line, state.trackedCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.last_event_line, state.lastEvent),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DebugActions() {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 拒绝时不发通知：系统会静默丢弃，不如什么都不做。
        if (granted) postTestNotification(context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { context.openListenerSettings() }) {
            Text(stringResource(R.string.action_open_listener_settings))
        }
        Button(
            onClick = {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    postTestNotification(context)
                } else {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        ) {
            Text(stringResource(R.string.action_post_test_notification))
        }
        OutlinedButton(onClick = { cancelTestNotification(context) }) {
            Text(stringResource(R.string.action_cancel_test_notification))
        }
    }
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
