package com.rearcue.poc.rear

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 背屏 Dashboard 的进程内句柄：上屏时登记实例，下屏时**只结束界面**、不杀进程。
 *
 * 为什么不用 `am force-stop`（票 #4 的旧退出实现）：通知监听与背屏 Dashboard 跑在同一个进程，
 * force-stop 会把监听服务一起杀掉（票 #3 实测系统要 9~13s 才重新绑定），
 * 末条通知消失后就再也没人把 Dashboard 拉回来了（票 #5 的「自动上/下屏」闭环断裂）。
 *
 * 登记按「实例存在」而不是「实例在屏」（onCreate/onDestroy）：AOD 抢回或背屏息屏会让界面
 * onStop，但那时它仍然占着背屏，[finishAll] 必须能结束它，否则「末条通知消失 → 退出」会静默失效。
 *
 * [finishAll] 会记下退出请求，用来兜住竞态：末条通知恰好在投送途中消失时界面还没创建，
 * 退出请求只能等它创建时立刻生效（见 [attach]）。下一次 [expectShow] 清掉该标记。
 */
object RearDashboardHost {

    private const val TAG = "RearCue"

    private val lock = Any()

    /** 当前存在的 Dashboard 实例（onCreate 到 onDestroy 之间）。 */
    private val instances = mutableSetOf<RearDashboardActivity>()

    private var exitRequested = false

    /** 延迟期间若有新 Activity、投送或主动退出，递增此序号取消过期的 detach 通知。 */
    private var detachGeneration = 0L

    /** 系统意外销毁最后一个 Dashboard 并持续缺失时通知应用容器；主动退出不会触发。 */
    @Volatile
    private var unexpectedDetachListener: (() -> Unit)? = null

    /**
     * 背屏点按回调（票 #66 Detail View 的 UI 源）：app 层注册、背屏界面触发（都在主线程）。
     * 依赖方向照旧 app → rear——这里只有回调注册，[RearDashboardActivity] 不知道容器。
     */
    @Volatile
    private var iconTapListener: ((String) -> Unit)? = null

    /** 背屏非交互区域点按回调（spec 0013 内容页切换的 UI 源）。 */
    @Volatile
    private var contentPageTapListener: (() -> Unit)? = null

    /** 背屏 Agent 页会话标识行点按回调（spec 0016 / 票 #156 会话列表的 UI 源）。 */
    @Volatile
    private var sessionLineTapListener: (() -> Unit)? = null

    /** 背屏会话列表条目选定回调（spec 0016 / 票 #156）：null = 选「自动」档。 */
    @Volatile
    private var sessionPickListener: ((String?) -> Unit)? = null

    /** 注册背屏图标/卡片点按处理（app 层接线用；null = 注销）。 */
    fun onIconTap(listener: ((String) -> Unit)?) {
        iconTapListener = listener
    }

    /**
     * 背屏界面点按了某枚图标（或 Detail 卡片）：转发给注册方翻译成
     * [com.rearcue.poc.core.DashboardEvent.DetailToggled]；无注册方（进程早期/边缘态）即丢弃。
     * `rear-tap received` 探针锚（票 #63 词形）在处理点打（AppContainer.onRearIconTap），
     * 不在发射点重复——一次点按一条锚。
     */
    fun emitIconTap(pkg: String) {
        iconTapListener?.invoke(pkg)
    }

    /** 注册背屏非交互区域点按处理（app 层接线用；null = 注销）。 */
    fun onContentPageTap(listener: (() -> Unit)?) {
        contentPageTapListener = listener
    }

    /**
     * 背屏界面点按了非交互区域：转发给注册方翻译成
     * [com.rearcue.poc.core.DashboardEvent.ContentPageToggle]；无注册方（进程早期/边缘态）即丢弃。
     * 图标、Detail 卡片、Agent 回底按钮的专属点按不走本入口。
     */
    fun emitContentPageTap() {
        contentPageTapListener?.invoke()
    }

    /** 注册背屏会话标识行点按处理（app 层接线用；null = 注销）。 */
    fun onSessionLineTap(listener: (() -> Unit)?) {
        sessionLineTapListener = listener
    }

    /**
     * 背屏界面点按了 Agent 页的会话标识行：转发给注册方翻译成
     * [com.rearcue.poc.core.DashboardEvent.AgentPickerToggle]（开/关列表由状态机判）；
     * 无注册方（进程早期/边缘态）即丢弃。
     */
    fun emitSessionLineTap() {
        sessionLineTapListener?.invoke()
    }

    /** 注册背屏会话列表的选定处理（app 层接线用；null = 注销）。 */
    fun onSessionPick(listener: ((String?) -> Unit)?) {
        sessionPickListener = listener
    }

    /**
     * 背屏界面在会话列表里选了一条：转发给注册方走 Session Lock 单入口
     * （app 层 `AppContainer.setSessionLock`，事件进 core + 写盘）；null = 「自动」档。
     */
    fun emitSessionPick(sessionId: String?) {
        sessionPickListener?.invoke(sessionId)
    }

    /** 主线程 Handler：`finish()` 必须在界面所属线程调用。 */
    private val main = Handler(Looper.getMainLooper())

    /** 当前存在的 Dashboard 实例数；0 = 背屏上没有本应用的界面。 */
    val instanceCount: Int get() = synchronized(lock) { instances.size }

    /** 即将投送：清掉上一次的退出请求（幂等）。 */
    fun expectShow() {
        synchronized(lock) {
            exitRequested = false
            detachGeneration++
        }
    }

    fun onUnexpectedDetach(listener: () -> Unit) {
        unexpectedDetachListener = listener
    }

    fun attach(activity: RearDashboardActivity) {
        val finishNow = synchronized(lock) {
            instances += activity
            detachGeneration++
            exitRequested
        }
        Log.i(TAG, "Dashboard attach 实例数=${instanceCount}")
        if (finishNow) {
            // 投送在途时末条通知已消失：界面刚创建就结束，避免背屏上留一个空 Icon Set 的 Dashboard。
            Log.i(TAG, "attach 时已有退出请求，立即结束该实例")
            main.post { activity.finish() }
        }
    }

    fun detach(activity: RearDashboardActivity) {
        val (left, recoveryGeneration) = synchronized(lock) {
            val removed = instances.remove(activity)
            val unexpected = removed && instances.isEmpty() && !exitRequested
            val recoveryGeneration = if (unexpected) ++detachGeneration else null
            if (instances.isEmpty()) exitRequested = false
            instances.size to recoveryGeneration
        }
        Log.i(TAG, "Dashboard detach 实例数=$left")
        if (recoveryGeneration != null) {
            Log.w(TAG, "Dashboard 意外销毁，${DETACH_RECOVERY_DELAY_MS}ms 后确认是否仍未创建")
            main.postDelayed({
                val stillDetached = synchronized(lock) {
                    recoveryGeneration == detachGeneration && instances.isEmpty() && !exitRequested
                }
                if (stillDetached) {
                    Log.w(TAG, "Dashboard 持续缺失，通知应用状态机")
                    unexpectedDetachListener?.invoke()
                }
            }, DETACH_RECOVERY_DELAY_MS)
        }
    }

    /** 结束全部在屏 Dashboard，返回本次结束的实例数（背屏随即交还 Native Rear Screen）。 */
    fun finishAll(): Int {
        val targets = synchronized(lock) {
            exitRequested = true
            detachGeneration++
            instances.toList()
        }
        targets.forEach { activity -> main.post { activity.finish() } }
        return targets.size
    }

    private const val DETACH_RECOVERY_DELAY_MS = 1500L
}
