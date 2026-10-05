package com.rearcue.poc.rear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用真实条目点按路径检查详情文字的位置，不把相机带的截屏像素误当成可读区域。 */
@RunWith(AndroidJUnit4::class)
class AgentDetailSafeAreaTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toolDetailKeepsHeadingBodyAndCloseOutsideCameraBand() {
        showMirror()
        composeRule.onNodeWithText(SUMMARY, substring = true).performClick()

        assertOutsideCameraBand(composeRule.onNodeWithText("详细内容", useUnmergedTree = true))
        assertOutsideCameraBand(composeRule.onNodeWithText(DETAIL, substring = true, useUnmergedTree = true))
        assertOutsideCameraBand(composeRule.onNodeWithText("×"))

        composeRule.onNodeWithText("×").performClick()
        composeRule.onNodeWithText("详细内容").assertDoesNotExist()
    }

    @Test
    fun fullRecordKeepsHeadingAndBodyOutsideCameraBand() {
        showMirror()
        composeRule.onNodeWithText("完整记录").performClick()

        val window = composeRule.onNodeWithTag(WINDOW).fetchSemanticsNode().boundsInRoot
        val heading = composeRule.onAllNodesWithText("完整记录", useUnmergedTree = true).fetchSemanticsNodes().last()
        assertTrue(
            "完整记录标题进入相机带: ${heading.boundsInRoot}",
            heading.boundsInRoot.left >= window.left + CAMERA_BAND,
        )
        assertOutsideCameraBand(composeRule.onNodeWithText(DETAIL, substring = true, useUnmergedTree = true))
        composeRule.onNodeWithText("×").performClick()
        composeRule.onNodeWithText(SUMMARY, substring = true).assertExists()
    }

    private fun assertOutsideCameraBand(node: SemanticsNodeInteraction) {
        val window = composeRule.onNodeWithTag(WINDOW).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("详情文字进入相机带: $bounds", bounds.left >= window.left + CAMERA_BAND)
        assertTrue("详情文字越过右屏缘: $bounds", bounds.right <= window.right)
    }

    private fun showMirror() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(904.dp, 572.dp).testTag(WINDOW)) {
                    AgentMirrorLayer(
                        state = AgentSessionState(
                            sessionId = "detail-safe-area",
                            title = "详情避让验证",
                            status = AgentStatus.WORKING,
                            turns = listOf(
                                AgentTurn(
                                    role = AgentTurnRole.AGENT,
                                    text = SUMMARY,
                                    kind = AgentTurnKind.TOOL,
                                    detail = DETAIL,
                                    entryId = "tool-1",
                                ),
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
                        follow = MirrorScrollPolicy.Follow.FOLLOWING,
                        onFollowChange = {},
                        interactive = true,
                        onBodyTap = {},
                    )
                }
            }
        }
    }

    private companion object {
        const val WINDOW = "detail-test-window"
        const val CAMERA_BAND = 296
        const val SUMMARY = "读取示例文件"
        const val DETAIL = "cat sample.txt\n示例输出"
    }
}
