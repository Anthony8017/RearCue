package com.rearcue.poc.rear

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Wake Keep-alive（CONTEXT.md「唤醒保活」；取舍见 docs/adr/0003）：Dashboard 在屏期间经 Shizuku
 * 周期注入**定向背屏**的唤醒键（[RearProjectionCommands.wakeKeyCommand]），让背屏保持点亮——
 * 锁屏后 Dashboard 不再 1.3–1.4s 被收走（E12/E13 实测，docs/poc-findings.md）。
 *
 * 生命周期跟随投送（[HyperOsRearDisplayBackend] 的 project/update/exit 驱动）：投送即起、
 * 退出/空集/投送失败即停；进程重建后按当前 Icon Set 恢复（监听快照对账 → DashboardCore 重投 →
 * project() → 本循环自起）。**不残留**：循环只是守护线程上的自调度任务，没有长驻 shell 进程
 * （每次注入都是一条独立命令），stop() 或进程退出即彻底消失。
 *
 * 强度 = [intervalMs]（运行中可调）：E12 实测 500ms/5000ms 能在 60s 窗内守住背屏、30000ms
 * 守不住；POC 不定死 MRSS 的 100ms，代价（耗电/发热）实测数据见 findings 票 #21。
 *
 * Shizuku 缺失时安全降级：注入失败只记日志、不抛异常，循环保持尝试（恢复后自然继续）。
 *
 * 日志行是 tools/ex 的判活/排障锚（`wake-keep-alive start|ok|stop|fail|tick-exception`，
 * ASCII 前缀）：改词形会断掉 `ex.ps1 -Task lock-survive -AppKeepAlive` 的判定链。
 */
class WakeKeepAlive(
    private val shell: Shell,
    intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    /** 注入间隔（强度）；自调度循环每次按当前值排下一拍，运行中修改立刻生效。 */
    @Volatile
    var intervalMs: Long = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)

    private var scheduler: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null

    /** 注入的定向目标（背屏 displayId）。 */
    @Volatile
    private var displayId: Int = -1

    /** 只在循环线程上读写。 */
    private var ticks = 0L
    private var failures = 0L

    val isRunning: Boolean get() = task != null

    /** 起循环（幂等：已在跑只刷新目标屏）。[rearDisplayId] 是注入的定向目标（背屏）。 */
    @Synchronized
    fun start(rearDisplayId: Int) {
        displayId = rearDisplayId
        if (task != null) return
        Log.i(TAG, "wake-keep-alive start displayId=$rearDisplayId intervalMs=$intervalMs")
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "rearcue-wake-keepalive").apply { isDaemon = true }
        }
        scheduler = executor
        task = executor.schedule(::pump, intervalMs, TimeUnit.MILLISECONDS)
    }

    /**
     * 停循环（幂等）：撤销任务 + 关掉线程，注入即止。没有需要清理的 shell 进程（每次注入
     * 都是一条独立命令），所以「exit 后无残留循环/进程」就是本函数返回后的自然状态。
     */
    @Synchronized
    fun stop() {
        val current = task ?: return
        current.cancel(false)
        task = null
        scheduler?.shutdownNow()
        scheduler = null
        Log.i(TAG, "wake-keep-alive stop ticks=$ticks failures=$failures")
    }

    /** 自调度一拍：本次注入 + 按当前 [intervalMs] 排下一拍（运行中调强度立刻生效）。 */
    private fun pump() {
        try {
            tick()
        } catch (t: Throwable) {
            // 日志行是 tools/ex 的判活/排障锚（ASCII 前缀，见 KDoc）：别改词形。
            Log.w(TAG, "wake-keep-alive tick-exception (degrade, continue)", t)
        }
        synchronized(this) {
            val executor = scheduler ?: return
            if (task == null) return
            try {
                task = executor.schedule(::pump, intervalMs, TimeUnit.MILLISECONDS)
            } catch (ignored: RejectedExecutionException) {
                // stop() 正在收尾（任务已撤、线程已关）：正常竞态，静默退出。
            }
        }
    }

    private fun tick() {
        val target = displayId
        if (target < 0) return
        val result = shell.run(RearProjectionCommands.wakeKeyCommand(target))
        ticks++
        if (result.ok) {
            failures = 0
            // 心跳不是每拍都打：2 拍/秒的全量日志是洪水；心跳供 tools/ex 判活。
            if (ticks % HEARTBEAT_TICKS == 0L) {
                Log.i(TAG, "wake-keep-alive ok ticks=$ticks intervalMs=$intervalMs")
            }
        } else {
            failures++
            if (failures == 1L || failures % FAILURE_LOG_EVERY == 0L) {
                Log.w(TAG, "wake-keep-alive fail consecutive=$failures out=${result.output}")
            }
        }
    }

    init {
        current = this
    }

    companion object {
        private const val TAG = "RearCue"

        /** 注入间隔**暂定值**：E12 实测 500ms 守得住（60s 窗全程 ON）。ADR 0003 约定默认值以票 #21 的耗电/发热补测定案，定案后在此修订。 */
        const val DEFAULT_INTERVAL_MS = 500L

        /** 间隔下限：再密只是烧电，背屏熄屏节拍没那么快。 */
        const val MIN_INTERVAL_MS = 50L

        /** 每多少拍记一条心跳（供 tools/ex 判活，不刷屏）。 */
        const val HEARTBEAT_TICKS = 10L

        /** 失败日志节流：每 N 次记一条，Shizuku 掉线期间不刷屏。 */
        const val FAILURE_LOG_EVERY = 20L

        /**
         * 进程内当前循环（本类构造时登记）。
         *
         * 只给调试入口（`DebugCommandReceiver` 的 WAKE_INTERVAL）调强度用——
         * `RearDisplayBackend` 接口不必为调试加方法（票 #21 约束）；业务代码不读它。
         */
        @Volatile
        var current: WakeKeepAlive? = null
    }
}
