package com.rearcue.poc.rear

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.KeyguardManager
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

    /**
     * 投送 Dashboard 到背屏（幂等：重复调用不产生第二个任务）；[iconSet] 与配套的
     * [unreadCounts] 一起推到背屏界面（[IconSetFeed.publish] 是全量入口，两态同笔写入）。
     *
     * 返回「投送是否已发出」：true 不代表已上屏（应用内启动被拒/被后台启动限制拦下都只是内部
     * aborted，票 #4 E2 实测），最终状态由异步确认/Shizuku 兜底回读任务栈后写进 [state]。
     */
    fun project(iconSet: List<String>, unreadCounts: Map<String, Int>): Boolean

    /**
     * Dashboard 已在屏时更新 Icon Set（背屏界面自己重组，不重新投送）；[iconSet] 与配套的
     * [unreadCounts] 同笔推到背屏界面（同 [project] 的全量口径）。
     *
     * 返回「内容是否已交给背屏」：界面还在时恒为 true；界面不在了（被系统结束）时自愈重投，
     * 返回重投是否已发出（**不代表已确认上屏**，确认是异步的，见 [project]）。
     */
    fun update(iconSet: List<String>, unreadCounts: Map<String, Int>): Boolean

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

    /**
     * 注册「背屏归属变化」信号回调（票 #6 / E4）：主屏亮灭、原生背屏亮灭都会来。
     *
     * 回调只报信号（动作名），是否按当前 Icon Set 重投由 DashboardCore 决定——被顶掉时它会
     * 产出重投，正常亮灭时它什么都不做（幂等）。
     */
    fun onRearDisplaySignal(action: (String) -> Unit)

    /**
     * 注册「兜底通道（Shizuku）可用性」回调（票 #6 / E8）：授权成功或 server 重新上线时为 true。
     *
     * 掉线**不**触发 Degrade——投送主路径是应用内启动，不需要 Shizuku（CONTEXT.md「投送通道」）；
     * 上线时由调用方按当前 Icon Set 重投一次（幂等），保证兜底通道恢复后能立刻接管。
     */
    fun onFallbackChanged(action: (Boolean) -> Unit)
}

/**
 * HyperOS 实现：把 Dashboard 投到运行时识别到的背屏上——主路径是应用内
 * `ActivityOptions.setLaunchDisplayId`，被系统拦下（后台启动限制 / 锁屏策略）时改走
 * Shizuku（shell uid）的 shell 兜底链（收口在 [ProjectionSession]，含锁屏首投的任务搬运事务）。
 *
 * 两段式（CONTEXT.md「投送会话」）：本类只负责「发起 + 异步确认 + 状态写入」这一段；
 * shell 兜底链的命令、解析、E14 词表、保活生命周期全在 [ProjectionSession] 背后。
 *
 * 前提（票 #4 / 票 #6 实测，见 docs/poc-findings.md）：
 *  - manifest 声明 `miui.rear.policy=1`，否则系统 `ActivityStarterImpl` 直接 aborted；
 *  - Shizuku 只是兜底：掉线不影响投送判据（应用内路径不需要它），也不触发 Degrade；
 *  - 锁屏状态下系统禁止第三方应用在背屏启动界面，两条通道都会被 deny。
 */
