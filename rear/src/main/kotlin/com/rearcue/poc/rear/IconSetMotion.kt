package com.rearcue.poc.rear

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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

private class IconMotionLayoutState {
    var lastPosition: Offset? = null
}

/**
 * 单枚 Icon Set 图标的动效层：外部只需用稳定包名 key 保持同一 composition。
 *
 * - `entering = true`：内容从 0 缩放入场，角标/图标共用同一进度淡入；
 * - 布局位置变化：从上一落点滑到新落点（由布局阶段观测，两屏复用同一实现）；
 * - `exiting = true`：收缩淡出（#148 的复用入口，本票不启用）；`onExitFinished` 用于动画
 *   结束后移除占位条目；
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
