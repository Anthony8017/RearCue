package com.rearcue.poc.rear

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.rearcue.poc.core.ContentPage
import com.rearcue.poc.core.NotificationDetail
import com.rearcue.poc.core.detailDisplayTitle
import com.rearcue.poc.design.RearCueChargingWave
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueHalo
import com.rearcue.poc.design.RearCueIconSize
import com.rearcue.poc.design.RearCueNotificationIcons
import com.rearcue.poc.design.RearCueShape
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTheme
import com.rearcue.poc.design.RearCueTypography
import com.rearcue.poc.design.maxCornerRadiusPx
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Icon Set 一枚应用图标：取票 #25 的语义化令牌（背屏与主屏共用一套）。 */
private val IconSize = RearCueIconSize.iconSetRearDisplay

/** 防烧屏漂移幅度（票 #26）：3dp；收敛成漂移边界是 [DisplaySafeArea] 的事。 */
private val DriftAmplitude = 3.dp

/** 电量字号不变，向下贴近底缘为两行桌面尺寸图标留出空间；另有圆角内缩。 */
private val ChargingNumberBottomGutter = 3.dp

/**
 * 充电白色电量数字字号（spec 0008 / 票 #67 起 60sp；票 #102 降档到 30sp、数值带 %）：
 * 电量整数 + 「%」（如「85%」），对照设计稿 `chatgpt/04-charging-green.png` 的右下角
 * 数字一档（Outfit Light 字体不变，见 [RearCueTypography.chargingNumber]）。
 */
private val ChargingNumberSize = 30.sp

/** 与 app 层日志同一个观测面（`adb logcat -s RearCue`）。 */
private const val TAG = "RearCue"

/**
 * 内容页交叉淡入淡出时长（spec 0013 / 票 #133，grilling Q9 的 150–200ms 中值）：
 * 两页同时进出、线性对折，全程不透出黑底；不响不震、无涟漪。
 */
private const val CONTENT_PAGE_CROSSFADE_MS = 180

/**
 * 拔电渐隐时长（grilling #114）：拔电**即刻开始**（无停留延迟）、非硬切的水位淡出窗。
 */
private const val CHARGING_FADE_OUT_MS = 400

/**
 * 充电层的渐隐进度（grilling #114）：插电即不透明（无淡入、即插即现），拔电即刻起
 * [CHARGING_FADE_OUT_MS] 淡出；冷启动未充电为 0（不闪现水体）。水位层唯一消费方；
 * 电量数字仍随 `charging` 标志即时显隐（数字是内容面信息，不参与背景淡出）。
 */
@Composable
private fun rememberChargingFade(charging: Boolean): Animatable<Float, AnimationVector1D> {
    val fade = remember { Animatable(if (charging) 1f else 0f) }
    LaunchedEffect(charging) {
        if (charging) fade.snapTo(1f) else fade.animateTo(0f, tween(CHARGING_FADE_OUT_MS))
    }
    return fade
}

/**
 * 背屏 Dashboard：纯黑背景 + Icon Set（spec 0008：常态无时间、无横幅——原生背屏已有时钟，
 * spec 0007 的 Notification Feed 从背屏撤下，见 CONTEXT.md「Dashboard」「Notification Feed」），
 * 叠加 Notification Highlight 瞬态（票 #65）、Detail View 临时视图（票 #66：点按图标 →
 * 图标放大淡出、卡片从其位置弹性展开；spec 0009 / 票 #74 起铺满整屏、不显示应用名；
 * 再点按/所示通知清除收起）与 Charging Animation 绿色水位（票 #67；spec 0009 / 票 #71 起
 * 铺满整屏含相机带，票 #75 水面微波；白色大号细体数字右下角——票 #72；图标以弥散光晕
 * 保持可见——票 #73 反转 0008 的白描边；对照设计稿
 * `docs/mockups/0008-dashboard-visual/chatgpt/04-charging-green.png`）。
 *
 * 由 [RearDisplayBackend] 投送到背屏（应用内 `setLaunchDisplayId` 为主，Shizuku 的
 * `am start --display <id>` 只是未锁屏兜底）；本界面不做投送决策，只渲染 [IconSetFeed] 的当前
 * Icon Set（居中放大，对照设计稿 `docs/mockups/0008-dashboard-visual/chatgpt/01-idle-icons.png`）、
 * [ChargingFeed] 的充电动画面、[DetailFeed] 的 Detail 卡片与 [HighlightFeed] 的高亮/呼吸。
 *
 * 上/下屏由「通知事件 → DashboardCore 效果 → 后端」（票 #5）驱动：下屏时后端经
 * [RearDashboardHost] 结束本界面，所以这里只登记自己在屏、不自己判断该不该退出。
 *
 * 韧性（票 #6）：主屏锁屏/息屏时背屏会被系统交给原生 AOD（Takeover），所以本界面要
 * ①锁屏之上可见、②上屏时点亮背屏、③在屏期间保持背屏点亮。①②③的窗口级声明只管本界面
 * 自己这一段（含 E6 的窗口级 KEEP_SCREEN_ON）；锁屏稳态下持续点亮背屏归 **Wake Keep-alive**
 * （CONTEXT.md「唤醒保活」，ADR 0003）——设备侧自驱循环按间隔注入定向背屏的唤醒键，
 * 与「保活轮询」「KEEP_SCREEN_ON」不是一回事（CONTEXT `_Avoid_`），别混称。
 * 锁屏下 `am start --display` 被 ActivityStarter 的 `rearDisplay check locked -> deny` 硬拒
 * （E3/E14 每次如此），锁屏首投走 Lock-screen First Cast 的**任务搬运事务**
 * （CONTEXT.md「锁屏首投」：`am start -n` 建任务 + `service call activity_task 51` 搬 root task），
 * 不是 `am start --display`。
 *
 * 安全区 + 防烧屏（票 #26）：Icon Set 全程落在 [DisplaySafeArea] 算出的内容安全
 * 矩形内——cutout 矩形、四角圆角半径、漂移幅度全部**运行时从系统读取**（DisplayCutout /
 * RoundedCorner，不硬编码机型数字），渲染层零决策照单执行（布局框 + 漂移边界 + 等比缩放
 * 都是约束输出）；防烧屏漂移按分钟轮驻极限位，任何时刻不越出安全矩形。Detail 卡片铺满
 * 全屏（spec 0009 反转，含相机带），但其可读文字与 Agent Mirror 文字一样横跨落位
 * （[SafeArea.textHorizontalPadding]）——左缘收进布局框水平区间不进带（相机模组会把带内
 * 内容物理挡住），右距屏缘 8px 排满、进圆角弧区自动外扩（票 #97）。
 */
