package com.rearcue.poc.rear

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val IconSize = 64.dp
private val ContentPadding = 24.dp

/** 与 app 层日志同一个观测面（`adb logcat -s RearCue`）。 */
private const val TAG = "RearCue"

/**
 * 背屏 Dashboard：纯黑背景 + 时间 + Icon Set（见 CONTEXT.md「Dashboard」）。
 *
 * 由 [RearDisplayBackend] 投送到背屏（应用内 `setLaunchDisplayId` 或 Shizuku 的
 * `am start --display <id>`）；本界面不做投送决策，只渲染 [IconSetFeed] 的当前 Icon Set。
 * 防烧屏：每次重组按分钟把内容整体偏移几个像素，不做常驻动画（票 #6 实测保活时再复核）。
 */
class RearDashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "RearDashboardActivity onCreate display=${display?.displayId}")
        setContent {
            val iconSet by IconSetFeed.iconSet.collectAsState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                DashboardContent(iconSet)
            }
        }
    }
}

@Composable
private fun DashboardContent(iconSet: List<String>) {
    val drift = driftOffset()
    Column(
        modifier = Modifier
            .padding(ContentPadding)
            .offset { drift },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TimeText()
        if (iconSet.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                iconSet.forEach { pkg -> DashboardIcon(pkg) }
            }
        }
    }
}

@Composable
private fun TimeText() {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Text(
        text = formatter.format(Date()),
        color = Color.White,
        fontSize = 56.sp,
        fontWeight = FontWeight.Light,
    )
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
        Box(
            modifier = Modifier
                .size(IconSize)
                .background(Color(0xFF222222)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = pkg.take(1).uppercase(), color = Color.White, fontSize = 28.sp)
        }
    }
}

/** 防烧屏微移：按分钟在 ±3px 内偏移，分钟变一次才变一次。 */
@Composable
private fun driftOffset(): IntOffset {
    val density = LocalDensity.current
    val minute = (System.currentTimeMillis() / 60_000L).toInt()
    val stepPx = with(density) { 3.dp.roundToPx() }
    val x = ((minute % 3) - 1) * stepPx
    val y = (((minute / 3) % 3) - 1) * stepPx
    return IntOffset(x, y)
}
