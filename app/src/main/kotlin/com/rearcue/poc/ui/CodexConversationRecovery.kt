package com.rearcue.poc.ui

/** Resolve saved selection after the cold roster arrives, before falling back to the mirror. */
internal fun preferredCodexSessionId(saved: String?, current: String?, available: List<String>): String? =
    saved?.takeIf { it in available } ?: current?.takeIf { it in available } ?: available.firstOrNull()

internal fun shouldConfirmCodexCreation(unknown: Boolean, attemptedPrompt: String?, prompt: String): Boolean =
    unknown && attemptedPrompt == prompt.trim()

internal fun canClearCodexCreationDraft(
    submittedDraft: String, submittedRevision: Int, currentDraft: String?, currentRevision: Int,
): Boolean = submittedDraft == currentDraft && submittedRevision == currentRevision
