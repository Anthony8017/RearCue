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
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing

/**
 * 会话选择器的一行（spec 0016 / 票 #156，:rear 的渲染模型）：内容全部来自同一份列表投影
 * （app 层 `AgentStateLogic.projectRoster` 映射成渲染行），本层只渲染、零决策——排序、
 * 标题派生、来源标记、选中态都不在这里算。
 *
 * [sessionId] 为 null = 「自动」档（回到谁忙看谁）；[title]/[subtitle] 是两行显示投影；
 * [waiting] = 该会话在等待确认（置顶行的非文字视觉标记）；[selected] = 当前 Session Lock 所在档。
 */
data class AgentPickerRow(
    val sessionId: String?,
    val title: String,
    val subtitle: String?,
    val waiting: Boolean,
    val selected: Boolean,
)

/**
 * 背屏会话选择器浮层（spec 0016 / 票 #156；行高按 spec 0020 / 票 #204 密排、行数上限由票 #211 二次修订废除）：Agent 页
 * 会话标识行单击打开的全窗列表。
 *
 * - 打开/关闭的决策全在 [com.rearcue.poc.core.DashboardCore]（`agentPicker` 投影），本层按投影挂撤；
 * - 条目 = 主行标题 + 副行「来源 · 目录」+ 等待确认标记 + 选中标记，每行
 *   [AgentPickerParams.ROW_HEIGHT_DP]dp（picker 专属两行高，非 48dp 触控目标）；
 * - **列表平铺**（spec 0020 二次修订 / 票 #211）：高度上限＝[SafeArea.flushReadingViewport] 可用高、
 *   行数无上限——会话多时末行可被屏缘截半行（可点，点即选中），其余在列表内滚动；会话少时列表
 *   自然矮、顶对齐，下方留黑。列表外整块浮层背景（相机带、列表下方留黑）都可点关闭（spec 0020
 *   推翻票 #160 的可见关闭带，二次修订又废 5 行上限——不再有任何行数换算）；
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
    /** 副行随 Mirror Text Size 三档联动（issue #213）；两行字号映射共用 [AgentMirrorParams.reading]。 */
    textSize: MirrorTextSize = MirrorTextSize.MEDIUM,
    /** 角部避让（spec 0019 / 票 #194）：关＝贴满缺角认了（默认）；开＝整列上下内缩出弧区。 */
    cornerAvoidance: Boolean = false,
) {
    val density = LocalDensity.current
    // 整列面（不逐行测量）：角部避让的上下内缩收口在 [SafeArea.flushReadingViewport]
    // （纯函数，有 JVM 判例），本层零决策照单执行。
    val viewport = rules.flushReadingViewport(cornerAvoidance)
    if (viewport.width <= 0 || viewport.height <= 0) return
    val cd = stringResource(R.string.agent_picker_cd)

    // 平铺（spec 0020 二次修订 / 票 #211）：列表高度上限＝视口整高，行数无换算——会话多时
    // 末行由视口底缘截断（滚动容器内可见、可点），会话少时列表自然矮于上限、顶对齐。
    val listMaxHeight = with(density) { viewport.height.toDp() }

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
                    // 列表之下到视口下缘的空白属于浮层背景的关闭区——点它 = 点列表外。
                    bottom = with(density) { (rules.windowHeight - viewport.bottom).toDp() },
                )
                .heightIn(max = listMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            rows.forEach { row -> AgentPickerItem(row, textSize, onPick) }
        }
    }
}

/** 一行条目：整行可点（点按即选定并关闭由上层处理），行高按 [AgentPickerParams.ROW_HEIGHT_DP] 密排。 */
@Composable
private fun AgentPickerItem(
    row: AgentPickerRow,
    textSize: MirrorTextSize,
    onPick: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AgentPickerParams.ROW_HEIGHT_DP.dp)
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
        val reading = AgentMirrorParams.reading(textSize)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = if (row.sessionId == null) stringResource(R.string.agent_picker_auto) else row.title,
                color = RearCueColors.onBackground,
                fontSize = reading.headingSp.sp,
                lineHeight = reading.headingLineHeightSp.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.subtitle != null) {
                Text(
                    text = row.subtitle,
                    color = RearCueColors.onBackgroundSecondary,
                    fontSize = reading.headingSubtitleSp.sp,
                    lineHeight = reading.headingSubtitleLineHeightSp.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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


/** 等待确认标记直径（dp）。 */
private const val AGENT_PICKER_MARK_DP = 8

/** 选中标记直径（dp）。 */
private const val AGENT_PICKER_SELECTED_DP = 12
