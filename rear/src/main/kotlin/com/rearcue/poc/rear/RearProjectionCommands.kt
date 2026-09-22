package com.rearcue.poc.rear

/**
 * 一次投送动作：在 shell（Shizuku）里按序执行的命令，外加一条可选的校验命令。
 *
 * 纯数据，不含 Android 引用——命令拼装是 JVM 可测的部分，执行由 [ShizukuShell] 负责。
 */
data class ShellPlan(
    val commands: List<String>,
    /** 本次投送的组件全名：回读任务栈校验上屏时按它匹配。 */
    val component: String,
    /** 校验命令：投送后确认任务是否真的落在背屏；null 表示不需要校验。 */
    val verify: String? = null,
) {
    /** 纯展示用：一次投送执行了哪些 shell 命令（调试页与 findings 都要记录真实命令）。 */
    val describe: String get() = commands.joinToString(" && ")
}

/**
 * 投送命令（Shizuku 兜底通道）。
 *
 * 命令形状与 `docs/poc-findings.md`「机制结论」一致：主路径是应用内 `setLaunchDisplayId`
 * （见 [HyperOsRearDisplayBackend.project]），本对象只负责 shell 兜底的上屏命令。
 *
 * 下屏**不在这里**：`am force-stop` 会连带杀掉同进程的通知监听（票 #5），
 * 所以退出背屏走 [RearDashboardHost] 的进程内结束界面。
 */
object RearProjectionCommands {

    /**
     * 背屏 Dashboard 的全限定类名。
     *
     * 与 [RearDashboardActivity] 的真实类名一致（由 `RearProjectionCommandsTest` 守着）：
     * 票 #4 曾手写成 `com.rearcue.poc.ui.RearDashboardActivity`，兜底通道必失败；
     * 这里保持纯字符串，不在 JVM seam 里引用 Android 类。
     */
    const val DASHBOARD_ACTIVITY_CLASS: String = "com.rearcue.poc.rear.RearDashboardActivity"

    /** Activity 全名：显式组件名，不依赖隐式 intent 解析。 */
    fun dashboardComponent(packageName: String): String = "$packageName/$DASHBOARD_ACTIVITY_CLASS"

    /** 投送（含首投与 Takeover 后重投，幂等：`--activity-reorder-to-front` 复用既有任务）。 */
    fun project(packageName: String, displayId: Int): ShellPlan {
        val component = dashboardComponent(packageName)
        return ShellPlan(
            commands = listOf(
                "am start --display $displayId --activity-reorder-to-front -n $component",
            ),
            component = component,
            verify = rearDisplayBlockCommand(displayId),
        )
    }

    /**
     * 只回读背屏那一段任务栈（校验「真的上屏了吗」，见 [RearProjectionVerifier]）。
     *
     * 窗口取 8 行不是随手写的：真机（票 #8 实测）背屏块先打 Task 头 2 行，Dashboard 的
     * topResumedActivity 落在第 3 行之后，-A2 只能捞到任务头，校验必然判「没上屏」。
     * 带上 grep 还顺带把整份 `dumpsys activity activities`（几百 KB）挡在日志之外——
     * 应用内投送确认会 250ms 轮询一次 [rearDisplayBlockCommand]，不裁剪就是日志洪水。
     *
     * 代价（已知有界）：窗口假设 Dashboard 是背屏最上层任务（投送命令带
     * `--activity-reorder-to-front`，实测成立）。真被别的任务压在下面时会误判「没上屏」，
     * 代价是多发一次幂等的 `am start`，不会留下错误状态。
     */
    fun rearDisplayBlockCommand(displayId: Int): String =
        "dumpsys activity activities | grep -A$VERIFY_WINDOW_LINES 'Display #$displayId'"

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
     *   只有「冻结」（进程还在）才继续注入；优雅退出走 [wakeLoopStopCommand] 的 stop 文件。
     * - **可调**：间隔文件每拍重读，[wakeLoopIntervalCommand] 运行中改写即生效。
     * - **判活锚不变**：循环用 `log -t RearCue` 打 `wake-keep-alive ok|fail|stop`（与应用日志同
     *   TAG、同词形契约），`tools/ex` 的 Get-ExAppKeepAliveFacts 解析链照旧工作。
     */
    fun wakeLoopStartCommand(displayId: Int, intervalMs: Long): String {
        val d = '$'
        val script = (
            "echo ${d}${d} > $WAKE_LOOP_PID_FILE; i=0; f=0; " +
                "while [ ! -f $WAKE_LOOP_STOP_FILE ] && pidof $PACKAGE_NAME >/dev/null; do " +
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
                "rm -f $WAKE_LOOP_STOP_FILE $WAKE_LOOP_PID_FILE; " +
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
     * 锁屏首投（票 #22）：**任务搬运事务**，把 root task 搬上指定屏。
     *
     * 事务号 **51** = `moveRootTaskToDisplay`（E14 在本机 Android 16 实测可用；MRSS 记录的 50
     * 在本构建是静默 no-op）。返回值只归档不判定（E14 口径）：上没上屏看回读任务栈，不看
     * `service call` 说啥。
     */
    fun moveRootTaskCommand(rootTaskId: Int, displayId: Int): String =
        "service call activity_task 51 i32 $rootTaskId i32 $displayId"

    /**
     * 缺任务时的建任务第一步：**默认屏** `am start -n`。刻意不带 `--display`：锁屏时带
     * `--display` 的路径被 ActivityStarter 的 `rearDisplay check locked -> deny` 硬拒（E3/E14），
     * 票 #22 明确不走。
     */
    fun startOnDefaultDisplayCommand(component: String): String = "am start -n $component"

    /** [RearTaskLocator] 的数据源。整份文本较大：只在兜底链里一次性取，不做周期轮询。 */
    fun activitiesDumpCommand(): String = "dumpsys activity activities"

    /** 校验命令向后多取的行数（见 [rearDisplayBlockCommand] 里的实测说明）。 */
    const val VERIFY_WINDOW_LINES: Int = 8
}

/**
 * 从 `dumpsys activity activities` 输出里判断 Dashboard 是否真的落在背屏。
 *
 * 存在意义：启动背屏 Activity 时系统**不会**抛异常，只在内部 aborted（E2 实测），
 * 所以「调用没报错」不等于「上屏了」——必须回读系统状态才算数。
 */
object RearProjectionVerifier {

    fun isOnDisplay(dumpsys: String, displayId: Int, component: String): Boolean {
        val lines = dumpsys.lines()
        val header = "Display #$displayId"
        val names = componentNames(component)
        var inSection = false
        for (line in lines) {
            if (line.contains(header)) {
                inSection = true
                continue
            }
            if (inSection && line.contains("Display #")) return false // 进入下一块，说明本块没有
            if (inSection && names.any(line::contains)) return true
        }
        return false
    }

    /**
     * 组件在 dumpsys 里的两种写法：全名 `pkg/pkg.rear.RearDashboardActivity`（调用方拼的那个）
     * 与短名 `pkg/.rear.RearDashboardActivity`。
     *
     * `ActivityRecord` 用 `ComponentName.flattenToShortString()` 打印，真机上**只出现短名**
     * （票 #8 实测）——只比全名的话这条校验永远是 false，而合成 fixture 写成全名正好掩盖它。
     */
    private fun componentNames(component: String): List<String> {
        val pkg = component.substringBefore('/')
        val cls = component.substringAfter('/', "")
        if (cls.isEmpty() || !cls.startsWith("$pkg.")) return listOf(component)
        return listOf(component, "$pkg/.${cls.removePrefix("$pkg.")}")
    }
}
