package com.rearcue.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * PC 实验控制入口（票 #6）：`adb shell am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a <action>`
 * 驱动的手动旁路，让 E3/E4/E6/E8 这类「应用在后台」的实验不用人点手机屏幕。
 *
 * 只在 debug 构建注册（声明在 `app/src/debug/AndroidManifest.xml`），且用
 * `android.permission.DUMP` 保护：只有 shell/system（`adb shell`）能发。
 * 动作全部走 [AppContainer] 的调试旁路（等价于主屏调试页上的按钮），不改动自动流转的决策。
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
    }
}
