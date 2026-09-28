package com.rearcue.poc.notification

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shade-visible 探测的纯 Kotlin 编排（ADR 0007，票 #130）：
 * 防抖 [debounceMs] → [snapshot] 取原始在册 key → [probe]（超时 [timeoutMs] 按未知 null）→ [apply]；
 * 在途期间到达的请求合并成一次尾随补跑，避免通知突发打爆 Shizuku；
 * 探测期间源掉线（[invalidateSource]）则本轮结果整次作废、只走 [discard]。
 *
 * 所有 lambda 只做机械搬运，不在这里决定可见性；[probe] 的阻塞实现由胶水隔离在 IO，
 * 使超时后本编排能继续处理尾随/后续请求。线程模型：所有方法必须在同一线程调用
 * （Android 侧为 AppContainer 主线程）。
 */
class ShadeVisibilityProbeScheduler(
    val debounceMs: Long = DEBOUNCE_MS,
    val timeoutMs: Long = TIMEOUT_MS,
) {

    private var inFlight = false
    private var rerun = false

    /**
     * 源代次：源掉线时递增，使跨代次的在途探测结果整次作废——
     * 掉线前发起的探测不得覆盖随后 fail-open 的全放行。
     */
    private var sourceGeneration = 0

    /**
     * 请求一次校准。返回非 null 表示调用方应立刻启动 [run]；返回 null 表示已有在途，
     * 本次请求已并入尾随补跑。
     */
    fun request(reason: String): String? {
        if (inFlight) {
            rerun = true
            return null
        }
        inFlight = true
        return reason
    }

    /** 源（Shizuku）掉线/切换：让在途探测结果整次作废；fail-open 与状态应用仍由调用方负责。 */
    fun invalidateSource() {
        sourceGeneration++
    }

    /**
     * 执行一轮或多轮尾随补跑。[snapshot] 返回探测发起时的原始在册 key；
     * [probe] 返回 SystemUI 可见 key（null=未知/失败）；[apply] 把结果交给路由层；
     * [discard] 是「本轮结果因源代次变更被作废」的观测钩子（判定不在这里）。
     */
    suspend fun run(
        initialReason: String,
        snapshot: () -> Set<String>,
        probe: suspend (reason: String, probedKeys: Set<String>) -> Set<String>?,
        apply: (reason: String, probedKeys: Set<String>, visibleKeys: Set<String>?) -> Unit,
        discard: (reason: String) -> Unit = {},
    ) {
        try {
            var reason = initialReason
            while (true) {
                delay(debounceMs)
                val generation = sourceGeneration
                val probedKeys = snapshot()
                val visibleKeys = withTimeoutOrNull(timeoutMs) { probe(reason, probedKeys) }
                if (generation == sourceGeneration) {
                    apply(reason, probedKeys, visibleKeys)
                } else {
                    discard(reason)
                }
                reason = finish() ?: return
            }
        } catch (e: Throwable) {
            // 异常退出也要放行后续请求；尾随合并随本轮丢弃。
            inFlight = false
            rerun = false
            throw e
        }
    }

    private fun finish(): String? {
        if (!rerun) {
            inFlight = false
            return null
        }
        rerun = false
        return QUEUED_REASON
    }

    companion object {
        /** 监听到达/移除常成批发生，200ms 内只发一次 shell。 */
        const val DEBOUNCE_MS = 200L

        /** shell 挂起时的看门狗：超时按未知 fail-open，不把后续请求一起卡死。 */
        const val TIMEOUT_MS = 4_000L

        /** 在途期间到达的请求合并后的尾随补跑 reason。 */
        const val QUEUED_REASON = "queued"
    }
}
