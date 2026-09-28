package com.rearcue.poc.notify

import android.util.Log
import com.rearcue.poc.LOG_TAG
import com.rearcue.poc.notification.ShadeVisibilityDump
import com.rearcue.poc.notification.ShadeVisibilityLog
import com.rearcue.poc.notification.ShadeVisibilityProbeScheduler
import com.rearcue.poc.notification.ShadeVisibleNotificationGate
import com.rearcue.poc.rear.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 「下拉栏可见性」的可选精确源：Shizuku 在线时读取 SystemUI 当前 NotifCollection，
 * 把结果交给 [ShadeVisibleNotificationGate]；不可用/解析失败/探测超时时交给 null（fail-open）。
 *
 * 防抖、在途合并、尾随补跑与超时编排在纯 Kotlin [ShadeVisibilityProbeScheduler]；本类只做
 * shell 调用、日志与 gate 接线。所有 gate 变更都回主线程，与通知监听回调同线程。
 */
class ShadeVisibilityMonitor(
    private val shell: Shell,
    private val gate: ShadeVisibleNotificationGate,
    private val scope: CoroutineScope,
    private val scheduler: ShadeVisibilityProbeScheduler = ShadeVisibilityProbeScheduler(),
) {

    /** 请求一次校准；[reason] 只进日志，不影响判定。 */
    fun request(reason: String) {
        scope.launch {
            val startReason = scheduler.request(reason) ?: return@launch
            scheduler.run(
                initialReason = startReason,
                snapshot = { gate.rawKeys },
                probe = { currentReason, _ -> probe(currentReason) },
                apply = { currentReason, probedKeys, visibleKeys ->
                    val effective = gate.applyVisibility(visibleKeys, probedKeys)
                    val shown = effective?.let { keys -> probedKeys.count { it in keys } } ?: probedKeys.size
                    Log.i(
                        LOG_TAG,
                        ShadeVisibilityLog.probe(currentReason, effective?.size, probedKeys.size, shown),
                    )
                },
            )
        }
    }

    /** Shizuku 掉线：立即撤回精确可见性，退回全部在册可见。 */
    fun onSourceUnavailable(reason: String) {
        scope.launch {
            gate.applyVisibility(null, gate.rawKeys)
            Log.i(LOG_TAG, ShadeVisibilityLog.fallback(reason))
        }
    }

    /**
     * shell 调用仍在 [Dispatchers.IO]，但以独立子任务启动后再 await；这样
     * [ShadeVisibilityProbeScheduler] 的 withTimeoutOrNull 只取消 await，不会被阻塞调用拖住，
     * 超时后尾随/后续请求仍可继续。
     */
    private suspend fun probe(reason: String): Set<String>? {
        if (!shell.available) return null
        val call = scope.async(Dispatchers.IO) {
            try {
                Result.success(shell.runQuiet(COMMAND))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val result = call.await()
        result.exceptionOrNull()?.let { failure ->
            Log.w(LOG_TAG, ShadeVisibilityLog.shellFailed(reason), failure)
            return null
        }
        val reply = result.getOrNull() ?: return null
        return if (reply.ok) ShadeVisibilityDump.parse(reply.output) else null
    }

    companion object {
        /** 只读 SystemUI 当前通知集合；目标名与解析器是 HyperOS 适配边界。 */
        const val COMMAND =
            "dumpsys activity service com.android.systemui/.SystemUIService NotifCollection"
    }
}
