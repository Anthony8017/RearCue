package com.rearcue.poc.rear

import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.design.RearCueColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Agent Mirror 内容层：只显示小字会话名与输出正文，不显示状态词和动作行。
 * 与通知详情共用 [CenteredReadingText]：每行水平居中、短内容整体垂直居中，上下最少 8px；
 * 文字避开相机带，圆角只增加受影响首尾行的纵向留白，不收窄整篇。
 * 滚动仍遵循 [MirrorScrollPolicy]：新输出跟随到底，上滑暂停、回到底部或点 ↓ 恢复。
 * 等待确认的会话名脉冲及 `agent pulse end` 日志契约保持不变。
 */
@Composable
fun AgentMirrorLayer(
    state: AgentSessionState,
    rules: SafeArea,
    modifier: Modifier = Modifier,
    pulseUntilMs: Long = 0L,
) {
    val density = LocalDensity.current
    val cd = stringResource(R.string.agent_mirror_cd)
    Log.d(
        "RearCue",
        "agent-mirror compose status=${state.status} ws=${state.workspace} reply=${state.latestReply?.length}",
    )

    // 等待确认的视觉强调：强调窗内会话标识行脉冲；播完日志词形是外部契约。
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

    // 不挂在 reply 非空的条件子树下，正文瞬时空缺不重建滚动状态。
    val scroll = rememberScrollState()
    val emptyReplyScroll = rememberScrollState()
    var follow by remember { mutableStateOf(MirrorScrollPolicy.Follow.FOLLOWING) }
    val scope = rememberCoroutineScope()
    val workspace = state.workspace?.takeIf { it.isNotBlank() }.orEmpty()
    val reply = state.latestReply?.takeIf { it.isNotBlank() }.orEmpty()

    // 双帧对齐：新文本测量前 maxValue 还是旧值，重排后再次对齐；回看态不被新输出打断。
    LaunchedEffect(reply, workspace) {
        if (reply.isNotEmpty() && MirrorScrollPolicy.shouldFollowNewOutput(follow)) {
            scroll.scrollTo(scroll.maxValue)
            withFrameNanos {}
            scroll.scrollTo(scroll.maxValue)
        }
    }
    LaunchedEffect(scroll) {
        var last = scroll.value
        snapshotFlow { scroll.value }.collect { value ->
            follow = MirrorScrollPolicy.onValueChange(follow, last, value, scroll.maxValue)
            last = value
        }
    }

    Box(modifier.fillMaxSize().semantics { contentDescription = cd }) {
        CenteredReadingText(
            heading = workspace,
            body = reply,
            headingStyle = TextStyle(
                color = RearCueColors.onBackgroundSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            bodyStyle = TextStyle(
                color = RearCueColors.onBackground,
                fontSize = AgentMirrorParams.REPLY_SP_BASE.sp,
                lineHeight = 24.sp,
            ),
            rules = rules,
            // 空正文用独立视口，避免其 maxValue=0 把历史回看位置钳回顶部。
            scroll = if (reply.isEmpty()) emptyReplyScroll else scroll,
            headingModifier = Modifier.graphicsLayer { alpha = if (pulseActive) pulseAlpha.value else 1f },
        )

        // 浮动按钮独立避让圆角，不能为了放按钮而收窄所有正文。
        if (reply.isNotEmpty() && follow == MirrorScrollPolicy.Follow.PAUSED) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = with(density) { (rules.windowWidth - rules.layoutRect.right).toDp() },
                        bottom = with(density) { (rules.windowHeight - rules.layoutRect.bottom).toDp() },
                    )
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(RearCueColors.surfaceHighlight)
                    .border(1.dp, RearCueColors.outline, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        follow = MirrorScrollPolicy.onResumeTap()
                        scope.launch { scroll.scrollTo(scroll.maxValue) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "↓", color = RearCueColors.onBackground, fontSize = 18.sp)
            }
        }
    }
}
