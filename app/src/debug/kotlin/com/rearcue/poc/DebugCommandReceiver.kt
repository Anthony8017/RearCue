package com.rearcue.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rearcue.poc.notify.cancelTestNotification
import com.rearcue.poc.notify.postTestNotification
import com.rearcue.poc.rear.WakeKeepAlive

/**
 * PC 实验控制入口（票 #6）：`adb shell am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a <action>`
 * 驱动的手动旁路，让 E3/E4/E6/E8 这类「应用在后台」的实验不用人点手机屏幕。
 *
 * 只在 debug 构建注册（声明在 `app/src/debug/AndroidManifest.xml`），且用
 * `android.permission.DUMP` 保护：只有 shell/system（`adb shell`）能发。
 * 投送/退出/状态三个动作走 [AppContainer] 的调试旁路（等价于主屏调试页上的按钮）；
 * 通知类的三个动作更薄——`POST_TEST`/`CANCEL_TEST` 直接调 `notify` 包里的发/撤函数，
 * `CANCEL_PACKAGE` 走 [AppContainer.cancelNotificationsOf]（撤销他人通知的权限只属于监听服务）。
 * 两类都不改动自动流转的决策。
 *
 * 票 #7 起，PC 脚本（`tools/ex`）用它驱动「发/撤测试通知」，这样整条链路
 * （通知 → Icon Set → 上/下屏）都能一键复跑，不必人碰手机。
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val container = (context?.applicationContext as? RearCueApp)?.container
        if (container == null) {
            Log.w(LOG_TAG, "调试动作 ${intent?.action} 到达时进程还没建好容器，忽略")
            return
        }
        when (intent?.action) {
            ACTION_PROJECT_REAR -> container.projectToRear()
            ACTION_EXIT_REAR -> container.exitRear()
            ACTION_POST_TEST -> {
                Log.i(LOG_TAG, "debug post test notification")
                context?.let(::postTestNotification)
            }
            ACTION_CANCEL_TEST -> {
                Log.i(LOG_TAG, "debug cancel test notification")
                context?.let(::cancelTestNotification)
            }
            // 撤销任意 Allowlist App 的通知（含 `cmd notification post` 的 shell 通知）：
            // 监听服务是唯一有权限撤销他人通知的角色，能力由它登记进容器。
            ACTION_CANCEL_PACKAGE -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                if (pkg.isNullOrEmpty()) {
                    Log.w(LOG_TAG, "调试动作 $ACTION_CANCEL_PACKAGE 缺 --es $EXTRA_PACKAGE")
                } else {
                    // 返回值只进日志：脚本侧判定看的是随后的通知事件，不是这里的结果码。
                    Log.i(LOG_TAG, "debug cancel pkg=$pkg -> ${container.cancelNotificationsOf(pkg)}")
                }
            }
            // E9 覆盖窗口准入探针（票 #10）：加/撤一块最小覆盖窗口，displayId 缺省 = 运行时识别的背屏。
            // 判定在 PC 侧（dumpsys window + 系统窗口日志），这里的日志只报失败原因。
            ACTION_OVERLAY_ADD -> {
                val display = intent.getIntExtra(EXTRA_DISPLAY_ID, -1).takeIf { it >= 0 }
                Log.i(LOG_TAG, "debug overlay add display=${display ?: "rear"}")
                context?.let { OverlayProbe.add(it, display) }
            }
            ACTION_OVERLAY_REMOVE -> {
                Log.i(LOG_TAG, "debug overlay remove")
                OverlayProbe.remove()
            }
            ACTION_STATE -> Log.i(
                LOG_TAG,
                "state ${container.state.value} rear=${container.rearBackend.state}",
            )
            // Wake Keep-alive 强度调节（票 #21）：`--el ms <间隔>` 运行中改注入间隔；
            // 不带参数只回读当前强度与是否在跑。只动 WakeKeepAlive.current，不进自动流转。
            ACTION_WAKE_INTERVAL -> {
                val ms = intent.getLongExtra(EXTRA_MS, -1L)
                val keepAlive = WakeKeepAlive.current
                when {
                    keepAlive == null -> Log.w(LOG_TAG, "调试动作 $ACTION_WAKE_INTERVAL：保活循环未初始化，忽略")
                    ms > 0 -> {
                        keepAlive.intervalMs = ms.coerceAtLeast(WakeKeepAlive.MIN_INTERVAL_MS)
                        Log.i(LOG_TAG, "debug wake-interval ms=${keepAlive.intervalMs} running=${keepAlive.isRunning}")
                    }
                    else -> Log.i(LOG_TAG, "debug wake-interval ms=${keepAlive.intervalMs} running=${keepAlive.isRunning}")
                }
            }
            // Shizuku 运行时授权申请（等价于调试页按钮的授权分支）：重装清掉授权后，
            // tools/ex 用它把授权框弹出来（弹窗本身仍要人在手机上点一次）。
            ACTION_SHIZUKU_REQUEST -> {
                Log.i(LOG_TAG, "debug shizuku request")
                container.rearBackend.requestPermission()
            }
            else -> Log.w(LOG_TAG, "未知调试动作 ${intent?.action}")
        }
    }

    companion object {
        /** 投送当前 Icon Set 到背屏（后台实验用；等价于调试页「投送到背屏」按钮）。 */
        const val ACTION_PROJECT_REAR = "com.rearcue.poc.action.PROJECT_REAR"

        /** 结束在屏 Dashboard（等价于调试页「退出背屏」按钮）。 */
        const val ACTION_EXIT_REAR = "com.rearcue.poc.action.EXIT_REAR"

        /** 把当前状态打进 logcat（实验取证用）。 */
        const val ACTION_STATE = "com.rearcue.poc.action.STATE"

        /** 发本应用的测试通知（等价于调试页「发测试通知」按钮）。 */
        const val ACTION_POST_TEST = "com.rearcue.poc.action.POST_TEST"

        /** 撤本应用的测试通知（等价于调试页「清除测试通知」按钮）。 */
        const val ACTION_CANCEL_TEST = "com.rearcue.poc.action.CANCEL_TEST"

        /** 撤销 `--es pkg <包名>` 的全部 Active Notification（PC 脚本清场用）。 */
        const val ACTION_CANCEL_PACKAGE = "com.rearcue.poc.action.CANCEL_PACKAGE"

        /** [ACTION_CANCEL_PACKAGE] 的目标包名（`am broadcast --es pkg <pkg>`）。 */
        const val EXTRA_PACKAGE = "pkg"

        /** 加一块最小覆盖窗口到背屏（票 #10 / E9 探针；`--ei displayId <id>` 可指定别的屏）。 */
        const val ACTION_OVERLAY_ADD = "com.rearcue.poc.action.OVERLAY_ADD"

        /** 撤掉 [ACTION_OVERLAY_ADD] 加的覆盖窗口（票 #10 / E9 探针）。 */
        const val ACTION_OVERLAY_REMOVE = "com.rearcue.poc.action.OVERLAY_REMOVE"

        /** [ACTION_OVERLAY_ADD] 的目标屏（`am broadcast --ei displayId <id>`；缺省 = 运行时识别的背屏）。 */
        const val EXTRA_DISPLAY_ID = "displayId"

        /** 调 Wake Keep-alive 注入间隔（票 #21；`am broadcast --el ms <间隔毫秒>`，缺省只回读）。 */
        const val ACTION_WAKE_INTERVAL = "com.rearcue.poc.action.WAKE_INTERVAL"

        /** [ACTION_WAKE_INTERVAL] 的间隔毫秒（`am broadcast --el ms <ms>`）。 */
        const val EXTRA_MS = "ms"

        /** 弹 Shizuku 运行时授权申请框（等价于调试页「投送到背屏」按钮的授权分支）。 */
        const val ACTION_SHIZUKU_REQUEST = "com.rearcue.poc.action.SHIZUKU_REQUEST"
    }
}
