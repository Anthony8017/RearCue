package com.rearcue.poc.rear

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/** 采集当前所有屏幕，供 [RearDisplayLocator] 做纯 Kotlin 判定。 */
class DisplaySource(private val context: Context) {

    fun screens(): List<ScreenInfo> {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return manager.displays.map { display ->
            ScreenInfo(
                displayId = display.displayId,
                name = display.name,
                flags = display.flags,
                isDefault = display.displayId == Display.DEFAULT_DISPLAY,
            )
        }
    }

    /** 运行时识别的背屏；识别不到返回 null（接口预留给非小米设备）。 */
    fun rearDisplay(): ScreenInfo? = RearDisplayLocator.locate(screens()).also { rear ->
        Log.i(TAG, "screens=${screens().map { "${it.displayId}:${it.flags}" }} rear=${rear?.displayId}")
    }

    companion object {
        private const val TAG = "RearCue"
    }
}
