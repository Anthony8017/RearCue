package com.rearcue.poc.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rearcue.poc.AppState
import com.rearcue.poc.R
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueIconSize
import com.rearcue.poc.design.RearCueMotion
import com.rearcue.poc.design.RearCueShape
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTheme
import com.rearcue.poc.design.RearCueTouch
import com.rearcue.poc.design.pressFeedback
import com.rearcue.poc.design.safeAreaPadding
import com.rearcue.poc.rear.resolveApp
import java.text.Collator

/** 名单行与候选行共用的应用排序：应用名系统序为主键、包名字典序为次键（顺序稳定）。 */
internal fun sortAppEntries(
    entries: List<Pair<String, String>>,
    collator: Collator,
): List<Pair<String, String>> = entries.sortedWith { a, b ->
    collator.compare(a.second, b.second).let { if (it != 0) it else a.first.compareTo(b.first) }
}

/**
 * 设置页：Allowlist 管理（spec 0005）+ 充电总开关（spec 0007 / 票 #57）+ 姿态门控开关
 * （票 #100）三个主题，各成一张卡片。
 *
 * Allowlist 每行 = 应用图标 + 应用名 + 包名 + 「通知中」活徽标 + 移除按钮；按应用名系统排序
 * （[Collator]）。被卸载的在册应用显示「未安装」灰色态、不自动剔除（重装自动恢复生效）。
 * 移除即时生效：容器发 [com.rearcue.poc.core.DashboardEvent.Allowlist] 并写盘，
 * 本页不做任何决策（零决策搬运，spec 0004 的分层约束沿用）。
 * （spec 0008：原第二张卡片「横幅区」（Privacy Mode / Auto-dismiss，票 #56）随横幅退役删除，
 * 判例见 docs/specs/0008-rear-visual-notification-highlight.md。）
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
                onAdd = container::addAllowlistApp,
                onChargingChange = container::setChargingAnimationEnabled,
                onPostureGateChange = container::setPostureGateEnabled,
            )
        }
    }
}

@Composable
private fun AllowlistSettingsScreen(
    state: AppState,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
    onChargingChange: (Boolean) -> Unit,
    onPostureGateChange: (Boolean) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
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
                // 「添加应用」：空名单与有名单都在（空名单 = Icon Set 恒空 = 永不投送，更要能加）。
                AddAppButton(onClick = { pickerOpen = true })
                // 充电区（spec 0007 票 #57）：状态与写入口由本页注入，本件不自取容器。
                ChargingSettingsSection(
                    chargingEnabled = state.chargingEnabled,
                    onChargingChange = onChargingChange,
                )
                // 姿态区（票 #100）：姿态门控开关（默认关）+ 只读姿态状态行，同款注入。
                PostureSettingsSection(
                    postureGateEnabled = state.postureGateEnabled,
                    postureFaceDown = state.postureFaceDown,
                    onPostureGateChange = onPostureGateChange,
                )
            }
            if (pickerOpen) {
                AppPickerSheet(
                    allowlist = state.allowlist,
                    onAdd = { onAdd(it); pickerOpen = false },
                    onDismiss = { pickerOpen = false },
                )
            }
        }
    }
}

/** 主操作按钮：全宽、强调色、触控目标 ≥[RearCueTouch.minTarget]、按压反馈（票 #25 判例）。 */
@Composable
private fun AddAppButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RearCueTouch.minTarget)
            .pressFeedback(interactionSource),
        shape = RoundedCornerShape(RearCueShape.medium),
        colors = ButtonDefaults.buttonColors(
            containerColor = RearCueColors.accent,
            contentColor = RearCueColors.onAccent,
        ),
        interactionSource = interactionSource,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(RearCueIconSize.medium),
        )
        Spacer(Modifier.size(RearCueSpacing.sm))
        Text(text = stringResource(R.string.action_add_app), style = MaterialTheme.typography.labelLarge)
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

/** 名单区：整块卡片（同 [SettingsSectionCard]），行按应用名系统排序（同名退包名字典序，顺序稳定可预期）。 */
@Composable
private fun AllowlistRows(state: AppState, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    val collator = remember { Collator.getInstance() }
    val rows = remember(state.allowlist) {
        val entries = state.allowlist.map { pkg ->
            pkg to context.packageManager.resolveApp(pkg, 0).label
        }
        sortAppEntries(entries, collator)
    }
    SettingsSectionCard(title = stringResource(R.string.settings_allowlist_count, state.allowlist.size)) {
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
private fun EmptyState() {    Column(
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

/**
 * App Picker（spec 0005 #46）：设备上可桌面启动的应用（launcher intent 查询，
 * 无桌面入口的系统组件自然不在列），搜索框同时匹配应用名与包名；点选即加即关。
 * 已在册的应用可见但不可点（带勾选态），不重复添加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    allowlist: List<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val collator = remember { Collator.getInstance() }
    // 候选全集只算一次：包名 + 应用名（图标逐行按需解析，避免几百个 toBitmap 一口气算）。
    val candidates = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching { pm.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo?.packageName }
            .distinct()
            .map { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrElse { pkg.substringAfterLast('.') }
                pkg to label
            }
            .let { sortAppEntries(it.toList(), collator) }
            .toList()
    }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val filtered = remember(candidates, q) {
        if (q.isEmpty()) candidates else candidates.filter { (pkg, label) ->
            label.contains(q, ignoreCase = true) || pkg.contains(q, ignoreCase = true)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RearCueSpacing.md)
                .padding(bottom = RearCueSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.picker_title),
                style = MaterialTheme.typography.titleSmall,
                color = RearCueColors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = {
                    Text(stringResource(R.string.picker_search_hint))
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = RearCueColors.onBackgroundSecondary,
                    )
                },
                shape = RoundedCornerShape(RearCueShape.medium),
                modifier = Modifier.fillMaxWidth(),
            )
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.picker_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = RearCueColors.onBackgroundSecondary,
                    modifier = Modifier.padding(vertical = RearCueSpacing.lg),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(filtered, key = { it.first }) { (pkg, label) ->
                        PickerRow(pkg = pkg, label = label, enrolled = pkg in allowlist, onAdd = onAdd)
                    }
                }
            }
        }
    }
}

/** 候选行：图标 + 名称/包名；在册 → 勾选态不可点；未在册 → 点选即加。 */
@Composable
private fun PickerRow(pkg: String, label: String, enrolled: Boolean, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    val size = RearCueIconSize.medium
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val app = remember(pkg, sizePx) { context.packageManager.resolveApp(pkg, sizePx) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RearCueTouch.minTarget)
            .then(if (enrolled) Modifier else Modifier.clickable { onAdd(pkg) }),
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.icon, app.label, size, dim = false)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enrolled) RearCueColors.onBackgroundSecondary else RearCueColors.onBackground,
            )
            Text(
                text = pkg,
                style = MaterialTheme.typography.labelSmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        if (enrolled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = RearCueColors.accent,
                    modifier = Modifier.size(RearCueIconSize.small),
                )
                Text(
                    text = stringResource(R.string.picker_enrolled),
                    style = MaterialTheme.typography.labelSmall,
                    color = RearCueColors.onBackgroundSecondary,
                )
            }
        } else {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.picker_add_cd, label),
                tint = RearCueColors.onBackgroundSecondary,
                modifier = Modifier.size(RearCueIconSize.medium),
            )
        }
    }
}
