package com.rearcue.poc.rear

import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.readingGutterFloorPx
import com.rearcue.poc.rear.MirrorScrollPolicy.Follow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 会话标识行样式（票 #161 起固定在屏幕顶部；Detail 卡片的标题仍走 [CenteredReadingText] 内联）。 */
private val MIRROR_HEADING_STYLE = TextStyle(
    color = RearCueColors.onBackgroundSecondary,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/**
 * Agent Mirror 页面层：只显示小字会话名与输出正文，不显示状态词和动作行。
 * 与通知详情共用 [CenteredReadingText]：每行水平居中、短内容整体垂直居中，上下最少 8px；
 * 文字避开相机带，圆角只增加受影响首尾行的纵向留白，不收窄整篇。
 * 滚动仍遵循 [MirrorScrollPolicy]：新输出跟随到底，上滑暂停、回到底部或点 ↓ 恢复。
 * 等待确认的会话名脉冲及 `agent pulse end` 日志契约保持不变。
 *
 * 翻页手感（spec 0013 / 票 #133）：
 * - [scroll] / [emptyReplyScroll] / [follow] 由页面级持有（[RearDashboardActivity]），交叉淡出
 *   期间本层被换出组合也不丢历史回看位置；重新入组合时由滚动值对齐跟随态。
 * - 点按正文经 [onBodyTap] 上报内容页切换，点按会话标识行经 [onHeadingTap] 上报开/关会话列表
 *   （spec 0016 / 票 #156：入口在标识行，不改正文的点按语义）；**拖动仍归滚动容器**，点 ↓ 只
 *   恢复实时跟随（[MirrorScrollPolicy.onResumeTap]），三者都不互相顶替。
 * - [interactive] = 本层是否为当前内容页：离场层不接点按，过渡期不残留旧页手势。
 * - 会话标识行**固定在屏幕顶部**（票 #161，机主定夺）：长正文跟随/回看时入口不被滚走，正文
 *   只在其余区域内按既有版式滚动，Detail 卡片的「标题+正文整体居中」不受影响。
 */
@Composable
fun AgentMirrorLayer(
    state: AgentSessionState,
    rules: SafeArea,
    scroll: ScrollState,
    emptyReplyScroll: ScrollState,
    follow: Follow,
    onFollowChange: (Follow) -> Unit,
    interactive: Boolean,
    onBodyTap: () -> Unit,
    modifier: Modifier = Modifier,
    onHeadingTap: () -> Unit = onBodyTap,
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

    // 滚动状态由页面级持有（票 #133），不挂在 reply 非空的条件子树下。
    val scope = rememberCoroutineScope()
    val heading = AgentSessionDisplay.title(state)
    val reply = state.latestReply?.takeIf { it.isNotBlank() }.orEmpty()

    // 双帧对齐：新文本测量前 maxValue 还是旧值，重排后再次对齐；回看态不被新输出打断。
    // 评审修复：长驻 snapshotFlow 不能闭包捕获旧 [follow]——上滑暂停后父层已改为 PAUSED，
    // 捕获的 FOLLOWING 会把小幅下拖（未触底）误判回 FOLLOWING。用 rememberUpdatedState
    // 在协程内读取最新状态/回调，语义对齐 origin/main 的局部 MutableState 实现。
    val latestFollow by rememberUpdatedState(follow)
    val latestOnFollowChange by rememberUpdatedState(onFollowChange)
    LaunchedEffect(reply, heading) {
        if (reply.isNotEmpty() && MirrorScrollPolicy.shouldFollowNewOutput(latestFollow)) {
            scroll.scrollTo(scroll.maxValue)
            withFrameNanos {}
            scroll.scrollTo(scroll.maxValue)
        }
    }
    LaunchedEffect(scroll) {
        var last = scroll.value
        snapshotFlow { scroll.value }.collect { value ->
            latestOnFollowChange(
                MirrorScrollPolicy.onValueChange(latestFollow, last, value, scroll.maxValue),
            )
            last = value
        }
    }

    Box(modifier.fillMaxSize().semantics { contentDescription = cd }) {
        // 会话标识行固定在屏幕顶部（票 #161）：长正文跟随/回看时都留在屏上，入口随时可点。
        // 正文视口整体下移这一段（topReservePx），其余阅读规则逐字不变。
        val gutterPx = with(density) { readingGutterFloorPx() }
        val viewport = rules.detailTextViewport(gutterPx)
        val headingReservePx = remember(density, viewport) {
            if (viewport.width <= 0 || viewport.height <= 0) {
                0
            } else {
                // 预留 = 标识行行高 + 一档间距：正文首行（含滚动中半截的那行）与标识行留出清晰间隔。
                with(density) { MIRROR_HEADING_STYLE.lineHeight.toPx() + RearCueSpacing.sm.toPx() }.roundToInt()
            }
        }
        if (headingReservePx > 0) {
            Text(
                text = heading,
                style = MIRROR_HEADING_STYLE.copy(textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = with(density) { viewport.left.toDp() },
                        end = with(density) { (rules.windowWidth - viewport.right).toDp() },
                        top = with(density) { viewport.top.toDp() },
                    )
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (pulseActive) pulseAlpha.value else 1f }
                    // 点标识行开会话列表（spec 0016 / 票 #156）；固定后不再随正文滚走。
                    .clickableOnTap(onHeadingTap.takeIf { interactive }),
            )
        }
        CenteredReadingText(
            // 标题行已固定在顶部，本件只渲染正文（票 #161）。
            heading = "",
            body = reply,
            headingStyle = MIRROR_HEADING_STYLE,
            bodyStyle = TextStyle(
                color = RearCueColors.onBackground,
                fontSize = AgentMirrorParams.REPLY_SP_BASE.sp,
                lineHeight = 24.sp,
            ),
            rules = rules,
            // 空正文用独立视口，避免其 maxValue=0 把历史回看位置钳回顶部。
            scroll = if (reply.isEmpty()) emptyReplyScroll else scroll,
            // 点按正文切回通知页（票 #133）；拖动由滚动容器消费，不触发回调。
            onTap = onBodyTap.takeIf { interactive },
            topReservePx = headingReservePx,
        )

        // 浮动按钮独立避让圆角，不能为了放按钮而收窄所有正文；离场层不接点按（过渡期防误触）。
        if (interactive && reply.isNotEmpty() && follow == MirrorScrollPolicy.Follow.PAUSED) {
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
                    // ↓ 只恢复实时跟随：本点击被按钮消费，不上报内容页切换（票 #133）。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onFollowChange(MirrorScrollPolicy.onResumeTap())
                        scope.launch { scroll.scrollTo(scroll.maxValue) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "↓", color = RearCueColors.onBackground, fontSize = 18.sp)
            }
        }
    }
}
