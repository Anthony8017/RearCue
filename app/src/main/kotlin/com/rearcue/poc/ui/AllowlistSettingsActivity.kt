package com.rearcue.poc.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rearcue.poc.AppState
import com.rearcue.poc.R
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueIconSize
import com.rearcue.poc.design.RearCueShape
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTheme
import com.rearcue.poc.design.RearCueTouch
import com.rearcue.poc.design.safeAreaPadding
import com.rearcue.poc.rear.resolveApp
import java.text.Collator

/**
 * 设置页（spec 0005）：Allowlist 管理唯一主题。
 *
 * 每行 = 应用图标 + 应用名 + 包名 + 「通知中」活徽标 + 移除按钮；按应用名系统排序
 * （[Collator]）。被卸载的在册应用显示「未安装」灰色态、不自动剔除（重装自动恢复生效）。
 * 移除即时生效：容器发 [com.rearcue.poc.core.DashboardEvent.Allowlist] 并写盘，
 * 本页不做任何决策（零决策搬运，spec 0004 的分层约束沿用）。
 */
class AllowlistSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as RearCueApp).container
            AllowlistSettingsScreen(
                state = container.state.collectAsState().value,
                onRemove = container::removeAllowlistApp,
            )
        }
    }
}

@Composable
private fun AllowlistSettingsScreen(state: AppState, onRemove: (String) -> Unit) {
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
                if (state.allowlist.isEmpty()) {
                    EmptyState()
                } else {
                    AllowlistRows(state, onRemove)
                }
            }
        }
    }
}

@Composable
private fun Header() {
    val activity = LocalContext.current as? ComponentActivity
    Column(verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { activity?.finish() },
                modifier = Modifier.heightIn(min = RearCueTouch.minTarget),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back_cd),
                    tint = RearCueColors.onBackground,
                    modifier = Modifier.size(RearCueIconSize.medium),
                )
            }
            Text(
                text = stringResource(R.string.settings_allowlist_title),
                style = MaterialTheme.typography.headlineSmall,
                color = RearCueColors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            text = stringResource(R.string.settings_allowlist_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = RearCueColors.onBackgroundSecondary,
        )
    }
}

/** 名单区：整块卡片，行按应用名系统排序（同名退包名字典序，顺序稳定可预期）。 */
@Composable
private fun AllowlistRows(state: AppState, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    val collator = remember { Collator.getInstance() }
    val rows = remember(state.allowlist) {
        state.allowlist
            .map { pkg -> pkg to context.packageManager.resolveApp(pkg, 0).label }
            .sortedWith { a, b ->
                collator.compare(a.second, b.second).let { if (it != 0) it else a.first.compareTo(b.first) }
            }
    }
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
            text = stringResource(R.string.settings_allowlist_count, state.allowlist.size),
            style = MaterialTheme.typography.labelMedium,
            color = RearCueColors.onBackgroundSecondary,
        )
        rows.forEach { (pkg, _) -> AllowlistRow(pkg, active = pkg in state.iconSet, onRemove = onRemove) }
    }
}

/** 一行：图标 + 名称/包名/活徽标 + 移除；触控目标 ≥[RearCueTouch.minTarget]。 */
@Composable
private fun AllowlistRow(pkg: String, active: Boolean, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    val size = RearCueIconSize.medium
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val installed = remember(pkg) {
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
    }
    val app = remember(pkg, sizePx) { context.packageManager.resolveApp(pkg, sizePx) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RearCueTouch.minTarget),
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.icon, app.label, size, dim = !installed)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = RearCueColors.onBackground,
                )
                if (!installed) {
                    Text(
                        text = stringResource(R.string.settings_row_not_installed),
                        style = MaterialTheme.typography.labelMedium,
                        color = RearCueColors.onBackgroundDisabled,
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(RearCueColors.surfaceHighlight)
                            .padding(horizontal = RearCueSpacing.sm, vertical = RearCueSpacing.xs),
                    )
                }
            }
            Text(
                text = pkg,
                style = MaterialTheme.typography.labelSmall,
                color = RearCueColors.onBackgroundSecondary,
            )
            if (active) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(RearCueColors.accent),
                    )
                    Text(
                        text = stringResource(R.string.settings_row_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = RearCueColors.accent,
                    )
                }
            }
        }
        IconButton(
            onClick = { onRemove(pkg) },
            modifier = Modifier.heightIn(min = RearCueTouch.minTarget),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.settings_row_remove_cd, app.label),
                tint = RearCueColors.onBackgroundSecondary,
                modifier = Modifier.size(RearCueIconSize.medium),
            )
        }
    }
}

/** 应用图标；解析不到退化为首字母方块（既有 PackageIcon 判例：错误态不崩）。 */
@Composable
private fun AppIcon(icon: Painter?, label: String, size: androidx.compose.ui.unit.Dp, dim: Boolean) {
    val alpha = if (dim) 0.4f else 1f
    if (icon != null) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier
                .size(size)
                .alpha(alpha),
        )
    } else {
        val shape = RoundedCornerShape(RearCueShape.medium)
        Box(
            modifier = Modifier
                .size(size)
                .alpha(alpha)
                .clip(shape)
                .background(RearCueColors.surfaceHighlight)
                .border(1.dp, RearCueColors.outline, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = RearCueColors.onBackground,
            )
        }
    }
}

/** 空态：空名单 = Icon Set 恒空 = 永不投送（核心状态机自然得出，spec 0005）。 */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RearCueSpacing.lg),
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
            text = stringResource(R.string.settings_allowlist_empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = RearCueColors.onBackground,
        )
        Text(
            text = stringResource(R.string.settings_allowlist_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = RearCueColors.onBackgroundSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