class RearDashboardActivity : ComponentActivity() {

    /** 显示几何输入：Android 层只采集，不做几何决策（采集口径同 design/SafeArea.kt）。 */
    private val geometry = MutableStateFlow<DisplayGeometry?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "RearDashboardActivity onCreate display=${display?.displayId}")
        // 锁屏存活（E3）：主屏锁屏后本界面仍可见，且被投送时点亮背屏。
        setShowWhenLocked(true)
        // issue #43：turnScreenOn 只在真正落到背屏时打开。锁屏首投兜底会先在主屏（Display 0）
        // 建本 Activity（am start --display 被锁屏门拒），manifest 无条件 true 会让系统在启动瞬间
        // 唤亮主屏（PowerGroup group 0 / TURN_ON:handleTurnScreenOn），违反 #36 story 8/12；
        // 落在主屏的实例绝不能开屏，背屏可见性由 Wake Keep-alive（ADR 0003，投送前已启动）保证。
        setTurnScreenOn(display?.displayId == 1)
        // 背屏保持点亮的窗口级一条腿（E6）：keep-screen-on 只作用于本界面所在的屏；
        // 锁屏稳态的持续点亮是 Wake Keep-alive（ADR 0003 设备侧循环）的事，两者不混称。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 登记「实例存在」（退到 onDestroy 才注销）：界面 onStop 后仍占着背屏，退出时也要能结束它。
        RearDashboardHost.attach(this)
        // 几何输入采集：insets 到达 / 布局完成各采一次（后到者覆盖，值一致）。
        val root = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            publishGeometry(view)
            insets
        }
        root.doOnLayout { publishGeometry(it) }
        setContent {
            RearCueTheme {
                val iconSet by IconSetFeed.iconSet.collectAsState()
                val unreadCounts by IconSetFeed.unreadCounts.collectAsState()
                val charging by ChargingFeed.charging.collectAsState()
                val levelPercent by ChargingFeed.levelPercent.collectAsState()
                val highlights by HighlightFeed.apps.collectAsState()
                val breathUntil by HighlightFeed.breathUntil.collectAsState()
                val detail by DetailFeed.detail.collectAsState()
                // 当前内容页（spec 0013 / 票 #132）：core 决定通知页/Agent 页，UI 只按投影渲染。
                val contentPage by AgentFeed.contentPage.collectAsState()
                val agentState by AgentFeed.state.collectAsState()
                val agentPulseUntil by AgentFeed.pulseUntilMs.collectAsState()
                val input by geometry.collectAsState()
                // 电量数字实测尺寸（票 #102）：数字与图标布局分属两棵子树，占位几何要先知道
                // 数字多大——渲染侧 onSizeChanged 回喂，首帧未测得前不预留（null）。
                var chargingNumberSize by remember { mutableStateOf(IntSize.Zero) }
                // Detail 过渡（spec 0008 / 票 #66；动效是产品要求，不写 JVM 测试）：
                // 0 = 图标态，1 = 卡片全展开。打开走 spring（过冲给「弹性展开」，收在图形层的
                // 系数里），收起走短 tween 逆向；进度只在图形层/派生态读（draw 阶段取值），
                // 过渡不逐帧重组。切换（A→B）不重播——进度已到位，卡片内容直接换。
                val detailProgress = remember { Animatable(0f) }
                val lastDetail = remember { mutableStateOf<NotificationDetail?>(null) }
                LaunchedEffect(detail) {
                    if (detail != null) {
                        lastDetail.value = detail
                        detailProgress.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f))
                    } else {
                        detailProgress.animateTo(0f, tween(durationMillis = 190, easing = FastOutSlowInEasing))
                    }
                }
                // 点按图标的位置采集（窗口 px）：卡片「从其位置弹性展开」的变换原点。
                val iconCenters = remember { mutableMapOf<String, Offset>() }
                val cardVisible by remember { derivedStateOf { detailProgress.value > 0.001f } }
                // Agent 页历史回看位置（spec 0013 / 票 #133，Q10）：ScrollState 提升到页面级，
                // 交叉淡入淡出期间本层被换出组合也保留；跟随态同层持有，重新入组合时按滚动值对齐。
                val agentMirrorScroll = rememberScrollState()
                val agentEmptyReplyScroll = rememberScrollState()
                var agentMirrorFollow by remember { mutableStateOf(MirrorScrollPolicy.Follow.FOLLOWING) }
                // 内容页切换（spec 0013 / 票 #133）：core 决定通知页/Agent 页（WFA 例外已在
                // contentPage 投影内），UI 只做约 180ms 的交叉淡入淡出——两页同帧进出，
                // 背景层（呼吸光晕/充电水位）不参与。
                val showAgentPage = contentPage == ContentPage.AGENT
                LaunchedEffect(showAgentPage) {
                    val startedAtMs = System.currentTimeMillis()
                    Log.i(TAG, "content page crossfade start show=" + (if (showAgentPage) "agent" else "notification"))
                    delay(CONTENT_PAGE_CROSSFADE_MS.toLong())
                    Log.i(
                        TAG,
                        "content page crossfade done show=" + (if (showAgentPage) "agent" else "notification") +
                            " durationMs=" + (System.currentTimeMillis() - startedAtMs),
                    )
                }
                // 进入 Agent 页时把跟随态对齐到既有滚动值（票 #133 Q10）：有回看余量即回看、
                // 已到底即实时跟随——切走期间位置不丢，回来也不凭陈旧标志抢滚或误显示 ↓。
                // 必须等一帧：ScrollState 在换页那一帧还没按新视口重算 maxValue，抢先读会把
                // 回看态误判成到底。
                LaunchedEffect(showAgentPage, agentMirrorScroll) {
                    if (showAgentPage) {
                        withFrameNanos { }
                        agentMirrorFollow = if (agentMirrorScroll.canScrollForward) {
                            MirrorScrollPolicy.Follow.PAUSED
                        } else {
                            MirrorScrollPolicy.Follow.FOLLOWING
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RearCueColors.background)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { RearDashboardHost.emitContentPageTap() },
                    contentAlignment = Alignment.Center,
                ) {
                    // Notification Highlight 呼吸光晕（spec 0008 / 票 #65）：背景层，不参与
                    // 漂移/安全区（同充电填充口径），也**不参与内容页交叉淡入淡出**——压在
                    // 全部内容之下，页面切换期间保持既有背景语义（票 #133）。
                    HighlightBreathLayer(breathUntil, input?.cornerRadius ?: 0)
                    val geom = input
                    if (geom != null) {
                        val rules = DisplaySafeArea.resolve(geom)
                        // 充电绿色水位（spec 0009 / 票 #71 反转 0008「相机带不染色」）：背景层，
                        // 不参与漂移/安全区，压在呼吸光晕之上、全部内容之下；铺满整个背屏
                        //（含相机带），水位语义照 [ChargingWater] 纯函数执行。
                        ChargingFillLayer(charging, levelPercent)
                        LaunchedEffect(rules, input) {
                            Log.i(TAG, "rear-safe-geometry $input -> content=${rules.contentRect} drift=${rules.driftBounds} layout=${rules.layoutRect}")
                        }
                        // 内容页交叉淡入淡出（spec 0013 / 票 #133）：通知页与 Agent 页平权，
                        // core 的 [ContentPage] 投影决定目标页（WFA 自动插队已收口其中，UI 不做
                        // 二次裁决）；两页同帧进出、仅透明度过渡，不响不震。背景层（呼吸光晕、
                        // 充电水位）在本 AnimatedContent 之外，不参与淡入淡出。
                        AnimatedContent(
                            targetState = showAgentPage,
                            transitionSpec = {
                                fadeIn(tween(CONTENT_PAGE_CROSSFADE_MS, easing = LinearEasing))
                                    .togetherWith(
                                        fadeOut(tween(CONTENT_PAGE_CROSSFADE_MS, easing = LinearEasing)),
                                    )
                            },
                            label = "contentPage",
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) { showAgent ->
                            if (showAgent) {
                                // Agent 页（spec 0010 / 内容页 spec 0013）：只有 core
                                // [ContentPage.AGENT] 时组；断连回落也由 core 交还，这里只跟
                                // AgentFeed 投影。滚动状态由页面级持有，切走再切回不丢回看位置。
                                agentState?.let { state ->
                                    AgentMirrorLayer(
                                        state = state,
                                        rules = rules,
                                        scroll = agentMirrorScroll,
                                        emptyReplyScroll = agentEmptyReplyScroll,
                                        follow = agentMirrorFollow,
                                        onFollowChange = { agentMirrorFollow = it },
                                        // 只有当前页接点按：交叉淡出中的旧 Agent 层不残留手势，
                                        // 过渡窗内再点也不会把刚换好的页翻回去（票 #133）。
                                        interactive = showAgent == showAgentPage,
                                        onBodyTap = RearDashboardHost::emitContentPageTap,
                                        pulseUntilMs = agentPulseUntil,
                                    )
                                }
                            } else {
                                // 通知页（spec 0008）：Icon Set、充电数字与 Detail 卡片同页，
                                // 点按语义全部保持（图标开 Detail、卡片先收起、空白走根节点上报）。
                                val minute by currentMinute()
                                val drift = rules.driftFor(minute)
                                // 数字占位 + 缺口（票 #102）：纯函数出口，充电且数字已上屏才预留；
                                // 图标布局照 [SafeArea.placeNotificationBlock] 让位，构造性不相交。
                                val density = LocalDensity.current
                                val numberPlaceholder = if (
                                    charging && levelPercent != null && chargingNumberSize != IntSize.Zero
                                ) {
                                    chargingNumberPlaceholder(
                                        numberRect = chargingNumberRect(
                                            windowWidth = geom.width,
                                            windowHeight = geom.height,
                                            cornerRadiusPx = geom.cornerRadius,
                                            extraInsetPx = with(density) { RearCueSpacing.md.roundToPx() },
                                            numberWidth = chargingNumberSize.width,
                                            numberHeight = chargingNumberSize.height,
                                            verticalExtraInsetPx = with(density) { ChargingNumberBottomGutter.roundToPx() },
                                        ),
                                        gapPx = with(density) { RearCueSpacing.sm.roundToPx() },
                                    )
                                } else {
                                    null
                                }
                                DashboardContent(
                                    iconSet = iconSet,
                                    unreadCounts = unreadCounts,
                                    charging = charging,
                                    highlights = highlights,
                                    detailApp = detail?.app ?: lastDetail.value?.app,
                                    detailProgress = { detailProgress.value },
                                    rules = rules,
                                    drift = drift,
                                    numberPlaceholder = numberPlaceholder,
                                    iconCenters = iconCenters,
                                    onIconTap = RearDashboardHost::emitIconTap,
                                )
                                // 充电电量数字（spec 0009 / 票 #72 反转 0008 的顶部居中；票 #102 降到
                                // 30sp 并带 %）：**整屏**右下角落位（#76 实机判定修正：contentRect 只有
                                // 511px 宽，居中图标行与右下角数字必然相碰——数字随水越出安全矩形，
                                // 圆角感知内缩），Outfit Light 细体；落位几何在 [chargingNumberRect]。
                                if (charging) {
                                    levelPercent?.let { percent ->
                                        Text(
                                            text = "$percent%",
                                            color = Color.White,
                                            fontSize = ChargingNumberSize,
                                            fontFamily = RearCueTypography.chargingNumber,
                                            fontWeight = FontWeight.Light,
                                            modifier = Modifier
                                                .chargingNumberPlacement(geom, drift)
                                                // 实测尺寸回喂数字占位（必须挂在 placement 之内：
                                                // placement 对外报满窗尺寸，外面量到的不是数字本体）。
                                                .onSizeChanged { chargingNumberSize = it },
                                        )
                                    }
                                }
                                // Detail 卡片层（票 #66；spec 0009 / 票 #74 反转 0008 落 contentRect）：
                                // 铺满整个背屏（含相机带，圆角随屏幕运行时读取），压在图标层之上；
                                // 卡底含带区是「视觉完整」判例，可读文字仍避让相机带、右距屏缘 8px 排满
                                // （textHorizontalPadding，票 #97）；
                                // progress≈0 不组（常态零开销），展开/收起过渡期随进度绘。
                                // 卡片点按＝「再点按同一 App」的收起同形事件；最后一条通知收起后
                                // 由 core 兜底切到 Agent 页（本层随之淡出，票 #133）。
                                if (cardVisible) {
                                    lastDetail.value?.let { shown ->
                                        val screenRect = PxRect(0, 0, geom.width, geom.height)
                                        DetailCard(
                                            shown = shown,
                                            progress = { detailProgress.value },
                                            cornerPx = geom.cornerRadius,
                                            origin = cardOrigin(iconCenters[shown.app], screenRect),
                                            rules = rules,
                                            onTap = { RearDashboardHost.emitIconTap(shown.app) },
                                        )
                                    }
                                }
                            }
                        }
                        // Approval Glow（CONTEXT.md「Approval Glow」/ 票 #105）：等确认
                        // 存续期的背屏边缘环绕光带——背屏侧由 AgentFeed 的状态派生（贴
                        // 「只在该状态存在」语义），挂靠当前内容页同一开关 showAgentPage
                        // （内容页模型后不再有独立的「AGENT 持有」内容层开关），
                        // 压在全部内容之上、纯视觉层铺满含相机带；是否亮由
                        // [AgentMirrorParams.approvalGlow] 纯函数收口（工作中/空闲
                        // 返回 null 即不组、状态离开 WAITING 即灭），不构成常驻动画。
                        if (showAgentPage) {
                            agentState?.let { st ->
                                AgentMirrorParams.approvalGlow(st.status, geom.width, geom.height)
                                    ?.let { spec -> ApprovalGlowLayer(spec, geom.cornerRadius) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (display != null) Log.i(TAG, "RearDashboardActivity onStart display=${display?.displayId}")
    }

    override fun onDestroy() {
        RearDashboardHost.detach(this)
        super.onDestroy()
    }

    /** 采集显示几何输入：cutout 矩形（DisplayCutout）+ 四角圆角半径（RoundedCorner）+ 窗口尺寸。 */
    private fun publishGeometry(view: View) {
        val insets = view.rootWindowInsets ?: return
        if (view.width <= 0 || view.height <= 0) return // 布局没完成：doOnLayout 会补采
        val cutouts = insets.displayCutout?.boundingRects
            ?.map { rect -> PxRect(rect.left, rect.top, rect.right, rect.bottom) }
            .orEmpty()
        // 圆角采集与主屏安全区共用同一口径（maxCornerRadiusPx，#30 review 抽取）。
        val radius = insets.maxCornerRadiusPx()
        val amplitudePx = (DriftAmplitude.value * resources.displayMetrics.density).roundToInt()
        geometry.value = DisplayGeometry(
            width = view.width,
            height = view.height,
            cutouts = cutouts,
            cornerRadius = radius,
            driftAmplitude = PxOffset(amplitudePx, amplitudePx),
        )
    }
}

/** Icon Set 的单条/网格共用桌面参照尺寸，实际可见区、角标外探与电量避让由纯几何决定。 */
@Composable
private fun DashboardContent(
    iconSet: List<String>,
    unreadCounts: Map<String, Int>,
    charging: Boolean,
    highlights: Set<String>,
    detailApp: String?,
    detailProgress: () -> Float,
    rules: SafeArea,
    drift: PxOffset,
    numberPlaceholder: PxRect?,
    iconCenters: MutableMap<String, Offset>,
    onIconTap: (String) -> Unit,
) {
    val density = LocalDensity.current
    val placement = remember(iconSet, unreadCounts, rules, drift, numberPlaceholder, density) {
        with(density) {
            val showBadges = IconGrid.isGridMode(unreadCounts)
            notificationIconPlacement(
                entries = iconSet.map { IconGridEntry(it, if (showBadges) unreadCounts[it] ?: 0 else 0) },
                safeArea = rules,
                targetIconSizePx = IconSize.roundToPx(),
                gapXPx = RearCueNotificationIcons.horizontalGap.roundToPx(),
                gapYPx = RearCueNotificationIcons.verticalGap.roundToPx(),
                chipWidthPx = IconGrid.chipWidth.roundToPx(),
                chipHeightPx = IconGrid.chipHeight.roundToPx(),
                showBadges = showBadges,
                numberPlaceholder = numberPlaceholder,
                drift = drift,
            )
        }
    } ?: return
    val grid = placement.grid
    val iconSize = with(density) { placement.iconSizePx.toDp() }
    Layout(
        content = {
            grid.cells.forEach { cell ->
                GridCell(
                    pkg = cell.app,
                    unread = cell.unread,
                    highlighted = cell.app in highlights,
                    chargingGlow = charging && cell.app !in highlights,
                    isDetailSubject = cell.app == detailApp,
                    detailProgress = detailProgress,
                    iconCenters = iconCenters,
                    onTap = { onIconTap(cell.app) },
                    iconSize = iconSize,
                    badgeOverhangPx = placement.badgeOverhangPx,
                )
            }
            if (grid.overflow > 0) OverflowChip(count = grid.overflow)
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        Log.i(TAG, "rear-icon-place size=${placement.iconSizePx} overhang=${placement.badgeOverhangPx} " +
            "block=${placement.block.rect} chip=${placement.chip} drift=$drift numberSlot=$numberPlaceholder")
        layout(constraints.maxWidth, constraints.maxHeight) {
            grid.cells.forEachIndexed { index, cell ->
                placeables[index].place(placement.block.x + cell.x, placement.block.y + cell.y)
            }
            placement.chip?.let { placeables.last().place(it.left, it.top) }
        }
    }
}
/**
 * 网格一格：桌面参照图标 + 右上外探角标，完整数字向左加宽。几何出口已把外探计入安全区
 * 与间距；数字 = 该 App 的 Active Notification 条数，0 即不显示。
 */
@Composable
private fun GridCell(
    pkg: String,
    unread: Int,
    highlighted: Boolean,
    chargingGlow: Boolean,
    isDetailSubject: Boolean,
    detailProgress: () -> Float,
    iconCenters: MutableMap<String, Offset>,
    onTap: () -> Unit,
    iconSize: Dp,
    badgeOverhangPx: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(iconSize)) {
        DashboardIcon(
            pkg = pkg,
            highlighted = highlighted,
            chargingGlow = chargingGlow,
            isDetailSubject = isDetailSubject,
            detailProgress = detailProgress,
            iconCenters = iconCenters,
            onTap = onTap,
            iconSize = iconSize,
        )
        if (unread > 0) {
            UnreadBadge(
                count = unread,
                iconSize = iconSize,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(badgeOverhangPx, -badgeOverhangPx) },
            )
        }
    }
}

/** 数字角标按最终屏上尺寸度量，向左加宽容纳完整计数；退化小格才缩字。 */
@Composable
private fun UnreadBadge(count: Int, iconSize: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Layout(
        modifier = modifier
            .clip(CircleShape)
            .background(RearCueNotificationIcons.badgeBackground),
        content = {
            Text(
                text = count.toString(),
                color = RearCueNotificationIcons.badgeForeground,
                fontSize = IconGrid.badgeFontSize,
                lineHeight = IconGrid.badgeFontSize * 1.1f,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        },
    ) { measurables, _ ->
        val text = measurables.single().measure(Constraints())
        val badge = with(density) {
            iconBadgeLayout(
                cellPx = iconSize.roundToPx(),
                minimumSizePx = IconGrid.badgeSize.roundToPx(),
                horizontalPaddingPx = RearCueNotificationIcons.badgeHorizontalPadding.roundToPx(),
                textWidthPx = text.width,
                textHeightPx = text.height,
            )
        }
        layout(badge.width, badge.height) {
            text.placeWithLayer(badge.textX, badge.textY) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = badge.textScale
                scaleY = badge.textScale
            }
        }
    }
}

/** 「+N」溢出徽标：网格末行下方居中（位置由 [iconGridLayout] 给），N = 未上屏的应用数。 */
@Composable
private fun OverflowChip(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(IconGrid.chipWidth, IconGrid.chipHeight)
            .clip(CircleShape)
            .background(RearCueColors.surfaceHighlight)
            .border(1.dp, RearCueColors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$count",
            color = RearCueColors.onBackgroundSecondary,
            fontSize = IconGrid.chipFontSize,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 充电绿色水位（票 #71 全屏化反转 0008「相机带不染色」；票 #75 水面微波；对照设计稿
 * `chatgpt/04-charging-green.png`）：绿色水铺满**整个背屏**（含相机带——0008 的避让反转
 * 留痕见 docs/specs/0009），自底部按电量比例填充（几何照 [ChargingWater] 纯函数）：
 * 低饱和翠绿垂直渐变（靠上缘亮、沉底深）；上缘是复合正弦**微波水面**（振幅数 px、慢相位
 * 漂移，安静档——无气泡无 3D 重力液体），上缘亮线与向上渐隐微光随波面走。
 *
 * **背景层语义（grilling #112/#114）**：充电期间长垫底、与内容无竞争——Icon Set、
 * Detail View、Agent Mirror 叠其上（本层在组合序最底）；满电（100%）水面贴顶，屏幕上沿
 * 呈持续荡漾的波浪线（水位到顶的渲染特例，不另设满电 UI）；**拔电即刻开始渐隐**
 * （[CHARGING_FADE_OUT_MS]、无停留延迟、非硬切，[rememberChargingFade]）。
 * 相位动画只在水体可见时才组（[rememberInfiniteTransition] 不进常态组合树），
 * 帧驱动在 draw 阶段读状态、不逐帧重组。背景层不参与漂移/安全区。
 * 无读数（[levelPercent] = null）或渐隐归零不渲染水体，黑底图标态兜底。
 */
@Composable
private fun ChargingFillLayer(charging: Boolean, levelPercent: Int?) {
    val cd = stringResource(R.string.charging_animation_cd)
    val fade = rememberChargingFade(charging)
    val waterCanvas: Modifier = Modifier
        .fillMaxSize()
        // 渐隐在图层阶段吃状态：淡出过程不逐帧重组（同 detailProgress 的 draw 阶段口径）。
        .graphicsLayer { alpha = fade.value }
        .semantics { contentDescription = cd }
    if (levelPercent == null || (!charging && fade.value <= 0f)) {
        Canvas(modifier = waterCanvas) {}
        return
    }
    val wavePhase = rememberInfiniteTransition(label = "chargingWater").animateFloat(
        initialValue = 0f,
        targetValue = ChargingWater.PHASE_PERIOD_RAD,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = RearCueChargingWave.phasePeriodMs, easing = LinearEasing),
        ),
        label = "wavePhase",
    )
    val columns = with(LocalDensity.current) {
        ChargingWater.WaveColumns(
            ampMainPx = RearCueChargingWave.amplitudeMain.toPx(),
            wavelengthMainPx = RearCueChargingWave.wavelengthMain.toPx(),
            ampRipplePx = RearCueChargingWave.amplitudeRipple.toPx(),
            wavelengthRipplePx = RearCueChargingWave.wavelengthRipple.toPx(),
        )
    }
    Canvas(modifier = waterCanvas) {
        val percent = levelPercent.coerceIn(0, 100)
        val baseTop = ChargingWater.fillTopPx(size.height, percent)
        if (baseTop >= size.height) return@Canvas // 0%：全空，不画微光带
        val phase = wavePhase.value
        val crest = columns.crestPx
        // 波面采样：48 段对 904px 级宽足够圆滑，段内由 Path 直线近似（数 px 振幅下不可辨）。
        val samples = 48
        val stepX = size.width / samples
        val offsets = FloatArray(samples + 1) { i ->
            ChargingWater.surfaceOffsetPx(i * stepX, columns, phase)
        }
        // 采样水线：沿波面在「基准线 + yDelta」处的开放路径（水体上缘 / 微光带上缘共用）。
        fun sampledLine(yDelta: Float): Path = Path().apply {
            moveTo(0f, baseTop + yDelta + offsets[0])
            for (i in 1..samples) lineTo(i * stepX, baseTop + yDelta + offsets[i])
        }
        val waterline = sampledLine(0f)
        // 水体：波面向下闭合到底边。
        drawPath(
            path = Path().apply {
                addPath(waterline)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            },
            brush = Brush.verticalGradient(
                colors = listOf(RearCueColors.chargingFillBright, RearCueColors.chargingFillDeep),
                startY = baseTop - crest,
                endY = size.height,
            ),
        )
        // 上缘微光带：水面上方 glow 高度向上渐隐，随波面起伏。
        val glow = 16.dp.toPx()
        drawPath(
            path = Path().apply {
                addPath(sampledLine(-glow))
                for (i in samples downTo 0) lineTo(i * stepX, baseTop + offsets[i])
                close()
            },
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, RearCueColors.chargingEdgeGlow),
                startY = baseTop - glow - crest,
                endY = baseTop + crest,
            ),
        )
        // 亮线沿波面走（光效克制，对照设计稿 04 的上缘亮边）。
        drawPath(
            path = waterline,
            color = RearCueColors.chargingEdgeGlow,
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

/**
 * Notification Highlight 整屏呼吸（spec 0008 / 票 #65，对照设计稿 `chatgpt/02-highlight.png`）：
 * 中心光晕向边缘渐隐 + 边缘微光描边，暖白 [RearCueColors.highlightWarm]；alpha 按 sin(πt)
 * 包络一次起落——**一次性动画、非循环**（呼吸「一次」的判定在 DashboardCore 冷却窗，
 * 本层只照单播放）。播放时长 = [breathUntil] 的剩余量：首投路径上呼吸指令先于界面挂载，
 * 晚挂载播剩余、已过期不播，不漏播也不双播（HighlightFeed KDoc）。
 *
 * 背景层不参与漂移/安全区（同充电填充口径，spec 0008 几何约束）；亮度跟随系统、无提亮
 * （spec 0008 story 20）。日志锚 `highlight breath end` 在动画播完打——词形契约见
 * `DashboardCore.LOG_HIGHLIGHT_CONTRACT`（`highlight breath start` 由 AppContainer 在效果
 * 执行处打），tools/ex 验收链按词形读，**byte 不可改**。
 */
@Composable
private fun HighlightBreathLayer(breathUntil: Long, cornerRadiusPx: Int) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(breathUntil) {
        val remainingMs = breathUntil - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(remainingMs.toInt(), easing = LinearEasing))
        Log.i(TAG, "highlight breath end")
    }
    val envelope = sin(PI * progress.value).toFloat()
    val warm = RearCueColors.highlightWarm
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (envelope <= 0f) return@Canvas
        // 中心光晕：spec 0009 起充电水/详情已铺满全屏，构图偏右避相机带的前提不再成立，
        // 回正中（票 #73）；向边缘渐隐。
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val glowRadius = maxOf(size.width, size.height) * 0.85f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    warm.copy(alpha = 0.26f * envelope),
                    warm.copy(alpha = 0.08f * envelope),
                    Color.Transparent,
                ),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = center,
        )
        // 边缘微光描边：圆角半径运行时读取（同安全区采集口径，不硬编码机型数字）。
        drawRoundRect(
            color = warm.copy(alpha = 0.55f * envelope),
            cornerRadius = CornerRadius(cornerRadiusPx.coerceAtLeast(0).toFloat()),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/**
 * Approval Glow 确认光带（CONTEXT.md「Approval Glow」/ 票 #105）：Waiting-for-Approval
 * 存续期的背屏边缘环绕灯带——持续点亮（[ApprovalGlow.alphaMin] > 0）＋亮度周期性起伏，
 * 不响不震。与 [HighlightBreathLayer] 分工叠加：那是 Notification Highlight 的「到达瞬态」
 * 一次性包络，这是等待确认的「尚未处理」持续提示；两层可同屏并存（描边同为边缘、色相不同）。
 *
 * 层只在等确认态被挂载（调用点由 [AgentMirrorParams.approvalGlow] 纯函数判定，状态离开
 * WAITING 即整层不组、动画随之停止），**不构成常驻动画**；纯视觉层压在全部内容之上、
 * 铺满含相机带（光带不避让，可读文字仍照 Agent Mirror 口径避相机带）。
 * 参数全部照纯函数给定，本层零决策——色值取 [RearCueColors.accent]（等确认标语同色），
 * 圆角随屏运行时读取（同 [HighlightBreathLayer] 口径）。
 */
@Composable
private fun ApprovalGlowLayer(spec: ApprovalGlow, cornerRadiusPx: Int) {
    val transition = rememberInfiniteTransition(label = "approvalGlow")
    val alpha by transition.animateFloat(
        initialValue = spec.alphaMin,
        targetValue = spec.alphaMax,
        animationSpec = infiniteRepeatable(
            animation = tween(spec.cycleMs / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "approvalGlowAlpha",
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRoundRect(
            color = RearCueColors.accent.copy(alpha = alpha),
            cornerRadius = CornerRadius(cornerRadiusPx.coerceAtLeast(0).toFloat()),
            style = Stroke(width = spec.strokeWidthPx),
        )
    }
}

/**
 * 点按图标中心 → 全屏 rect 内的变换原点（0..1）：「卡片从其位置弹性展开」的锚。
 * 无采集落点（病态/未布局）退化为居中。
 */
private fun cardOrigin(iconCenter: Offset?, rect: PxRect): Offset {
    if (iconCenter == null || rect.width <= 0 || rect.height <= 0) return Offset(0.5f, 0.5f)
    return Offset(
        ((iconCenter.x - rect.left) / rect.width).coerceIn(0f, 1f),
        ((iconCenter.y - rect.top) / rect.height).coerceIn(0f, 1f),
    )
}

/**
 * Detail View 卡片（票 #66；spec 0009 / 票 #74 反转 0008 的 contentRect 落位）：
 * **纯黑**圆角卡片铺满**整个背屏**（含相机带；圆角随屏幕运行时读取），不显示应用名——
 * 标题恰为应用名且正文非空时连标题行一并省略（grill #89：正文界面不显示软件名称，
 * 判据在 [detailDisplayTitle] 纯函数、标签由本层采集）；
 * 标题 + 全文（白字、逐行居中、长文可滚动——「显示全文」无遮蔽档，CONTEXT.md「Detail View」）。
 * 卡底含相机带是「视觉完整」判例，**可读文字不进带**：标题/正文横跨照
 * [DetailText] 按实际行框落位——左缘避相机带、右距屏缘 8px；短内容整体上下居中，
 * 上下最小 8px，首尾行按圆角局部增加纵向留白，不收窄整篇正文。
 *
 * 过渡动效（产品要求，不写 JVM 测试）：进度驱动 alpha 淡入与 scale 弹性展开——scale 从
 * [origin]（点按图标位置）向全尺寸弹开（spring 过冲由进度携带）；收起逆向。再点按卡片 =
 * 「再点按同一 App」的收起同形事件（[onTap]），决策全在 DashboardCore。
 */
@Composable
private fun DetailCard(
    shown: NotificationDetail,
    progress: () -> Float,
    cornerPx: Int,
    origin: Offset,
    rules: SafeArea,
    onTap: () -> Unit,
) {
    // 标题行口径（grill #89：正文界面不显示软件名称）：标题即应用名且正文非空 → 省略。
    // Android 侧只采集标签（包可见性同设置页取标签口径），判据在 detailDisplayTitle 纯函数。
    val context = LocalContext.current
    val appLabel = remember(shown.app) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(shown.app, 0)).toString()
        }.getOrNull()
    }
    val title = detailDisplayTitle(shown.title, appLabel, shown.text)
    Box(
        modifier = Modifier
            .detailFullscreenPlacement()
            .graphicsLayer {
                val p = progress()
                alpha = p.coerceIn(0f, 1f)
                val s = (0.3f + 0.7f * p).coerceAtLeast(0.01f)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(origin.x, origin.y)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .clip(RoundedCornerShape(cornerPx.coerceAtLeast(0).toFloat()))
            .background(RearCueColors.background),
    ) {
        DetailText(title, shown.text, rules, shown.key)
    }
}

/** Detail 卡片全屏落位：量成窗口满尺寸、摆在 (0,0)（渲染零决策照单执行，spec 0009 / 票 #74）。 */
private fun Modifier.detailFullscreenPlacement(): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints.fixed(
                constraints.maxWidth.coerceAtLeast(0),
                constraints.maxHeight.coerceAtLeast(0),
            ),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(0, 0)
        }
    }

