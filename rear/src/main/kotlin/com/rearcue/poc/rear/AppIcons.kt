package com.rearcue.poc.rear

import android.content.pm.PackageManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.graphics.drawable.toBitmap

/** 一枚要展示的应用图标：[label] 仅调试页用，背屏只画 [icon]。 */
data class AppBadge(
    val label: String,
    val icon: Painter?,
)

/**
 * 按包名解析应用图标与应用名。
 *
 * Android 11+ 需要 manifest 里声明 `<queries>` 可见性才能拿到第三方应用数据；
 * 解析失败（包被卸载/不可见）退化为包名末段 + 首字母，不崩。
 */
fun PackageManager.resolveApp(pkg: String, sizePx: Int): AppBadge {
    val label = runCatching { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
        .getOrElse { pkg.substringAfterLast('.') }
    return AppBadge(label = label, icon = resolveIcon(pkg, sizePx))
}

/** 只取图标（背屏 Dashboard 不显示文字标签）。 */
fun PackageManager.resolveIcon(pkg: String, sizePx: Int): Painter? = runCatching {
    getApplicationIcon(pkg).toBitmap(width = sizePx, height = sizePx).asImageBitmap()
}.getOrNull()?.let(::BitmapPainter)
