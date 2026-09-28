package com.rearcue.poc.notify

import android.util.Log
import com.rearcue.poc.LOG_TAG
import com.rearcue.poc.notification.ShadeVisibilityDump
import com.rearcue.poc.notification.ShadeVisibilityProbe
import com.rearcue.poc.notification.ShadeVisibleNotificationGate
import com.rearcue.poc.rear.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「下拉栏可见性」的可选精确源：Shizuku 在线时读取 SystemUI 当前 NotifCollection，
 * 把结果交给 [ShadeVisibleNotificationGate]；不可用/解析失败时交给 null（fail-open）。
 *
 * 事件突发时只保留一个在途探测，期间再来的请求合并成一次尾随探测；所有 gate 变更都回主线程，
 * 与通知监听回调同线程。
 */
class ShadeVisibilityMonitor(
    private val shell: Shell,
    private val gate: ShadeVisibleNotificationGate,
    private val scope: CoroutineScope,
) {

    private var inFlight = false
    private var rerun = false
    private var sourceGeneration = 0

    /** 请求一次校准；[reason] 只进日志，不影响判定。 */
    fun request(reason: String) {
        scope.launch {
            if (inFlight) {
                rerun = true
                return@launch
            }
            inFlight = true
            try {
                delay(DEBOUNCE_MS)
                val generation = sourceGeneration
                val probedKeys = gate.rawKeys
                val visibleKeys = if (shell.available) {
                    val result = try {
                        withContext(Dispatchers.IO) { shell.runQuiet(COMMAND) }
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "shade-visible probe shell failed reason=$reason", e)
                        null
                    }
                    if (result?.ok == true) ShadeVisibilityDump.parse(result.output) else null
                } else {
                    null
                }
                if (generation != sourceGeneration) {
                    Log.i(LOG_TAG, "shade-visible probe discarded reason=$reason source-changed")
                    return@launch
                }
                val probe = ShadeVisibilityProbe(visibleKeys, probedKeys)
                gate.applyVisibility(probe)
                Log.i(
                    LOG_TAG,
                    "shade-visible probe reason=$reason systemUiVisible=${probe.visibleKeys?.size ?: "unknown"} raw=${probe.probedKeys.size} shown=${gate.visibleCount}",
                )
            } finally {
                inFlight = false
                if (rerun) {
                    rerun = false
                    request("queued")
                }
            }
        }
    }

    /** Shizuku 掉线：立即撤回精确可见性，退回全部在册可见。 */
    fun onSourceUnavailable(reason: String) {
        scope.launch {
            sourceGeneration++
            gate.applyVisibility(ShadeVisibilityProbe(null, gate.rawKeys))
            Log.i(LOG_TAG, "shade-visible probe fallback reason=$reason")
        }
    }

    companion object {
        /** 只读 SystemUI 当前通知集合；目标名与解析器是 HyperOS 适配边界。 */
        const val COMMAND =
            "dumpsys activity service com.android.systemui/.SystemUIService NotifCollection"

        /** 监听到达/移除常成批发生，200ms 内只发一次 shell。 */
        const val DEBOUNCE_MS = 200L
    }
}
