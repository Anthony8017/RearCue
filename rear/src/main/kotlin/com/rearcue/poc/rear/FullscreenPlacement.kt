package com.rearcue.poc.rear

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * 全屏浮层落位（Detail 卡片与会话选择器共用）：量成窗口满尺寸、摆在 (0,0)——
 * 渲染零决策照单执行（spec 0009 / 票 #74 的 Detail 判例，spec 0016 / 票 #156 的选择器沿用）。
 *
 * 独立成文件：两个浮层的共同几何出口，不属于任何一层的实现细节。
 */
internal fun Modifier.fullscreenPlacement(): Modifier =
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
