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
     */
    fun rearDisplayBlockCommand(displayId: Int): String =
        "dumpsys activity activities | grep -A$VERIFY_WINDOW_LINES 'Display #$displayId'"

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
