package com.rearcue.poc.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rearcue.poc.RearCueApp

/** 主屏二级对话页；会话选择只影响本页，不改背屏 Session Lock。 */
class CodexConversationActivity : ComponentActivity() {
    private var requestedSession by mutableStateOf<String?>(null)
    private var requestRevision by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedSession = if (savedInstanceState == null) intent.getStringExtra(EXTRA_SESSION) else null
        enableEdgeToEdge()
        setContent {
            val container = (application as RearCueApp).container
            val state by container.state.collectAsStateWithLifecycle()
            val lifecycleState by lifecycle.currentStateFlow.collectAsState()
            val client = remember { BridgeCodexConversationClient(container.bridgeClient) }
            CodexConversationScreen(
                client = client,
                bridgeStatus = state.bridgeLinkStatus,
                sessions = state.agentRoster,
                initialSessionId = state.agentState?.sessionId,
                explicitSessionId = requestedSession,
                explicitSessionRevision = requestRevision,
                preferences = getSharedPreferences("codex_remote", Context.MODE_PRIVATE),
                foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
                isDeviceUnlocked = { getSystemService(KeyguardManager::class.java)?.isDeviceLocked != true },
                onBack = ::finish,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedSession = intent.getStringExtra(EXTRA_SESSION)
        requestRevision++
    }

    companion object {
        private const val EXTRA_SESSION = "codex-conversation-session"
        fun intentFor(context: Context, sessionId: String? = null): Intent =
            Intent(context, CodexConversationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                sessionId?.let { putExtra(EXTRA_SESSION, it) }
            }
    }
}
