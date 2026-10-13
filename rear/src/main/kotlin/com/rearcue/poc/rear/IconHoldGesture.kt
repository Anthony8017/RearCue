package com.rearcue.poc.rear

import kotlin.math.hypot

/**
 * 背屏通知图标长按（spec 0035）。
 *
 * 只判断手指与按下时那枚图标的关系，不决定清哪些通知。半秒前松开是短按；
 * 手指自己移出图标才取消；图标挪走但手指几乎没动，仍算原来那一枚。
 */
internal object IconHoldGesture {
    const val PREVIEW_MS = 180L
    const val COMMIT_MS = 500L
    const val HOLD_SCALE = 0.86f

    data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun contains(x: Float, y: Float): Boolean {
            if (right <= left || bottom <= top) return true
            return x in left..right && y in top..bottom
        }
    }
}

internal class IconHoldSession(
    private val previewMs: Long = IconHoldGesture.PREVIEW_MS,
    private val commitMs: Long = IconHoldGesture.COMMIT_MS,
) {
    private var app: String? = null
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var slop = 0f
    private var phase = Phase.IDLE

    private enum class Phase { IDLE, TRACKING, PREVIEW, COMMITTED, DONE }

    sealed interface Outcome {
        data object None : Outcome
        data class Preview(val app: String) : Outcome
        data class Commit(val app: String) : Outcome
        data class Tap(val app: String) : Outcome
        data object Cancel : Outcome
    }

    fun down(app: String, at: Long, x: Float, y: Float, slop: Float) {
        this.app = app
        downAt = at
        downX = x
        downY = y
        this.slop = slop
        phase = Phase.TRACKING
    }

    fun move(x: Float, y: Float, bounds: IconHoldGesture.Bounds): Outcome {
        if (phase != Phase.TRACKING && phase != Phase.PREVIEW) return Outcome.None
        val moved = hypot(x - downX, y - downY) > slop
        if (moved && !bounds.contains(x, y)) {
            phase = Phase.DONE
            return Outcome.Cancel
        }
        return Outcome.None
    }

    fun tick(at: Long): Outcome {
        if (phase != Phase.TRACKING && phase != Phase.PREVIEW) return Outcome.None
        val elapsed = at - downAt
        val target = app ?: return Outcome.None
        if (elapsed >= commitMs) {
            phase = Phase.COMMITTED
            return Outcome.Commit(target)
        }
        if (elapsed >= previewMs && phase == Phase.TRACKING) {
            phase = Phase.PREVIEW
            return Outcome.Preview(target)
        }
        return Outcome.None
    }

    fun up(at: Long, x: Float, y: Float, bounds: IconHoldGesture.Bounds): Outcome {
        if (phase == Phase.COMMITTED || phase == Phase.DONE || phase == Phase.IDLE) {
            phase = Phase.DONE
            return Outcome.None
        }
        val moved = hypot(x - downX, y - downY) > slop
        val target = app
        phase = Phase.DONE
        if (target == null) return Outcome.None
        if (moved && !bounds.contains(x, y)) return Outcome.Cancel
        if (at - downAt >= commitMs) return Outcome.Commit(target)
        return Outcome.Tap(target)
    }

    /** 页面被换走或图标拆掉。半秒后已经算开始，不再取消。 */
    fun abandon(): Outcome {
        if (phase != Phase.TRACKING && phase != Phase.PREVIEW) return Outcome.None
        phase = Phase.DONE
        return Outcome.Cancel
    }
}
