package com.rearcue.poc.rear

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 背屏投送状态，供调试页与 logcat 展示。 */
data class RearBackendState(
    val available: Boolean = false,
    val rearDisplayId: Int? = null,
    val projected: Boolean = false,
    val lastDetail: String = "-",
    /** 最近一次投送给背屏 Dashboard 的 Icon Set。 */
    val iconSet: List<String> = emptyList(),
)

/**
 * 背屏后端：把「投送 / 退出 / 探测」收口成一个接口，HyperOS 专有操作全部藏在实现里。
 *
 * 目的见 spec 0001：HyperOS 更新破坏白名单或 service call 行为时只改这一处。
 */
interface RearDisplayBackend {
    val stateFlow: StateFlow<RearBackendState>

    val state: RearBackendState get() = stateFlow.value

    /** 重新探测 Shizuku 与背屏，更新 state 并返回它。 */
    fun refresh(): RearBackendState

    /** 投送 Dashboard 到背屏（幂等：重复调用不产生第二个任务）；[iconSet] 会推到背屏界面。 */
    fun project(iconSet: List<String>): Boolean

    /**
     * Dashboard 已在屏时更新 Icon Set（背屏界面自己重组，不重新投送）。
     *
     * 返回「内容是否已交给背屏」：界面还在时恒为 true；界面不在了（被系统结束）时自愈重投，
     * 返回重投是否已发出（**不代表已确认上屏**，确认是异步的，见 [project]）。
     */
    fun update(iconSet: List<String>): Boolean

    /**
     * 交还背屏：结束在屏 Dashboard 界面，恢复 Native Rear Screen。
     *
     * 只结束界面、不杀进程——通知监听还要继续（见 [RearDashboardHost]）。
     */
    fun exit()

    /** 是否还需要向用户申请 Shizuku 权限。 */
    fun permissionRequired(): Boolean

    /** 申请 Shizuku 权限（弹 Shizuku 的授权对话框）。 */
    fun requestPermission()

    /** 注册「Shizuku 权限已授予」回调；授权后自动重投当前 Icon Set（票 #6 的恢复路径也用它）。 */
    fun onPermissionGranted(action: () -> Unit)
}

/**
 * HyperOS 实现：经 Shizuku（shell uid）在运行时识别到的背屏上 `am start` 本应用的
 * RearDashboardActivity。
 *
 * 前提（票 #4 的 E1/E2 实测，见 docs/poc-findings.md）：
 *  - manifest 声明 `miui.rear.policy=1`，否则系统 `ActivityStarterImpl` 直接 aborted；
 *  - Shizuku server 在跑且本应用已获授权，否则 [project] 返回 false（调用方进入 Degrade）。
 */
