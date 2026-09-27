package com.rearcue.poc.rear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Agent Mirror 内容层（spec 0010 / 票 #84）：背屏 Dashboard 的第五种内容，
 * core 仲裁为 AGENT 持有时整屏替换既有内容（优先级链见 CONTEXT.md「Waiting-for-Approval」）。
 *
 * 布局口径：全屏（含相机带，同 Detail 全屏判例）——内容纵排列于水平留白内
 * （留白=短边 8%，[AgentMirrorParams] 纯函数），自上而下：状态标语（等确认档放大）、
 * 工作区名、当前动作一行、最新回复原文（不打码，spec 0010 定案；超长滚动，不做历史回看）。
 * 全部渲染参数来自 [AgentMirrorParams]，本层零几何决策。
 */
@Composable
fun AgentMirrorLayer(
    state: AgentSessionState,
    screenWidthPx: Int,
    screenHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    val shortEdge = minOf(screenWidthPx, screenHeightPx)
    val density = LocalDensity.current
    val horizontalPadding = with(density) { AgentMirrorParams.horizontalPaddingPx(shortEdge).toDp() }
    val replyMaxLines = AgentMirrorParams.replyMaxLines(shortEdge)
    val cd = stringResource(R.string.agent_mirror_cd)
    val actionPrefix = stringResource(R.string.agent_mirror_action_label)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding, vertical = 48.dp)
            .semantics { contentDescription = cd }
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        // 状态标语：等确认放大一级（AgentMirrorParams），强调色点睛。
        Text(
            text = statusLabel(state.status),
            color = if (state.status == AgentStatus.WAITING_FOR_APPROVAL) {
                RearCueColors.accent
            } else {
                RearCueColors.onBackground
            },
            fontSize = AgentMirrorParams.statusSp(state.status).sp,
            fontWeight = FontWeight.Medium,
        )

        state.workspace?.takeIf { it.isNotBlank() }?.let { workspace ->
            Text(
                text = workspace,
                color = RearCueColors.onBackgroundSecondary,
                fontSize = 13.sp,
            )
        }

        // 当前动作一行（有才显示）：「正在」前缀 + 截断后的动作摘要。
        AgentMirrorParams.actionLine(state.currentAction)?.let { action ->
            Text(
                text = "$actionPrefix $action",
                color = RearCueColors.onBackgroundSecondary,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 最新回复原文（不打码）；空则不占位。
        state.latestReply?.takeIf { it.isNotBlank() }?.let { reply ->
            Text(
                text = reply,
                color = RearCueColors.onBackground,
                fontSize = AgentMirrorParams.REPLY_SP_BASE.sp,
                lineHeight = 24.sp,
                maxLines = replyMaxLines,
                modifier = Modifier.fillMaxWidth(),
            )
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
