package com.rearcue.poc.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 主题装配（票 #25）：Material3 调色板直接取自 [RearCueColors]，组件（Button/Card/Text）与
 * 自绘组件共用同一份语义令牌。只有深色一套主题——Dashboard 定义就是纯黑（CONTEXT.md
 * 「Dashboard」），AMOLED 纯黑 + 单一强调色是产品级设计语言，不提供浅色主题。
 */
private val RearCueColorScheme = darkColorScheme(
    primary = RearCueColors.accent,
    onPrimary = RearCueColors.onAccent,
    primaryContainer = RearCueColors.accent,
    onPrimaryContainer = RearCueColors.onAccent,
    background = RearCueColors.background,
    onBackground = RearCueColors.onBackground,
    surface = RearCueColors.surface,
    onSurface = RearCueColors.onBackground,
    surfaceVariant = RearCueColors.surfaceHighlight,
    onSurfaceVariant = RearCueColors.onBackgroundSecondary,
    outline = RearCueColors.outline,
    error = RearCueColors.error,
    onError = RearCueColors.onError,
)

@Composable
fun RearCueTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RearCueColorScheme, content = content)
}
