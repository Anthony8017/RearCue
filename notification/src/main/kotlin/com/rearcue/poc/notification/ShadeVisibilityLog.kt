package com.rearcue.poc.notification

/**
 * Shade-visible 探测日志锚词形契约（ADR 0007，票 #130，同 `DashboardCore` 日志契约惯例）：
 * `shade-visible probe reason=<reason> systemUiVisible=<n|unknown> raw=<n> shown=<n>` /
 * `shade-visible probe shell failed reason=<reason>` /
 * `shade-visible probe fallback reason=<reason>`——tools/ex 验收链按词形读，**byte 不可改**。
 * logcat 实现统一 TAG=RearCue。
 */
object ShadeVisibilityLog {

    const val LOG_CONTRACT =
        "shade-visible probe reason=<reason> systemUiVisible=<n|unknown> raw=<n> shown=<n>; " +
            "shade-visible probe shell failed reason=<reason>; " +
            "shade-visible probe fallback reason=<reason>"

    fun probe(reason: String, systemUiVisible: Int?, raw: Int, shown: Int): String =
        "shade-visible probe reason=$reason systemUiVisible=${systemUiVisible ?: "unknown"} raw=$raw shown=$shown"

    fun shellFailed(reason: String): String =
        "shade-visible probe shell failed reason=$reason"

    fun fallback(reason: String): String =
        "shade-visible probe fallback reason=$reason"
}
