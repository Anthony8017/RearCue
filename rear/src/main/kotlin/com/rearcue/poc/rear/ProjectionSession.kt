package com.rearcue.poc.rear

/**
 * 保活守护的最小面：投送生命周期驱动它起停（CONTEXT.md「Wake Keep-alive」）。
 *
 * 实现是 [WakeKeepAlive]（设备侧自驱循环 + 双通道停令），测试用假实现记录起停——
 * 两个 adapter，所以这是一条真 seam。
 */
interface WakeGuard {

    /** 投送在屏期间让背屏保持点亮（幂等；目标屏变了整条重启）。 */
    fun start(rearDisplayId: Int)

    /** 停止守护（幂等；不残留契约 ≤2 个注入周期）。 */
    fun stop()
}

/**
 * 投送会话（CONTEXT.md「投送会话」）：一次「把 Dashboard 投送上背屏」的 shell 阶段全权收口——
 * 命令拼装（[RearProjectionCommands]）、任务栈回读（[RearTaskLocator] / [RearProjectionVerifier]）、
 * E14 词表判定、证据落盘（[ShellTranscript]）、保活起停（[WakeGuard]）、搬走任务的归还。
 *
 * **两段式**：应用内 launch + 异步确认是另一段（决策在 [ConfirmPolicy]，等待机制留在调用方）；
 * 本 module 只跑 shell 兜底链并**同步**给出 [CastResult]。锁屏与未锁屏走不同兜底：锁屏稳态下
 * `am start --display` 被 ActivityStarter 的 `rearDisplay check locked -> deny` 硬拒（E3/E14 每次
 * 如此），直达任务搬运事务；未锁屏保留 `am start --display`（票 #4 的既定兜底）。
 *
 * **安全降级**：每一步失败只记日志 + 判定，绝不抛异常。判定只认服务端回执文本与回读任务栈，
 * **不看进程退出码**（E14 口径）。
 *
 * 纯 Kotlin（JVM seam，同 [WakeKeepAlive]）：shell 执行走 [Shell]，日志经 [log]/[warn] 注入，
 * 不引 `android.util.Log`。日志词形是 tools/ex 判定链契约（`task-move word=…` 等，**byte 不可改**）。
 */
