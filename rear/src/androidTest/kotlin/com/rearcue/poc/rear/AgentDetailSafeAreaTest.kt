package com.rearcue.poc.rear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 原文在正文直接可读；过程开合、长输出滚动与正文回执互不替代。 */
@RunWith(AndroidJUnit4::class)
class AgentDetailSafeAreaTest {
    @get:Rule
    val composeRule = createComposeRule()
    private var bodyTaps = 0

    @Test
    fun toolOriginalIsInlineAndOutsideCameraBand() {
        showMirror()
        assertOutsideCameraBand(composeRule.onNodeWithText(SUMMARY))
        assertOutsideCameraBand(composeRule.onNodeWithText(DETAIL, substring = true))
        composeRule.onNodeWithText("完整记录").assertDoesNotExist()
        composeRule.onNodeWithText("详细内容").assertDoesNotExist()
        composeRule.onNodeWithText(DETAIL, substring = true).performClick()
        composeRule.runOnIdle { assertEquals(1, bodyTaps) }
    }

    @Test
    fun completedProcessExpandsOriginalInBodyAndKeepsReplyVisible() {
        showMirror(AgentStatus.IDLE)
        composeRule.onNodeWithText(DETAIL, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("正式回答").assertExists()
        composeRule.onNodeWithText("▸ 过程", substring = true).performClick()
        assertOutsideCameraBand(composeRule.onNodeWithText(DETAIL, substring = true))
        composeRule.onNodeWithText("完整记录").assertDoesNotExist()
        composeRule.onNodeWithText("▾ 过程", substring = true).performClick()
        composeRule.onNodeWithText(DETAIL, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("正式回答").assertExists()
        composeRule.runOnIdle { assertEquals(0, bodyTaps) }
    }

    @Test
    fun longOriginalScrollsInMainBodyAndReplyRemainsReachable() {
        val original = (1..1500).joinToString("\n") { "  # output $it **literal**" }
        showMirror(original = original)
        composeRule.onNodeWithText("正式回答").assertIsDisplayed()
        composeRule.onNodeWithText(SUMMARY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(original, substring = true).assertExists()
        composeRule.onNodeWithText("完整记录").assertDoesNotExist()
        composeRule.onNodeWithText("正式回答").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, bodyTaps) }
    }

    private fun assertOutsideCameraBand(node: SemanticsNodeInteraction) {
        val window = composeRule.onNodeWithTag(WINDOW).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("详情文字进入相机带: $bounds", bounds.left >= window.left + CAMERA_BAND)
        assertTrue("详情文字越过右屏缘: $bounds", bounds.right <= window.right)
    }

    private fun showMirror(status: AgentStatus = AgentStatus.WORKING, original: String = DETAIL) {
        val choices = mutableStateOf<Map<String, Boolean>>(emptyMap())
        val follow = mutableStateOf(MirrorScrollPolicy.Follow.FOLLOWING)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(904.dp, 572.dp).testTag(WINDOW)) {
                    AgentMirrorLayer(
                        state = AgentSessionState(
                            sessionId = "detail-safe-area",
                            title = "详情避让验证",
                            status = status,
                            turns = listOf(
                                AgentTurn(
                                    role = AgentTurnRole.AGENT,
                                    text = SUMMARY,
                                    kind = AgentTurnKind.TOOL,
                                    detail = original,
                                    entryId = "tool-1",
                                ),
                                AgentTurn(role = AgentTurnRole.AGENT, text = "正式回答", entryId = "answer-1"),
                            ),
                        ),
                        rules = SafeArea(
                            contentRect = PxRect(CAMERA_BAND, 0, 904, 572),
                            driftBounds = PxOffset(0, 0),
                            windowWidth = 904,
                            windowHeight = 572,
                            cornerRadius = 97,
                            cutoutSides = PxPadding(CAMERA_BAND, 0),
                        ),
                        scroll = rememberScrollState(),
                        emptyReplyScroll = rememberScrollState(),
                        follow = follow.value,
                        onFollowChange = { follow.value = it },
                        interactive = true,
                        onBodyTap = { bodyTaps++ },
                        processChoices = choices.value,
                        onProcessChoice = { key, expanded -> choices.value = choices.value + (key to expanded) },
                    )
                }
            }
        }
    }

    private companion object {
        const val WINDOW = "detail-test-window"
        const val CAMERA_BAND = 296
        const val SUMMARY = "读取示例文件"
        const val DETAIL = "cat sample.txt\n# 示例输出\n  **保留原文符号与缩进**"
    }
}