class HyperOsRearDisplayBackend(
    private val context: Context,
    /**
     * 唯一的 Shizuku 通道实例，由调用方注入（票 #129）：本类不自建，避免与可见性探测
     * 各起一份 UserService 生命周期。
     */
    private val shell: ShizukuShell,
    private val displays: DisplaySource = DisplaySource(context),
    private val transcript: ShellTranscript = ShellTranscript(transcriptDir(context)),
) : RearDisplayBackend {

    private val _stateFlow = MutableStateFlow(RearBackendState())
    override val stateFlow: StateFlow<RearBackendState> = _stateFlow.asStateFlow()

    /** 背屏归属信号（票 #6 / E4）：HyperOS 专有广播收口在这里，上层只收归一化后的信号回调。 */
    private val signals = RearDisplaySignals(context) { forwardSignal(it) }

    private val signalListeners = mutableListOf<(String) -> Unit>()

    /** 上一次把信号交给上层的时刻，用来合并成对出现的广播（见 [forwardSignal]）。 */
    private var lastSignalAt = 0L

    /** 最近一次实际转发的信号；保留窗口内更强的 SUB_SCREEN_ON，避免被弱信号吞掉。 */
    private var lastForwardedSignal: String? = null

    init {
        signals.start()
    }

    /** 投送结果确认用的单线程执行器（守护线程，不拖住进程退出）。 */
    private val confirmer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rearcue-rear-confirm").apply { isDaemon = true }
    }

    /**
     * Wake Keep-alive（CONTEXT.md「唤醒保活」，票 #21）：投送在屏期间的背屏保活循环。
     *
     * 生命周期跟随投送，启停决策收在 [ProjectionSession]（投送即起、退出/空集/投送失败即停）；
     * 强度经 `WakeKeepAlive.current` 由调试入口调整。
     */
    private val keepAlive = WakeKeepAlive(shell, wakeStopFile(context), ::logWakeAnchor)

    /**
     * 投送会话（CONTEXT.md「投送会话」）：shell 兜底链 + E14 词表 + 保活生命周期的唯一收口。
     * 这里只喂它 Android 事实（包名、keyguard、日志锚），HyperOS 命令形状不再外泄。
     */
    private val session = ProjectionSession(
        packageName = context.packageName,
        shell = shell,
        wake = keepAlive,
        transcript = transcript,
        keyguardLocked = { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true },
        log = { Log.i(TAG, it) },
        warn = { Log.w(TAG, it) },
    )

    /**
     * Wake Keep-alive 的日志锚（词形契约见 [WakeKeepAlive] KDoc）：`fail` 词形走 W 级、其余 I 级
     * ——纯 Kotlin 面不引 `android.util.Log`（README seam），logcat 实现收口在这里。
     */
    private fun logWakeAnchor(line: String) {
        if (line.startsWith("wake-keep-alive fail")) Log.w(TAG, line) else Log.i(TAG, line)
    }

    override fun refresh(): RearBackendState {
        shell.bind() // 已授权时把 UserService 绑上；未授权是空操作
        val rear = displays.rearDisplay()
        if (rear == null) {
            // 背屏没了（注销/热插拔）：保活没有定向目标，停掉（安全降级；回来重投时自会再起）。
            session.stopGuard()
        }
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

    override fun project(iconSet: List<String>, unreadCounts: Map<String, Int>): Boolean {
        // 先撤销上一次的退出请求（下屏请求可能比投送晚到，见 RearDashboardHost.attach）。
        RearDashboardHost.expectShow()
        // 先把 Icon Set（包名 + 未读角标计数，全量同笔）广播给背屏界面，再投送：首帧就带正确内容。
        IconSetFeed.publish(iconSet, unreadCounts)
        val current = refresh()
        val displayId = current.rearDisplayId
        if (displayId == null) {
            Log.w(TAG, "project 跳过：${current.lastDetail}")
            return false
        }

        // Wake Keep-alive（票 #21）：投送在途即起——锁屏掉屏两大原因之一（背屏随主屏断电）
        // 靠它消掉（E12/E13 实测）；投送失败在投送会话的失败路径停掉。
        session.begin(displayId)

        // 首选：应用自己把 RearDashboardActivity 投到背屏（own-activity 投送，不需要 shell）。
        // 系统不认时只在内部 aborted、不抛异常（E2 实测），所以结果要异步确认——见 launchAndConfirm。
        if (launchAndConfirm(displayId, iconSet)) {
            update(
                projected = true,
                iconSet = iconSet,
                lastDetail = "displayId=$displayId 投送中（界面确认后转为已投送）",
            )
            Log.i(TAG, "project iconSet=$iconSet -> 应用内投送已发出 displayId=$displayId")
            return true
        }
        return applyCast(session.cast(displayId, iconSet, reason = "应用内启动被拒"), iconSet).onDisplay
    }

    /** 把 [CastResult] 落进 [RearBackendState]：判定在投送会话，状态写入只此一处。 */
    private fun applyCast(result: CastResult, iconSet: List<String>): CastResult {
        update(projected = result.onDisplay, iconSet = iconSet, lastDetail = result.detail)
        return result
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
    private fun launchAndConfirm(displayId: Int, iconSet: List<String>): Boolean {
        val app = context.applicationContext as? Application
        // 先挂确认再投送：界面可能在上屏后 15ms 内就 onCreate（E7 实测），后挂会漏掉这个信号。
        val watcher = DashboardCreationWatcher()
        return try {
            app?.registerActivityLifecycleCallbacks(watcher)
            val intent = Intent(context, RearDashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            DashboardPresence.launchPending = true
            context.startActivity(intent, options)
            confirmLater(displayId, watcher, app, iconSet)
            true
        } catch (e: Exception) {
            DashboardPresence.launchPending = false
            app?.unregisterActivityLifecycleCallbacks(watcher)
            Log.w(TAG, "应用内投送 displayId=$displayId 失败", e)
            false
        }
    }

    /**
     * 后台线程确认界面是否真的起来了；确认到才把状态写成「已投送」。决策在 [ConfirmPolicy]，
     * 这里只做等待与证据采集（生命周期信号 + 回读背屏任务栈）。
     *
     * 确认不到时**自动走 shell 兜底链**（[ProjectionSession.cast]）：应用在后台时 `startActivity`
     * 会被系统的后台启动限制拦掉（E-BAL 实测：`Background activity launch blocked! ... BAL_BLOCK`），
     * 而「手机闲置时来通知」正是本应用的主路径，不能只靠前台才能投送。
     */
    private fun confirmLater(
        displayId: Int,
        watcher: DashboardCreationWatcher,
        app: Application?,
        iconSet: List<String>,
    ) {
        // 已经在屏（重复投送/重投）：界面实例就是证据，不必再等生命周期回调——
        // 已存在的 Activity 不会再 onActivityCreated，等下去只会误报「未确认」。
        val pre = ConfirmPolicy.step(
            present = DashboardPresence.read() == Presence.ON_SCREEN,
            created = false,
            onDisplay = false,
            remainingMs = LAUNCH_TIMEOUT_MS,
        )
        if (pre is ConfirmStep.Confirmed) {
            DashboardPresence.launchPending = false
            app?.unregisterActivityLifecycleCallbacks(watcher)
            update(lastDetail = "已投送 displayId=$displayId（${pre.via.detailText}）")
            Log.i(TAG, confirmLog(pre.via, displayId))
            return
        }
        val canReadTaskStack = shell.available
        confirmer.execute {
            try {
                val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
                while (true) {
                    val created = watcher.awaitCreation(LAUNCH_POLL_MS)
                    val remainingMs = deadline - System.currentTimeMillis()
                    val onDisplay = !created && canReadTaskStack && session.onDisplayNow(displayId)
                    when (val step = ConfirmPolicy.step(
                        present = false,
                        created = created,
                        onDisplay = onDisplay,
                        remainingMs = remainingMs,
                    )) {
                        is ConfirmStep.Confirmed -> {
                            update(lastDetail = "已投送 displayId=$displayId（${step.via.detailText}）")
                            Log.i(TAG, confirmLog(step.via, displayId))
                            return@execute
                        }
                        ConfirmStep.WaitMore -> Unit
                        ConfirmStep.TimedOut -> {
                            Log.w(
                                TAG,
                                "投送 displayId=$displayId 未获确认（${LAUNCH_TIMEOUT_MS}ms，可能被背屏白名单或后台启动限制拦下）",
                            )
                            update(projected = false, lastDetail = "未确认上屏：displayId=$displayId 被系统拒绝的可能性大")
                            // 主路径失效（后台启动限制 / 背屏白名单）：交给 shell 兜底链（锁屏直达任务搬运）。
                            applyCast(session.cast(displayId, iconSet, reason = "应用内投送未获确认"), iconSet)
                            return@execute
                        }
                    }
                }
            } finally {
                DashboardPresence.launchPending = false
                app?.unregisterActivityLifecycleCallbacks(watcher)
            }
        }
    }

    /** 确认日志（词形照旧）：三种证据各自一句，进 logcat 与调试页。 */
    private fun confirmLog(via: ConfirmVia, displayId: Int): String = when (via) {
        ConfirmVia.ALREADY_ON_SCREEN -> "投送确认：Dashboard 已在屏 displayId=$displayId"
        ConfirmVia.LAUNCHED -> "投送确认：RearDashboardActivity 已创建 displayId=$displayId"
        ConfirmVia.TASK_STACK -> "投送确认：背屏任务栈出现 Dashboard displayId=$displayId"
    }

    override fun update(iconSet: List<String>, unreadCounts: Map<String, Int>): Boolean {
        // 界面被系统结束（AOD 抢回 / 任务被清）时不能只广播图标：那样背屏上什么都没有。
        // 自愈成重投（幂等），保证「Icon Set 变化 → 背屏可见」始终成立（票 #5）；
        // AOD/锁屏的 Takeover 判定（SUB_SCREEN_ON/OFF 广播）仍归票 #6。
        // 上屏在途中的空档不算「不在屏」，否则第一枚通知之后的每枚都会重复投送。
        if (DashboardPresence.read() == Presence.ABSENT) {
            Log.w(TAG, "update 时背屏无 Dashboard 实例，转为重投 iconSet=$iconSet")
            return project(iconSet, unreadCounts)
        }
        IconSetFeed.publish(iconSet, unreadCounts)
        update(projected = true, iconSet = iconSet, lastDetail = "Icon Set 已更新（${iconSet.size} 枚）")
        Log.i(TAG, "update iconSet=$iconSet")
        return true
    }

    override fun exit() {
        // 全量清空（包名与计数同笔）：只清图标会留下「空 Icon Set + 旧计数」的漂移态。
        IconSetFeed.publish(emptyList(), emptyMap())
        val finished = RearDashboardHost.finishAll()
        // 保活即停（票 #21：exit 后无残留循环）+ 任务搬运事务搬走的 root task 搬回默认屏
        // （票 #22 AC：通知清空后把背屏交还原生界面）。
        session.end()
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

    override fun onRearDisplaySignal(action: (String) -> Unit) {
        signalListeners += action
    }

    override fun onFallbackChanged(action: (Boolean) -> Unit) = shell.onAvailabilityChanged(action)

    /**
     * 背屏信号合并：锁屏/解锁时 SUB_SCREEN 与主屏 SCREEN 广播成对出现，逐条重投会连发多次
     * `startActivity`；这是传输层的合并（1s 窗口），是否重投仍由 DashboardCore 判定。
     * `SUB_SCREEN_ON` 表示 Native Rear Screen 可能已经 Takeover，即使落在合并窗口内也不能
     * 被先到的 `SUB_SCREEN_OFF`/`SCREEN_OFF` 吞掉。
     */
    private fun forwardSignal(action: String) {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastSignalAt
        if (!RearDisplaySignalPolicy.shouldForward(
                action = action,
                sinceLastForwardMs = sinceLast,
                lastForwardedAction = lastForwardedSignal,
                debounceMs = SIGNAL_DEBOUNCE_MS,
            )
        ) {
            Log.i(TAG, "背屏信号 $action 合并（距上次 ${sinceLast}ms）")
            return
        }
        lastSignalAt = now
        lastForwardedSignal = action
        signalListeners.toList().forEach { it(action) }
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

        /** 背屏信号合并窗口：窗口内的连续广播只触发一次重投决策。 */
        private const val SIGNAL_DEBOUNCE_MS = 1000L

        /** 证据落盘目录：应用外部私有目录，`adb pull` 可直接取。 */
        fun transcriptDir(context: Context): File =
            File(context.getExternalFilesDir(null), "poc-logs")

        /**
         * Wake Keep-alive 的应用侧 stop 标记（不残留契约）：应用外部私有目录——应用可直写、
         * shell uid 可读（同 [transcriptDir] 的可达性口径），Shizuku 掉线时停令也送达。
         */
        fun wakeStopFile(context: Context): File =
            File(context.getExternalFilesDir(null), WakeKeepAliveScript.WAKE_LOOP_APP_STOP_FILE_NAME)
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
