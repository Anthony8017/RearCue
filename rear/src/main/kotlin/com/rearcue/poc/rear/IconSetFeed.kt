package com.rearcue.poc.rear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Icon Set 进程内广播：主屏调试页与背屏 Dashboard 都在同一进程，用一条状态流对齐，
 * 避免背屏界面反过来依赖 app 层容器（依赖方向只能是 app → rear）。
 *
 * 写方是 app 层 [com.rearcue.poc.AppContainer]（refresh 每刷重发包名 + 未读角标计数，
 * 唯一事实源是 DashboardCore 的两个投影）与投送链路
 * [RearDisplayBackend.project]/[RearDisplayBackend.update]/[RearDisplayBackend.exit]
 * ——两者都走 [publish] 的**全量入口**（恒两参，两态同笔写入，不留「只换图标、计数沿用上一回」
 * 的半更新缝）。读方是 [RearDashboardActivity]：包名决定画哪些图标，计数（[unreadCounts]）
 * 决定角标数字与单/多切换（issue #101）。
 */
object IconSetFeed {

    private val _iconSet = MutableStateFlow<List<String>>(emptyList())

    val iconSet: StateFlow<List<String>> = _iconSet.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 每 App 未读角标计数（键与序恒同 [iconSet]，见 `DashboardCore.unreadCounts`）。 */
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    /**
     * 发布 Icon Set（**唯一入口，恒两参**）：包名与未读角标计数是同一事实的两个投影
     * （键/序恒等），分两次写会让单/多切换（issue #101）读到「新图标 + 旧计数」的漂移态——
     * 两流同笔更新，调用方必须把 core 的两个投影一起交上来。
     */
    fun publish(packages: List<String>, unreadCounts: Map<String, Int>) {
        _iconSet.value = packages
        _unreadCounts.value = unreadCounts
    }
}