class HyperOsRearDisplayBackend(
    private val context: Context,
    private val shell: ShizukuShell = ShizukuShell(context),
    private val displays: DisplaySource = DisplaySource(context),
    private val transcript: ShellTranscript = ShellTranscript(transcriptDir(context)),
) : RearDisplayBackend {

    private val _stateFlow = MutableStateFlow(RearBackendState())
    override val stateFlow: StateFlow<RearBackendState> = _stateFlow.asStateFlow()

    /** 投送结果确认用的单线程执行器（守护线程，不拖住进程退出）。 */
    private val confirmer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rearcue-rear-confirm").apply { isDaemon = true }
    }

    /**
     * 上屏正在途中（已 startActivity，界面还没 onStart）。
     *
     * E7 实测：投送发出到界面创建有 ~40ms 空档，期间任何一枚通知都会让 [update] 误判「背屏无界面」
     * 而重复投送，所以这个窗口内不做自愈重投。
     */
    @Volatile
    private var launchPending = false

    override fun refresh(): RearBackendState {
        shell.bind() // 已授权时把 UserService 绑上；未授权是空操作
        val rear = displays.rearDisplay()
        Log.i(TAG, "refresh ${shell.diagnostic} rear=${rear?.displayId}")
        return update(
            available = shell.available,
            rearDisplayId = rear?.displayId,
            lastDetail = when {
                shell.permissionRequired -> "待 Shizuku 授权"
                !shell.available -> "Shizuku 不可用（${shell.diagnostic}）"
                rear == null -> "未识别到背屏"
                else -> "背屏 displayId=${rear.displayId}"
            },
        )
    }

    override fun project(iconSet: List<String>): Boolean {
        // 先撤销上一次的退出请求（下屏请求可能比投送晚到，见 RearDashboardHost.attach）。
        RearDashboardHost.expectShow()
        // 先把 Icon Set 广播给背屏界面，再投送：首帧就带正确内容。
        IconSetFeed.publish(iconSet)
        val current = refresh()
        val displayId = current.rearDisplayId
        if (displayId == null) {
            Log.w(TAG, "project 跳过：${current.lastDetail}")
            return false
        }

        // 首选：应用自己把 RearDashboardActivity 投到背屏（own-activity 投送，不需要 shell）。
        // 系统不认时只在内部 aborted、不抛异常（E2 实测），所以结果要异步确认——见 launchAndConfirm。
        if (launchAndConfirm(displayId)) {
            update(
                projected = true,
                iconSet = iconSet,
                lastDetail = "displayId=$displayId 投送中（界面确认后转为已投送）",
            )
            Log.i(TAG, "project iconSet=$iconSet -> 应用内投送已发出 displayId=$displayId")
            return true
        }
        return projectViaShizuku(current, displayId, iconSet)
    }

    /** 兜底：Shizuku（shell uid）里执行投送命令；Shizuku 不可用时不抛异常，直接判失败。 */
    private fun projectViaShizuku(
        current: RearBackendState,
        displayId: Int,
        iconSet: List<String>,
    ): Boolean {
        if (!current.available) {
            update(projected = false, lastDetail = "投送失败：${current.lastDetail}")
            Log.w(TAG, "project 失败：应用内启动被拒且 Shizuku 不可用")
            return false
        }
        val result = run(RearProjectionCommands.project(context.packageName, displayId))
        update(
            projected = result.ok,
            iconSet = iconSet,
            lastDetail = if (result.ok) "已投送 displayId=$displayId（Shizuku）" else "投送失败：${result.output}",
        )
        Log.i(TAG, "project iconSet=$iconSet -> projected=${result.ok}（Shizuku 兜底）")
        return result.ok
    }

    /**
     * 应用内投送到指定屏幕：`ActivityOptions.setLaunchDisplayId` + `FLAG_ACTIVITY_NEW_TASK`。
     *
     * 好处是不依赖 Shizuku 通道；背屏准入仍由 manifest 的 `miui.rear.policy=1` 决定。
     *
     * **不能在这里同步等结果**：调用通常发生在主线程（按钮回调），主线程被阻塞时
     * 界面 `onCreate` 也排不上队，会自锁到超时（本轮实测：onCreate 恰好晚于超时 17ms）。
     * 所以这里只负责「调用是否成功」，真正的确认放到后台线程 [confirmLater]。
     */
    private fun launchAndConfirm(displayId: Int): Boolean {
        val app = context.applicationContext as? Application
        // 先挂确认再投送：界面可能在上屏后 15ms 内就 onCreate（E7 实测），后挂会漏掉这个信号。
        val watcher = DashboardCreationWatcher()
        return try {
            app?.registerActivityLifecycleCallbacks(watcher)
            val intent = Intent(context, RearDashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            launchPending = true
            context.startActivity(intent, options)
            confirmLater(displayId, watcher, app)
            true
        } catch (e: Exception) {
            launchPending = false
            app?.unregisterActivityLifecycleCallbacks(watcher)
            Log.w(TAG, "应用内投送 displayId=$displayId 失败", e)
            false
        }
    }

    /** 后台线程确认界面是否真的起来了；确认到才把状态写成「已投送」。 */
    private fun confirmLater(
        displayId: Int,
        watcher: DashboardCreationWatcher,
        app: Application?,
    ) {
        // 已经在屏（重复投送/重投）：界面实例就是证据，不必再等生命周期回调——
        // 已存在的 Activity 不会再 onActivityCreated，等下去只会误报「未确认」。
        if (RearDashboardHost.instanceCount > 0) {
            launchPending = false
            app?.unregisterActivityLifecycleCallbacks(watcher)
            update(lastDetail = "已投送 displayId=$displayId（界面已在屏）")
            Log.i(TAG, "投送确认：Dashboard 已在屏 displayId=$displayId")
            return
        }
        val canReadTaskStack = shell.available
        val component = RearProjectionCommands.dashboardComponent(context.packageName)
        confirmer.execute {
            try {
                val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (watcher.awaitCreation(LAUNCH_POLL_MS)) {
                        update(lastDetail = "已投送 displayId=$displayId（应用内启动）")
                        Log.i(TAG, "投送确认：RearDashboardActivity 已创建 displayId=$displayId")
                        return@execute
                    }
                    if (canReadTaskStack) {
                        val state = shell.run(DUMP_ACTIVITIES).output
                        if (RearProjectionVerifier.isOnDisplay(state, displayId, component)) {
                            update(lastDetail = "已投送 displayId=$displayId（应用内启动，任务栈确认）")
                            Log.i(TAG, "投送确认：背屏任务栈出现 Dashboard displayId=$displayId")
                            return@execute
                        }
                    }
                }
                Log.w(TAG, "投送 displayId=$displayId 未获确认（${LAUNCH_TIMEOUT_MS}ms，可能被背屏白名单拒绝）")
                update(projected = false, lastDetail = "未确认上屏：displayId=$displayId 被系统拒绝的可能性大")
            } finally {
                launchPending = false
                app?.unregisterActivityLifecycleCallbacks(watcher)
            }
        }
    }

    override fun update(iconSet: List<String>): Boolean {
        // 界面被系统结束（AOD 抢回 / 任务被清）时不能只广播图标：那样背屏上什么都没有。
        // 自愈成重投（幂等），保证「Icon Set 变化 → 背屏可见」始终成立（票 #5）；
        // AOD/锁屏的 Takeover 判定（SUB_SCREEN_ON/OFF 广播）仍归票 #6。
        // 上屏在途中的空档不算「不在屏」，否则第一枚通知之后的每枚都会重复投送。
        if (RearDashboardHost.instanceCount == 0 && !launchPending) {
            Log.w(TAG, "update 时背屏无 Dashboard 实例，转为重投 iconSet=$iconSet")
            return project(iconSet)
        }
        IconSetFeed.publish(iconSet)
        update(projected = true, iconSet = iconSet, lastDetail = "Icon Set 已更新（${iconSet.size} 枚）")
        Log.i(TAG, "update iconSet=$iconSet")
        return true
    }

    override fun exit() {
        IconSetFeed.publish(emptyList())
        val finished = RearDashboardHost.finishAll()
        update(
            projected = false,
            iconSet = emptyList(),
            lastDetail = if (finished > 0) "已退出（结束 $finished 个界面）" else "已退出（背屏无界面，退出请求已挂起）",
        )
        Log.i(TAG, "exit 结束在屏 Dashboard=$finished，进程与通知监听继续")
    }

    override fun permissionRequired(): Boolean = shell.permissionRequired

    override fun requestPermission() {
        Log.i(TAG, "申请 Shizuku 权限")
        shell.requestPermission()
    }

    override fun onPermissionGranted(action: () -> Unit) {
        shell.onPermissionGranted(action)
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

    private fun update(
        available: Boolean = state.available,
        rearDisplayId: Int? = state.rearDisplayId,
        projected: Boolean = state.projected,
        iconSet: List<String> = state.iconSet,
        lastDetail: String = state.lastDetail,
    ): RearBackendState = RearBackendState(
        available = available,
        rearDisplayId = rearDisplayId,
        projected = projected,
        lastDetail = lastDetail,
        iconSet = iconSet,
    ).also { _stateFlow.value = it }

    companion object {
        private const val TAG = "RearCue"

        /** 等待「投送生效」的上限：超时即认定被背屏白名单拒绝。 */
        private const val LAUNCH_TIMEOUT_MS = 2500L

        /** 每条等待信号的轮询间隔。 */
        private const val LAUNCH_POLL_MS = 250L

        /** 读背屏任务栈用的命令（只在 Shizuku 可用时执行）。 */
        private const val DUMP_ACTIVITIES = "dumpsys activity activities"

        /** 证据落盘目录：应用外部私有目录，`adb pull` 可直接取。 */
        fun transcriptDir(context: Context): File =
            File(context.getExternalFilesDir(null), "poc-logs")
    }
}

/**
 * 等「[RearDashboardActivity] 被创建」的生命周期监听器。
 *
 * 投送是否真的上屏只能靠系统事实确认（E2 实测：被背屏白名单拒绝时 `startActivity`
 * 不抛异常也没有报错），界面创建是第一个可信信号；[awaitCreation] 供后台线程轮询等待。
 */
private class DashboardCreationWatcher : Application.ActivityLifecycleCallbacks {

    private val created = CountDownLatch(1)

    fun awaitCreation(timeoutMs: Long): Boolean = created.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is RearDashboardActivity) created.countDown()
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

