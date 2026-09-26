package com.rearcue.poc.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * 充电插拔信号（spec 0007 票 #57）：`ACTION_POWER_CONNECTED/DISCONNECTED` → 回调。
 *
 * 只做「系统信号 → 回调」这段搬运，投/撤与总开关豁免的决策全在 DashboardCore
 * （接线层零决策，JVM 测试不在本层写）。注册沿 `RearDisplaySignals` 先例：进程存活期间
 * 注册一次、`runCatching` 兜底、不注销；电源广播是 protected broadcast（只有系统能发），
 * 与背屏信号同样用 `RECEIVER_EXPORTED` 注册。
 */
class PowerSignals(
    context: Context,
    private val onPowerChanged: (connected: Boolean) -> Unit,
) {

    private val appContext = context.applicationContext

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.i(TAG, "电源信号收到 $action")
            onPowerChanged(action == Intent.ACTION_POWER_CONNECTED)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }
            .onSuccess { Log.i(TAG, "电源信号监听已注册 $filter") }
            .onFailure { Log.w(TAG, "电源信号监听注册失败", it) }
    }

    companion object {
        private const val TAG = "RearCue"
    }
}
