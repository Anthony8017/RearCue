package com.rearcue.poc.rear

/**
 * 从 `dumpsys activity activities` 文本里找出本应用的 root task id（票 #22 锁屏首投的任务搬运前置）。
 *
 * 事务通道（`service call activity_task 51`，E14 验证）搬的是 **root task**，所以第一步永远是
 * 「Dashboard 在哪个任务里」。语义（与 tools/ex 的 `Get-ExTaskPlacement` 同口径）：
 * - 任务块起点 = `* Task{<hash> #<id> type=standard A=<uid>:<package>` 任意缩进（顶层与嵌套重复行
 *   都算，重复出现同一个 id 是 dumpsys 的正常冗余）；
 * - 块内出现 `RearDashboardActivity t<id>` 即认定为「Dashboard 任务」，优先返回它；
 * - 没有 Dashboard 任务时退而返回本应用的**第一个**任务（任务仍在、界面被回收的场景）；
 * - 文本里一个本应用任务都没有时返回 null（调用方报 NO-TASK，绝不猜 id）。
 *
 * 纯解析（文本进、事实出）：JVM 测试用真实设备 dumpsys 逐字行做 fixture。
 */
object RearTaskLocator {

    /** 匹配任务块起点行，捕获 task id。 */
    private fun taskStartRegex(packageName: String): Regex =
        Regex("""\*\s+Task\{[0-9a-f]+ #(\d+) type=\w+ A=\d+:${Regex.escape(packageName)}\b""")

    /** 匹配「Dashboard 在任务 t<id> 里」的 ActivityRecord 行，捕获 task id。 */
    private val dashboardInTask: Regex = Regex("""RearDashboardActivity t(\d+)""")

    fun findRootTaskId(dumpsysText: String, packageName: String): Int? {
        val start = taskStartRegex(packageName)
        var firstCandidate: Int? = null
        var currentId: Int? = null
        var dashboardId: Int? = null
        for (line in dumpsysText.lineSequence()) {
            val startMatch = start.find(line)
            if (startMatch != null) {
                currentId = startMatch.groupValues[1].toInt()
                if (firstCandidate == null) firstCandidate = currentId
                continue
            }
            val dashMatch = dashboardInTask.find(line)
            if (dashMatch != null && dashboardId == null) {
                val inTask = dashMatch.groupValues[1].toInt()
                // 只认「落在本应用任务块里」的 Dashboard 行：块外/别的包的历史行不作数
                if (currentId != null && inTask == currentId) dashboardId = inTask
            }
        }
        return dashboardId ?: firstCandidate
    }

    /** Dashboard 是否在 [taskId] 这个任务里（搬任务后的自检口径之一）。 */
    fun taskContainsDashboard(dumpsysText: String, taskId: Int): Boolean =
        dashboardInTask.findAll(dumpsysText).any { it.groupValues[1].toInt() == taskId }
}
