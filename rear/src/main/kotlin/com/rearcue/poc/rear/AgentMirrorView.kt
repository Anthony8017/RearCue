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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.rear.MirrorScrollPolicy.Follow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 链路状态点直径（dp，票 #165）：非文字标记，与选择器里的等待/选中圆点同族但更小。 */
private const val AGENT_LINK_DOT_DP = 8

/**
 * Agent Mirror 页面层（spec 0010；spec 0017 / 票 #169 改版）：**左对齐问答流**——会话标识行与
 * agent 输出同一条左缘，机主提问走右锚提问泡；不显示状态词和动作行（#113）。
 *
 * 与通知详情的关系（spec 0017 最硬边界）：**不再共用阅读版式**。[DetailText] 仍走
 * [CenteredReadingText] 的「每行居中 + 标题正文整体居中 + 右距 8px」；本页走
 * `agentReadingViewport`（右距 16dp）与左对齐。共用件只剩行框工具与几何纯函数，两页互不渗漏。
 *
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
 * - 会话标识行**固定在屏幕顶部**（票 #161，机主定夺）：由 [AgentReadingText] 排在滚动区之外，
 *   长正文跟随/回看时入口不被滚走。
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
    /** PC 桥链路状态（票 #165）：画成会话标识行旁的非文字状态点；判据见 [AgentMirrorParams.linkDot]。 */
    linkStatus: BridgeLinkStatus = BridgeLinkStatus.DISABLED,
    /** 正文档位（spec 0017 / 票 #169）：主屏设置的投影，本层零决策照单执行。 */
    textSize: MirrorTextSize = MirrorTextSize.MEDIUM,
) {
    val density = LocalDensity.current
    val cd = stringResource(R.string.agent_mirror_cd)
    Log.d(
        "RearCue",
        "agent-mirror compose status=${state.status} ws=${state.workspace} " +
            "turns=${state.turns.size} reply=${state.latestReply?.length}",
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

    // 滚动状态由页面级持有（票 #133），不挂在正文非空的条件子树下。
    val scope = rememberCoroutineScope()
    val heading = AgentSessionDisplay.title(state)
    val liveTurns = state.readingTurns()

    // 回看时屏上静止（spec 0017 / 票 #169）：进入回看那一刻把问答流与版式参数一起冻下来，
    // 新输出在后台攒着；点 ↓ 或滚回底部回跟随态时才一次性接上最新——「读着读着整屏跳走」
    // 就此消失。判据收口在 [MirrorScrollPolicy]（纯函数，有判例），这里只存快照。
    var frozenTurns by remember { mutableStateOf(emptyList<AgentTurn>()) }
    var frozenTextSize by remember { mutableStateOf(textSize) }
    LaunchedEffect(liveTurns, follow, textSize) {
        if (MirrorScrollPolicy.shouldApplyLayoutUpdate(follow)) {
            frozenTurns = liveTurns
            frozenTextSize = textSize
        }
    }
    val effectiveTextSize = if (MirrorScrollPolicy.shouldApplyLayoutUpdate(follow)) textSize else frozenTextSize
    val turns = MirrorScrollPolicy.effectiveTurns(
        state = follow,
        frozen = frozenTurns,
        live = liveTurns,
        // 内容层撤了就不给冻结快照（留残影与「内容层撤了就撤了」的口径冲突）。
        contentAvailable = liveTurns.isNotEmpty(),
    )

    // 双帧对齐：新文本测量前 maxValue 还是旧值，重排后再次对齐；回看态不被新输出打断。
    // 评审修复：长驻 snapshotFlow 不能闭包捕获旧 [follow]——上滑暂停后父层已改为 PAUSED，
    // 捕获的 FOLLOWING 会把小幅下拖（未触底）误判回 FOLLOWING。用 rememberUpdatedState
    // 在协程内读取最新状态/回调，语义对齐 origin/main 的局部 MutableState 实现。
    val latestFollow by rememberUpdatedState(follow)
    val latestOnFollowChange by rememberUpdatedState(onFollowChange)
    LaunchedEffect(turns, heading) {
        if (turns.isNotEmpty() && MirrorScrollPolicy.shouldFollowNewOutput(latestFollow)) {
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
        // 标识行本体由 [AgentReadingText] 画在滚动区之外；本层只留一条**等高手势带**——
        // 从这条带起手的上滑照旧打断跟随进回看（票 #162 评审：固定标识行不能把顶部手势区
        // 挖成死区）。带宽是纯几何（[AgentMirrorParams.headingReservePx]，有 JVM 判例）。
        val viewport = rules.agentReadingViewport(density)
        val reading = AgentMirrorParams.reading(textSize)
        val headingBandPx = remember(density, viewport, reading) {
            if (viewport.width <= 0 || viewport.height <= 0) {
                0
            } else {
                with(density) {
                    AgentMirrorParams.headingReservePx(
                        lineHeightPx = reading.headingLineHeightSp.sp.toPx(),
                        gapPx = RearCueSpacing.xs.toPx(),
                    )
                }
            }
        }
        if (headingBandPx > 0) {
            val gestureShim = rememberScrollableState { delta -> scroll.dispatchRawDelta(-delta) }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = with(density) { viewport.left.toDp() },
                        end = with(density) { (rules.windowWidth - viewport.right).toDp() },
                        top = with(density) { viewport.top.toDp() },
                    )
                    .fillMaxWidth()
                    .height(with(density) { headingBandPx.toDp() })
                    .scrollable(gestureShim, Orientation.Vertical),
            )
            // 会话标识行单击 = 开/关会话列表（spec 0016 / 票 #156）；脉冲也打在这条热区上
            // （本体在滚动区内，热区在它上面 → 本体视觉照旧、点按归热区）。点正文仍是切内容页。
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = with(density) { viewport.left.toDp() },
                        end = with(density) { (rules.windowWidth - viewport.right).toDp() },
                        top = with(density) { viewport.top.toDp() },
                    )
                    .fillMaxWidth()
                    .height(with(density) { headingBandPx.toDp() })
                    .graphicsLayer { alpha = if (pulseActive) pulseAlpha.value else 1f }
                    .clickableOnTap(onHeadingTap.takeIf { interactive }),
            )
        }
        // 链路状态点（票 #165）：非文字、只看颜色——与主屏设置页/主页概览显示的是同一份
        // BridgeLinkStatus（app 层一份事实）。未配置/停用不画点。
        val linkDotColor = when (AgentMirrorParams.linkDot(linkStatus)) {
            AgentMirrorParams.LinkDot.CONNECTED -> RearCueColors.accent
            AgentMirrorParams.LinkDot.PENDING -> RearCueColors.onBackgroundDisabled
            null -> null
        }
        linkDotColor?.let { color ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = with(density) { viewport.left.toDp() },
                        top = with(density) { viewport.top.toDp() },
                    )
                    .size(AGENT_LINK_DOT_DP.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        AgentReadingText(
            turns = turns,
            heading = heading,
            headingReservePx = headingBandPx,
            size = effectiveTextSize,
            rules = rules,
            scroll = scroll,
            // 空正文用独立视口，避免其 maxValue=0 把历史回看位置钳回顶部。
            emptyScroll = emptyReplyScroll,
            // 点按正文切回通知页（票 #133）；拖动由滚动容器消费，不触发回调。
            onBodyTap = onBodyTap.takeIf { interactive },
        )

        // 浮动按钮独立避让圆角，不能为了放按钮而收窄所有正文；离场层不接点按（过渡期防误触）。
        if (interactive && turns.isNotEmpty() && follow == MirrorScrollPolicy.Follow.PAUSED) {
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
