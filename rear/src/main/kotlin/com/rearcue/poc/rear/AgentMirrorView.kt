package com.rearcue.poc.rear

import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.readingGutterFloorPx
import kotlinx.coroutines.delay

/**
 * Agent Mirror 内容层（spec 0010 / 票 #84）：背屏 Dashboard 的第五种内容，
 * core 仲裁为 AGENT 持有时整屏替换既有内容（优先级链见 CONTEXT.md「Waiting-for-Approval」）。
 *
 * 布局口径（票 #86 实机修订）：**头部块固定、回复区独占剩余高度**——状态标语/工作区/当前动作
 * 一行成不滚动的头部，最新回复原文（不打码）占满其余空间、超长内部滚动。首版曾整体单列滚动，
 * 四元素在小屏上把回复推出视口（回复是本镜像的核心阅读面，不可首屏缺席）。
 * 字号档来自 [AgentMirrorParams] 纯函数，水平留白照 [SafeArea.textHorizontalPadding] 落
 * [RearCueSpacing.readingGutter]（与 Detail 同观感，grill #89 定案），本层零几何决策；
 * 层底全屏含相机带（Detail 判例），但**可读文字不进带**（相机模组会把带内文字物理挡住）。
 */
@Composable
fun AgentMirrorLayer(
    state: AgentSessionState,
    rules: SafeArea,
    screenWidthPx: Int,
    screenHeightPx: Int,
    modifier: Modifier = Modifier,
    pulseUntilMs: Long = 0L,
) {
    val density = LocalDensity.current
    val textPad = with(density) {
        rules.textHorizontalPadding(screenWidthPx, readingGutterFloorPx())
    }
    val cd = stringResource(R.string.agent_mirror_cd)
    val actionPrefix = stringResource(R.string.agent_mirror_action_label)
    Log.d(
        "RearCue",
        "agent-mirror compose status=${state.status} ws=${state.workspace} action=${state.currentAction?.length} reply=${state.latestReply?.length}",
    )

    // 等待确认的视觉强调（spec 0010 / 票 #85）：强调窗内状态标语整块脉冲（0.45→1.0 往返），
    // 一次性非循环、不响不震；播完打 `agent pulse end`（词形契约 LOG_AGENT_PULSE_CONTRACT）。
    val pulseActive = pulseUntilMs > System.currentTimeMillis()
    val pulseAlpha = rememberInfiniteTransition(label = "agentPulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "agentPulseAlpha",
    )
    LaunchedEffect(pulseUntilMs) {
        if (pulseActive) {
            delay(pulseUntilMs - System.currentTimeMillis())
            Log.i("RearCue", "agent pulse end")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = with(density) { textPad.start.toDp() },
                top = 32.dp,
                end = with(density) { textPad.end.toDp() },
                bottom = 32.dp,
            )
            .semantics { contentDescription = cd },
    ) {
        // 头部块（不滚动）：状态标语（等确认放大＋强调色）→ 工作区 → 当前动作一行。
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val waiting = state.status == AgentStatus.WAITING_FOR_APPROVAL
            Text(
                text = statusLabel(state.status),
                color = if (waiting) RearCueColors.accent else RearCueColors.onBackground,
                fontSize = AgentMirrorParams.statusSp(state.status).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.graphicsLayer { alpha = if (pulseActive) pulseAlpha.value else 1f },
            )

            state.workspace?.takeIf { it.isNotBlank() }?.let { workspace ->
                Text(
                    text = workspace,
                    color = RearCueColors.onBackgroundSecondary,
                    fontSize = 13.sp,
                )
            }

            AgentMirrorParams.actionLine(state.currentAction)?.let { action ->
                Text(
                    text = "$actionPrefix $action",
                    color = RearCueColors.onBackgroundSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 回复区：独占剩余高度、内部滚动（spec 0010：回复原文是核心阅读面，不做历史回看）。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            state.latestReply?.takeIf { it.isNotBlank() }?.let { reply ->
                Text(
                    text = reply,
                    color = RearCueColors.onBackground,
                    fontSize = AgentMirrorParams.REPLY_SP_BASE.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun statusLabel(status: AgentStatus): String = stringResource(
    when (status) {
        AgentStatus.WORKING -> R.string.agent_mirror_working
        AgentStatus.WAITING_FOR_APPROVAL -> R.string.agent_mirror_waiting
        AgentStatus.IDLE -> R.string.agent_mirror_idle
    },
)
