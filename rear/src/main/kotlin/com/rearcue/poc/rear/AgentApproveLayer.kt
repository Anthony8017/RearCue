package com.rearcue.poc.rear

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentPendingOption
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.rear.AgentApproveParams.Layout
import com.rearcue.poc.rear.AgentApproveParams.Target
import kotlin.math.roundToInt

/**
 * 批准浮层的渲染模型（spec 0018-5 / 票 #175，:rear 的渲染面）：内容全部来自 app 层批准判定
 * （[AgentApprovePolicy]）的投影——本层零决策，显隐、入口形态、能力表都不在这里算。
 *
 * [isQuestion] = 提问类（选项点选）；false = 确认类（同意/拒绝）。[summary] 是批准上下文
 * 一句话摘要（可缺省），只做显示。**没有自由文字输入**（ADR 0009）。
 */
data class AgentApprovePrompt(
    val sessionId: String,
    val isQuestion: Boolean,
    val summary: String?,
    val options: List<AgentPendingOption>,
)

/**
 * 背屏批准二次确认浮层（spec 0018-5 / ADR 0009）：全窗一层，压在镜像内容之上。
 *
 * - 第一下点按弹层后本层才挂；**按钮以内点按＝生效、按钮以外＝取消**（含会话标识行区域），
 *   判定全在 [AgentApproveParams]（几何＋生效纯函数，判例锁死），本层照单执行；
 * - 确认类＝「同意/拒绝」两个大按钮（视口内水平二等分、垂直居中）；提问类＝选项行点选；
 * - 不响不震、无涟漪、无系统反馈（沿背屏触控既有口径）；不设超时自动关；
 * - 文字落阅读视口内（避相机带与圆角，与 Detail / Agent Mirror / 会话选择器同口径），
 *   卡底铺满整个背屏（含相机带）。
 */
@Composable
internal fun AgentApproveLayer(
    prompt: AgentApprovePrompt,
    rules: SafeArea,
    cornerPx: Int,
    onTarget: (Target) -> Unit,
) {
    val density = LocalDensity.current
    val viewport = rules.readingViewport(density)
    if (viewport.width <= 0 || viewport.height <= 0) return
    val cd = stringResource(R.string.agent_approve_cd)
    val buttonHeightPx = with(density) { AgentApproveParams.BUTTON_HEIGHT_DP.dp.roundToPx() }
    val buttonGapPx = with(density) { AgentApproveParams.BUTTON_GAP_DP.dp.roundToPx() }
    val rowHeightPx = with(density) { AgentApproveParams.OPTION_ROW_HEIGHT_DP.dp.roundToPx() }
    val rowGapPx = with(density) { AgentApproveParams.OPTION_ROW_GAP_DP.dp.roundToPx() }
    val minRowHeightPx = with(density) { AgentApproveParams.OPTION_ROW_MIN_HEIGHT_DP.dp.roundToPx() }
    val layout: Layout = if (prompt.isQuestion) {
        AgentApproveParams.optionLayout(
            viewportLeft = viewport.left,
            viewportTop = viewport.top,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            optionIds = prompt.options.map { it.id },
            rowHeightPx = rowHeightPx,
            rowGapPx = rowGapPx,
            minRowHeightPx = minRowHeightPx,
        )
    } else {
        AgentApproveParams.confirmLayout(
            viewportLeft = viewport.left,
            viewportTop = viewport.top,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            buttonHeightPx = buttonHeightPx,
            buttonGapPx = buttonGapPx,
        )
    }

    Box(
        modifier = Modifier
            .fullscreenPlacement()
            .semantics { contentDescription = cd }
            // 全层唯一手势面：坐标进 [AgentApproveParams.Layout.hit]，命中交给上层分发。
            .pointerInput(layout) {
                detectTapGestures { offset ->
                    onTarget(layout.hit(offset.x.roundToInt(), offset.y.roundToInt()))
                }
            }
            .clip(RoundedCornerShape(cornerPx.coerceAtLeast(0).toFloat()))
            .background(RearCueColors.background),
    ) {
        // 批准上下文一句话摘要（可缺省）：视口顶部左对齐，两行截断——抬手知道在批什么。
        if (!prompt.summary.isNullOrBlank()) {
            Text(
                text = prompt.summary,
                color = RearCueColors.onBackgroundSecondary,
                fontSize = AGENT_APPROVE_SUMMARY_SP.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset { IntOffset(viewport.left, viewport.top) }
                    .size(
                        with(density) { viewport.width.toDp() },
                        with(density) { (buttonHeightPx * 2).toDp() },
                    ),
            )
        }
        if (prompt.isQuestion) {
            val labelById = prompt.options.associateBy(AgentPendingOption::id, AgentPendingOption::label)
            layout.options.forEach { (id, rect) ->
                OptionRow(label = labelById[id].orEmpty(), rect = rect)
            }
        } else {
            ActionButton(
                label = stringResource(R.string.agent_approve_yes),
                rect = layout.yes,
                accent = true,
            )
            ActionButton(
                label = stringResource(R.string.agent_approve_no),
                rect = layout.no,
                accent = false,
            )
        }
    }
}

/** 确认类的一个大按钮：整块矩形可点（命中判定在 [AgentApproveParams.Layout.hit]）。 */
@Composable
private fun ActionButton(label: String, rect: AgentApproveParams.Rect, accent: Boolean) {
    val density = LocalDensity.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset { IntOffset(rect.left, rect.top) }
            .size(
                with(density) { rect.width.toDp() },
                with(density) { rect.height.toDp() },
            )
            .clip(RoundedCornerShape(AGENT_APPROVE_CORNER_DP.dp))
            .background(if (accent) RearCueColors.accent else RearCueColors.surface),
    ) {
        Text(
            text = label,
            color = if (accent) RearCueColors.onAccent else RearCueColors.onBackground,
            fontSize = AGENT_APPROVE_BUTTON_SP.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** 提问类的一个选项行：整行可点（同二次确认语义——弹层里再点才生效）。 */
@Composable
private fun OptionRow(label: String, rect: AgentApproveParams.Rect) {
    val density = LocalDensity.current
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .offset { IntOffset(rect.left, rect.top) }
            .size(
                with(density) { rect.width.toDp() },
                with(density) { rect.height.toDp() },
            )
            .clip(RoundedCornerShape(AGENT_APPROVE_CORNER_DP.dp))
            .background(RearCueColors.surface),
    ) {
        Text(
            text = label,
            color = RearCueColors.onBackground,
            fontSize = AGENT_APPROVE_OPTION_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = RearCueSpacing.md),
        )
    }
}

/** 确认按钮字号（sp）：背屏大按钮一档。 */
private const val AGENT_APPROVE_BUTTON_SP = 20f

/** 选项行字号（sp）：沿会话选择器条目一档。 */
private const val AGENT_APPROVE_OPTION_SP = 15f

/** 批准上下文摘要字号（sp）：比正文小一档，只做提示。 */
private const val AGENT_APPROVE_SUMMARY_SP = 12f

/** 按钮/选项行圆角（dp）。 */
private const val AGENT_APPROVE_CORNER_DP = 12
