package com.rearcue.poc.rear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import java.text.BreakIterator
import java.util.Locale
import kotlinx.coroutines.delay

internal class PseudoStreamReveal {
    data class Frame(val turns: List<AgentTurn>, val active: Boolean)

    private data class Pending(
        val text: String,
        val boundaries: List<Int>,
        val durationMs: Long,
        var startedAt: Long? = null,
        var paceMs: Long = durationMs,
    )

    private val known = mutableMapOf<String, AgentTurn>()
    private val pending = linkedMapOf<String, Pending>()
    private var initialized = false

    fun frame(
        observed: List<AgentTurn>,
        shown: List<AgentTurn>,
        visible: Boolean,
        nowMs: Long,
        historicalIds: Set<String> = emptySet(),
    ): Frame {
        if (!initialized || !visible) {
            pending.clear()
            initialized = true
        } else {
            val shownIds = shown.mapNotNullTo(hashSetOf()) { it.entryId }
            val lastKnownIndex = observed.indexOfLast { it.entryId != null && it.entryId in known }
            observed.forEachIndexed { index, turn ->
                val id = turn.entryId ?: return@forEachIndexed
                val previous = known[id]
                if (id in pending && (pending.getValue(id).text != turn.text || turn.open)) pending.remove(id)
                if (previous == null && index > lastKnownIndex && id in shownIds && id !in historicalIds && !turn.open &&
                    turn.text.isNotEmpty() && eligible(turn)
                ) {
                    val boundaries = revealBoundaries(turn.text)
                    pending[id] = Pending(turn.text, boundaries, (boundaries.size * 35L).coerceIn(180L, 2_000L))
                }
            }
            pending.keys.retainAll(shownIds)
            if (pending.size > 3) pending.clear()
        }
        known.clear()
        observed.forEach { turn -> turn.entryId?.let { known[it] = turn } }

        while (pending.isNotEmpty()) {
            val first = pending.entries.first()
            val item = first.value
            if (item.startedAt == null) item.startedAt = nowMs
            item.paceMs = item.paceMs.coerceAtMost(1_800L / pending.size)
            if (nowMs - item.startedAt!! < item.paceMs) break
            pending.remove(first.key)
        }
        val result = shown.map { turn ->
            val item = pending[turn.entryId] ?: return@map turn
            val start = item.startedAt
            val count = if (start == null) 0 else
                ((nowMs - start).coerceAtLeast(0L) * item.boundaries.size / item.paceMs + 1).toInt()
            val end = item.boundaries.getOrNull(count.coerceAtLeast(1) - 1) ?: item.text.length
            turn.copy(text = if (start == null) "" else item.text.substring(0, end))
        }
        return Frame(result, pending.isNotEmpty())
    }

    fun finish() { pending.clear() }

    private fun eligible(turn: AgentTurn): Boolean = turn.role == AgentTurnRole.AGENT && turn.kind in setOf(
        AgentTurnKind.ANSWER, AgentTurnKind.PROGRESS, AgentTurnKind.THINKING,
        AgentTurnKind.TOOL, AgentTurnKind.TOOL_RESULT,
    )
}

private fun revealBoundaries(text: String): List<Int> {
    val boundaries = mutableListOf<Int>()
    val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    var position = 0
    while (position < text.length) {
        val lineStart = position == 0 || text[position - 1] == '\n'
        val lineEnd = text.indexOf('\n', position).let { if (it == -1) text.length else it + 1 }
        val line = text.substring(position, lineEnd)
        val end = when {
            lineStart && (line.startsWith("```") || line.startsWith("~~~")) -> {
                val delimiter = line.take(3)
                val closing = text.indexOf("\n$delimiter", lineEnd)
                if (closing == -1) text.length else text.indexOf('\n', closing + 1).let {
                    if (it == -1) text.length else it + 1
                }
            }
            lineStart && line.contains('|') && lineEnd < text.length &&
                text.substring(lineEnd).lineSequence().first().contains('|') -> {
                var next = lineEnd
                while (next < text.length) {
                    val followingEnd = text.indexOf('\n', next).let { if (it == -1) text.length else it + 1 }
                    if (!text.substring(next, followingEnd).contains('|')) break
                    next = followingEnd
                }
                next
            }
            lineStart && (line.startsWith("    ") || line.startsWith("\t")) -> lineEnd
            text[position] == '[' || (text[position] == '!' && text.getOrNull(position + 1) == '[') -> {
                val close = text.indexOf("](", position + 1)
                if (close == -1) graphemes.following(position) else text.indexOf(')', close + 2).let {
                    if (it == -1) graphemes.following(position) else it + 1
                }
            }
            text[position] == '`' -> text.indexOf('`', position + 1).let {
                if (it == -1) graphemes.following(position) else it + 1
            }
            else -> {
                var next = graphemes.following(position)
                while (next < text.length && (text[next - 1] == '\u200D' || text[next] == '\u200D' ||
                    Character.getType(text.codePointAt(next)) == Character.NON_SPACING_MARK.toInt()
                )) next = graphemes.following(next)
                next
            }
        }
        position = end.coerceAtMost(text.length)
        boundaries += position
    }
    return boundaries
}

data class PseudoStreamPresentation(val turns: List<AgentTurn>, val active: Boolean, val finish: () -> Unit)

@Composable
fun rememberPseudoStreamTurns(
    sessionId: String?,
    observed: List<AgentTurn>,
    shown: List<AgentTurn>,
    visible: Boolean,
    historicalIds: Set<String> = emptySet(),
): PseudoStreamPresentation {
    val controller = remember(sessionId) { PseudoStreamReveal() }
    var revision by remember(controller) { mutableIntStateOf(0) }
    val frame = remember(controller, observed, shown, visible, historicalIds, revision) {
        controller.frame(observed, shown, visible, System.nanoTime() / 1_000_000, historicalIds)
    }
    LaunchedEffect(controller, frame.active, revision) {
        if (frame.active) {
            delay(32)
            revision++
        }
    }
    return PseudoStreamPresentation(frame.turns, frame.active) {
        controller.finish()
        revision++
    }
}
