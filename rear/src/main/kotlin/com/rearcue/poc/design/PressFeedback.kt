package com.rearcue.poc.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 按压反馈（票 #25）：按下在 [RearCueMotion.pressFeedbackMs]（80–150ms 带）内给出缩放反馈，
 * 叠加 Material3 原生 ripple（平台原生缓动）；只动 graphicsLayer，按压态不改变布局边界。
 */
@Composable
fun Modifier.pressFeedback(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = RearCueMotion.pressFeedbackMs, easing = FastOutSlowInEasing),
        label = "pressFeedback",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
