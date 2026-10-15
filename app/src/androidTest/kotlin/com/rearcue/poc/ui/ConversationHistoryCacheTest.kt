package com.rearcue.poc.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationHistoryCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("conversation_history_cache_test", Context.MODE_PRIVATE)

    @Before fun clear() {
        preferences.edit().clear().commit()
    }

    @Test fun roundTripPreservesLoadedPageAndEvictsOldestSession() {
        var now = 0L
        val cache = ConversationHistoryCache(preferences) { now++ }
        val turn = AgentTurn(
            role = AgentTurnRole.AGENT,
            text = "历史回答",
            ts = 3L,
            kind = AgentTurnKind.ANSWER,
            entryId = "answer-3",
            roundId = "round-1",
            roundComplete = true,
        )
        cache.write("session-a", listOf(turn), "older-token")
        val restored = cache.read("session-a")!!
        assertEquals("older-token", restored.nextCursor)
        assertEquals(turn, restored.turns.single())

        repeat(8) { index -> cache.write("session-$index", listOf(turn.copy(entryId = "answer-$index")), null) }
        assertNull(cache.read("session-a"))
        assertEquals("answer-7", cache.read("session-7")!!.turns.single().entryId)
    }
}
