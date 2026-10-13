package com.rearcue.poc.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rearcue.poc.RearCueApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Only focused main-display pages inspect the current clipboard. No background permission. */
abstract class ClipboardBridgeActivity : ComponentActivity() {
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val container get() = (application as RearCueApp).container
    private val changed = ClipboardManager.OnPrimaryClipChangedListener { checkBridgeClipboard() }
    private var observing = false
    private var stateJob: Job? = null

    override fun onResume() {
        super.onResume()
        stateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                container.state.collect { checkBridgeClipboard() }
            }
        }
        if (!observing) {
            clipboard?.addPrimaryClipChangedListener(changed)
            observing = true
        }
        checkBridgeClipboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkBridgeClipboard()
    }

    override fun onPause() {
        stateJob?.cancel()
        stateJob = null
        if (observing) clipboard?.removePrimaryClipChangedListener(changed)
        observing = false
        super.onPause()
    }

    protected fun checkBridgeClipboard(force: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !hasWindowFocus() ||
            !container.canInspectBridgeClipboard()) return
        try {
            val description = clipboard?.primaryClipDescription
            if (description == null) {
                container.inspectBridgeClipboard(null, force)
                return
            }
            if (!container.shouldReadBridgeClipboard(description.timestamp, force)) return
            val textClip = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            // Read text only; never resolve copied URIs or trigger a content provider.
            val text = if (textClip) clipboard?.primaryClip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString() else null
            container.inspectBridgeClipboard(text, force)
        } catch (_: SecurityException) {
            // The system can revoke focus between the check and the read.
        }
    }
}
