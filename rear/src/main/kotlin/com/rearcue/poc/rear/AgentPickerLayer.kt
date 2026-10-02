package com.rearcue.poc.rear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.design.RearCueColors
import kotlin.math.max

/**
 * 会话选择器的一行（spec 0016 / 票 #156，:rear 的渲染模型）：内容全部来自同一份列表投影
 * （app 层 `AgentStateLogic.projectRoster` 映射成渲染行），本层只渲染、零决策。
 *
 * [sessionId] 为 null = 「自动」档（回到谁忙看谁）；[title]/[subtitle] 是两行显示投影；
 * [status] 是会话状态，左侧状态点颜色与状态光带共用五档；[selected] = 当前 Session Lock 所在档。
 */
data class AgentPickerRow(
    val sessionId: String?,
    val title: String,
    val subtitle: String?,
    val status: AgentStatus?,
    val selected: Boolean,
)

/**
 * 背屏会话选择器浮层：Agent 页会话标识行单击打开的全窗列表。
 *
 * 2026-10-02 收口：
 * - 同一屏恒定 5 个完整条目，不显示半截；角部避让开/关都保持 5 条，只改变每条的垂直高度；
 * - 超过 5 条时自由滚动，停稳/惯性结束后按单条边界吸附；
 * - 左侧是状态点（颜色与 Status Glow 一致、点静止），自动行无状态点；
 * - 右侧选中圆点删除，选中行改中性浅灰描边；
 * - 会话多时底部出现极淡向下提示，不显示页码。
 */
@Composable
internal fun AgentPickerLayer(
    rows: List<AgentPickerRow>,
    linkStatus: BridgeLinkStatus,
    rules: SafeArea,
    cornerPx: Int,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    textSize: MirrorTextSize = MirrorTextSize.MEDIUM,
    cornerAvoidance: Boolean = false,
) {
    val density = LocalDensity.current
    val viewport = rules.flushReadingViewport(cornerAvoidance)
    if (viewport.width <= 0 || viewport.height <= 0) return
    val cd = stringResource(R.string.agent_picker_cd)

    val rowHeightPx = AgentPickerParams.rowHeightPx(viewport.height)
    if (rowHeightPx <= 0 || rows.isEmpty()) return
    val rowHeightDp = with(density) { rowHeightPx.toDp() }
    val visibleHeightDp = with(density) { AgentPickerParams.visibleHeightPx(viewport.height).toDp() }
    val listHeightDp = if (rows.size <= AgentPickerParams.PAGE_ROWS) {
        rowHeightDp * rows.size
    } else {
        visibleHeightDp
    }

    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = SnapPosition.Start)
    val hasMore by remember(rows.size) {
        derivedStateOf {
            listState.firstVisibleItemIndex < rows.size - AgentPickerParams.PAGE_ROWS
        }
    }

    Box(
        modifier = Modifier
            .fullscreenPlacement()
            .semantics { contentDescription = cd }
            .clip(RoundedCornerShape(cornerPx.coerceAtLeast(0).toFloat()))
            .background(RearCueColors.background),
    ) {
        // 关闭热区只放列表后面的背景层：列表自身必须独占手势，否则拖动会穿透成内容页切换。
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = with(density) { viewport.left.toDp() },
                    top = with(density) { viewport.top.toDp() },
                    end = with(density) { (rules.windowWidth - viewport.right).toDp() },
                    bottom = with(density) { (rules.windowHeight - viewport.bottom).toDp() },
                )
                .height(listHeightDp),
            userScrollEnabled = true,
        ) {
            items(rows, key = { it.sessionId ?: "auto" }) { row ->
                AgentPickerItem(
                    row = row,
                    linkStatus = linkStatus,
                    textSize = textSize,
                    rowHeightDp = rowHeightDp,
                    onPick = onPick,
                )
            }
        }

        if (hasMore) {
            Text(
                text = "⌄",
                color = RearCueColors.onBackgroundSecondary.copy(alpha = 0.45f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 1.dp),
            )
        }
    }
}

/** 一行条目：固定五等分行高，滚动停下时按条目边界吸附，不会停在半截条目。 */
@Composable
private fun AgentPickerItem(
    row: AgentPickerRow,
    linkStatus: BridgeLinkStatus,
    textSize: MirrorTextSize,
    rowHeightDp: Dp,
    onPick: (String?) -> Unit,
) {
    val statusColor = row.status?.let { AgentMirrorParams.statusColor(it, linkStatus) }
    val rowShape = RoundedCornerShape(6.dp)
    val selectedModifier = if (row.selected) {
        Modifier
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .clip(rowShape)
            .border(
                width = AgentPickerParams.SELECTED_BORDER_DP.dp,
                color = RearCueColors.onBackgroundSecondary.copy(alpha = 0.65f),
                shape = rowShape,
            )
    } else {
        Modifier.padding(horizontal = 3.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeightDp)
            .then(selectedModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onPick(row.sessionId) }
            .padding(horizontal = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 自动行不画状态点，但保留同宽占位，标题左缘保持对齐。
        Box(
            modifier = Modifier
                .size(AgentPickerParams.STATUS_DOT_DP.dp)
                .clip(CircleShape)
                .background(statusColor ?: Color.Transparent),
        )

        val reading = AgentMirrorParams.reading(textSize)
        val maxLineSp = rowHeightDp.value * 0.48f
        val textScale = minOf(
            1f,
            maxLineSp / max(reading.headingSp, reading.headingSubtitleSp),
        )
        val headingSp = reading.headingSp * textScale
        val subtitleSp = reading.headingSubtitleSp * textScale

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (row.sessionId == null) stringResource(R.string.agent_picker_auto) else row.title,
                color = RearCueColors.onBackground,
                fontSize = headingSp.sp,
                lineHeight = (headingSp + 0.5f).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.subtitle != null) {
                Text(
                    text = row.subtitle,
                    color = RearCueColors.onBackgroundSecondary,
                    fontSize = subtitleSp.sp,
                    lineHeight = (subtitleSp + 0.5f).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
