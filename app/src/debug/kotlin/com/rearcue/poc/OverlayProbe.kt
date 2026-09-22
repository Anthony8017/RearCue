package com.rearcue.poc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.rearcue.poc.rear.DisplaySource

/**
 * E9 覆盖窗口准入探针（票 #10，spec 0002）：加/撤一块最小覆盖窗口到背屏。
 *
 * 只回答「MIUI 让不让 SYSTEM_ALERT_WINDOW 的 TYPE_APPLICATION_OVERLAY 上背屏」，
 * **不承载 Dashboard 内容**——渲染复用、生命周期、通道选择是票 #13 的事。
 *
 * 只在 debug 构建存在（与 [DebugCommandReceiver] 同一 source set，release 不带探针）：
 * PC 脚本 `tools/ex 06-overlay.ps1` 用 debug 广播驱动这里的加/撤，判定不在本进程做——
 * 窗口是否真的在背屏以 `dumpsys window` 为准，这里的日志只提供旁证与失败原因
 * （未授权 / 被系统拒绝的异常），每条都是 ASCII 锚点，方便解析层认。
 */
object OverlayProbe {

    /** `dumpsys window` 里找这块窗口的标题（tools/ex 解析层与 fixture 认这个 ASCII 标记）。 */
    const val WINDOW_TITLE = "RearCueOverlayProbe"

    /** 进程内唯一句柄：debug 广播无状态，加/撤要跨两次广播找到同一块窗口。 */
    @Volatile
    private var overlayWindow: WindowManager? = null

    @Volatile
    private var overlayView: View? = null

    /**
     * 加一块最小覆盖窗口；[displayId] 为空时加到运行时识别的背屏（[DisplaySource]），指定即覆盖。
     * 返回值只进日志，成败由设备事实说话（见类注释）。
     */
    @SuppressLint("InflateParams") // addView 的参数由本方法构造，不走 inflater
    fun add(context: Context, displayId: Int?) {
        val app = context.applicationContext
        // 未授权必须明确报告（验收点），不能让 addView 抛 SecurityException 了事。
        if (!Settings.canDrawOverlays(app)) {
            Log.w(LOG_TAG, "overlay add failed reason=no-system-alert-window canDrawOverlays=false")
            return
        }
        val manager = app.getSystemService(DisplayManager::class.java)
        val resolved = displayId ?: DisplaySource(app).rearDisplay()?.displayId
        val display = resolved?.let { manager?.getDisplay(it) }
        if (display == null) {
            Log.w(LOG_TAG, "overlay add failed reason=no-display requested=${displayId ?: "rear"}")
            return
        }

        val view = TextView(app).apply {
            text = "RearCue E9 overlay probe"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6323232.toInt()) // 深灰半透，区别于原生背屏的纯黑
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
        }
        val params = WindowManager.LayoutParams(
            320,
            160,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.RGBA_8888,
        ).apply {
            title = WINDOW_TITLE
            gravity = Gravity.CENTER
        }

        // API 30+ 的正规路径：为「这块屏上的这类窗口」建 window context，再取它的 WindowManager。
        // 直接用应用 context 的 WindowManager 加非 Activity 窗口，窗口会落主屏。
        try {
            val windowContext = app.createWindowContext(
                display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null,
            )
            val windowManager = windowContext.getSystemService(WindowManager::class.java)
            windowManager.addView(view, params)
            overlayWindow = windowManager
            overlayView = view
            Log.i(
                LOG_TAG,
                "overlay added display=${display.displayId} title=$WINDOW_TITLE " +
                    "flags=not-focusable+not-touchable+keep-screen-on",
            )
        } catch (e: Exception) {
            overlayWindow = null
            overlayView = null
            // BadTokenException / SecurityException / 系统策略拒绝都在这里显式报出，E9 靠它分诊。
            Log.w(LOG_TAG, "overlay add failed reason=${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 撤掉探针窗口；没加过就明说，不静默。 */
    fun remove() {
        val window = overlayWindow
        val view = overlayView
        if (window == null || view == null) {
            Log.w(LOG_TAG, "overlay absent reason=not-added")
            return
        }
        try {
            window.removeView(view)
            Log.i(LOG_TAG, "overlay removed")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "overlay remove failed reason=${e.javaClass.simpleName}: ${e.message}")
        } finally {
            overlayWindow = null
            overlayView = null
        }
    }
}
