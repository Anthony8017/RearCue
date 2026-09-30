package com.rearcue.poc.rear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTouch

/**
 * 会话选择器的一行（spec 0016 / 票 #156，:rear 的渲染模型）：内容全部来自同一份列表投影
 * （app 层 `AgentStateLogic.projectRoster` 映射成渲染行），本层只渲染、零决策——排序、
 * 标题派生、来源标记、选中态都不在这里算。
 *
 * [sessionId] 为 null = 「自动」档（回到谁忙看谁）；[waiting] = 该会话在等待确认（置顶行的
 * 非文字视觉标记）；[selected] = 当前 Session Lock 所在档。
 */
data class AgentPickerRow(
    val sessionId: String?,
    val title: String,
    val sourceLabel: String?,
    val waiting: Boolean,
    val selected: Boolean,
)

/**
 * 背屏会话选择器浮层（spec 0016 / 票 #156；溢出与关闭带按票 #160 修订）：Agent 页会话标识行
 * 单击打开的全窗列表。
 *
 * - 打开/关闭的决策全在 [com.rearcue.poc.core.DashboardCore]（`agentPicker` 投影），本层按投影挂撤；
 * - 条目 = 标题 + 来源标记 + 等待确认标记 + 选中标记，每行不小于 [RearCueTouch.minTarget]（48dp）；
 * - **列表最多完整展示 [AgentPickerParams.VISIBLE_ROWS] 行**，其余在列表内滚动，底部恒留一条
 *   看得见的空白关闭带——点它即「点列表外」关闭（票 #160：不再只剩相机带那条看不见的空白）；
 * - 点条目 = 选定（走 app 层 Session Lock 单入口）+ 关闭；**不超时自动关**；
 * - 不响不震：无涟漪、无系统反馈（沿背屏触控既有口径）；
 * - 文字落在 [SafeArea.flushReadingViewport] 内（spec 0019 版心贴缘：左贴相机带右缘、
 *   右上下贴屏缘，与 Agent 会话页同一套口径）；角部避让开档时整列上下再让
 *   [SafeArea.columnArcInsetPx]（不逐行测量，以列外接矩形为单位）。卡底与 Detail 卡片同款铺满整屏。
 */
@Composable
internal fun AgentPickerLayer(
    rows: List<AgentPickerRow>,
    rules: SafeArea,
    cornerPx: Int,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** 角部避让（spec 0019 / 票 #194）：关＝贴满缺角认了（默认）；开＝整列上下内缩出弧区。 */
    cornerAvoidance: Boolean = false,
) {
    val density = LocalDensity.current
    val baseViewport = rules.flushReadingViewport()
    // 列是整宽矩形（不逐行测量），角部避让以列外接矩形为单位：上下各让列宽触角块所需的内缩。
    val viewport = if (cornerAvoidance) {
        val inset = rules.columnArcInsetPx(baseViewport.left, baseViewport.right)
        baseViewport.copy(
            top = (baseViewport.top + inset).coerceAtMost(baseViewport.bottom),
            bottom = (baseViewport.bottom - inset).coerceAtLeast(baseViewport.top),
        )
    } else {
        baseViewport
    }
    if (viewport.width <= 0 || viewport.height <= 0) return
    val cd = stringResource(R.string.agent_picker_cd)

    // 行数（票 #160 / #162 评审）：尽量 3 行，但必须给底部关闭带留下最小可见高度（屏太矮就退行），
    // 列表上限随之收紧——关闭带是**算出来的不变量**，不是行数够少时的副产品。
    val rowHeightPx = with(density) { RearCueTouch.minTarget.roundToPx() }
    val gapPx = with(density) { RearCueSpacing.xs.roundToPx() }
    val minStripPx = with(density) { AgentPickerParams.MIN_STRIP_DP.dp.roundToPx() }
    val visibleRows = AgentPickerParams.visibleRows(viewport.height, rowHeightPx, gapPx, minStripPx)
    val listMaxHeight = with(density) {
        AgentPickerParams.listMaxHeightPx(rowHeightPx, gapPx, visibleRows).toDp()
    }

    Box(
        modifier = Modifier
            .fullscreenPlacement()
            .semantics { contentDescription = cd }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() }
            .clip(RoundedCornerShape(cornerPx.coerceAtLeast(0).toFloat()))
            .background(RearCueColors.background),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = with(density) { viewport.left.toDp() },
                    top = with(density) { viewport.top.toDp() },
                    end = with(density) { (rules.windowWidth - viewport.right).toDp() },
                    // 阅读视口的下缘 pad 照留（票 #162 评审：文字不进圆角/相机带区）；
                    // 列表之下到视口下缘的空白就是关闭带——点它 = 点列表外。
                    bottom = with(density) { (rules.windowHeight - viewport.bottom).toDp() },
                )
                .heightIn(max = listMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            rows.forEach { row -> AgentPickerItem(row, onPick) }
        }
    }
}

/** 一行条目：整行可点（点按即选定并关闭由上层处理），触控目标 ≥48dp。 */
@Composable
private fun AgentPickerItem(row: AgentPickerRow, onPick: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RearCueTouch.minTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onPick(row.sessionId) },
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 等待确认标记：accent 圆点（非文字——背屏不引入状态词，与「去状态词」判例一致）。
        Box(
            modifier = Modifier
                .size(AGENT_PICKER_MARK_DP.dp)
                .clip(CircleShape)
                .background(if (row.waiting) RearCueColors.accent else Color.Transparent),
        )
        Text(
            text = if (row.sessionId == null) stringResource(R.string.agent_picker_auto) else row.title,
            color = RearCueColors.onBackground,
            fontSize = AGENT_PICKER_TITLE_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.sourceLabel != null) {
            Text(
                text = row.sourceLabel,
                color = RearCueColors.accent,
                fontSize = AGENT_PICKER_SOURCE_SP.sp,
                maxLines = 1,
            )
        }
        // 选中标记：实心 accent 圆点（与主屏列表的选中圆点同语言，尺寸随背屏略小）。
        if (row.selected) {
            Box(
                modifier = Modifier
                    .size(AGENT_PICKER_SELECTED_DP.dp)
                    .clip(CircleShape)
                    .background(RearCueColors.accent),
            )
        }
    }
}

/** 条目标题字号（sp）：背屏列表一行一档，比镜像正文略小、比来源标记明显。 */
private const val AGENT_PICKER_TITLE_SP = 15f

/** 来源标记字号（sp）。 */
private const val AGENT_PICKER_SOURCE_SP = 11f

/** 等待确认标记直径（dp）。 */
private const val AGENT_PICKER_MARK_DP = 8

/** 选中标记直径（dp）。 */
private const val AGENT_PICKER_SELECTED_DP = 12
