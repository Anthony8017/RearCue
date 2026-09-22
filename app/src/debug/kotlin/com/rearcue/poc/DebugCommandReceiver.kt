package com.rearcue.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rearcue.poc.notify.cancelTestNotification
import com.rearcue.poc.notify.postTestNotification

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
            ACTION_STATE -> Log.i(
                LOG_TAG,
                "state ${container.state.value} rear=${container.rearBackend.state}",
            )
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
    }
}
