package com.rearcue.poc.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * 电量读数信号（spec 0008 / 票 #67，Charging Animation 显示面数据）：
 * `ACTION_BATTERY_CHANGED`（BatteryManager 既有广播链）→ 回调百分比。
 *
 * sticky 广播：注册即收到最近一次电量广播——进程启动后首个读数即刻到位
 * （已插电启动/`ACTION_POWER_CONNECTED` 不重播的场合都靠它补上初值）；
 * 此后每次电量变化广播（含 `cmd battery set level` 的软件模拟）照常送达。
 *
 * 只做「系统信号 → 回调百分比」这段搬运（level/scale → 百分比、scale≤0/负值丢弃），
 * 进状态与显示决策全在 DashboardCore（接线层零决策，JVM 测试不在本层写）。
 * 注册沿 `PowerSignals` 先例：进程存活期间注册一次、`runCatching` 兜底、不注销。
 */
class BatterySignals(
    context: Context,
    private val onLevelPercent: (percent: Int) -> Unit,
) {

    private val appContext = context.applicationContext

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) {
                Log.w(TAG, "电量广播缺有效读数 level=$level scale=$scale，丢弃")
                return
            }
            val percent = (level * 100 / scale).coerceIn(0, 100)
            Log.i(TAG, "电量读数 level=$level scale=$scale → $percent%")
            onLevelPercent(percent)
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        runCatching { appContext.registerReceiver(receiver, filter) }
            .onSuccess { Log.i(TAG, "电量信号监听已注册（sticky 首读）") }
            .onFailure { Log.w(TAG, "电量信号监听注册失败", it) }
    }

    companion object {
        private const val TAG = "RearCue"
    }
}
