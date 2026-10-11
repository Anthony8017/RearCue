package com.rearcue.poc.design

import android.view.RoundedCorner
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 主屏安全区（票 #25）：内容全程避开挖孔、四角圆角与底部手势条。
 *
 * 数据全部取自平台 `android.view.WindowInsets`，不自算几何、不硬编码机型数字（对齐
 * RearDisplayLocator「运行时读取」先例）：
 * - [WindowInsets.safeDrawing]：displayCutout（本机顶部挖孔 150px）+ 系统栏/手势条（本机 52px）；
 * - `WindowInsets.getRoundedCorner()`：四角圆角半径（本机 r=190px），折成矩形安全留白
 *   `r·(1−1/√2)`（圆角内接正方形的角点距离，向上取整 dp）；
 * - 常规内容留白 = max(布局 gutter, 折算留白)；主屏可把上下边压到系统安全边界，
 *   同时用圆角折算留白兜底，换机型只换输入、留白自动跟上。
 */
@Composable
fun Modifier.safeAreaPadding(
    contentTopFlush: Boolean = false,
    contentBottomFlush: Boolean = false,
): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val clearance = roundedCornerClearance(view, density)
    val gutter = maxOf(RearCueSpacing.screenGutter, clearance)
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val safeTop = safePadding.calculateTopPadding()
    val safeBottom = safePadding.calculateBottomPadding()
    val topGutter = if (contentTopFlush) {
        maxOf(0.dp, clearance - safeTop)
    } else {
        gutter
    }
    val bottomGutter = if (contentBottomFlush) {
        maxOf(0.dp, clearance - safeBottom)
    } else {
        gutter
    }
    return this
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(
            start = gutter,
            top = topGutter,
            end = gutter,
            bottom = bottomGutter,
        )
}

/** 四角圆角半径的最大值（px）：圆角采集的唯一口径（主屏留白与背屏显示几何共用，#30 review 抽取）。
 *  接收者用全限定名：与 Compose 的 `WindowInsets` 同名，避免 import 撞名。 */
internal fun android.view.WindowInsets.maxCornerRadiusPx(): Int = listOf(
    RoundedCorner.POSITION_TOP_LEFT,
    RoundedCorner.POSITION_TOP_RIGHT,
    RoundedCorner.POSITION_BOTTOM_LEFT,
    RoundedCorner.POSITION_BOTTOM_RIGHT,
).mapNotNull { position -> getRoundedCorner(position)?.radius }.maxOrNull() ?: 0

/** 四角圆角 → 矩形安全留白；读不到窗口 insets（未附着）时为 0，只留布局 gutter。 */
private fun roundedCornerClearance(view: View, density: Density): Dp {
    val insets = view.rootWindowInsets ?: return 0.dp
    val radiusPx = insets.maxCornerRadiusPx()
    return with(density) { ceil(radiusPx * (1.0 - 1.0 / sqrt(2.0))).toFloat().toDp() }
}
