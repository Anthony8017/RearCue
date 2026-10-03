package com.rearcue.poc.rear

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext

/**
 * 会话状态标识（2026-10-04 机主定夺）：
 * - 工作中＝Codex Desktop 同观感 Spinner，浅灰、30% 底环＋270° 前景弧、顺时针 2 秒一圈；
 * - 其他可显示状态＝静止圆点，形状与颜色来自 [AgentMirrorParams.sessionStatusIndicator]；
 * - 系统关闭动画时 Spinner 停在缺口朝右的静止帧，不动。
 *
 * 几何沿参考文档的可见圆环比例：外半径 8、内半径 6，即环宽是可见外径的 1/8。
 * 渲染槽仍沿用会话状态标识的 8dp，视觉外径与旧圆点相当。
 */
@Composable
internal fun SessionStatusIndicatorView(
    indicator: AgentMirrorParams.SessionStatusIndicator,
    modifier: Modifier = Modifier,
) {
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val rotation = if (indicator.kind == AgentMirrorParams.SessionIndicatorKind.SPINNER && animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "sessionStatusSpinner")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = AgentMirrorParams.SPINNER_CYCLE_MS,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sessionStatusSpinnerProgress",
        )
        AgentMirrorParams.spinnerRotationDegrees(progress)
    } else {
        0f
    }

    Canvas(modifier = modifier.rotate(rotation)) {
        when (indicator.kind) {
            AgentMirrorParams.SessionIndicatorKind.SPINNER -> {
                val stroke = size.minDimension * AgentMirrorParams.SPINNER_STROKE_RATIO
                val inset = stroke / 2f
                val ringSize = Size(size.width - stroke, size.height - stroke)
                drawCircle(
                    color = indicator.color.copy(alpha = AgentMirrorParams.SPINNER_TRACK_ALPHA),
                    radius = (size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = indicator.color,
                    startAngle = -90f,
                    sweepAngle = AgentMirrorParams.SPINNER_FOREGROUND_SWEEP_DEGREES,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = ringSize,
                    style = Stroke(width = stroke),
                )
            }
            AgentMirrorParams.SessionIndicatorKind.DOT -> {
                drawCircle(color = indicator.color)
            }
        }
    }
}

/**
 * Android 的「移除动画/减少动态效果」由 animator duration scale 表达；监听它，
 * 系统设置在页面存活期间切换也能立即停住 Spinner。
 */
@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
    val contentResolver = LocalContext.current.contentResolver
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                enabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        contentResolver.registerContentObserver(uri, false, observer)
        enabled = ValueAnimator.areAnimatorsEnabled()
        onDispose { contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

