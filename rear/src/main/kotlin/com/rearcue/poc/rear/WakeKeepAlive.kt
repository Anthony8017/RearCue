package com.rearcue.poc.rear

import java.io.File

/**
 * Wake Keep-alive（CONTEXT.md「唤醒保活」；取舍见 docs/adr/0003）：Dashboard 在屏期间周期注入
 * **定向背屏**的唤醒键（[WakeKeepAliveScript.wakeKeyCommand]），让背屏保持点亮——锁屏后
 * Dashboard 不再 1.3–1.4s 被收走（E12/E13 实测，docs/poc-findings.md）。
 *
 * **循环在哪跑（票 #24 的关键决策）**：定时器不在应用里，在 **shell uid 的设备侧自驱循环**
 * （[WakeKeepAliveScript.wakeLoopStartCommand]）——GreezeManager 在锁屏后 ~1.5–4s 冻结应用
 * 进程（票 #24 实测 `FZ uid`，≥5000ms 节奏的应用侧泵被冻死），shell uid 2000 不吃这道冻结
 * （E12 探针循环实测跨锁存活）。本类只做「起/停/调强度」的决策与日志锚，注入本身每次都是
 * 同一条 [WakeKeepAliveScript.wakeKeyCommand]。
 *
 * 生命周期跟随投送（[HyperOsRearDisplayBackend] 的 project/update/exit 驱动）：投送即起、
 * 退出/空集/投送失败即停；进程重建后按当前 Icon Set 恢复。**不残留契约**：退出/清空/投送失败/
 * 应用被杀/Shizuku 掉线（停令送达）任何一种之后 ≤2 个注入周期内停止注入。三条腿：
 * ① 停令双通道：stop() **直写**应用侧 stop 标记（[appStopFile]，纯文件 IO、不经 shell——
 *   Shizuku 掉线时 shell 通道写不进 stop 文件，这条腿保证停令照样送达，写失败限时重试）+
 *   shell `touch` stop 文件（[WakeKeepAliveScript.wakeLoopStopCommand]）；
 * ② 循环每拍判定两个 stop 文件任一存在即退出（watchdog 的 stop 文件判定，最迟一个间隔）；
 * ③ 进程消失走循环里的 `pidof` 看门狗——只有「进程还活着但被冻结」时循环继续值守
 *   （冻结 ≠ 停令：锁屏后应用被 GreezeManager 冻结是常态，断租即自停的租约方案会把
 *   票 #24 的锁屏存活一起停掉，故不采用）。
 * Shizuku 掉线本身不是停令（守护对象还在屏，停了背屏就熄）；掉线期间到来的停令仍 ≤1 拍生效。
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
 *
 * 纯 Kotlin（JVM 单测 seam，同 [RearProjectionCommands] 风格）：日志经 [log] 注入，
 * Android 侧的 logcat 锚实现由 [HyperOsRearDisplayBackend] 提供，本文件不引 `android.util.Log`。
 */
