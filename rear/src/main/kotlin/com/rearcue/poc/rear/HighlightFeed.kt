package com.rearcue.poc.rear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification Highlight 进程内广播（spec 0008 / 票 #65）：与 [IconSetFeed]/[ChargingFeed]
 * 同一形状——状态流对齐同进程的两端，依赖方向仍是 app → rear（背屏界面不依赖 app 层容器）。
 *
 * 两条流：
 * - [apps]：当前高亮集（图标暖白描边的常态数据，core 的 `highlightApps` 投影，每次状态刷新重发）；
 * - [breathUntil]：最近一次呼吸的**截止时刻**（epoch ms，core 呼吸窗决策换算）。用绝对截止而不是
 *   一次性事件，是因首投路径上呼吸指令先于背屏界面挂载——晚挂载按剩余时长播放、已过期不播，
 *   「呼吸一次约 3 秒」不因竞态变成漏播或双播。
 *
 * 写方是 app 层 `AppContainer`（refresh 重发 apps；HighlightBreath 效果执行处发 breathUntil），
 * 读方是 [RearDashboardActivity]。
 */
object HighlightFeed {

    private val _apps = MutableStateFlow<Set<String>>(emptySet())

    val apps: StateFlow<Set<String>> = _apps.asStateFlow()

    private val _breathUntil = MutableStateFlow(0L)

    val breathUntil: StateFlow<Long> = _breathUntil.asStateFlow()

    fun publish(apps: Set<String>) {
        _apps.value = apps
    }

    fun publishBreath(untilMs: Long) {
        _breathUntil.value = untilMs
    }
}
