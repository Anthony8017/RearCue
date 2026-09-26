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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rearcue.poc.AppState
import com.rearcue.poc.R
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.core.DashboardCore
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueIconSize
import com.rearcue.poc.design.RearCueMotion
import com.rearcue.poc.design.RearCueShape
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTheme
import com.rearcue.poc.design.RearCueTouch
import com.rearcue.poc.design.pressFeedback
import com.rearcue.poc.design.safeAreaPadding
import com.rearcue.poc.feed.AutoDismissPolicy
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
 * 开关/数字输入只吐布尔与时长值，容器发 [com.rearcue.poc.core.DashboardEvent.PrivacyMode]/
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
                onChargingChange = container::setChargingAnimationEnabled,
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
    onChargingChange: (Boolean) -> Unit,
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
                // 充电区（spec 0007 票 #57）：状态与写入口由本页注入（同横幅区的参数口径），
                // 两区各自独立增删、互不依赖。
                ChargingSettingsSection(
                    chargingEnabled = state.chargingEnabled,
                    onChargingChange = onChargingChange,
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

/**
 * 横幅区（spec 0007 story 3/5 / 票 #56）：Privacy Mode 开关 + Auto-dismiss 时长，Allowlist 区之后的第二张卡片。
 *
 * 零决策搬运：本区不自持档位——显示值是容器按 core 重建的 [AppState]（档位唯一事实在 core），
 * 交互只吐布尔/时长值交给容器（发事件 + 写盘）。开关即时作用于在屏横幅、改档即时重排在走的计时，
 * 都由容器的 `refresh` 带出，页内不做任何显隐判断。卡片壳与开关行取共享件
 * （[SettingsSectionCard] / [SettingsSwitchRow]），与充电区同一件。
 */
@Composable
private fun BannerSection(
    privacyMode: Boolean,
    autoDismissMs: Long,
    onPrivacyModeChange: (Boolean) -> Unit,
    onAutoDismissChange: (Long) -> Unit,
) {
    SettingsSectionCard(title = stringResource(R.string.settings_banner_section)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_privacy_mode),
            description = stringResource(R.string.settings_privacy_mode_desc),
            checked = privacyMode,
            onCheckedChange = onPrivacyModeChange,
        )
        AutoDismissSetting(durationMs = autoDismissMs, onValueChange = onAutoDismissChange)
    }
}

/**
 * Auto-dismiss 设置（spec 0007 story 5 / 票 #56）：数字输入 + 单位档（秒/分/时）+ 无上限独立行。
 *
 * 取值域「5 秒～无上限」的收口在 [AutoDismissPolicy]（纯类可测）：**任意整数秒 ≥5 秒都可达**
 * （7 秒、10 分、2 小时不再是档位外值），无上限只经独立行进入、开启时输入与单位档停用
 * （输入框保留最后一条有限值，关闭即回填）。交互只吐时限值交给容器（发事件 + 写盘，改档
 * 即时作用于在走的计时）；非法/空/低于 5 秒的输入不落值、失焦回退到上一个生效值。
 * 触控目标 ≥[RearCueTouch.minTarget]，纯黑 + 单一强调色令牌，零决策搬运。
 */
