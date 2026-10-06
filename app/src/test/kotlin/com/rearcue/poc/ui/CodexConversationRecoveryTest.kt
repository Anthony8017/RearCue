package com.rearcue.poc.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexConversationRecoveryTest {
    @Test fun coldRosterArrivalRestoresSavedConversationBeforeCurrentMirror() {
        assertEquals(null, preferredCodexSessionId("recent", "mirror", emptyList()))
        assertEquals("recent", preferredCodexSessionId("recent", "mirror", listOf("mirror", "recent")))
    }

    @Test fun removedSavedConversationFallsBackToCurrentThenFirst() {
        assertEquals("mirror", preferredCodexSessionId("removed", "mirror", listOf("first", "mirror")))
        assertEquals("first", preferredCodexSessionId("removed", "removed", listOf("first", "second")))
    }

    @Test fun unfinishedOrUnknownCreationRequiresSamePromptRetryConfirmation() {
        assertTrue(shouldConfirmCodexCreation(true, "do the task", "  do the task  "))
        assertFalse(shouldConfirmCodexCreation(false, "do the task", "do the task"))
        assertFalse(shouldConfirmCodexCreation(true, "do the task", "different task"))
    }

    @Test fun lateCreationReceiptClearsOnlyTheSubmittedDraftRevision() {
        assertTrue(canClearCodexCreationDraft("old", 4, "old", 4))
        assertFalse(canClearCodexCreationDraft("old", 4, "next", 5))
        assertFalse(canClearCodexCreationDraft("old", 4, "old", 6))
    }
}
