package com.rearcue.poc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rearcue.poc.R
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.agentmirror.AgentApprovePolicy
import com.rearcue.poc.design.*

/** 通知与 Agent 会话的二级页面；设置不在这里重复。 */
class MainContentActivity : ClipboardBridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val notifications = intent.getStringExtra("content") == NOTIFICATIONS
        setContent {
            val container = (application as RearCueApp).container
            val state by container.state.collectAsState()
            RearCueTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = RearCueColors.background) {
                    Column(modifier = Modifier.fillMaxSize().safeAreaPadding().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.md)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(onClick = ::finish) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back_cd))
                            }
                            Text(stringResource(if (notifications) R.string.home_notifications else R.string.home_sessions),
                                style = MaterialTheme.typography.headlineSmall)
                        }
                        if (notifications) {
                            IconSetCard(state)
                        } else {
                            SettingsSectionCard(title = stringResource(R.string.home_sessions)) {
                                val approvals = AgentApprovePolicy.visibleApprovals(
                                    state.agentRoster + listOfNotNull(state.agentDebugSession),
                                    state.agentCapabilities, state.agentApproveEnabled)
                                ApprovalBlock(approvals.filter { AgentApprovePolicy.isDebugSession(it.sessionId) }, null, container::sendAgentAction)
                                SessionLockList(state.sessionLock, state.agentRoster, container::setSessionLock,
                                    approvals, state.agentActionNote, container::sendAgentAction)
                            }
                        }
                    }
                }
            }
        }
    }
    companion object {
        const val NOTIFICATIONS = "notifications"
        const val AGENT = "agent"
        fun intentFor(context: Context, content: String) = Intent(context, MainContentActivity::class.java).putExtra("content", content)
    }
}
