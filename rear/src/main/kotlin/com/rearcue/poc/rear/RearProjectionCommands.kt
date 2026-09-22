package com.rearcue.poc.rear

/**
 * 一次投送动作：在 shell（Shizuku）里按序执行的命令，外加一条可选的校验命令。
 *
 * 纯数据，不含 Android 引用——命令拼装是 JVM 可测的部分，执行由 [ShizukuShell] 负责。
 */
data class ShellPlan(
    val commands: List<String>,
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
            verify = "dumpsys activity activities | grep -A2 'Display #$displayId'",
        )
    }
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
        var inSection = false
        for (line in lines) {
            if (line.contains(header)) {
                inSection = true
                continue
            }
            if (inSection && line.contains("Display #")) return false // 进入下一块，说明本块没有
            if (inSection && line.contains(component)) return true
        }
        return false
    }
}
