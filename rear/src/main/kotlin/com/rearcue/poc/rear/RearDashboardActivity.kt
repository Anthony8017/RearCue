package com.rearcue.poc.rear

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueIconSize
import com.rearcue.poc.design.RearCueShape
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTheme
import com.rearcue.poc.design.maxCornerRadiusPx
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Icon Set 一枚应用图标：取票 #25 的语义化令牌（背屏与主屏共用一套）。 */
private val IconSize = RearCueIconSize.iconSetRearDisplay

/** 防烧屏漂移幅度（票 #26）：3dp；收敛成漂移边界是 [DisplaySafeArea] 的事。 */
private val DriftAmplitude = 3.dp

/** 充电闪电尺寸（spec 0007 票 #57）：介于结构图标与 Icon Set 图标之间的独立观感档。 */
private val ChargingBoltSize = 48.dp

/** 与 app 层日志同一个观测面（`adb logcat -s RearCue`）。 */
private const val TAG = "RearCue"

/**
 * 背屏 Dashboard：纯黑背景 + Icon Set（spec 0008：常态无时间、无横幅——原生背屏已有时钟，
 * spec 0007 的 Notification Feed 从背屏撤下，见 CONTEXT.md「Dashboard」「Notification Feed」）。
 *
 * 由 [RearDisplayBackend] 投送到背屏（应用内 `setLaunchDisplayId` 为主，Shizuku 的
 * `am start --display <id>` 只是未锁屏兜底）；本界面不做投送决策，只渲染 [IconSetFeed] 的当前
 * Icon Set（居中放大，对照设计稿 `docs/mockups/0008-dashboard-visual/chatgpt/01-idle-icons.png`）
 * 与 [ChargingFeed] 的充电动画面。
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
 * 都是约束输出）；防烧屏漂移按分钟轮驻极限位，任何时刻不越出安全矩形。
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
                val input by geometry.collectAsState()
                val rules = input?.let(DisplaySafeArea::resolve)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RearCueColors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    if (rules != null) {
                        LaunchedEffect(rules, input) {
                            Log.i(TAG, "rear-safe-geometry $input -> content=${rules.contentRect} drift=${rules.driftBounds} layout=${rules.layoutRect}")
                        }
                        val minute by currentMinute()
                        DashboardContent(iconSet, charging, rules, rules.driftFor(minute))
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
 * Dashboard 内容（spec 0008）：Icon Set 居中为常态本体，充电时叠加充电动画。
 *
 * 整体是 [dashboardPlacement] 度量的**单个子节点**（Column），动画与图标行都在这个
 * 被度量的子树内——漂移/缩放/安全区对整块内容统一生效，任何一块都不另起一套几何。
 */
@Composable
private fun DashboardContent(
    iconSet: List<String>,
    charging: Boolean,
    rules: SafeArea,
    drift: PxOffset,
) {
    Column(
        modifier = Modifier.dashboardPlacement(rules, drift),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
    ) {
        ChargingContent(charging)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        ) {
            iconSet.forEach { pkg -> DashboardIcon(pkg) }
        }
    }
}

/**
 * 充电动画（spec 0007 票 #57）：2D 简化闪电，纯 Compose Canvas 绘制——不引重力传感器、
 * 不引图片资源、不做 MRSS 的液体效果（Out of Scope）。呼吸式明暗 + 缩放脉冲自持循环，
 * 显隐走 [AnimatedVisibility] 淡入淡出（同横幅口径），退出时不留残影。
 */
@Composable
private fun ChargingContent(charging: Boolean) {
    AnimatedVisibility(visible = charging) {
        val transition = rememberInfiniteTransition(label = "charging")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "charging-alpha",
        )
        val scale by transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "charging-scale",
        )
        val cd = stringResource(R.string.charging_animation_cd)
        Box(
            modifier = Modifier
                .size(ChargingBoltSize)
                .semantics { contentDescription = cd }
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 闪电折线多边形（0..1 归一化坐标 → 画布尺寸）：两段折线的简化锯齿。
                val bolt = Path().apply {
                    moveTo(size.width * 0.58f, 0f)
                    lineTo(size.width * 0.16f, size.height * 0.58f)
                    lineTo(size.width * 0.44f, size.height * 0.58f)
                    lineTo(size.width * 0.38f, size.height)
                    lineTo(size.width * 0.86f, size.height * 0.40f)
                    lineTo(size.width * 0.56f, size.height * 0.40f)
                    close()
                }
                drawPath(path = bolt, color = RearCueColors.accent)
            }
        }
    }
}

@Composable
private fun DashboardIcon(pkg: String) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val icon = remember(pkg, sizePx) { context.packageManager.resolveIcon(pkg, sizePx) }
    if (icon != null) {
        Image(
            painter = icon,
            contentDescription = pkg,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(IconSize),
        )
    } else {
        // 解析不到图标退化为首字母块（错误态不崩），形状/描边同主屏图标退化态。
        val shape = RoundedCornerShape(RearCueShape.medium)
        Box(
            modifier = Modifier
                .size(IconSize)
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
