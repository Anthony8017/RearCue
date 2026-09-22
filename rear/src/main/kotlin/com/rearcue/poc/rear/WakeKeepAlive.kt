package com.rearcue.poc.rear

import android.util.Log

/**
 * Wake Keep-alive（CONTEXT.md「唤醒保活」；取舍见 docs/adr/0003）：Dashboard 在屏期间周期注入
 * **定向背屏**的唤醒键（[RearProjectionCommands.wakeKeyCommand]），让背屏保持点亮——锁屏后
 * Dashboard 不再 1.3–1.4s 被收走（E12/E13 实测，docs/poc-findings.md）。
 *
 * **循环在哪跑（票 #24 的关键决策）**：定时器不在应用里，在 **shell uid 的设备侧自驱循环**
 * （[RearProjectionCommands.wakeLoopStartCommand]）——GreezeManager 在锁屏后 ~1.5–4s 冻结应用
 * 进程（票 #24 实测 `FZ uid`，≥5000ms 节奏的应用侧泵被冻死），shell uid 2000 不吃这道冻结
 * （E12 探针循环实测跨锁存活）。本类只做「起/停/调强度」的决策与日志锚，注入本身每次都是
 * 同一条 [RearProjectionCommands.wakeKeyCommand]。
 *
 * 生命周期跟随投送（[HyperOsRearDisplayBackend] 的 project/update/exit 驱动）：投送即起、
 * 退出/空集/投送失败即停；进程重建后按当前 Icon Set 恢复。**不残留**：优雅路径 stop() 写 stop
 * 文件、循环最迟一个间隔后自杀并打 `wake-keep-alive stop ticks=`；进程被杀/崩溃走循环里的
 * `pidof` 看门狗（应用进程消失即退出）——只有「进程还活着但被冻结」时循环继续值守。
 *
 * 强度 = [intervalMs]（运行中可调，语义不变）：E12 实测 500ms/5000ms 能在 60s 窗内守住背屏、
 * 30000ms 守不住；默认间隔定档 5000ms（[DEFAULT_INTERVAL_MS]，spec 0004 / 票 #24），代价
 * （耗电/发热）实测数据见 findings 票 #21/#24。运行中调强度改写循环每拍重读的间隔文件，立刻生效。
 *
 * Shizuku 缺失时安全降级：起/停命令失败只记日志、不抛异常（恢复后下一次投送自然再起）。
 *
 * 日志行是 tools/ex 的判活/排障锚（`wake-keep-alive start|ok|stop|fail|tick-exception`，
 * ASCII 前缀；start 由本类打，ok/stop/fail 由设备侧循环以 `log -t RearCue` 打同词形）：改词形
 * 会断掉 `ex.ps1 -Task lock-survive -AppKeepAlive` 的判定链（Get-ExAppKeepAliveFacts）。
 */
class WakeKeepAlive(
    private val shell: Shell,
    intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    /**
     * 注入间隔（强度）；运行中修改立刻生效（改写循环间隔文件）。设备侧循环每拍重读该文件，
     * 所以「自调度按当前值排下一拍」的旧语义原样保留，只是调度器搬到了不被冻结的一侧。
     */
    @Volatile
    var intervalMs: Long = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        set(value) {
            field = value.coerceAtLeast(MIN_INTERVAL_MS)
            if (running) {
                val result = runCatching { shell.run(RearProjectionCommands.wakeLoopIntervalCommand(field)) }
                    .getOrElse { ShellResult(exitCode = -1, output = it.toString()) }
                if (!result.ok) {
                    Log.w(TAG, "wake-keep-alive fail consecutive=1 out=${result.output}")
                }
            }
        }

    /** 注入的定向目标（背屏 displayId）。 */
    @Volatile
    private var displayId: Int = -1

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /**
     * 起循环（幂等：已在跑且目标屏没变就什么都不做）。[rearDisplayId] 是注入的定向目标（背屏）。
     * 目标屏变了就整条重启（启动命令自带按 pid 文件清遗留循环，不会出双循环）。
     */
    @Synchronized
    fun start(rearDisplayId: Int) {
        if (running && displayId == rearDisplayId) return
        displayId = rearDisplayId
        val result = runCatching { shell.run(RearProjectionCommands.wakeLoopStartCommand(rearDisplayId, intervalMs)) }
            .getOrElse { ShellResult(exitCode = -1, output = it.toString()) }
        if (result.ok) {
            running = true
            Log.i(TAG, "wake-keep-alive start displayId=$rearDisplayId intervalMs=$intervalMs")
        } else {
            // 日志行是 tools/ex 的判活/排障锚（ASCII 前缀，见 KDoc）：别改词形。
            Log.w(TAG, "wake-keep-alive fail consecutive=1 out=${result.output}")
        }
    }

    /**
     * 停循环（幂等）：写 stop 文件，循环最迟一个间隔后退出并自己打 `wake-keep-alive stop ticks=`。
     * 命令返回即视为停（进程消失的兜底是循环里的 `pidof` 看门狗，不是这里）。
     */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        val result = runCatching { shell.run(RearProjectionCommands.wakeLoopStopCommand()) }
            .getOrElse { ShellResult(exitCode = -1, output = it.toString()) }
        if (!result.ok) {
            Log.w(TAG, "wake-keep-alive fail consecutive=1 out=${result.output}")
        }
    }

    init {
        current = this
    }

    companion object {
        private const val TAG = "RearCue"

        /**
         * 默认注入间隔（**定档值**，spec 0004 / 票 #24）：5000ms——不改任何设置即锁屏守住 Dashboard。
         * 依据 E12 实测边界（CONTEXT.md）：500ms/5000ms 在 60s 窗内全程 ON、30000ms 守不住；
         * 代价定档（5000ms 干净代价轮）见 findings 票 #24。仍可调（[intervalMs]，语义不变）。
         */
        const val DEFAULT_INTERVAL_MS = 5000L

        /** 间隔下限：再密只是烧电，背屏熄屏节拍没那么快。 */
        const val MIN_INTERVAL_MS = 50L

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
