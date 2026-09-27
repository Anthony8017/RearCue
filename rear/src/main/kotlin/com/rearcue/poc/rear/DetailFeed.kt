package com.rearcue.poc.rear

import com.rearcue.poc.core.NotificationDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detail View 进程内广播（spec 0008 / 票 #66）：与 [IconSetFeed]/[HighlightFeed] 同一形状——
 * 状态流对齐同进程的两端，依赖方向仍是 app → rear（背屏界面不依赖 app 层容器）。
 *
 * null = 纯图标常态（无 Detail）；非空 = 卡片所示快照（core 的 `detail` 投影，打开即冻结，
 * 每次状态刷新重发，同 [IconSetFeed] 口径——Dashboard 撤下/降级/抢回重投时 core 已随之清，
 * 这里不会留着过期卡片）。
 *
 * 写方是 app 层 `AppContainer`（refresh 重发）；读方是 [RearDashboardActivity]。
 */
object DetailFeed {

    private val _detail = MutableStateFlow<NotificationDetail?>(null)

    val detail: StateFlow<NotificationDetail?> = _detail.asStateFlow()

    fun publish(detail: NotificationDetail?) {
        _detail.value = detail
    }
}
