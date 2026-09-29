package com.rearcue.poc.rear

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Icon Set 入场/补位动效的共享原语（spec 0015 / 票 #147）。
 *
 * 身份只认包名、不认下标：前后两帧用 [enteringApps]/[exitingApps] 对账；实际落点由
 * [IconSetMotionLayer] 按 Compose 的稳定 key 观察布局位置后自行推导。背屏 Dashboard
 * 与主屏总览页共用这里的时长与弹性取值，保证观感一致。
 *
 * #148 复用：用 [exitingApps] 找出退场包名，让对应 [IconSetMotionLayer] 继续留在组合树里
 * （`exiting = true`），几何层继续把退场条目算作占格；动画结束后再由上层移除。
 * #149 复用：档位整组缩放请在布局/组容器层用 [MoveSpec] 驱动尺寸或 scale；本层只负责
 * 单枚图标的入场与落点位移，不重新做几何判决。
 */
object IconSetMotion {

    /** 入场约 0.25 秒；沿用 Detail View 展开的弹性族（0.62 阻尼比）。 */
    const val ENTER_DURATION_MS = 250

    /** 新生图标从 0 轻微过冲后收回；幅度由 0.62 阻尼比与 700 刚度共同定档。 */
    val EnterScaleSpec: SpringSpec<Float> = spring(dampingRatio = 0.62f, stiffness = 700f)

    /** 单枚图标落点平滑过渡：略高阻尼，快速到位但不过度回弹。 */
    val MoveSpec: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 700f)

    /** 退场收缩（#148 使用）：同族反向，避免退场拖尾。 */
    val ExitScaleSpec: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 700f)

    /**
     * 当前帧比上一帧新增的包名。`previous == null` 表示没有可对账的上一帧
     * （冷启动首帧），此时不播入场；空列表是有效上一帧，之后到来的图标仍会入场。
     */
    fun enteringApps(previous: List<String>?, current: List<String>): Set<String> =
        if (previous == null) emptySet() else current.toSet() - previous.toSet()

    /** 上一帧有、当前帧没有的包名，供 #148 保持退场条目占格与收缩。 */
    fun exitingApps(previous: List<String>?, current: List<String>): Set<String> =
        if (previous == null) emptySet() else previous.toSet() - current.toSet()

    /**
     * 「已呈现」序列（spec 0015 / 票 #148，纯函数）：退场条目留在它们上一帧的槽位（占格），
     * 其余条目按当前帧顺序补进空槽，多出来的接在尾部。退场条目占格但不占容量，所以
     * [capacity] 有限时容量先给当前帧的条目，超出的退场条目这一步就退出呈现（不演、不占「+N」）。
     */
    fun presentedOrder(
        previousOnScreen: List<String>,
        current: List<String>,
        exiting: Set<String>,
        capacity: Int = Int.MAX_VALUE,
    ): List<String> {
        val kept = previousOnScreen
            .filter { it in exiting }
            .take((capacity - current.size).coerceAtLeast(0))
            .toSet()
        val presented = ArrayList<String>(current.size + kept.size)
        var next = 0
        previousOnScreen.forEach { app ->
            when {
                app in kept -> presented += app
                app !in exiting && next < current.size -> presented += current[next++]
            }
        }
        while (next < current.size) presented += current[next++]
        return presented
    }
}

private class IconSetMotionHistory {
    var previous: List<String>? = null
}

/**
 * 按包名对账出的本帧入场集合；空 Icon Set 也会写入历史，避免「清空后再来第一条」
 * 被误判成冷启动、漏掉入场动画。
 */
@Composable
fun rememberIconSetEnteringApps(current: List<String>): Set<String> {
    val history = remember { IconSetMotionHistory() }
    return remember(current) {
        val entering = IconSetMotion.enteringApps(history.previous, current)
        history.previous = current
        entering
    }
}

/** 一帧的「已呈现」账目：本帧留在组合树里的序列与其中的退场条目（spec 0015 / 票 #148）。 */
data class IconSetPresentation(
    /** 本帧要画在屏上的包名：退场条目按上一帧槽位占格，其余按当前帧顺序。 */
    val apps: List<String>,
    /** 其中的退场条目：收缩淡出，演完由 [IconSetExitLedger.finish] 摘除占格。 */
    val exiting: Set<String>,
)

/**
 * 退场账本（spec 0015 / 票 #148）：跨帧记住「上一帧真正画在屏上的包名」，把当前帧缺席的
 * 登记为退场中，直到 [IconSetMotionLayer.onExitFinished] 回执（[finish]）才让出格位。
 *
 * 退场条目**占格不占容量**：它们继续留在组合树与几何计算里，其余条目按既有纯几何落位；
 * 若退场条目不占格，同一处补位会被拆成「数据变化」与「摘除占格」两次（先闪一下、再抖一次）。
 * [silent] 是「不演退场」的当口（详情卡片盖着的图标），直接让出格位；上一帧在「+N」里的
 * 条目不在本账内（背屏回填的基线只含网格里的条目），天然不演。
 *
 * 每帧的调用顺序：组合期用 [frame] 对账本帧呈现，帧末用 [onScreen] 回填「本帧真正画在屏上」
 * 的包名（含退场条目）作为下一帧基线。背屏传网格里的条目（≤6），主屏传全量。
 */
