package com.rearcue.poc.agentmirror

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.notify.AGENT_ALERT_NOTIFICATION_ID
import com.rearcue.poc.notify.cancelAgentAlert
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteApprovalDeviceTest {
    @Test
    fun disablingApprovalRemovesRealNotificationActionsAndRejectsSavedPendingIntent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as RearCueApp).container
        SystemClock.sleep(500)
        val original = container.state.value
        assumeTrue("Mirror, alerts and approval must already be enabled", original.agentEnabled && original.agentAlertEnabled && original.agentApproveEnabled)
        assumeTrue("The reserved debug session must be unused", original.agentDebugSession == null)
        val manager = context.getSystemService(NotificationManager::class.java)
        val previousPermission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        val preserved = manager.activeNotifications.filter { it.id == AGENT_ALERT_NOTIFICATION_ID && it.tag != "debug" }
        var adoptedPermission = false
        var injectedDebug = false
        fun debugNotification() = manager.activeNotifications.singleOrNull { it.tag == "debug" && it.id == AGENT_ALERT_NOTIFICATION_ID }?.notification
        try {
            if (previousPermission != PackageManager.PERMISSION_GRANTED) {
                instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
                adoptedPermission = true
            }
            assumeTrue("Notification authority must be available without changing user permissions", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled())
            instrumentation.runOnMainSync {
                injectedDebug = true
                container.debugInjectAgentState("waiting", "RemoteApprovalDeviceTest", "instrumentation approval fixture", null)
            }
            await("The container did not post a waiting notification with actions") { debugNotification()?.actions.orEmpty().isNotEmpty() }
            val savedAction = requireNotNull(requireNotNull(debugNotification()).actions.first().actionIntent)
            instrumentation.runOnMainSync { container.setAgentApproveEnabled(false) }
            await("Disabling approval did not remove notification actions") { debugNotification()?.let { it.actions.orEmpty().isEmpty() } == true }
            await("Approval setting did not become disabled") { !container.state.value.agentApproveEnabled }
            savedAction.send()
            await("The saved PendingIntent was not rejected") { container.state.value.agentActionNote != null }
            assertEquals(AgentStatus.WAITING_FOR_APPROVAL, container.state.value.agentDebugSession?.status)
            assertTrue(requireNotNull(debugNotification()).actions.orEmpty().isEmpty())
        } finally {
            try {
                if (injectedDebug) {
                    instrumentation.runOnMainSync {
                        container.debugClearAgentState()
                        container.setAgentApproveEnabled(original.agentApproveEnabled)
                    }
                    try {
                        await("Approval setting was not restored") { runBlocking { AgentMirrorSettingsStore.loadApprovalEnabled(context) == original.agentApproveEnabled } }
                    } finally {
                        val active = manager.activeNotifications.associateBy { it.tag to it.id }
                        preserved.forEach { saved ->
                            active[saved.tag to saved.id]?.takeIf { it.notification.`when` == saved.notification.`when` }?.let { current ->
                                manager.notify(current.tag, current.id, Notification.Builder.recoverBuilder(context, current.notification).setActions(*saved.notification.actions.orEmpty()).setOnlyAlertOnce(true).build())
                            }
                        }
                    }
                }
            } finally {
                if (injectedDebug) cancelAgentAlert(context, "debug")
                if (adoptedPermission) instrumentation.uiAutomation.dropShellPermissionIdentity()
                assertEquals(previousPermission, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
                assertEquals(original.agentEnabled, container.state.value.agentEnabled)
                assertEquals(original.agentAlertEnabled, container.state.value.agentAlertEnabled)
                assertEquals(original.agentAlertVibrate, container.state.value.agentAlertVibrate)
                assertEquals(original.bridgeAddress, container.state.value.bridgeAddress)
                assertEquals(original.bridgeAddressSource, container.state.value.bridgeAddressSource)
                assertEquals(null, container.state.value.agentDebugSession)
            }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue(message, condition())
    }
}
