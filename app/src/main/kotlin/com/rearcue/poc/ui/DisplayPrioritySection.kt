package com.rearcue.poc.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rearcue.poc.R
import com.rearcue.poc.core.DisplayPriority
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing

private fun movePriority(order: List<DisplayPriority>, priority: DisplayPriority, delta: Int): List<DisplayPriority>? {
    val from = order.indexOf(priority)
    val to = from + delta
    if (from < 0 || to !in order.indices) return null
    return order.toMutableList().apply { removeAt(from); add(to, priority) }
}

/** 从拖动手柄换位；四行等高，无嵌套滚动列表。无障碍提供同等的上移/下移动作。 */
@Composable
fun DisplayPrioritySection(order: List<DisplayPriority>, onChange: (List<DisplayPriority>) -> Unit) {
    val currentOrder by rememberUpdatedState(order)
    val currentOnChange by rememberUpdatedState(onChange)
    var dragging by remember { mutableStateOf<DisplayPriority?>(null) }
    val rowPixels = with(LocalDensity.current) { (64.dp + RearCueSpacing.sm).toPx() }
    val dragDescription = stringResource(R.string.display_priority_drag)
    val moveUp = stringResource(R.string.display_priority_up)
    val moveDown = stringResource(R.string.display_priority_down)
    SettingsSectionCard(title = stringResource(R.string.display_priority_title)) {
        Text(stringResource(R.string.display_priority_description), color = RearCueColors.onBackgroundSecondary,
            style = MaterialTheme.typography.bodySmall)
        order.forEachIndexed { index, priority ->
            key(priority) {
                val label = stringResource(when (priority) {
                    DisplayPriority.APPROVAL -> R.string.display_priority_approval
                    DisplayPriority.QUESTION -> R.string.display_priority_question
                    DisplayPriority.NOTIFICATION -> R.string.priority_notification
                    DisplayPriority.VOICE -> R.string.display_priority_voice
                })
                fun move(delta: Int): Boolean {
                    val changed = movePriority(currentOrder, priority, delta) ?: return false
                    currentOnChange(changed)
                    return true
                }
                Row(
                    Modifier.fillMaxWidth().height(64.dp).semantics {
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction(moveUp) { move(-1) })
                            if (index < order.lastIndex) add(CustomAccessibilityAction(moveDown) { move(1) })
                        }
                    },
                    horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}", color = RearCueColors.onBackgroundSecondary)
                    Text(label, Modifier.weight(1f), color = if (dragging == priority) RearCueColors.accent else RearCueColors.onBackground)
                    Box(Modifier.size(48.dp).semantics { contentDescription = "$dragDescription：$label" }
                        .pointerInput(priority, rowPixels) {
                            var distance = 0f
                            var gestureOrder = currentOrder
                            detectVerticalDragGestures(
                                onDragStart = { dragging = priority; distance = 0f; gestureOrder = currentOrder },
                                onDragEnd = { dragging = null },
                                onDragCancel = { dragging = null },
                            ) { change, amount ->
                                change.consume()
                                distance += amount
                                while (distance >= rowPixels || distance <= -rowPixels) {
                                    val delta = if (distance > 0) 1 else -1
                                    val changed = movePriority(gestureOrder, priority, delta)
                                    if (changed == null) { distance = 0f; break }
                                    gestureOrder = changed
                                    currentOnChange(changed)
                                    distance -= delta * rowPixels
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                        Text("≡", style = MaterialTheme.typography.headlineSmall, color = RearCueColors.onBackgroundSecondary)
                    }
                }
            }
        }
    }
}
