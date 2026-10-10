package com.rearcue.poc.rear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 独立测试 Activity；不接 PC 桥、不改真实会话或手机偏好。 */
class AgentProcessFoldUiTest {
    @get:Rule val rule = createComposeRule()
    private val follow = mutableStateOf(MirrorScrollPolicy.Follow.FOLLOWING)
    private val choices = mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var bodyTaps = 0
    private val initial = listOf(
        turn("prompt", "机主消息", AgentTurnKind.PROMPT, "r1"),
        turn("think", "思考正文", AgentTurnKind.THINKING, "r1"),
        turn("tool", "工具摘要", AgentTurnKind.TOOL, "r1"),
        turn("answer", "正式回答", AgentTurnKind.ANSWER, "r1"),
    )
    private val state = mutableStateOf(AgentSessionState("ui-session", title = "过程折叠验证", status = AgentStatus.WORKING, turns = initial))

    private fun turn(id: String, text: String, kind: AgentTurnKind, round: String) = AgentTurn(
        role = if (kind == AgentTurnKind.PROMPT) AgentTurnRole.USER else AgentTurnRole.AGENT,
        text = text, kind = kind, entryId = id, roundId = round,
    )

    private fun show(anchor: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(904.dp, 572.dp)) {
                    AgentMirrorLayer(
                        state = state.value,
                        rules = SafeArea(
                            contentRect = PxRect(296, 0, 904, 572), driftBounds = PxOffset(0, 0),
                            windowWidth = 904, windowHeight = 572, cornerRadius = 97,
                            cutoutSides = PxPadding(296, 0),
                        ),
                        scroll = rememberScrollState(), emptyReplyScroll = rememberScrollState(),
                        follow = follow.value, onFollowChange = { follow.value = it },
                        interactive = true, onBodyTap = { bodyTaps++ },
                        processChoices = choices.value,
                        onProcessChoice = { key, expanded -> choices.value = choices.value + (key to expanded) },
                        entryRequest = if (anchor && follow.value == MirrorScrollPolicy.Follow.READING) {
                            AgentEntryAnchor.Request("ui-session", 1)
                        } else null,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun manualToggleKeepsAnswerAndDoesNotSendBodyReceipt() {
        show()
        rule.onNodeWithText("▾ 过程", substring = true).performClick()
        rule.onNodeWithText("思考正文").assertDoesNotExist()
        rule.onNodeWithText("工具摘要").assertDoesNotExist()
        rule.onNodeWithText("正式回答").assertExists()
        rule.onNodeWithText("▸ 过程", substring = true).performClick()
        rule.onNodeWithText("思考正文").assertExists()
        rule.onNodeWithText("工具摘要").assertExists()
        rule.runOnIdle { assertEquals(0, bodyTaps) }
    }

    @Test fun completionAndFailureFoldButLeaveErrorVisible() {
        show()
        rule.runOnIdle { state.value = state.value.copy(status = AgentStatus.IDLE) }
        rule.onNodeWithText("▸ 过程", substring = true).assertExists()
        rule.onNodeWithText("思考正文").assertDoesNotExist()
        val next = initial.map { it.copy(roundComplete = true) } + listOf(
            turn("p2", "下一轮消息", AgentTurnKind.PROMPT, "r2"),
            turn("t2", "新轮过程", AgentTurnKind.THINKING, "r2"),
        )
        rule.runOnIdle { state.value = state.value.copy(status = AgentStatus.WORKING, turns = next) }
        rule.onNodeWithText("新轮过程").assertExists()
        rule.runOnIdle {
            state.value = state.value.copy(status = AgentStatus.ERROR,
                turns = next + turn("error", "单独可见的错误", AgentTurnKind.ERROR, "r2"))
        }
        rule.onNodeWithText("新轮过程").assertDoesNotExist()
        rule.onNodeWithText("单独可见的错误").assertExists()
        rule.runOnIdle { assertEquals(MirrorScrollPolicy.Follow.FOLLOWING, follow.value) }
    }

    @Test fun anchoredReadingFreezesUpdatesUntilResumeButton() {
        follow.value = MirrorScrollPolicy.Follow.READING
        state.value = state.value.copy(status = AgentStatus.IDLE)
        show(anchor = true)
        rule.runOnIdle {
            state.value = state.value.copy(status = AgentStatus.WORKING, turns = initial + listOf(
                turn("p2", "新增机主消息", AgentTurnKind.PROMPT, "r2"),
                turn("a2", "新增答复", AgentTurnKind.ANSWER, "r2"),
            ))
        }
        rule.onNodeWithText("机主消息").assertExists()
        rule.onNodeWithText("新增机主消息").assertDoesNotExist()
        rule.onNodeWithText("↓").performClick()
        rule.onNodeWithText("新增机主消息").assertExists()
        rule.onNodeWithText("新增答复").assertExists()
        rule.runOnIdle {
            assertEquals(MirrorScrollPolicy.Follow.FOLLOWING, follow.value)
            assertEquals(0, bodyTaps)
        }
    }
}
