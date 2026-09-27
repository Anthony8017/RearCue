package com.rearcue.poc.rear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Charging Animation 进程内广播（spec 0007 / 票 #57；spec 0008 / 票 #67 扩显示面数据）：
 * 背屏 Dashboard 当前该不该显示充电动画、以及电量比例读数，与 [IconSetFeed] 同一形状——
 * 状态流对齐同进程的两端，依赖方向仍是 app → rear（背屏界面不依赖 app 层容器）。
 * （spec 0008：同形状的 NotificationFeed 横幅流随横幅层退役删除。）
 *
 * 两条流：
 * - [charging]：充电在屏面（core 的 `chargingOnScreen` 投影，false = 动画面隐藏）；
 * - [levelPercent]：电量百分比读数（core 的 `batteryPercent` 投影，票 #67 的
 *   [com.rearcue.poc.core.DashboardEvent.BatteryLevel] 事件面，null = 尚无读数）——
 *   绿色比例填充与白色大号数字随它刷新（68→69），只在 [charging] 为真时呈现。
 *
 * 写方是 app 层 `AppContainer`：每次状态刷新都按 core 投影重发一遍
 * （投送/更新/退出/电量变化等一切内容产出路径统一收口，不漏发、不落旧值）。
 * 读方是 [RearDashboardActivity]。
 */
object ChargingFeed {

    private val _charging = MutableStateFlow(false)

    val charging: StateFlow<Boolean> = _charging.asStateFlow()

    private val _levelPercent = MutableStateFlow<Int?>(null)

    val levelPercent: StateFlow<Int?> = _levelPercent.asStateFlow()

    fun publish(charging: Boolean) {
        _charging.value = charging
    }

    fun publishLevel(percent: Int?) {
        _levelPercent.value = percent
    }
}
