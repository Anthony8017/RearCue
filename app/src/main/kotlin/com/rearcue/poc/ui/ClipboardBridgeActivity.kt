package com.rearcue.poc.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.agentmirror.ClipboardBridgeImporter
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
            val manager = clipboard ?: return
            val description = manager.primaryClipDescription
            container.inspectBridgeClipboard(
                timestamp = description?.timestamp,
                read = {
                    when {
                        description == null -> if (!manager.hasPrimaryClip()) ClipboardBridgeImporter.Content(null) else null
                        !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) -> ClipboardBridgeImporter.Content(null)
                        else -> {
                            // Read text only; never resolve copied URIs or trigger a content provider.
                            val clip = manager.primaryClip
                            if (clip == null || clip.description.timestamp != description.timestamp) null
                            else ClipboardBridgeImporter.Content(clip.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString())
                        }
                    }
                },
                focused = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() },
                force = force,
            )
        } catch (_: SecurityException) {
            // The system can revoke focus between the check and the read.
        }
    }
}
