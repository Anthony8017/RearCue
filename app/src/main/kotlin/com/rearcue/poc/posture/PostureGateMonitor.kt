package com.rearcue.poc.posture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rearcue.poc.LOG_TAG

/**
 * 姿态稳定窗（spec 0006 / 票 #53）：接近传感器读数需持续 [stableMs] 才提交一次姿态翻转，
 * 投与撤两个方向对称防抖——翻面过渡/拿起抖动不产生高频投送/归还震荡。
 *
 * 纯 Kotlin（时间戳由调用方注入，JVM 可测）；首次提交也过窗：进程启动后首个稳定读数
 * （<1s 量级）定初始姿态，此前 core 侧门控初值放行（见 DashboardCore.faceDown 注释）。
 * 提交含义：near = 倒扣（主屏朝下），far = 正放。
 */
class PostureStableWindow(private val stableMs: Long) {

    /** 防抖窗定档（数值实现期定，spec 0006）：800ms 滤掉拿起/放下的过渡抖动（人手自然翻面停顿 >1s），
     *  又让指示延迟感不明显（<1s）。 */
    private var pendingNear: Boolean? = null
    private var pendingSinceMs = 0L
    private var committedNear: Boolean? = null

    /**
     * 喂一个传感器读数；返回本次新提交的姿态（true=倒扣/false=正放），null=无提交。
     * 时间戳单调即可（SystemClock.elapsedRealtime）。
     */
    fun onReading(near: Boolean, nowMs: Long): Boolean? {
        if (pendingNear != near) {
            pendingNear = near
            pendingSinceMs = nowMs
            return null
        }
        if (nowMs - pendingSinceMs < stableMs) return null
        if (committedNear == near) return null
        committedNear = near
        return near
    }
}

/**
 * Posture Gate 的 Android 胶水（spec 0006 / 票 #53）：TYPE_PROXIMITY 持续监听 →
 * 「倒扣=近」判定（values[0] < maxRange/2，二值传感器上即 0=近/满量程=远）→
 * [PostureStableWindow] 防抖 → 姿态提交回调。零新权限。
 *
 * 这里不做决策，只搬「传感器事实 → 防抖后的姿态布尔」；门控语义在 :core。
 * 无接近传感器的设备：只留一条日志、不提交（core 门恒开，产品可用优先）。
 */
class PostureGateMonitor(
    context: Context,
    private val stableMs: Long = STABLE_MS_DEFAULT,
    private val onPostureCommitted: (faceDown: Boolean) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val window = PostureStableWindow(stableMs)
    private val handler = Handler(Looper.getMainLooper())

    /** 二值接近传感器的近/远分界：满量程一半。 */
    private var nearThreshold = Float.MAX_VALUE

    /** 最近一次读数（近/远）；稳定到期 tick 以它做「无新事件=未变化」的合成读数。 */
    private var lastNear: Boolean? = null

    /** 稳定到期检查：on-change 传感器只在变化时送事件，静止时没有下一读数来确认稳定。 */
    private val stabilityTick = Runnable {
        val near = lastNear ?: return@Runnable
        commitIfAny(near, android.os.SystemClock.elapsedRealtime())
    }

    /** 进程存活期间持续监听（接近传感器功耗极低，spec 0006 的「持续判定」）；不做反注册。 */
    fun start() {
        if (proximity == null) {
            Log.w(LOG_TAG, "无接近传感器：Posture Gate 恒开放行")
            return
        }
        nearThreshold = proximity.maximumRange / 2f
        sensorManager?.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL, handler)
        Log.i(LOG_TAG, "Posture Gate 监听启动 stableMs=$stableMs maxRange=${proximity.maximumRange}")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_PROXIMITY) return
        val value = event.values.firstOrNull() ?: return
        val near = value < nearThreshold
        lastNear = near
        commitIfAny(near, android.os.SystemClock.elapsedRealtime())
        // 每次变化重排稳定到期检查（从本读数起算 stableMs）。
        handler.removeCallbacks(stabilityTick)
        handler.postDelayed(stabilityTick, stableMs)
    }

    private fun commitIfAny(near: Boolean, nowMs: Long) {
        val committed = window.onReading(near, nowMs)
        if (committed != null) {
            Log.i(LOG_TAG, "姿态提交 ${if (committed) "倒扣" else "正放"} near=$near")
            onPostureCommitted(committed)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** 稳定窗定档：800ms（投与撤对称）。 */
        const val STABLE_MS_DEFAULT = 800L
    }
}
