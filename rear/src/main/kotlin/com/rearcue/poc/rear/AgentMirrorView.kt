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
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.core.VoiceBroadcastFollow
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.rear.MirrorScrollPolicy.Follow
import kotlinx.coroutines.delay

/** 页面级持有的正文回看快照，插队换出组合时保留。 */
internal class AgentReadingSnapshot(textSize: MirrorTextSize = MirrorTextSize.DEFAULT) {
    var sessionId: String? = null
    var questionIds: List<String> = emptyList()
    val frozenTurns = mutableStateOf(emptyList<AgentTurn>())
    val frozenStatus = mutableStateOf(AgentStatus.IDLE)
    val frozenTextSize = mutableStateOf(textSize)
    var entryGeneration: Long? = null
    val entryApplied = mutableStateOf(false)
    val entryCancelled = mutableStateOf(false)
}

/**
 * Agent Mirror 页面层（spec 0010；spec 0017 / 票 #169 改版）：**左对齐问答流**——agent 输出
 * 左缘恒贴相机带右缘（2026-10-03 机主定夺，不再内缩对齐标识行文字），机主提问走右锚提问泡；
 * 不显示状态词和动作行（#113）。
 *
 * 与通知详情的关系（spec 0017 最硬边界）：**不再共用阅读版式**。[DetailText] 仍走
 * [CenteredReadingText] 的「每行居中 + 标题正文整体居中 + 右距 8px」；本页走
 * [SafeArea.flushReadingViewport]（spec 0019 版心贴缘：左贴相机带右缘、右上下贴屏缘）
 * 与左对齐。共用件只剩行框工具与几何纯函数，两页互不渗漏。
 *
 * 滚动仍遵循 [MirrorScrollPolicy]：新输出跟随到底，上滑暂停、回到底部或点 ↓ 恢复。
 * 等待确认的会话名脉冲及 `agent pulse end` 日志契约保持不变。
 *
 * 翻页手感（spec 0013 / 票 #133）：
 * - [scroll] / [emptyReplyScroll] / [follow] 由页面级持有（[RearDashboardActivity]），交叉淡出
 *   期间本层被换出组合也不丢历史回看位置；重新入组合时由滚动值对齐跟随态。
 * - 点按正文经 [onBodyTap] 上报已阅与内容页切换，点按会话标识行经 [onHeadingTap] 上报开/关会话列表
 *   （spec 0016 / 票 #156：入口在标识行，不改正文的点按语义）；**拖动仍归滚动容器**，点 ↓ 只
 *   恢复实时跟随（[MirrorScrollPolicy.onResumeTap]），三者都不互相顶替。
 * - [interactive] = 本层是否为当前内容页：离场层不接点按，过渡期不残留旧页手势。
 * - 会话标识行**覆盖在屏幕顶部**（票 #161 / spec 0025，机主定夺）：正文先画、标题与
 *   Title Fade Band 后画，长正文跟随/回看时入口不被滚走且可滚入标题下方渐隐。
 */
