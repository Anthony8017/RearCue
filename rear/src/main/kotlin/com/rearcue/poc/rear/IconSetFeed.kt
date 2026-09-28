package com.rearcue.poc.rear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Icon Set 进程内广播：主屏调试页与背屏 Dashboard 都在同一进程，用一条状态流对齐，
 * 避免背屏界面反过来依赖 app 层容器（依赖方向只能是 app → rear）。
 *
 * 写方是 app 层 [com.rearcue.poc.AppContainer]（refresh 每刷重发包名 + 未读角标计数，
 * 唯一事实源是 DashboardCore 的两个投影）；投送链路
 * [RearDisplayBackend.project]/[RearDisplayBackend.update]/[RearDisplayBackend.exit]
 * 只知道包名列表，走 [publish] 的单参形态（只换图标、计数沿用上一回）。读方是
 * [RearDashboardActivity]：包名决定画哪些图标，计数（[unreadCounts]）决定角标数字与
 * 单/多切换（issue #101）。
 */
object IconSetFeed {

    private val _iconSet = MutableStateFlow<List<String>>(emptyList())

    val iconSet: StateFlow<List<String>> = _iconSet.asStateFlow()

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 每 App 未读角标计数（键 ⊆ [iconSet]，键序同 [iconSet] 的时间倒序）。 */
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    /**
     * 发布 Icon Set。[unreadCounts] 为 null = 本次只换图标、计数保持上一回的值
     * （投送链路的单参调用）；app 层 refresh 每刷都显式传入 core 的计数投影。
     */
    fun publish(packages: List<String>, unreadCounts: Map<String, Int>? = null) {
        _iconSet.value = packages
        if (unreadCounts != null) _unreadCounts.value = unreadCounts
    }
}
