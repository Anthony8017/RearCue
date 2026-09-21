package com.rearcue.poc.rear

import com.rearcue.poc.core.DashboardEffect

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
 * 效果 → 投送动作的映射（投送策略的唯一出口）。
 *
 * 命令形状与 `docs/poc-findings.md`「机制结论」一致：
 * 主路径 `am start --display <id>`；兜底把既有 root task 迁到背屏（不硬编码 transaction 编号，
 * 优先 `am` 的公开参数，只有确实需要 binder 直调时才用 `service call`）。
 */
object RearProjectionCommands {

    /** Activity 全名：显式组件名，不依赖隐式 intent 解析。 */
    fun dashboardComponent(packageName: String): String =
        "$packageName/$packageName.ui.RearDashboardActivity"

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

    /** 退出 Dashboard：结束本应用在背屏的任务，背屏交还 Native Rear Screen。 */
    fun exit(packageName: String): ShellPlan =
        ShellPlan(commands = listOf("am force-stop $packageName"))

    /**
     * DashboardEffect → 投送动作；不认识的（Degrade 等）返回 null，由调用方决定降级行为。
     */
    fun forEffect(effect: DashboardEffect, packageName: String, displayId: Int): ShellPlan? =
        when (effect) {
            is DashboardEffect.LaunchDashboard -> project(packageName, displayId)
            is DashboardEffect.UpdateIconSet -> null // 背屏界面自己刷新，不需要 shell
            DashboardEffect.ExitDashboard -> exit(packageName)
            DashboardEffect.Degrade -> null
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
