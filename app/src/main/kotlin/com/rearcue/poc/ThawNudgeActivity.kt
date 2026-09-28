package com.rearcue.poc

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * 冻结唤醒空转页（Freeze Thaw Nudge，无 UI）。
 *
 * 背景：主屏灭屏后 HyperOS 的 GreezeManager 会在 ~5s 内冻结本应用进程
 * （`FZ uid = <uid> pid = [ ... ] reason : screen off / tobg`）。冻结期间
 * NotificationListenerService 的回调被压在队列里，背屏 Dashboard 停在冻前的画面
 * ——机主看到的就是「锁屏后飞书来了消息，背屏图标不更新」（2026-09-29 实测复现）。
 * 解冻那一刻队列会一次性补投（实测 3ms 内到齐），所以关键只是**把应用叫醒**。
 *
 * 谁来叫：应用自己已经被冻住、喊不动自己，于是交给设备侧的 Wake Keep-alive 循环
 * （shell uid，不吃这道冻结，见 [com.rearcue.poc.rear.WakeKeepAliveScript]）：
 * 循环每拍读一次本进程的 `cgroup.freeze`，为 1 就 `am start` 本页一次。
 * 进程被冻结时无法启动 Activity ⇒ 系统必须先解冻它，Activity Start 发生时事件随即补投。
 *
 * 本页不画任何东西并在 `onCreate` 立即 `finish()`（配 `Theme.NoDisplay` 与
 * `taskAffinity=""`），机主看不到它，也不改变背屏上的 Dashboard。
 */
class ThawNudgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(LOG_TAG, "thaw-nudge start")
        finish()
    }

    companion object {
        /** 与应用其余部分同一个 logcat tag（tools/ex 的验收链按此 tag 读锚）。 */
        const val LOG_TAG = "RearCue"

        /** 设备侧循环 am start 的目标组件名（与 manifest 里的 exported 声明一一对应）。 */
        const val COMPONENT = "com.rearcue.poc/.ThawNudgeActivity"
    }
}
