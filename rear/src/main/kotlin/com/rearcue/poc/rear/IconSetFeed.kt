package com.rearcue.poc.rear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Icon Set 进程内广播：主屏调试页与背屏 Dashboard 都在同一进程，用一条状态流对齐，
 * 避免背屏界面反过来依赖 app 层容器（依赖方向只能是 app → rear）。
 *
 * 写方是 app 层 [com.rearcue.poc.AppContainer]；读方是 [RearDashboardActivity]。
 */
object IconSetFeed {

    private val _iconSet = MutableStateFlow<List<String>>(emptyList())

    val iconSet: StateFlow<List<String>> = _iconSet.asStateFlow()

    fun publish(packages: List<String>) {
        _iconSet.value = packages
    }
}