class ProjectionSession(
    private val packageName: String,
    private val shell: Shell,
    private val wake: WakeGuard,
    private val transcript: ShellTranscript,
    private val keyguardLocked: () -> Boolean,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit,
) {

    /** 任务搬运事务搬走的 root task（票 #22）：[end] 时搬回默认屏，把背屏交还原生界面。 */
    private var movedTaskId: Int? = null

    /** 投送开始：保活即起（幂等，票 #21——锁屏掉屏两大原因之一靠它消掉）。 */
    fun begin(displayId: Int) {
        wake.start(displayId)
    }

    /** 保活即停（背屏没了 = 没有守护对象）；不动任务搬运记账。 */
    fun stopGuard() {
        wake.stop()
    }

    /** 投送结束：保活即停 + 归还被搬走的 root task（票 #22 AC：背屏交还原生界面）。 */
    fun end() {
        wake.stop()
        handBackMovedTask()
    }

    /** 回读背屏任务栈：Dashboard 是否真的在屏（口径见 [RearProjectionVerifier]）。 */
    fun onDisplayNow(displayId: Int): Boolean {
        val component = RearProjectionCommands.dashboardComponent(packageName)
        val block = shell.run(RearProjectionCommands.rearDisplayBlockCommand(displayId)).output
        return RearProjectionVerifier.isOnDisplay(block, displayId, component)
    }

    /**
     * shell 兜底链（两段式的第一段）：按锁屏状态路由，同步跑完并给出 [CastResult]。
     *
     * 路由（票 #22）：锁屏稳态直达 [taskMove]（E14 验证过的任务搬运事务）；未锁屏走 [shellStart]
     * （`am start --display`，票 #4 的既定兜底），再不济也落到任务搬运由调用方决定是否重试。
     */
    fun cast(displayId: Int, iconSet: List<String>, reason: String): CastResult =
        if (keyguardLocked()) {
            log("project 兜底：锁屏稳态，跳过 am start --display（被 rearDisplay check locked 拒），走任务搬运")
            taskMove(displayId, iconSet, reason = "$reason（锁屏首投走任务搬运）")
        } else {
            shellStart(displayId, iconSet, reason)
        }

    /**
     * 兜底（票 #22）：任务搬运事务（`service call activity_task 51`）把 **Dashboard 所在的
     * root task** 搬上背屏。缺任务先用默认屏 `am start -n` 建（背屏门不参与）；建完仍没有 Dashboard
     * 的任务 = NO-TASK，**不搬**（把 MainActivity 的空任务搬上背屏不是本票要的东西）。
     */
    private fun taskMove(displayId: Int, iconSet: List<String>, reason: String): CastResult {
        if (!shell.available) return channelDown(reason)
        val component = RearProjectionCommands.dashboardComponent(packageName)
        var dump = shell.run(RearProjectionCommands.activitiesDumpCommand()).output
        var taskId = RearTaskLocator.findRootTaskId(dump, packageName)
        if (taskId == null || !RearTaskLocator.taskContainsDashboard(dump, taskId)) {
            // 建任务/补 Dashboard 实例：默认屏启动（`--display` 路径锁屏被拒，票 #22 不走）
            val created = shell.run(RearProjectionCommands.startOnDefaultDisplayCommand(component))
            log("task-move create-task exit=${created.exitCode} out=${created.output}")
            dump = shell.run(RearProjectionCommands.activitiesDumpCommand()).output
            taskId = RearTaskLocator.findRootTaskId(dump, packageName)
        }
        if (taskId == null || !RearTaskLocator.taskContainsDashboard(dump, taskId)) {
            return fail(
                CastVerdict.NO_TASK,
                "task-move word=NO-TASK taskId=none displayId=$displayId onDisplay=false reason=$reason",
                "任务事务未执行：没有带 Dashboard 的任务可搬（NO-TASK）",
            )
        }
        val txn = shell.run(RearProjectionCommands.moveRootTaskCommand(taskId, displayId))
        // 事务返回值只归档（E14 口径）：判断只认回读任务栈 + 服务端回执文本。
        log("task-move txn-raw exit=${txn.exitCode} out=${txn.output}")
        val onDisplay = onDisplayNow(displayId)
        // TXN-BROKEN 的依据是服务端回执文本（设备事实），不是 exitCode：
        // `service call` 逻辑被拒也回 exit=0，拿退出码判会把「没效果」误报成「事务坏了」。
        val txnBrokenReply = txn.output.isBlank() ||
            txn.output.contains("Unable to find service") ||
            txn.output.contains("Unknown transaction") ||
            txn.output.contains("SecurityException")
        val verdict = when {
            onDisplay -> CastVerdict.OK
            txnBrokenReply -> CastVerdict.TXN_BROKEN
            else -> CastVerdict.NO_EFFECT
        }
        val detail = when (verdict) {
            CastVerdict.OK -> "已投送 displayId=$displayId（任务搬运事务 taskId=$taskId）"
            CastVerdict.TXN_BROKEN -> "任务事务通道坏了（TXN-BROKEN）：${txn.output}"
            else -> "任务事务无效果（NO-EFFECT，taskId=$taskId 未上屏；被拒与否从 logcat 的系统拒绝行判读）"
        }
        val wordLine =
            "task-move word=${verdict.word} taskId=$taskId displayId=$displayId onDisplay=$onDisplay reason=$reason"
        if (onDisplay) {
            movedTaskId = taskId // end 时搬回默认屏，把背屏交还原生界面（票 #22 AC）
            log(wordLine)
            return CastResult(verdict, true, detail)
        }
        wake.stop() // 没上屏 = 没有守护对象（不残留契约）
        log(wordLine)
        return CastResult(verdict, false, detail)
    }

    /**
     * 兜底：Shizuku（shell uid）里执行投送命令；Shizuku 不可用时不抛异常，直接判失败。
     *
     * 结果**回读任务栈**才算数：`am start` 的退出码只说明命令执行了，背屏白名单/锁屏策略照样会
     * 静默 aborted（票 #4 的 E2 教训），只报退出码会把失败记成成功。这条路径没有 word 表锚，
     * 判定收敛为 OK / NO-EFFECT（词表见 [CastVerdict]）。
     */
    private fun shellStart(displayId: Int, iconSet: List<String>, reason: String): CastResult {
        if (!shell.available) return channelDown(reason)
        val plan = RearProjectionCommands.project(packageName, displayId)
        val result = run(plan)
        val onDisplay = result.ok && plan.verify?.let { verify ->
            RearProjectionVerifier.isOnDisplay(shell.run(verify).output, displayId, plan.component)
        } == true
        if (!onDisplay) {
            // 投送彻底失败：屏上没有 Dashboard，保活没有守护对象，停掉（下一次重投会再起）。
            wake.stop()
        }
        val detail = when {
            onDisplay -> "已投送 displayId=$displayId（Shizuku）"
            result.ok -> "未确认上屏：displayId=$displayId 任务栈里没有 Dashboard（Shizuku 兜底）"
            else -> "投送失败：${result.output}"
        }
        log("project iconSet=$iconSet -> onDisplay=$onDisplay（Shizuku 兜底，原因：$reason）")
        return CastResult(if (onDisplay) CastVerdict.OK else CastVerdict.NO_EFFECT, onDisplay, detail)
    }

    /** 通道没了（Shizuku 不可用）：安全降级，只记日志 + 判定，绝不抛异常。 */
    private fun channelDown(reason: String): CastResult {
        wake.stop() // 注入无从谈起：安全降级，恢复重投时再起
        val detail = "投送失败：$reason 且 Shizuku 不可用"
        warn("project 失败：$reason 且 Shizuku 不可用")
        return CastResult(CastVerdict.CHANNEL_DOWN, false, detail)
    }

    /** 统一失败出口（票 #22 AC）：停保活 + 记日志 + 给判定，绝不抛异常。 */
    private fun fail(verdict: CastVerdict, logLine: String, detail: String): CastResult {
        wake.stop() // 没上屏 = 没有守护对象
        warn(logLine)
        return CastResult(verdict, false, detail)
    }

    /** 按序执行投送动作，命令与输出全部落盘，返回最后一条命令的结果。 */
    private fun run(plan: ShellPlan): ShellResult {
        var last = ShellResult(exitCode = -1, output = "no command")
        for (command in plan.commands) {
            last = shell.run(command)
            transcript.record(command, last)
            if (!last.ok) break
        }
        // 校验命令只作证据：grep 无匹配返回 1，不算投送失败。
        plan.verify?.let { verify ->
            transcript.record(verify, shell.run(verify))
        }
        transcript.flush("rear-shell-transcript.txt")
        return last
    }

    /** 任务搬运事务搬走的 root task 搬回默认屏（票 #22 AC）；Shizuku 不可用时保留记账，等恢复再还。 */
    private fun handBackMovedTask() {
        val moved = movedTaskId
        if (moved != null && shell.available) {
            movedTaskId = null
            val back = shell.run(RearProjectionCommands.moveRootTaskCommand(moved, MAIN_DISPLAY_ID))
            log("task-move hand-back taskId=$moved exit=${back.exitCode} out=${back.output}")
        }
    }

    companion object {
        /** 主屏 display id：任务搬运的「来处」，[end] 时把搬走的 root task 归还回它。 */
        private const val MAIN_DISPLAY_ID = 0
    }
}

/**
 * 投送判定（CONTEXT.md「Cast Verdict」）：一次投送的终态结论。
 *
 * [word] 是 E14 词表词形（`task-move word=` 判定锚），tools/ex 的判定链按它读——**byte 不可改**；
 * [CHANNEL_DOWN] 不产生 word 锚（通道都没了，只记失败行）。任务搬运链给出完整词表；
 * `am start --display` 链只收敛到 OK / NO-EFFECT（它没有 word 锚，判定锚是
 * `project iconSet=… -> onDisplay=…（Shizuku 兜底…`）。
 */
enum class CastVerdict(val word: String?) {
    OK("OK"),
    NO_TASK("NO-TASK"),
    TXN_BROKEN("TXN-BROKEN"),
    NO_EFFECT("NO-EFFECT"),
    CHANNEL_DOWN(null),
}

/** 一次 shell 兜底投送的结果：判定 + 上屏与否 + 写进 [RearBackendState.lastDetail] 的原文。 */
data class CastResult(
    val verdict: CastVerdict,
    val onDisplay: Boolean,
    val detail: String,
)
