package com.rearcue.poc.rear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Charging Animation 进程内广播（spec 0007 / 票 #57）：背屏 Dashboard 当前该不该显示
 * 充电动画，与 [IconSetFeed]/[NotificationFeed] 同一形状——状态流对齐同进程的两端，
 * 依赖方向仍是 app → rear（背屏界面不依赖 app 层容器）。
 *
 * 写方是 app 层 `AppContainer`：每次状态刷新都按 `DashboardCore.chargingOnScreen` 重发一遍
 * （投送/更新/退出等一切内容产出路径统一收口，不漏发、不落旧值；#55 横幅面同款收口）。
 * 读方是 [RearDashboardActivity]；false = 动画面隐藏。
 */
object ChargingFeed {

    private val _charging = MutableStateFlow(false)

    val charging: StateFlow<Boolean> = _charging.asStateFlow()

    fun publish(charging: Boolean) {
        _charging.value = charging
    }
}