class WakeKeepAlive(
    private val shell: Shell,
    private val appStopFile: File,
    private val log: (String) -> Unit,
    intervalMs: Long = DEFAULT_INTERVAL_MS,
) : WakeGuard {

    /**
     * 注入间隔（强度）；运行中修改立刻生效（改写循环间隔文件）。设备侧循环每拍重读该文件，
     * 所以「自调度按当前值排下一拍」的旧语义原样保留，只是调度器搬到了不被冻结的一侧。
     */
    @Volatile
    var intervalMs: Long = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        set(value) {
            field = value.coerceAtLeast(MIN_INTERVAL_MS)
            if (running) {
                val result = runShell(WakeKeepAliveScript.wakeLoopIntervalCommand(field))
                if (!result.ok) {
                    log("wake-keep-alive fail consecutive=1 out=${result.output}")
                }
            }
        }

    /** 注入的定向目标（背屏 displayId）。 */
    @Volatile
    private var displayId: Int = -1

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** shell 命令统一执行口：失败不抛（Shizuku 缺失安全降级），转成失败 [ShellResult] 交调用方记日志。 */
    private fun runShell(command: String): ShellResult =
        runCatching { shell.run(command) }.getOrElse { ShellResult(exitCode = -1, output = it.toString()) }

    /**
     * 起循环（幂等：已在跑且目标屏没变就什么都不做）。[rearDisplayId] 是注入的定向目标（背屏）。
     * 目标屏变了就整条重启（启动命令自带按 pid 文件清遗留循环，不会出双循环）。
     * 先删上一代的应用侧 stop 标记（直删不经 shell，起循环前必生效），启动命令再清 shell 侧残留。
     */
    @Synchronized
    override fun start(rearDisplayId: Int) {
        if (running && displayId == rearDisplayId) return
        displayId = rearDisplayId
        runCatching { appStopFile.delete() }
        val result = runShell(
            WakeKeepAliveScript.wakeLoopStartCommand(rearDisplayId, intervalMs, appStopFile.absolutePath),
        )
        if (result.ok) {
            running = true
            log("wake-keep-alive start displayId=$rearDisplayId intervalMs=$intervalMs")
        } else {
            // 日志行是 tools/ex 的判活/排障锚（ASCII 前缀，见 KDoc）：别改词形。
            log("wake-keep-alive fail consecutive=1 out=${result.output}")
        }
    }

    /**
     * 停循环（幂等）：写 stop 标记 + shell `touch` stop 文件，循环最迟一个间隔后退出并自己打
     * `wake-keep-alive stop ticks=`。命令返回即视为停（进程消失的兜底是循环里的 `pidof` 看门狗）。
     *
     * 应用侧 stop 标记（[writeAppStopMarker]）**无条件写**：即使本代没起过循环，也能把上代残留
     * 的循环停掉；shell 侧 `touch` 保持只在在跑时发（未 start 的 stop 仍是零命令空操作）。
     */
    @Synchronized
    override fun stop() {
        writeAppStopMarker()
        if (!running) return
        running = false
        val result = runShell(WakeKeepAliveScript.wakeLoopStopCommand())
        if (!result.ok) {
            log("wake-keep-alive fail consecutive=1 out=${result.output}")
        }
    }

    /**
     * 直写应用侧 stop 标记（纯文件 IO，**不经 shell**）：Shizuku 掉线时 shell 通道写不进
     * stop 文件，残留循环会每 5s 注入、背屏长亮——这条腿保证停令在任何 shell 状态下都送达。
     * 写失败限时重试（[STOP_WRITE_ATTEMPTS]），仍失败只记日志（`pidof` 看门狗仍在）。
     */
    private fun writeAppStopMarker() {
        repeat(STOP_WRITE_ATTEMPTS) { attempt ->
            if (runCatching { appStopFile.writeText("stop"); true }.getOrDefault(false)) return
            log("wake-keep-alive fail consecutive=${attempt + 1} out=app-stop-marker")
            runCatching { Thread.sleep(STOP_WRITE_RETRY_DELAY_MS) }
        }
    }

    init {
        current = this
    }

    companion object {
        /**
         * 默认注入间隔（**定档值**，spec 0004 / 票 #24）：5000ms——不改任何设置即锁屏守住 Dashboard。
         * 依据 E12 实测边界（CONTEXT.md）：500ms/5000ms 在 60s 窗内全程 ON、30000ms 守不住；
         * 代价定档（5000ms 干净代价轮）见 findings 票 #24。仍可调（[intervalMs]，语义不变）。
         */
        const val DEFAULT_INTERVAL_MS = 5000L

        /** 间隔下限：再密只是烧电，背屏熄屏节拍没那么快。 */
        const val MIN_INTERVAL_MS = 50L

        /** stop 标记写入重试次数（stop 失败重试的那条腿；总耗时 ≤ 一个注入周期的零头）。 */
        const val STOP_WRITE_ATTEMPTS = 3

        /** stop 标记写入重试间隔。 */
        const val STOP_WRITE_RETRY_DELAY_MS = 50L

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
