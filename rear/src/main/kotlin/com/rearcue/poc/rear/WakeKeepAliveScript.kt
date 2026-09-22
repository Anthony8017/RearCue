package com.rearcue.poc.rear

/**
 * Wake Keep-alive 的**设备侧自驱循环**脚本生成（票 #24）：与投送命令分开的独立单元
 * （#30 review JUDGE#7：保活脚本生成与投送命令各自收敛，投送命令语义不变，见
 * [RearProjectionCommands]）。纯数据、不含 Android 引用——命令拼装是 JVM 可测的部分，
 * 执行由 [Shell] 负责，启停调度在 [WakeKeepAlive]。
 */
object WakeKeepAliveScript {

    /**
     * Wake Keep-alive 的单次注入（票 #21）：**定向背屏**的唤醒键。
     *
     * `-d <displayId>` 是关键：不带定向的 KEYCODE_WAKEUP 会翻主屏电源键模式（E13 实测：
     * 定向唤醒后 3ms 内 PowerGroup 记一次 power_button 断主屏 group），定向注入只动背屏 group，
     * 主屏全程不亮（E12 三轮干净轮实测）。
     */
    fun wakeKeyCommand(displayId: Int): String = "input -d $displayId keyevent KEYCODE_WAKEUP"

    /**
     * Wake Keep-alive 的**设备侧自驱循环**启动命令（票 #24）。
     *
     * 为什么不把定时器留在应用里：GreezeManager 在锁屏后 ~1.5–4s 冻结应用进程（票 #24 实测
     * `FZ uid = 10336`，poc-logs/20260923-032337、035612），≥5000ms 节奏下应用侧泵必被冻死；
     * 而 shell uid 2000 的进程不受这道冻结（E12 探针循环实测跨锁存活）。所以一条命令把循环
     * 下放给 shell uid 的后台 sh（经 [RearShellService] 起，注入物同 [wakeKeyCommand]）：
     *
     * - **不残留**：`pidof [PACKAGE_NAME]` 看门狗——应用进程消失（force-stop/崩溃）循环自杀，
     *   只有「冻结」（进程还在）才继续注入；优雅退出走 [wakeLoopStopCommand] 的 stop 文件，
     *   外加**应用侧 stop 标记**（[WAKE_LOOP_APP_STOP_FILE]，应用直写、不经 shell——Shizuku
     *   掉线时 shell 通道写不进 stop 文件，这条腿保证停令照样送达），循环每拍判定两个 stop
     *   文件任一存在即退出。
     * - **可调**：间隔文件每拍重读，[wakeLoopIntervalCommand] 运行中改写即生效。
     * - **判活锚不变**：循环用 `log -t RearCue` 打 `wake-keep-alive ok|fail|stop`（与应用日志同
     *   TAG、同词形契约），`tools/ex` 的 Get-ExAppKeepAliveFacts 解析链照旧工作。
     */
    fun wakeLoopStartCommand(
        displayId: Int,
        intervalMs: Long,
        appStopFile: String = WAKE_LOOP_APP_STOP_FILE,
    ): String {
        val d = '$'
        val script = (
            "echo ${d}${d} > $WAKE_LOOP_PID_FILE; i=0; f=0; " +
                "while [ ! -f $WAKE_LOOP_STOP_FILE ] && [ ! -f $appStopFile ] && pidof $PACKAGE_NAME >/dev/null; do " +
                "set -- ${d}(cat $WAKE_LOOP_INTERVAL_FILE); " +
                "if ${wakeKeyCommand(displayId)}; then " +
                "i=${d}((i+1)); f=0; " +
                "[ ${d}((i % 10)) -eq 0 ] && log -t RearCue \"wake-keep-alive ok ticks=${d}i intervalMs=${d}1\"; " +
                "else f=${d}((f+1)); " +
                "if [ ${d}f -eq 1 ] || [ ${d}((f % 20)) -eq 0 ]; then " +
                "log -t RearCue \"wake-keep-alive fail consecutive=${d}f out=loop\"; fi; fi; " +
                "sleep ${d}2; done; " +
                "log -t RearCue \"wake-keep-alive stop ticks=${d}i failures=${d}f\"; " +
                "rm -f $WAKE_LOOP_PID_FILE"
            )
        // 前缀按 pid 文件清掉遗留循环再起新的；nohup + & 让 sh -c 立即返回、后台 sh 不占
        // ShizukuShell 的输出管道（重定向 /dev/null 后 10s 执行超时不会误伤循环）。
        return (
            "[ -f $WAKE_LOOP_PID_FILE ] && kill ${d}(cat $WAKE_LOOP_PID_FILE) >/dev/null 2>&1; " +
                "rm -f $WAKE_LOOP_STOP_FILE $appStopFile $WAKE_LOOP_PID_FILE; " +
                "${wakeLoopIntervalCommand(intervalMs)}; " +
                "nohup sh -c '$script' >/dev/null 2>&1 &"
            )
    }

    /** 优雅停循环（票 #24）：写 stop 文件，循环最迟下一个间隔自删。 */
    fun wakeLoopStopCommand(): String = "touch $WAKE_LOOP_STOP_FILE"

    /** 运行中调强度（票 #24）：改写循环每拍重读的间隔文件（`<毫秒> <秒>` 两个字段）。 */
    fun wakeLoopIntervalCommand(intervalMs: Long): String =
        "echo '${intervalMs} ${intervalSeconds(intervalMs)}' > $WAKE_LOOP_INTERVAL_FILE"

    /** 间隔的 sh `sleep` 实参（秒，定长小数）：toybox sleep 收小数，500ms → `0.500`。 */
    internal fun intervalSeconds(intervalMs: Long): String =
        "%d.%03d".format(intervalMs / 1000, intervalMs % 1000)

    /** 应用包名（pidof 看门狗按它判「进程还在不在」；纯字符串，不引 Android 种，同上风格）。 */
    const val PACKAGE_NAME: String = "com.rearcue.poc"

    /** 循环的间隔文件（每拍重读，运行中可调）。 */
    const val WAKE_LOOP_INTERVAL_FILE: String = "/data/local/tmp/rearcue-wake-loop.interval"

    /** 循环的停止标记（[wakeLoopStopCommand] 写它 = 优雅退出）。 */
    const val WAKE_LOOP_STOP_FILE: String = "/data/local/tmp/rearcue-wake-loop.stop"

    /** 循环的 pid 文件（启动命令按它清遗留循环；循环退出时自删）。 */
    const val WAKE_LOOP_PID_FILE: String = "/data/local/tmp/rearcue-wake-loop.pid"

    /**
     * 应用侧 stop 标记文件名（不残留契约的第二条停令腿）：落在**应用外部私有目录**
     * （`getExternalFilesDir(null)`，同 [ShellTranscript] 的证据目录——应用可直写、shell uid 可读）。
     * 完整路径运行时解析后经 [wakeLoopStartCommand] 的 `appStopFile` 参数带进循环脚本。
     */
    const val WAKE_LOOP_APP_STOP_FILE_NAME: String = "rearcue-wake-loop.stop"

    /**
     * 应用侧 stop 标记的默认路径（纯字符串 seam 与单测用；运行时应传调用方解析出的
     * `getExternalFilesDir` 路径——双存储机型上外置路径可能不是 `/sdcard`）。
     */
    val WAKE_LOOP_APP_STOP_FILE: String =
        "/sdcard/Android/data/$PACKAGE_NAME/files/$WAKE_LOOP_APP_STOP_FILE_NAME"
}
