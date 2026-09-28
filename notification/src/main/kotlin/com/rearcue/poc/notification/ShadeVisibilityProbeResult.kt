package com.rearcue.poc.notification

/**
 * 一次 SystemUI 探测结果的纯 Kotlin 归一化（ADR 0007，票 #130）。
 *
 * [visibleKeys] 为 null 表示探测不可用/解析失败/超时，按未知 fail-open。
 * 非空的 [probedKeys] 与 [visibleKeys] 完全不相交时，按 dump 形状漂移处理，返回 null
 * （全部在册即可见，宁可多显示不漏消息）；可见集为空仍是合法探测结果，不得误判为未知。
 */
object ShadeVisibilityProbeResult {

    fun normalize(probedKeys: Set<String>, visibleKeys: Set<String>?): Set<String>? {
        if (visibleKeys == null) return null
        if (probedKeys.isEmpty() || visibleKeys.isEmpty()) return visibleKeys
        return visibleKeys.takeIf { keys -> probedKeys.any { it in keys } }
    }
}
