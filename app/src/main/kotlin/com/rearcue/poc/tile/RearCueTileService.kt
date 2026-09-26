package com.rearcue.poc.tile

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.rearcue.poc.LOG_TAG
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.rear.DashboardPresence
import com.rearcue.poc.rear.Presence

/**
 * Quick Tile Entry（spec 0006 / 票 #54）：控制中心快捷开关承载的正式手动入口。
 *
 * - 点击语义：无 Dashboard → 投送；有 → 退出（[RearCueApp.toggleRearDashboard]，与
 *   Debug Bypass 同走 core 的 ManualCast/ManualExit，记 manual 来源——豁免 DND Follow 与
 *   Posture Gate，也不被自动逻辑撤下）。
 * - tile 状态反映 Presence（CONTEXT.md：全项目唯一在屏事实）：OnScreen/LaunchPending →
 *   ACTIVE（点击 = 退出语义），Absent → INACTIVE（点击 = 投送）。锁屏态 QS 可直接点。
 * - 复用既有投送命令路径（rearBackend → ProjectionSession），本服务零投送逻辑、零 Shizuku。
 * - 状态刷新时机：onStartListening（面板可见）+ 点击后延迟一次（投/退的会话收口在途，
 *   Presence 稍后才翻转）。不做常驻轮询——面板关着时 tile 旧态无害，重开即同步。
 */
class RearCueTileService : TileService() {

    private val container get() = (applicationContext as RearCueApp).container
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val exiting = DashboardPresence.read() != Presence.ABSENT
        Log.i(LOG_TAG, "tile 点击 → ${if (exiting) "退出" else "投送"}")
        container.toggleRearDashboard()
        syncTile()
        // 投送会话异步收口（task-move/回读），Presence 翻转后再同步一次状态。
        handler.postDelayed(::syncTile, TILE_SYNC_DELAY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        tile.state = if (DashboardPresence.read() != Presence.ABSENT) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        private const val TILE_SYNC_DELAY_MS = 1500L
    }
}
