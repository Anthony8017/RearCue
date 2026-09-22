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

    /** 主线程 Handler：`finish()` 必须在界面所属线程调用。 */
    private val main = Handler(Looper.getMainLooper())

    /** 当前存在的 Dashboard 实例数；0 = 背屏上没有本应用的界面。 */
    val instanceCount: Int get() = synchronized(lock) { instances.size }

    /** 即将投送：清掉上一次的退出请求（幂等）。 */
    fun expectShow() {
        synchronized(lock) { exitRequested = false }
    }

    fun attach(activity: RearDashboardActivity) {
        val finishNow = synchronized(lock) {
            instances += activity
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
        val left = synchronized(lock) {
            instances -= activity
            if (instances.isEmpty()) exitRequested = false
            instances.size
        }
        Log.i(TAG, "Dashboard detach 实例数=$left")
    }

    /** 结束全部在屏 Dashboard，返回本次结束的实例数（背屏随即交还 Native Rear Screen）。 */
    fun finishAll(): Int {
        val targets = synchronized(lock) {
            exitRequested = true
            instances.toList()
        }
        targets.forEach { activity -> main.post { activity.finish() } }
        return targets.size
    }
}
