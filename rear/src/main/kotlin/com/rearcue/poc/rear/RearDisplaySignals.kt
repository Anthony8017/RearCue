package com.rearcue.poc.rear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * 背屏归属信号（票 #6 / E4）：HyperOS 把背屏交给原生界面（AOD 抢回）或交还、以及主屏亮灭时，
 * 系统都会发这些广播；它们合起来表达「背屏归属可能变了」。
 *
 * 只做「系统信号 → 回调」这一段搬运，是否重投仍由 DashboardCore 决定
 * （回调进 `DashboardEvent.TakeoverDetected`）；信号与重投的合并见 [HyperOsRearDisplayBackend]。
 *
 * 注册用 `RECEIVER_EXPORTED`：这些广播来自本应用之外（`com.xiaomi.subscreencenter` 与系统）。
 * 生命周期与进程一致，不提供注销（POC 只在进程启动时注册一次）。
 */
class RearDisplaySignals(
    context: Context,
    private val onSignal: (String) -> Unit,
) {

    private val appContext = context.applicationContext

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.i(TAG, "背屏信号收到 $action")
            onSignal(action)
        }
    }

    fun start() {
        val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
        runCatching { appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }
            .onSuccess { Log.i(TAG, "背屏信号监听已注册 $ACTIONS") }
            .onFailure { Log.w(TAG, "背屏信号监听注册失败", it) }
    }

    companion object {
        private const val TAG = "RearCue"

        /** 原生背屏（AOD/SubScreenLauncher）亮起：背屏被原生界面占据。 */
        const val SUB_SCREEN_ON = "miui.intent.action.SUB_SCREEN_ON"

        /** 原生背屏熄灭：背屏空出来了。 */
        const val SUB_SCREEN_OFF = "miui.intent.action.SUB_SCREEN_OFF"

        /**
         * 关心的广播：两条 miui 背屏广播是归属变化的直接信号；主屏亮/灭是背屏归属改变的入口
         * （锁屏瞬间 AOD 才接管背屏，见 docs/poc-findings.md 票 #6 的 E3/E4 记录）。
         */
        val ACTIONS = listOf(
            SUB_SCREEN_ON,
            SUB_SCREEN_OFF,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
        )
    }
}