@Composable
private fun DashboardIcon(
    pkg: String,
    highlighted: Boolean,
    chargingGlow: Boolean,
    isDetailSubject: Boolean,
    detailProgress: () -> Float,
    iconCenters: MutableMap<String, Offset>,
    onTap: () -> Unit,
    iconSize: Dp,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { iconSize.roundToPx() }
    val icon = remember(pkg, sizePx) { context.packageManager.resolveIcon(pkg, sizePx) }
    // Rear Tap（票 #63 探针 → 票 #66 真实点击处理）：点按经 RearDashboardHost 转发给 app 层
    // 接线（AppContainer.onRearIconTap 翻译成 DetailToggled）。锚 `rear-tap received` 随处理点
    // 打、一次点按一条（词形契约 byte 不可改），不在发射点重复。
    val tap = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { onTap() }
    // Detail 过渡（票 #66）：主体图标放大并淡出、其余图标弱化（「淡出或弱化」的渲染实现自定，
    // 判例不约束）；进度在 draw 阶段读，过渡不逐帧重组。位置采集供卡片「从其位置弹性展开」
    // 定变换原点（localPositionOf 换算到窗口系，含放置层的缩放/平移）。
    val detailMotion = Modifier
        .graphicsLayer {
            val p = detailProgress()
            if (isDetailSubject) {
                val s = 1f + 0.5f * p
                scaleX = s
                scaleY = s
                alpha = (1f - p).coerceIn(0f, 1f)
            } else {
                alpha = 1f - 0.85f * p
            }
        }
        .onGloballyPositioned { coords ->
            iconCenters[pkg] = coords.findRootCoordinates()
                .localPositionOf(coords, Offset(coords.size.width / 2f, coords.size.height / 2f))
        }
    // 图标强调光晕（spec 0009 / 票 #73 反转 0008 的描边圈）：Notification Highlight 的
    // 暖白弥散光晕（票 #65）优先；充电中（票 #67）非高亮图标带白色弥散光晕保持可见——
    // 绿水上的可读性。光从图标向外弥散渐隐、无贴边硬轮廓；drawBehind 不参与布局，
    // Icon Set 几何与漂移判定完全不动。两档参数在 DesignTokens.RearCueHalo。
    val highlightHalo = Modifier.drawBehind {
        if (highlighted) {
            drawHalo(RearCueColors.highlightWarm, RearCueHalo.highlightSpread, RearCueHalo.highlightAlpha)
        } else if (chargingGlow) {
            drawHalo(Color.White, RearCueHalo.chargingSpread, RearCueHalo.chargingAlpha)
        }
    }
    if (icon != null) {
        Image(
            painter = icon,
            contentDescription = pkg,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(iconSize)
                .then(detailMotion)
                .then(tap)
                .then(highlightHalo),
        )
    } else {
        // 解析不到图标退化为首字母块（错误态不崩），形状/强调同主屏图标退化态。
        val shape = RoundedCornerShape(RearCueShape.medium)
        Box(
            modifier = Modifier
                .size(iconSize)
                .then(detailMotion)
                .then(tap)
                .then(highlightHalo)
                .clip(shape)
                .background(RearCueColors.surfaceHighlight)
                .border(1.dp, RearCueColors.outline, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = pkg.take(1).uppercase(),
                color = RearCueColors.onBackground,
                fontSize = 28.sp,
            )
        }
    }
}

