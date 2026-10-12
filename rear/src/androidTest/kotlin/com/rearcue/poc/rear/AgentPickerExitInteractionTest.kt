package com.rearcue.poc.rear

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.BridgeLinkStatus
import com.rearcue.poc.core.ContentPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AgentPickerExitInteractionTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun notificationBlankReceivesTouchAfterPickerExitWithoutAnotherSnapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertNotEquals("com.rearcue.poc", context.packageName)
        val previousSurface = AgentFeed.pageSurface.value
        val previousState = AgentFeed.state.value
        val previousDisplay = AgentFeed.display.value
        val previousLink = AgentFeed.link.value
        val previousApprove = AgentFeed.approve.value
        val previousActionNote = AgentFeed.actionNote.value
        val previousIcons = IconSetFeed.iconSet.value
        val previousCounts = IconSetFeed.unreadCounts.value
        val previousCharging = ChargingFeed.charging.value
        val previousDetail = DetailFeed.detail.value
        val session = AgentSessionState(
            sessionId = "picker-exit-interaction",
            title = "列表退场点击验证",
            status = AgentStatus.IDLE,
        )
        val display = AgentSessionDisplay.forState(session)
        val rows = listOf(
            AgentPickerRow(null, context.getString(R.string.agent_picker_auto), null, null),
            AgentPickerRow(session.sessionId, display.title, null, session.status),
        )
        val shortcuts = AtomicInteger()
        val pageTaps = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                RearDashboardHost.expectShow()
                RearDashboardHost.onContentPageTap { pageTaps.incrementAndGet() }
                RearDashboardHost.onSessionNotificationShortcut {
                    shortcuts.incrementAndGet()
                    AgentFeed.markImmediatePageChange()
                    AgentFeed.publish(ContentPage.NOTIFICATION, session, display, pickerRows = rows)
                }
                IconSetFeed.publish(emptyList(), emptyMap())
                ChargingFeed.publish(false)
                DetailFeed.publish(null)
                AgentFeed.publishLink(BridgeLinkStatus.DISABLED)
                AgentFeed.publishApprove(null, null)
                AgentFeed.publish(ContentPage.AGENT, session, display, pickerOpen = true, pickerRows = rows)
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            val command = "am start -W -n ${context.packageName}/${RearDashboardActivity::class.java.name}"
            val launch = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            }
            assertTrue(launch, launch.contains("Status: ok"))
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithText(session.title!!).fetchSemanticsNode() }.isSuccess
            }
            rule.onNodeWithText(session.title!!).assertIsDisplayed()
            val picker = rule.onNodeWithContentDescription(context.getString(R.string.agent_picker_cd))
            val pickerBounds = picker.fetchSemanticsNode().boundsInRoot
            val autoBounds = rule.onNodeWithText(context.getString(R.string.agent_picker_auto))
                .fetchSemanticsNode().boundsInRoot
            picker.performTouchInput {
                click(Offset(pickerBounds.width * 0.85f, autoBounds.center.y - pickerBounds.top))
            }
            rule.runOnIdle {
                assertEquals(1, shortcuts.get())
                assertEquals(ContentPage.NOTIFICATION, AgentFeed.pageSurface.value.contentPage)
                assertTrue(AgentFeed.pageSurface.value.immediateTransition)
            }
            rule.mainClock.advanceTimeBy(1_000L)
            SystemClock.sleep(300L)
            rule.waitForIdle()
            val exitSnapshot = AgentFeed.pageSurface.value

            rule.onRoot().performTouchInput { click(center) }

            rule.runOnIdle {
                assertEquals(1, pageTaps.get())
                assertSame(exitSnapshot, AgentFeed.pageSurface.value)
                assertTrue(exitSnapshot.immediateTransition)
            }
        } finally {
            instrumentation.runOnMainSync {
                RearDashboardHost.finishAll()
                RearDashboardHost.onContentPageTap(null)
                RearDashboardHost.onSessionNotificationShortcut(null)
                IconSetFeed.publish(previousIcons, previousCounts)
                ChargingFeed.publish(previousCharging)
                DetailFeed.publish(previousDetail)
                AgentFeed.publishLink(previousLink)
                AgentFeed.publishApprove(previousApprove, previousActionNote)
                if (previousSurface.immediateTransition) AgentFeed.markImmediatePageChange()
                AgentFeed.publish(
                    contentPage = previousSurface.contentPage,
                    state = previousState,
                    display = previousDisplay,
                    pickerOpen = previousSurface.pickerOpen,
                    pickerRows = previousSurface.pickerRows,
                    readingResetVersion = previousSurface.readingResetVersion,
                    restoredReadingSessionId = previousSurface.restoredReadingSessionId,
                    interrupted = previousSurface.interrupted,
                )
            }
        }
    }
}