@Composable
internal fun AgentMirrorLayer(
    state: AgentSessionState,
    /** 两行显示投影（issue #213）：缺省时仍从 [AgentSessionDisplay] 单处派生。 */
    display: AgentSessionDisplay? = null,
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
    /** PC 桥链路状态：只参与会话状态标识的断链灰压档；判据见 [AgentMirrorParams.sessionStatusIndicator]。 */
    linkStatus: BridgeLinkStatus = BridgeLinkStatus.DISABLED,
    /** 正文档位（spec 0017 / 票 #169）：主屏设置的投影，本层零决策照单执行。 */
    textSize: MirrorTextSize = MirrorTextSize.MEDIUM,
    /** 角部避让（spec 0019/0025）：关＝标题与正文贴满缺角认了（默认），开＝标题避让、正文逐行避让。 */
    cornerAvoidance: Boolean = false,
    voiceFollow: VoiceBroadcastFollow? = null,
    voiceFollowActive: Boolean = false,
    onVoiceFollowPause: () -> Unit = {},
    readingSnapshot: AgentReadingSnapshot = remember { AgentReadingSnapshot(textSize) },
    entryRequest: AgentEntryAnchor.Request? = null,
    processChoices: Map<String, Boolean> = emptyMap(),
    onProcessChoice: (String, Boolean) -> Unit = { _, _ -> },
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
    var questionPanelOpen by remember(state.sessionId) { mutableStateOf(false) }
    val headingDisplay = display ?: AgentSessionDisplay.forState(state)
    val liveTurns = state.readingTurns()

    // 回看时屏上静止（spec 0017 / 票 #169）：进入回看那一刻把问答流与版式参数一起冻下来，
    // 新输出在后台攒着；点 ↓ 或滚回底部回跟随态时才一次性接上最新——「读着读着整屏跳走」
    // 就此消失。判据收口在 [MirrorScrollPolicy]（纯函数，有判例），这里只存快照。
    remember(readingSnapshot, entryRequest?.generation) {
        if (readingSnapshot.entryGeneration != entryRequest?.generation) {
            readingSnapshot.entryGeneration = entryRequest?.generation
            readingSnapshot.entryApplied.value = false
            readingSnapshot.entryCancelled.value = false
        }
    }
    var entryApplied by readingSnapshot.entryApplied
    var entryCancelled by readingSnapshot.entryCancelled
    val entryPending = entryRequest != null && !entryApplied && !entryCancelled
    var frozenTurns by readingSnapshot.frozenTurns
    var frozenStatus by readingSnapshot.frozenStatus
    var frozenTextSize by readingSnapshot.frozenTextSize
    // 换会话即作废冻结快照：留着会把**上一个会话**的问答流画在回看态里（Session Lock 切换、
    // 等确认插队都会换会话）。这条不给"回看中屏上静止"让路——静的是内容更新，不是串台。
    val automaticQuestion = state.source != "codex" && state.pendingQuestions.isNotEmpty()
    val automaticQuestionIds = if (automaticQuestion) state.pendingQuestions.map { it.id } else emptyList()
    LaunchedEffect(state.sessionId, automaticQuestionIds) {
        val questionIds = automaticQuestionIds
        if (readingSnapshot.sessionId == state.sessionId && readingSnapshot.questionIds == questionIds) {
            return@LaunchedEffect
        }
        readingSnapshot.sessionId = state.sessionId
        readingSnapshot.questionIds = questionIds
        frozenTurns = liveTurns
        frozenStatus = state.status
        frozenTextSize = textSize
        if (automaticQuestion) {
            onFollowChange(MirrorScrollPolicy.onResumeTap())
        }
    }
    val preserving = follow != Follow.FOLLOWING
    LaunchedEffect(liveTurns, state.status, preserving, entryPending, textSize) {
        if (!preserving || entryPending) {
            frozenTurns = liveTurns
            frozenStatus = state.status
            frozenTextSize = textSize
        }
    }
    val effectiveTextSize = MirrorScrollPolicy.effectiveTextSize(
        state = if (preserving) Follow.PAUSED else follow,
        frozen = frozenTextSize,
        configured = textSize,
    )
    val rawTurns = MirrorScrollPolicy.effectiveTurns(
        state = if (preserving && !entryPending) Follow.PAUSED else Follow.FOLLOWING,
        frozen = frozenTurns,
        live = liveTurns,
    )
    val process = AgentProcessPresentation.project(
        sessionId = state.sessionId,
        turns = rawTurns,
        status = if (preserving && !entryPending) frozenStatus else state.status,
        choices = processChoices,
        pendingQuestion = automaticQuestion,
    )
    val turns = process.turns
    // 回看锁位的运行时读数（spec 0017 验收链判「静止」要靠它）：follow 态 + 两份流各几条。
    // 只在**份数变化**时打一条，免得每帧刷屏。
    LaunchedEffect(follow, liveTurns.size, frozenTurns.size, turns.size) {
        Log.d(
            "RearCue",
            "agent-mirror freeze follow=$follow live=${liveTurns.size} frozen=${frozenTurns.size} shown=${turns.size}",
        )
    }

    // 双帧对齐：新文本测量前 maxValue 还是旧值，重排后再次对齐；回看态不被新输出打断。
    // 评审修复：长驻 snapshotFlow 不能闭包捕获旧 [follow]——上滑暂停后父层已改为 PAUSED，
    // 捕获的 FOLLOWING 会把小幅下拖（未触底）误判回 FOLLOWING。用 rememberUpdatedState
    // 在协程内读取最新状态/回调，语义对齐 origin/main 的局部 MutableState 实现。
    val latestFollow by rememberUpdatedState(follow)
    val latestOnFollowChange by rememberUpdatedState(onFollowChange)
    val latestVoiceFollowActive by rememberUpdatedState(voiceFollowActive)
    val latestOnVoiceFollowPause by rememberUpdatedState(onVoiceFollowPause)
    var voiceProgrammaticScroll by remember { mutableStateOf(false) }
    LaunchedEffect(turns, headingDisplay, follow) {
        if ((!latestVoiceFollowActive || automaticQuestion) &&
            turns.isNotEmpty() && MirrorScrollPolicy.shouldFollowNewOutput(latestFollow)
        ) {
            voiceProgrammaticScroll = true
            try {
                withFrameNanos { }
                scroll.scrollTo(if (automaticQuestion) 0 else scroll.maxValue)
                withFrameNanos { }
                scroll.scrollTo(if (automaticQuestion) 0 else scroll.maxValue)
                withFrameNanos { }
            } finally {
                voiceProgrammaticScroll = false
            }
        }
    }
    LaunchedEffect(scroll.interactionSource, state.sessionId, entryRequest?.generation) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                entryCancelled = true
                latestOnVoiceFollowPause()
            }
        }
    }
    LaunchedEffect(scroll) {
        var last = scroll.value
        snapshotFlow { scroll.value }.collect { value ->
            if (value != last && !voiceProgrammaticScroll) {
                if (latestVoiceFollowActive) latestOnVoiceFollowPause()
                latestOnFollowChange(
                    MirrorScrollPolicy.onValueChange(latestFollow, last, value, scroll.maxValue),
                )
            }
            last = value
        }
    }

    // 会话状态标识：工作中是浅灰 Spinner，其余按已阅/等待/出错显示彩色圆点；桥未连时
    // 旧状态不可信，统一灰点。它不复用 Status Glow 的屏缘颜色。
    val statusIndicator = AgentMirrorParams.sessionStatusIndicator(
        status = state.attentionStatus,
        link = linkStatus,
        readState = state.readState,
    )

    Box(modifier.fillMaxSize().semantics { contentDescription = cd }) {
        // 会话标识行固定在屏幕顶部（票 #161）：长正文跟随/回看时都留在屏上，入口随时可点。
        // **绘制、脉冲、点按热区、手势带、会话状态标识五件事共用这一份几何**（同一个 `viewport.top`
        // 与同一条 `headingBandPx`）——评审抓过一次「只改一半」：标识行被挪下去、热区留在原处，
        // 结果脉冲打在空盒子上、点名字反而切了内容页。
        // 带宽是纯几何（[AgentMirrorParams.headingBandPx]，有 JVM 判例）。
        val viewport = rules.flushReadingViewport()
        val reading = AgentMirrorParams.reading(effectiveTextSize)
        val headingBandPx = with(density) {
            AgentMirrorParams.headingBandPx(reading.headingLineHeightSp.sp.toPx())
        }
        // Corner Avoidance 开时标题也退出圆角缺字区；关时标题与正文都贴顶、缺角认了。
        val headingTopInsetPx = AgentMirrorParams.headingTopInsetPx(
            columnArcInsetPx = rules.columnArcInsetPx(viewport.left, viewport.right),
            cornerAvoidance = cornerAvoidance,
        )
        val headingOverlayPx = headingTopInsetPx + headingBandPx
        val titleFadeBandPx = with(density) {
            AgentMirrorParams.titleFadeBandPx(reading.lineHeightSp.sp.toPx())
        }
        val fadeOverlayPx = headingOverlayPx + titleFadeBandPx
        val headingMetrics = Modifier
            .align(Alignment.TopStart)
            .padding(
                start = with(density) { viewport.left.toDp() },
                end = with(density) { (rules.windowWidth - viewport.right).toDp() },
                top = with(density) { (viewport.top + headingTopInsetPx).toDp() },
            )
            .fillMaxWidth()
            .height(with(density) { headingBandPx.toDp() })
        val headingStyle = AgentMirrorParams.headingStyle(LocalTextStyle.current, effectiveTextSize)

        // 正文先画、标题与渐隐层后画：长正文可滚到标题下方，在渐隐带里连续变淡；
        // 后画的标题/手势层同时保住会话入口的点按命中。
        AgentReadingText(
            turns = turns,
            size = effectiveTextSize,
            rules = rules,
            scroll = scroll,
            // 空正文用独立视口，避免其 maxValue=0 把历史回看位置钳回顶部。
            emptyScroll = emptyReplyScroll,
            // 点按正文切回通知页（票 #133）；拖动由滚动容器消费，不触发回调。
            onBodyTap = onBodyTap.takeIf { interactive },
            // 标题覆写区高度：短内容从标题下缘居中，长内容可向上滚入渐隐带（spec 0025）。
            headingOverlayPx = headingOverlayPx,
            readableTopPx = fadeOverlayPx,
            anchorMode = entryRequest != null && follow == Follow.READING,
            entryGeneration = entryRequest?.generation?.takeIf { entryPending },
            onEntryApplied = {
                frozenTurns = rawTurns
                frozenStatus = state.status
                entryApplied = true
            },
            processHeaders = process.headers,
            onProcessToggle = if (interactive) {
                { group ->
                    onVoiceFollowPause()
                    entryCancelled = true
                    frozenTurns = rawTurns
                    frozenStatus = if (preserving) frozenStatus else state.status
                    onFollowChange(if (follow == Follow.READING) Follow.READING else Follow.PAUSED)
                    onProcessChoice(group.choiceKey, !group.expanded)
                }
            } else null,
            // 角部避让（spec 0019/0025）：正文逐行避让；标题另在上方按同一开关避让。
            cornerAvoidance = cornerAvoidance,
            voiceFollow = voiceFollow,
            voiceFollowActive = voiceFollowActive,
            onVoiceFollowPause = onVoiceFollowPause,
            onVoiceScrolling = { voiceProgrammaticScroll = it },
        )

        if (fadeOverlayPx > 0) {
            val opaqueStop = (headingOverlayPx.toFloat() / fadeOverlayPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = with(density) { viewport.left.toDp() },
                        end = with(density) { (rules.windowWidth - viewport.right).toDp() },
                        top = with(density) { viewport.top.toDp() },
                    )
                    .fillMaxWidth()
                    .height(with(density) { fadeOverlayPx.toDp() })
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black,
                            opaqueStop to Color.Black,
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }

        if (headingBandPx > 0) {
            // 从标题覆写区起手的上滑照旧打断跟随进回看；这层只负责竖直拖动、不接点按。
            val gestureShim = rememberScrollableState { delta -> scroll.dispatchRawDelta(-delta) }
            Box(modifier = headingMetrics.scrollable(gestureShim, Orientation.Vertical))
            // 标识行本体：会话状态标识与标题同一行；点按挂在可见 Row 上，避免命中区与可见元素错位。
            Row(
                modifier = headingMetrics
                    .graphicsLayer { alpha = if (pulseActive) pulseAlpha.value else 1f }
                    .clickableOnTap(onHeadingTap.takeIf { interactive }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = RearCueSpacing.xs)
                        .size(AgentMirrorParams.STATUS_INDICATOR_DP.dp),
                ) {
                    if (statusIndicator != null) {
                        SessionStatusIndicatorView(
                            indicator = statusIndicator,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = headingDisplay.title,
                        style = headingStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (interactive && state.source == "codex" && state.pendingQuestions.isNotEmpty()) {
                    Text("待答 " + state.pendingQuestions.size + " 题", color = Color(0xFF3ECF8E), fontSize = 10.sp, lineHeight = 12.sp,
                        modifier = Modifier.clickable { onVoiceFollowPause(); questionPanelOpen = true }.padding(start = 4.dp, end = 18.dp, top = 4.dp, bottom = 4.dp))
                }
            }
        }
        if (interactive && questionPanelOpen) {
            AgentQuestionReplyPanel(state, linkStatus == BridgeLinkStatus.CONNECTED, onClose = { questionPanelOpen = false },
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = with(density) { viewport.left.toDp() }, top = with(density) { (viewport.top + headingOverlayPx).toDp() })
                    .width(with(density) { viewport.width.toDp() })
                    .height(with(density) { (viewport.height - headingOverlayPx).coerceAtLeast(1).toDp() }))
        }

        // 浮动按钮独立避让圆角，不能为了放按钮而收窄所有正文；离场层不接点按（过渡期防误触）。
        if (interactive && !questionPanelOpen && turns.isNotEmpty() && follow != Follow.FOLLOWING) {
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
                        onVoiceFollowPause()
                        onFollowChange(MirrorScrollPolicy.onResumeTap())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "↓", color = RearCueColors.onBackground, fontSize = 18.sp)
            }
        }

    }
}
