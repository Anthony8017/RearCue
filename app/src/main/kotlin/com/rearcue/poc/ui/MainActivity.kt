package com.rearcue.poc.ui

import android.Manifest
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rearcue.poc.AppContainer
import com.rearcue.poc.R
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.core.PocAllowlist
import com.rearcue.poc.notification.ActiveNotificationListener
import com.rearcue.poc.notify.cancelTestNotification
import com.rearcue.poc.notify.isListenerEnabled
import com.rearcue.poc.notify.listenerSettingsIntent
import com.rearcue.poc.notify.postTestNotification

private val IconSize = 48.dp

/**
 * 主屏调试页：实时展示 Icon Set（票 #3 的验收面），并提供通知授权入口与测试通知按钮。
 *
 * 背屏投送（票 #4/#5）尚未接入；本页只反映「通知 → Icon Set」这条链路。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state = rememberIconSetState((application as RearCueApp).container)
            MainScreen(state)
        }
    }
}

/** Icon Set 展示状态：每次仓库事件或 ON_RESUME 后从容器重读（[revision] 只用于触发重组）。 */
private class IconSetState(
    val iconSet: List<String>,
    val listenerConnected: Boolean,
    val trackedCount: Int,
    val listenerEnabled: Boolean,
    val lastCause: String,
    @Suppress("unused") val revision: Int,
)

@Composable
private fun rememberIconSetState(container: AppContainer): IconSetState {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    var listenerEnabled by remember { mutableStateOf(false) }

    // 订阅式读取：仓库事件到达就推动重组，不需要 Activity 轮询。
    DisposableEffect(container) {
        val listener = ActiveNotificationListener { revision++ }
        container.repository.subscribe(listener)
        onDispose { container.repository.unsubscribe(listener) }
    }

    // ON_RESUME 再兜一次「授权/设置页返回」「服务连接早于本页创建」这类窗口。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listenerEnabled = isListenerEnabled(context)
                revision++
            }
        }
        listenerEnabled = isListenerEnabled(context)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return IconSetState(
        iconSet = container.iconSet.value,
        listenerConnected = container.listenerConnected.value,
        trackedCount = container.trackedCount.value,
        listenerEnabled = listenerEnabled,
        lastCause = container.lastCause.value,
        revision = revision,
    )
}

@Composable
private fun MainScreen(state: IconSetState) {
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
            StatusCard(state)
            DebugActions()
        }
    }
}

@Composable
private fun IconSetCard(state: IconSetState) {
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

/** 一枚图标：Allowlist App 的应用图标；解析不到时退化为包名首字母。 */
@Composable
private fun PackageIcon(pkg: String) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val icon = remember(pkg, sizePx) { context.packageManager.resolveIcon(pkg, sizePx) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = pkg,
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
                Text(text = pkg.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            text = pkg.substringAfterLast('.'),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusCard(state: IconSetState) {
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
                    if (state.listenerEnabled) R.string.listener_enabled else R.string.listener_denied,
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
                text = stringResource(R.string.last_event_line, state.lastCause),
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
private fun android.content.Context.openListenerSettings() {
    runCatching { startActivity(listenerSettingsIntent(this)) }
        .onFailure {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
}

/** 解析应用图标为 Compose Painter；包不可见或解析失败返回 null。 */
private fun PackageManager.resolveIcon(pkg: String, sizePx: Int): Painter? = runCatching {
    val icon = getApplicationIcon(pkg)
    val bitmap: ImageBitmap = icon.toBitmap(width = sizePx, height = sizePx).asImageBitmap()
    BitmapPainter(bitmap) as Painter
}.getOrNull()
