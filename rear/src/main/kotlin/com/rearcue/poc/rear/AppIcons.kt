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
fun PackageManager.resolveApp(pkg: String, sizePx: Int): AppBadge =
    AppBadge(label = resolveLabel(pkg), icon = resolveIcon(pkg, sizePx))

/** 只取应用名（Notification Feed 横幅的应用名）；解析失败退化为包名末段，同 [resolveApp] 口径。 */
fun PackageManager.resolveLabel(pkg: String): String =
    runCatching { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
        .getOrElse { pkg.substringAfterLast('.') }

/** 只取图标（图标行不显示文字标签；横幅文案见 [resolveLabel]）。 */
fun PackageManager.resolveIcon(pkg: String, sizePx: Int): Painter? = runCatching {
    getApplicationIcon(pkg).toBitmap(width = sizePx, height = sizePx).asImageBitmap()
}.getOrNull()?.let(::BitmapPainter)