class IconSetExitLedger(private val capacity: Int = Int.MAX_VALUE) {

    /**
     * 上一帧真正画在屏上的包名（含退场条目）。snapshot state：[finish] 摘除它要触发一次重组；
     * 回填走 SideEffect（帧末一次，晚于本帧推导、早于下一帧），等值守卫保证值没变不写。
     */
    private var onScreen by mutableStateOf(emptyList<String>())

    /**
     * 本帧呈现账目。**组合期同步调用**（等副作用会先闪一帧空档），只读上一帧基线与入参，
     * 同一帧的组合跑几趟结果都一样。
     */
    fun frame(current: List<String>, silent: Set<String> = emptySet()): IconSetPresentation {
        val live = current.toSet()
        val exiting = onScreen.filterTo(mutableSetOf()) { it !in live && it !in silent }
        return IconSetPresentation(
            apps = IconSetMotion.presentedOrder(onScreen, current, exiting, capacity),
            exiting = exiting,
        )
    }

    /**
     * 帧末回填：`apps` 是本帧真正画在屏上的包名——背屏是网格里的条目（≤6，+N 溢出不在内）、
     * 主屏是全量。它同时是下一帧的退场对账基线，所以要把退场条目一起记进去。
     */
    fun onScreen(apps: List<String>) {
        if (apps != onScreen) onScreen = apps
    }

    /** 退场演完（[IconSetMotionLayer.onExitFinished] 回执）：让出格位，其余条目这一步滑到位。 */
    fun finish(app: String) {
        if (app !in onScreen) return
        onScreen = onScreen.filterNot { it == app }
    }
}

private class IconMotionLayoutState {
    var lastPosition: Offset? = null
}

/**
 * 单枚 Icon Set 图标的动效层：外部只需用稳定包名 key 保持同一 composition。
 *
 * - `entering = true`：内容从 0 缩放入场，角标/图标共用同一进度淡入；
 * - 布局位置变化：从上一落点滑到新落点（由布局阶段观测，两屏复用同一实现）；
 * - `exiting = true`：收缩淡出（spec 0015 / 票 #148），播完回调 `onExitFinished` 由调用方
 *   摘除占格条目——退场期间它照旧占格，其余图标按既有几何落位；
 * - 防烧屏漂移应作用于外层整层，不能进入本层观测的父坐标系，否则会被误判为补位。
 */
@Composable
fun IconSetMotionLayer(
    identity: Any,
    entering: Boolean,
    exiting: Boolean = false,
    modifier: Modifier = Modifier,
    onExitFinished: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scale = remember(identity) { Animatable(if (entering) 0f else 1f) }
    val offsetX = remember(identity) { Animatable(0f) }
    val offsetY = remember(identity) { Animatable(0f) }
    val layoutState = remember(identity) { IconMotionLayoutState() }
    val scope = rememberCoroutineScope()
    val exitFinished = rememberUpdatedState(onExitFinished)

    LaunchedEffect(identity, entering, exiting) {
        when {
            exiting -> {
                scale.animateTo(0f, IconSetMotion.ExitScaleSpec)
                exitFinished.value()
            }
            entering -> {
                scale.snapTo(0f)
                scale.animateTo(1f, IconSetMotion.EnterScaleSpec)
            }
            scale.value < 1f -> scale.animateTo(1f, IconSetMotion.EnterScaleSpec)
            else -> scale.snapTo(1f)
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val newPosition = coordinates.positionInParent()
            val oldPosition = layoutState.lastPosition
            if (oldPosition != null && newPosition != oldPosition) {
                val deltaX = oldPosition.x + offsetX.value - newPosition.x
                val deltaY = oldPosition.y + offsetY.value - newPosition.y
                // UNDISPATCHED: 布局回调内立即把图层拨回旧落点，避免先闪一帧新位置。
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offsetX.snapTo(deltaX)
                    offsetY.snapTo(deltaY)
                    launch { offsetX.animateTo(0f, IconSetMotion.MoveSpec) }
                    launch { offsetY.animateTo(0f, IconSetMotion.MoveSpec) }
                }
            }
            layoutState.lastPosition = newPosition
        },
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                translationX = offsetX.value
                translationY = offsetY.value
                scaleX = scale.value
                scaleY = scale.value
                // 角标与图标共用同一入场进度；回弹过冲只体现在 scale，不放大透明度。
                alpha = scale.value.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin.Center
            },
        ) {
            content()
        }
    }
}