/**
 * 图标弥散光晕（spec 0009 / 票 #73）：多层填充圆角矩形由贴图标向外逐层放大、透明度按
 * 平方衰减——视觉上是「光从图标弥散渐隐」，不用 RenderEffect/blur（背屏低端渲染面，
 * 分层近似足够且开销可控）。中心各层被图标本体盖住，只露外圈弥散。
 * 层数/扩散/透明度三档取值见 DesignTokens.RearCueHalo（实机出现同心硬边带时优先增层）。
 */
private fun DrawScope.drawHalo(color: Color, spread: Dp, baseAlpha: Float) {
    val spreadPx = spread.toPx()
    val baseCorner = size.width * 0.30f
    for (i in 1..RearCueHalo.steps) {
        val f = i / RearCueHalo.steps.toFloat()
        val alpha = baseAlpha * (1f - f) * (1f - f)
        if (alpha < 0.01f) continue
        val expand = spreadPx * f
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(-expand, -expand),
            size = Size(size.width + expand * 2, size.height + expand * 2),
            cornerRadius = CornerRadius(baseCorner + expand * 0.5f),
        )
    }
}

/**
 * 充电电量数字整屏右下角落位（spec 0009 / 票 #72，#76 实机判定修正；票 #102 把几何提为
 * [chargingNumberRect] 纯函数并降档 30sp 带 %）：数字随水**越出内容安全矩形**、锚到整屏
 * 右下。横向内缩仍为半径 × 0.35 + md；纵向改为半径 × 0.35 + 3dp，保持字号同时腾出
 * 两行桌面尺寸图标的净空。数字与图标同量漂移，所有相位的本体均处于真实圆弧内。
 * 数字独立于 Icon Set 子树，不参与图标缩放；图标
 * 布局经 [SafeArea.placeNotificationBlock] 给数字占位（[chargingNumberPlaceholder] 含缺口）让位，
 * 两者构造性不相交（DisplaySafeAreaTest 钉住）。
 */
private fun Modifier.chargingNumberPlacement(geom: DisplayGeometry, drift: PxOffset): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints())
        val rect = chargingNumberRect(
            windowWidth = geom.width,
            windowHeight = geom.height,
            cornerRadiusPx = geom.cornerRadius,
            extraInsetPx = RearCueSpacing.md.roundToPx(),
            numberWidth = placeable.width,
            numberHeight = placeable.height,
            verticalExtraInsetPx = ChargingNumberBottomGutter.roundToPx(),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(rect.left + drift.x, rect.top + drift.y)
        }
    }

/** 每分钟打点（防烧屏漂移按分钟换位轮驻）。 */
@Composable
private fun currentMinute(): State<Int> = produceState(initialValue = minuteOf(System.currentTimeMillis())) {
    while (true) {
        val now = System.currentTimeMillis()
        delay(60_000L - now % 60_000L + 250L) // 跨分钟后留 250ms 让重组落定
        value = minuteOf(System.currentTimeMillis())
    }
}

private fun minuteOf(epochMs: Long): Int = (epochMs / 60_000L).toInt()
