package com.rearcue.poc.rear

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.rearcue.poc.core.NotificationDetail
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueChargingWave
import com.rearcue.poc.design.RearCueHalo
import com.rearcue.poc.design.RearCueIconSize
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

/**
 * 充电白色大号数字字号（spec 0008 / 票 #67；spec 0009 / 票 #72 只换字体与落位不改字号档）：
 * 电量整数（不带百分号），对照设计稿 `chatgpt/04-charging-green.png` 的大数字一档。
 */
private val ChargingNumberSize = 60.sp

/** 与 app 层日志同一个观测面（`adb logcat -s RearCue`）。 */
private const val TAG = "RearCue"

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
 * 都是约束输出）；防烧屏漂移按分钟轮驻极限位，任何时刻不越出安全矩形。Detail 卡片同落
 * contentRect（瞬态视图不参与漂移）。
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
                val charging by ChargingFeed.charging.collectAsState()
                val levelPercent by ChargingFeed.levelPercent.collectAsState()
                val highlights by HighlightFeed.apps.collectAsState()
                val breathUntil by HighlightFeed.breathUntil.collectAsState()
                val detail by DetailFeed.detail.collectAsState()
                val input by geometry.collectAsState()
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RearCueColors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    // Notification Highlight 呼吸光晕（spec 0008 / 票 #65）：背景层，不参与
                    // 漂移/安全区（同充电填充口径），压在全部内容之下。
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
                        val minute by currentMinute()
                        val drift = rules.driftFor(minute)
                        DashboardContent(
                            iconSet = iconSet,
                            charging = charging,
                            highlights = highlights,
                            detailApp = detail?.app ?: lastDetail.value?.app,
                            detailProgress = { detailProgress.value },
                            rules = rules,
                            drift = drift,
                            iconCenters = iconCenters,
                            onIconTap = RearDashboardHost::emitIconTap,
                        )
                        // 充电大数字（spec 0009 / 票 #72 反转 0008 的顶部居中）：右下角落位
                        //（layoutRect 已含圆角与漂移余量，再随漂移平移），Outfit Light 细体。
                        if (charging) {
                            levelPercent?.let { percent ->
                                Text(
                                    text = percent.toString(),
                                    color = Color.White,
                                    fontSize = ChargingNumberSize,
                                    fontFamily = RearCueTypography.chargingNumber,
                                    fontWeight = FontWeight.Light,
                                    modifier = Modifier.chargingNumberPlacement(rules, drift),
                                )
                            }
                        }
                        // Detail 卡片层（票 #66；spec 0009 / 票 #74 反转 0008 落 contentRect）：
                        // 铺满整个背屏（含相机带，圆角随屏幕运行时读取），压在图标层之上；
                        // progress≈0 不组（常态零开销），展开/收起过渡期随进度绘。
                        // 卡片点按＝「再点按同一 App」的收起同形事件。
                        if (cardVisible) {
                            lastDetail.value?.let { shown ->
                                val screenRect = PxRect(0, 0, geom.width, geom.height)
                                DetailCard(
                                    shown = shown,
                                    progress = { detailProgress.value },
                                    cornerPx = geom.cornerRadius,
                                    origin = cardOrigin(iconCenters[shown.app], screenRect),
                                    onTap = { RearDashboardHost.emitIconTap(shown.app) },
                                )
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

/**
 * Dashboard 内容（spec 0009）：Icon Set 居中为常态本体（充电大数字已移出本子树——
 * 票 #72 把它挪到背屏右下角独立落位，不再参与 Icon Set 的居中缩放）。
 * 高亮集内的图标带暖白光晕（[HighlightFeed]，Notification Highlight 票 #65）；
 * 充电时非高亮图标带白色光晕保持可见（票 #67 / spec 0009 票 #73 改弥散光晕）。
 *
 * [detailApp]/[detailProgress] 是 Detail View 的过渡输入（票 #66）：主体图标放大淡出、其余
 * 图标弱化；点按图标经 [onIconTap] 发往 app 层接线（DetailToggled）。过渡只动图形层，
 * Icon Set 几何与漂移判定完全不动。
 *
 * 整体是 [dashboardPlacement] 度量的**单个子节点**（Column），动画与图标行都在这个
 * 被度量的子树内——漂移/缩放/安全区对整块内容统一生效，任何一块都不另起一套几何。
 */
@Composable
private fun DashboardContent(
    iconSet: List<String>,
    charging: Boolean,
    highlights: Set<String>,
    detailApp: String?,
    detailProgress: () -> Float,
    rules: SafeArea,
    drift: PxOffset,
    iconCenters: MutableMap<String, Offset>,
    onIconTap: (String) -> Unit,
) {
    Column(
        modifier = Modifier.dashboardPlacement(rules, drift),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        ) {
            iconSet.forEach { pkg ->
                DashboardIcon(
                    pkg = pkg,
                    highlighted = pkg in highlights,
                    chargingGlow = charging && pkg !in highlights,
                    isDetailSubject = pkg == detailApp,
                    detailProgress = detailProgress,
                    iconCenters = iconCenters,
                    onTap = { onIconTap(pkg) },
                )
            }
        }
    }
}

/**
 * 充电绿色水位（票 #71 全屏化反转 0008「相机带不染色」；票 #75 水面微波；对照设计稿
 * `chatgpt/04-charging-green.png`）：绿色水铺满**整个背屏**（含相机带——0008 的避让反转
 * 留痕见 docs/specs/0009），自底部按电量比例填充（几何照 [ChargingWater] 纯函数）：
 * 低饱和翠绿垂直渐变（靠上缘亮、沉底深）；上缘是复合正弦**微波水面**（振幅数 px、慢相位
 * 漂移，安静档——无气泡无 3D 重力液体），上缘亮线与向上渐隐微光随波面走。
 * 相位动画只在充电且无读数缺失时才组（[rememberInfiniteTransition] 不进常态组合树），
 * 帧驱动在 draw 阶段读状态、不逐帧重组。背景层不参与漂移/安全区。
 * 无读数（[levelPercent] = null）或未充电不渲染水体，黑底图标态兜底。
 */
@Composable
private fun ChargingFillLayer(charging: Boolean, levelPercent: Int?) {
    val cd = stringResource(R.string.charging_animation_cd)
    val waterCanvas: Modifier = Modifier
        .fillMaxSize()
        .semantics { contentDescription = cd }
    if (!charging || levelPercent == null) {
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
 * 深灰圆角卡片铺满**整个背屏**（含相机带；圆角随屏幕运行时读取），不显示应用名——
 * 机主定案：应用名与通知标题常重复成两遍，标题承载来源（小图标同行删去）；
 * 标题 + 全文（白字、留白充分、长文可滚动——「显示全文」无遮蔽档，CONTEXT.md「Detail View」）。
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
    onTap: () -> Unit,
) {
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
            .background(RearCueColors.detailSurface)
            // 全屏读文留白：屏缘取 lg（比 0008 小卡片的 md 放宽一档），正文吃剩余高度。
            .padding(RearCueSpacing.lg),
    ) {
        Column {
            if (shown.title.isNotEmpty()) {
                Text(
                    text = shown.title,
                    color = RearCueColors.onBackground,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(RearCueSpacing.xs))
            }
            if (shown.text.isNotEmpty()) {
                Text(
                    text = shown.text,
                    color = RearCueColors.onBackground,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    // weight(1f, fill=false)：正文只吃标题行之下的剩余高度，超出在剩余高度内
                    // 滚动（「显示全文」无遮蔽档），不把 Column 撑出卡片底缘被裁。
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
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
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
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
                .size(IconSize)
                .then(detailMotion)
                .then(tap)
                .then(highlightHalo),
        )
    } else {
        // 解析不到图标退化为首字母块（错误态不崩），形状/强调同主屏图标退化态。
        val shape = RoundedCornerShape(RearCueShape.medium)
        Box(
            modifier = Modifier
                .size(IconSize)
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
 * 充电大数字右下角落位（spec 0009 / 票 #72，照单执行 [DisplaySafeArea] 输出）：
 * 锚在 [SafeArea.layoutRect] 右下内缩 [RearCueSpacing.md]（layoutRect 已收缩圆角与漂移
 * 幅度，角落避让圆角），再随漂移平移——漂移极限位仍不出 contentRect（同 Icon Set 判例）。
 * 独立于 Icon Set 子树：数字不参与居中缩放，Icon Set 几何与 0008 不回退地一致。
 */
private fun Modifier.chargingNumberPlacement(rules: SafeArea, drift: PxOffset): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints())
        val pad = RearCueSpacing.md.roundToPx()
        val x = rules.layoutRect.right - placeable.width - pad + drift.x
        val y = rules.layoutRect.bottom - placeable.height - pad + drift.y
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(x, y)
        }
    }

/**
 * 照单执行 [DisplaySafeArea] 的输出（票 #26，渲染层零决策）：
 * 内容按 [SafeArea.fitScale] 等比缩进布局框、在布局框内居中锚定，再整体按漂移偏移平移——
 * 缩放系数、锚定点、可漂移范围全部来自约束输出，本层只搬运。
 */
private fun Modifier.dashboardPlacement(rules: SafeArea, drift: PxOffset): Modifier =
    this.layout { measurable, constraints ->
        val maxWidth = when {
            rules.layoutRect.width > 0 -> rules.layoutRect.width
            constraints.hasBoundedWidth -> constraints.maxWidth
            else -> rules.contentRect.right
        }
        val placeable = measurable.measure(
            Constraints(maxWidth = maxWidth, maxHeight = Constraints.Infinity),
        )
        val scale = rules.fitScale(placeable.width, placeable.height)
        val scaledWidth = placeable.width * scale
        val scaledHeight = placeable.height * scale
        val x = (rules.layoutRect.left + drift.x + (rules.layoutRect.width - scaledWidth) / 2).roundToInt()
        val y = (rules.layoutRect.top + drift.y + (rules.layoutRect.height - scaledHeight) / 2).roundToInt()
        val placedRight = (x + scaledWidth).roundToInt()
        val placedBottom = (y + scaledHeight).roundToInt()
        Log.i(
            TAG,
            "rear-safe-place layout=${rules.layoutRect} drift=$drift " +
                "natural=${placeable.width}x${placeable.height} scale=$scale " +
                "placed=[$x, $y, $placedRight, $placedBottom]",
        )
        val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placedRight
        val layoutHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else placedBottom
        layout(layoutWidth, layoutHeight) {
            placeable.placeWithLayer(x, y) {
                scaleX = scale.toFloat()
                scaleY = scale.toFloat()
                transformOrigin = TransformOrigin(0f, 0f)
            }
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
