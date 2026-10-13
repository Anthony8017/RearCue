package com.rearcue.poc.rear

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalViewConfiguration

private class IconHoldBounds {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    fun update(origin: Offset, width: Float, height: Float) {
        left = origin.x
        top = origin.y
        right = origin.x + width
        bottom = origin.y + height
    }

    fun snapshot() = IconHoldGesture.Bounds(left, top, right, bottom)
}

/**
 * 图标上的点按/长按。按下后的整段手势都留在这枚图标上，
 * 避免松手时被当成空白点按，或被补位滑来的图标接走。
 */
@Composable
internal fun Modifier.iconHoldGesture(
    onPreview: () -> Unit,
    onCancel: () -> Unit,
    onTap: () -> Unit,
    onCommit: () -> Unit,
): Modifier {
    val preview = rememberUpdatedState(onPreview)
    val cancel = rememberUpdatedState(onCancel)
    val tap = rememberUpdatedState(onTap)
    val commit = rememberUpdatedState(onCommit)
    val slop = LocalViewConfiguration.current.touchSlop
    val bounds = remember { IconHoldBounds() }
    return this
        .onGloballyPositioned { coordinates ->
            bounds.update(
                coordinates.positionInRoot(),
                coordinates.size.width.toFloat(),
                coordinates.size.height.toFloat(),
            )
        }
        .pointerInput(slop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val session = IconHoldSession()
                val downBounds = bounds.snapshot()
                val downRoot = Offset(downBounds.left + down.position.x, downBounds.top + down.position.y)
                val start = SystemClock.uptimeMillis()
                session.down("", start, downRoot.x, downRoot.y, slop)
                var finished = false
                while (!finished) {
                    val elapsed = SystemClock.uptimeMillis() - start
                    val wait = when {
                        elapsed < IconHoldGesture.PREVIEW_MS -> IconHoldGesture.PREVIEW_MS - elapsed
                        elapsed < IconHoldGesture.COMMIT_MS -> IconHoldGesture.COMMIT_MS - elapsed
                        else -> null
                    }
                    val event = if (wait == null) {
                        awaitPointerEvent()
                    } else {
                        withTimeoutOrNull(wait.coerceAtLeast(1L)) { awaitPointerEvent() }
                    }
                    if (event == null) {
                        when (session.tick(SystemClock.uptimeMillis())) {
                            is IconHoldSession.Outcome.Preview -> preview.value()
                            is IconHoldSession.Outcome.Commit -> commit.value()
                            else -> Unit
                        }
                        continue
                    }
                    val change = event.changes.firstOrNull() ?: continue
                    change.consume()
                    val current = bounds.snapshot()
                    val root = Offset(current.left + change.position.x, current.top + change.position.y)
                    if (!change.pressed) {
                        when (session.up(SystemClock.uptimeMillis(), root.x, root.y, current)) {
                            is IconHoldSession.Outcome.Tap -> {
                                cancel.value()
                                tap.value()
                            }
                            is IconHoldSession.Outcome.Commit -> commit.value()
                            IconHoldSession.Outcome.Cancel -> cancel.value()
                            else -> Unit
                        }
                        finished = true
                    } else if (session.move(root.x, root.y, current) is IconHoldSession.Outcome.Cancel) {
                        cancel.value()
                        while (true) {
                            val rest = awaitPointerEvent()
                            rest.changes.forEach { it.consume() }
                            if (rest.changes.none { it.pressed }) break
                        }
                        finished = true
                    }
                }
            }
        }
}
