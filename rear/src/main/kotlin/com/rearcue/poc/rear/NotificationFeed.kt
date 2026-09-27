package com.rearcue.poc.rear

import com.rearcue.poc.core.FeedBanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification Feed 进程内广播（spec 0007）：背屏 Dashboard 的横幅内容，与 [IconSetFeed]
 * 同一形状——状态流对齐同进程的两端，依赖方向仍是 app → rear（背屏界面不依赖 app 层容器）。
 *
 * 写方是 app 层 `AppContainer`：Show/Hide 效果侧即时广播，且每次状态刷新都按
 * `DashboardCore.feedOnScreen` 重发一遍（投送/更新/退出等所有内容产出路径统一收口，
 * 不漏发、不落旧值）。读方是 [RearDashboardActivity]；null = 横幅隐藏。
 */
object NotificationFeed {

    private val _banner = MutableStateFlow<FeedBanner?>(null)

    val banner: StateFlow<FeedBanner?> = _banner.asStateFlow()

    fun publish(banner: FeedBanner?) {
        _banner.value = banner
    }
}
