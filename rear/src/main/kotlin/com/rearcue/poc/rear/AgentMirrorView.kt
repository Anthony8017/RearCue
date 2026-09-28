package com.rearcue.poc.rear

import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.readingGutterFloorPx
import kotlinx.coroutines.delay

/**
 * Agent Mirror 内容层（spec 0010 / 票 #84；呈现重排 grilling #113）：
 * 背屏 Dashboard 的第五种内容，core 内容层仲裁（WFA > 通知 > agent）选中时整屏显示。
 *
 * 呈现（grilling #113「去状态词、正文最大化」）：**不显示**「工作中/等你确认/空闲」状态词与
 * 「正在 xxx」动作行——顶部只留一行极小、低对比的会话标识（workspace，分辨镜像的是哪个会话），
 * 其余全部高度归会话输出正文（核心阅读面）。等待确认的视觉标记由另一张票实现（grilling Q14）；
 * 本层的脉冲动效宿主移到会话标识行（强调窗内标识行呼吸，播完仍打 `agent pulse end`
 * 词形契约，见 [DashboardCore.LOG_AGENT_PULSE_CONTRACT]）。
 *
 * 布局口径（票 #86 实机修订沿用）：回复区独占剩余高度、超长内部滚动。字号档来自
 * [AgentMirrorParams] 纯函数，水平留白照 [SafeArea.textHorizontalPadding] 落
 * [RearCueSpacing.readingGutter] 左缘地板（与 Detail 同观感，grill #89 定案）、右距屏缘
 * 8px 排满、文字进圆角弧区右缘自动外扩（票 #97，与 Detail 同一纯函数出口），本层零几何决策；
 * 层底全屏含相机带（Detail 判例），但**可读文字不进带**（相机模组会把带内文字物理挡住）。
 */
@Composable
fun AgentMirrorLayer(
    state: AgentSessionState,
    rules: SafeArea,
    modifier: Modifier = Modifier,
    pulseUntilMs: Long = 0L,
) {
    val density = LocalDensity.current
    // 上下内边距一处定值：它同时给出文字块的垂直占位（右距弧区外扩的判据，票 #97）。
    val verticalGutter = 32.dp
    val textPad = with(density) {
        val gutterPx = verticalGutter.roundToPx()
        rules.textHorizontalPadding(
            designGutterPx = readingGutterFloorPx(),
            textTopPx = gutterPx,
            textBottomPx = rules.windowHeight - gutterPx,
        )
    }
    val cd = stringResource(R.string.agent_mirror_cd)
    Log.d(
        "RearCue",
        "agent-mirror compose status=${state.status} ws=${state.workspace} reply=${state.latestReply?.length}",
    )

    // 等待确认的视觉强调（spec 0010 / 票 #85）：强调窗内会话标识行脉冲（0.45→1.0 往返），
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
                top = verticalGutter,
                end = with(density) { textPad.end.toDp() },
                bottom = verticalGutter,
            )
            .semantics { contentDescription = cd },
    ) {
        // 一行小字会话标识（grilling #113：极小、低对比；状态词与动作行已删）。
        // 等确认强调的呼吸宿主也在这里（标识行是唯一的头部元素）。
        state.workspace?.takeIf { it.isNotBlank() }?.let { workspace ->
            Text(
                text = workspace,
                color = RearCueColors.onBackgroundSecondary,
                fontSize = 12.sp,
                modifier = Modifier.graphicsLayer { alpha = if (pulseActive) pulseAlpha.value else 1f },
            )
        }

        // 正文区：独占其余全部高度（内容最大化）、内部滚动——会话输出是核心阅读面。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
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
