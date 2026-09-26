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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
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
import com.rearcue.poc.feed.AutoDismissSteps
import com.rearcue.poc.feed.AutoDismissSteps.DurationLabel
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
 * 设置页：Allowlist 管理（spec 0005）+ 横幅区（spec 0007 / 票 #56）两主题，各成一张卡片。
 *
 * Allowlist 每行 = 应用图标 + 应用名 + 包名 + 「通知中」活徽标 + 移除按钮；按应用名系统排序
 * （[Collator]）。被卸载的在册应用显示「未安装」灰色态、不自动剔除（重装自动恢复生效）。
 * 移除即时生效：容器发 [com.rearcue.poc.core.DashboardEvent.Allowlist] 并写盘，
 * 本页不做任何决策（零决策搬运，spec 0004 的分层约束沿用）——横幅区同口径：
 * 开关/步进只吐档位值，容器发 [com.rearcue.poc.core.DashboardEvent.PrivacyMode]/
 * [com.rearcue.poc.core.DashboardEvent.AutoDismiss] 并写 FeedSettingsStore。
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
                onPrivacyModeChange = container::setFeedPrivacyMode,
                onAutoDismissChange = container::setFeedAutoDismissMs,
            )
        }
    }
}

@Composable
private fun AllowlistSettingsScreen(
    state: AppState,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
    onPrivacyModeChange: (Boolean) -> Unit,
    onAutoDismissChange: (Long) -> Unit,
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
                // 横幅区（spec 0007 / 票 #56）：Allowlist 区之后的第二张卡片。
                BannerSection(
                    privacyMode = state.feedPrivacyMode,
                    autoDismissMs = state.feedAutoDismissMs,
                    onPrivacyModeChange = onPrivacyModeChange,
                    onAutoDismissChange = onAutoDismissChange,
                )
                // 充电区（spec 0007 票 #57）：独立成件、自取状态自发送，与横幅区互不依赖。
                ChargingSettingsSection()
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

/**
 * 横幅区（spec 0007 story 3/5 / 票 #56）：Privacy Mode 开关 + Auto-dismiss 时长，Allowlist 区之后的第二张卡片。
 *
 * 零决策搬运：本区不自持档位——显示值是容器按 core 重建的 [AppState]（档位唯一事实在 core），
 * 交互只吐布尔/档位值交给容器（发事件 + 写盘）。开关即时作用于在屏横幅、改档即时重排在走的计时，
 * 都由容器的 `refresh` 带出，页内不做任何显隐判断。
 *
 * 无 Switch/Slider 判例（票 #56 首个开关/步进件）：开关 = 整行 [Modifier.toggleable]
 * （触控目标 ≥[RearCueTouch.minTarget]、[Role.Switch] 语义）+ 自绘轨道滑块；时长 = 两枚步进钮
 * （端点禁用）+ 强调色当前值。颜色/间距/形状全取令牌，纯黑 + 单一强调色不变。
 */
@Composable
private fun BannerSection(
    privacyMode: Boolean,
    autoDismissMs: Long,
    onPrivacyModeChange: (Boolean) -> Unit,
    onAutoDismissChange: (Long) -> Unit,
) {
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
            text = stringResource(R.string.settings_banner_section),
            style = MaterialTheme.typography.labelMedium,
            color = RearCueColors.onBackgroundSecondary,
        )
        PrivacyModeRow(checked = privacyMode, onCheckedChange = onPrivacyModeChange)
        AutoDismissRow(durationMs = autoDismissMs, onValueChange = onAutoDismissChange)
    }
}

/** Privacy Mode 行：整行可点（≥[RearCueTouch.minTarget]）+ 自绘开关；换档即时作用于当前横幅。 */
@Composable
private fun PrivacyModeRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RearCueTouch.minTarget)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.settings_privacy_mode),
                style = MaterialTheme.typography.bodyLarge,
                color = RearCueColors.onBackground,
            )
            Text(
                text = stringResource(R.string.settings_privacy_mode_desc),
                style = MaterialTheme.typography.labelSmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        PrivacySwitch(checked = checked)
    }
}

/**
 * 开关件（票 #56 首个，无既有判例）：轨道 + 滑块，只做显示——触控与 [Role.Switch] 语义由整行承担。
 * 开 = 强调色轨道 + 黑滑块，关 = 面内高亮轨 + 次要色滑块（单色令牌，不用第二强调色）。
 */
@Composable
private fun PrivacySwitch(checked: Boolean) {
    val track = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(track)
            .background(if (checked) RearCueColors.accent else RearCueColors.surfaceHighlight)
            .border(1.dp, if (checked) RearCueColors.accent else RearCueColors.outline, track),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(RearCueSpacing.xs)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) RearCueColors.onAccent else RearCueColors.onBackgroundSecondary),
        )
    }
}

/**
 * Auto-dismiss 行：左标签/说明 + 右步进（减号、当前值、加号；当前值取强调色）。
 * 档位表与端点判定在 [AutoDismissSteps]（纯类可测）；5 秒档与无上限档端点禁用。
 */
@Composable
private fun AutoDismissRow(durationMs: Long, onValueChange: (Long) -> Unit) {
    val current = AutoDismissSteps.coerce(durationMs)
    val canShorten = AutoDismissSteps.canShorten(current)
    val canLengthen = AutoDismissSteps.canLengthen(current)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RearCueTouch.minTarget),
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.settings_autodismiss),
                style = MaterialTheme.typography.bodyLarge,
                color = RearCueColors.onBackground,
            )
            Text(
                text = stringResource(R.string.settings_autodismiss_desc),
                style = MaterialTheme.typography.labelSmall,
                color = RearCueColors.onBackgroundSecondary,
            )
        }
        IconButton(
            onClick = { onValueChange(AutoDismissSteps.previous(current)) },
            enabled = canShorten,
            modifier = Modifier.heightIn(min = RearCueTouch.minTarget),
        ) {
            Text(
                text = stringResource(R.string.settings_autodismiss_minus),
                style = MaterialTheme.typography.titleLarge,
                color = if (canShorten) RearCueColors.onBackground else RearCueColors.onBackgroundDisabled,
            )
        }
        Text(
            text = AutoDismissLabel(current),
            style = MaterialTheme.typography.labelLarge,
            color = RearCueColors.accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 64.dp), // 换档只换字不挪两侧按钮
        )
        IconButton(
            onClick = { onValueChange(AutoDismissSteps.next(current)) },
            enabled = canLengthen,
            modifier = Modifier.heightIn(min = RearCueTouch.minTarget),
        ) {
            Text(
                text = stringResource(R.string.settings_autodismiss_plus),
                style = MaterialTheme.typography.titleLarge,
                color = if (canLengthen) RearCueColors.onBackground else RearCueColors.onBackgroundDisabled,
            )
        }
    }
}

/** 当前档位文案：形态决策在 [AutoDismissSteps.label]（JVM 判例），本层只按形态选资源。 */
@Composable
private fun AutoDismissLabel(durationMs: Long): String {
    val label = AutoDismissSteps.label(durationMs)
    return when (label.kind) {
        DurationLabel.Kind.UNLIMITED -> stringResource(R.string.settings_autodismiss_unlimited)
        DurationLabel.Kind.MINUTES -> stringResource(R.string.settings_autodismiss_minutes, label.value)
        DurationLabel.Kind.SECONDS -> stringResource(R.string.settings_autodismiss_seconds, label.value)
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
        val entries = state.allowlist.map { pkg ->
            pkg to context.packageManager.resolveApp(pkg, 0).label
        }
        sortAppEntries(entries, collator)
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