@Composable
private fun AutoDismissSetting(
    durationMs: Long,
    onValueChange: (Long) -> Unit,
) {
    // 无上限没有数值条目：回退默认 10 秒条目（无上限开启时输入本就停用，回填只在关闭时发生）。
    val fallback = AutoDismissPolicy.entryOf(durationMs) ?: AutoDismissPolicy.defaultEntry()
    var unit by remember { mutableStateOf(fallback.unit) }
    var field by remember { mutableStateOf(TextFieldValue(text = fallback.amount.toString())) }
    var focused by remember { mutableStateOf(false) }
    val unlimited = AutoDismissPolicy.isUnlimited(durationMs)
    val valueContentDescription = stringResource(R.string.settings_autodismiss_value_cd)

    // 外部改值（存储首读/恢复/调试页写入）在失焦时对齐输入框；聚焦期间以输入为准（打字不被打断）。
    LaunchedEffect(durationMs) {
        if (focused) return@LaunchedEffect
        val entry = AutoDismissPolicy.entryOf(durationMs) ?: return@LaunchedEffect
        if (AutoDismissPolicy.parseInput(field.text, unit) != durationMs) {
            unit = entry.unit
            field = TextFieldValue(text = entry.amount.toString())
        }
    }

    /** 输入收口：只留数字；解析通过才落值（非法 = 保持不动，失焦时回退）。 */
    val onTextInput: (TextFieldValue) -> Unit = { input ->
        val digits = input.text.filter { it.isDigit() }
        field = if (digits == input.text) {
            input
        } else {
            TextFieldValue(text = digits, selection = TextRange(digits.length))
        }
        AutoDismissPolicy.parseInput(digits, unit)?.let(onValueChange)
    }

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
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            OutlinedTextField(
                value = field,
                onValueChange = onTextInput,
                enabled = !unlimited,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.titleLarge.copy(color = RearCueColors.accent),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RearCueColors.accent,
                    unfocusedBorderColor = RearCueColors.outline,
                    disabledBorderColor = RearCueColors.outline,
                    disabledTextColor = RearCueColors.onBackgroundDisabled,
                ),
                shape = RoundedCornerShape(RearCueShape.medium),
                modifier = Modifier
                    .widthIn(min = 88.dp, max = 132.dp)
                    .heightIn(min = RearCueTouch.minTarget)
                    // PC 实验脚本（tools/ex 21）按这个抓手定位输入框：ASCII，各语言一致。
                    .semantics { contentDescription = valueContentDescription }
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            // 聚焦即全选：手输与 `adb shell input text` 都直接覆盖旧值。
                            focused = true
                            field = field.copy(selection = TextRange(0, field.text.length))
                        } else if (focused) {
                            focused = false
                            // 失焦收口：解析不过（空/非数字/低于 5 秒）回退到上一个生效值。
                            if (AutoDismissPolicy.parseInput(field.text, unit) != durationMs) {
                                unit = fallback.unit
                                field = TextFieldValue(text = fallback.amount.toString())
                            }
                        }
                    },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.xs)) {
                AutoDismissPolicy.Unit.entries.forEach { candidate ->
                    UnitChip(
                        label = when (candidate) {
                            AutoDismissPolicy.Unit.SECONDS -> stringResource(R.string.settings_unit_second)
                            AutoDismissPolicy.Unit.MINUTES -> stringResource(R.string.settings_unit_minute)
                            AutoDismissPolicy.Unit.HOURS -> stringResource(R.string.settings_unit_hour)
                        },
                        selected = candidate == unit,
                        enabled = !unlimited,
                        onClick = {
                            unit = candidate
                            AutoDismissPolicy.parseInput(field.text, candidate)?.let(onValueChange)
                        },
                    )
                }
            }
        }
    }
    SettingsSwitchRow(
        title = stringResource(R.string.settings_autodismiss_unlimited),
        description = stringResource(R.string.settings_autodismiss_unlimited_desc),
        checked = unlimited,
        onCheckedChange = { enable ->
            if (enable) {
                onValueChange(AutoDismissPolicy.UNLIMITED_MS)
            } else {
                // 关闭回填输入框里最后一条有限值；不可用（首开即无上限）时回默认 10 秒。
                onValueChange(
                    AutoDismissPolicy.parseInput(field.text, unit) ?: DashboardCore.AUTO_DISMISS_DEFAULT_MS,
                )
            }
        },
    )
}

/**
 * 单位档（秒/分/时）：一枚 ≥[RearCueTouch.minTarget] 的可点文本，选中态用强调色轨道令牌
 * （与开关同一套颜色语义）；无上限开启时停用并降为禁用色。
 */
@Composable
private fun UnitChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(RearCueShape.medium)
    val active = selected && enabled
    Box(
        modifier = Modifier
            .widthIn(min = RearCueTouch.minTarget)
            .heightIn(min = RearCueTouch.minTarget)
            .clip(shape)
            .background(if (active) RearCueColors.accent else RearCueColors.surfaceHighlight)
            .border(1.dp, if (active) RearCueColors.accent else RearCueColors.outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = RearCueSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> RearCueColors.onBackgroundDisabled
                selected -> RearCueColors.onAccent
                else -> RearCueColors.onBackground
            },
        )
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
