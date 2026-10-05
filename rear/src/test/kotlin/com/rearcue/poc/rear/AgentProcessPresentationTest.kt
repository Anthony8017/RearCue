package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnKind
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.agent.SessionReadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentProcessPresentationTest {
    private fun user(id: String, text: String = id, round: String? = null) =
        AgentTurn(AgentTurnRole.USER, text, entryId = id, roundId = round)
    private fun entry(id: String, kind: AgentTurnKind, round: String? = null, ended: Boolean = false) =
        AgentTurn(AgentTurnRole.AGENT, id, kind = kind, entryId = id, roundId = round, roundComplete = ended)
    private fun project(turns: List<AgentTurn>, status: AgentStatus = AgentStatus.IDLE, choices: Map<String, Boolean> = emptyMap()) =
        AgentProcessPresentation.project("session", turns, status, choices)

    @Test fun `完成只收起过程 提问回答错误批准和题目不隐藏`() {
        val turns = listOf(user("prompt"), entry("think", AgentTurnKind.THINKING),
            entry("tool", AgentTurnKind.TOOL), entry("result", AgentTurnKind.TOOL_RESULT),
            entry("answer", AgentTurnKind.ANSWER), entry("error", AgentTurnKind.ERROR),
            entry("approval", AgentTurnKind.APPROVAL), entry("question", AgentTurnKind.QUESTION))
        val shown = project(turns)
        assertEquals(listOf("prompt", "answer", "error", "approval", "question"),
            shown.turns.filterNot { it.entryId in shown.headers }.map { it.entryId })
        assertEquals(3, shown.headers.values.single().count)
        assertFalse(shown.headers.values.single().expanded)
        assertEquals(8, turns.size)
    }

    @Test fun `工作和批准等待保持展开 失败收起`() {
        val turns = listOf(user("p"), entry("t", AgentTurnKind.TOOL))
        for (status in listOf(AgentStatus.WORKING, AgentStatus.WAITING_FOR_APPROVAL)) {
            assertTrue(project(turns, status).headers.values.single().expanded)
        }
        assertFalse(project(turns, AgentStatus.ERROR).headers.values.single().expanded)
        assertTrue(AgentProcessPresentation.project("session", turns, AgentStatus.IDLE, emptyMap(), pendingQuestion = true)
            .headers.values.single().expanded)
    }

    @Test fun `每轮一组 新轮展开 已结束旧轮收起`() {
        val shown = project(listOf(user("p1"), entry("t1", AgentTurnKind.THINKING),
            user("p2"), entry("t2", AgentTurnKind.TOOL)), AgentStatus.WORKING)
        assertEquals(listOf(false, true), shown.headers.values.map { it.expanded })
        assertEquals(listOf("p1", "p2", "t2"), shown.turns.filterNot { it.entryId in shown.headers }.map { it.entryId })
    }

    @Test fun `来源回合标识让问卷作答不拆轮 展开仍按原顺序`() {
        val turns = listOf(user("p", round = "r"), entry("t", AgentTurnKind.THINKING, "r"),
            entry("a", AgentTurnKind.ANSWER, "r"), user("reply", round = "r"),
            entry("tool", AgentTurnKind.TOOL, "r"))
        val shown = project(turns, AgentStatus.WORKING)
        assertEquals(1, shown.headers.size)
        assertEquals(turns, shown.turns.filterNot { it.entryId in shown.headers })
    }

    @Test fun `手动选择跨重入优先于结束状态 不污染另一会话或新回合`() {
        val turns = listOf(user("p", round = "r"), entry("t", AgentTurnKind.TOOL, "r", ended = true))
        val key = project(turns).headers.values.single().choiceKey
        val choices = mapOf(key to true)
        assertTrue(project(turns, choices = choices).headers.values.single().expanded)
        assertFalse(AgentProcessPresentation.project("other", turns, AgentStatus.IDLE, choices).headers.values.single().expanded)
        val next = turns + listOf(user("next", round = "next"), entry("t2", AgentTurnKind.TOOL, "next"))
        assertEquals(listOf(true, true), project(next, AgentStatus.WORKING, choices).headers.values.map { it.expanded })
    }

    @Test fun `冻结的过程不会被后台完成标记收起`() {
        val frozen = listOf(user("p", round = "r"), entry("t", AgentTurnKind.THINKING, "r"))
        val live = frozen.map { it.copy(roundComplete = true) }
        val shown = MirrorScrollPolicy.effectiveTurns(MirrorScrollPolicy.Follow.READING, frozen, live)
        assertTrue(project(shown, AgentStatus.WORKING).headers.values.single().expanded)
        assertFalse(project(live).headers.values.single().expanded)
    }

    @Test fun `新来源开始事实仍在时 已结束记录不能重新展开`() {
        val turns = listOf(user("p", round = "r"), entry("t", AgentTurnKind.TOOL, "r", ended = true))
        assertFalse(project(turns, AgentStatus.WORKING).headers.values.single().expanded)
    }

    @Test fun `只有空闲未阅普通条目适用入场锚点`() {
        val row = AgentPickerRow("s", "会话", null, AgentStatus.IDLE, SessionReadState.UNREAD)
        assertTrue(AgentEntryAnchor.eligible(row))
        assertFalse(AgentEntryAnchor.eligible(row.copy(readState = SessionReadState.READ)))
        for (status in listOf(AgentStatus.WORKING, AgentStatus.ERROR, AgentStatus.WAITING_FOR_APPROVAL)) {
            assertFalse(AgentEntryAnchor.eligible(row.copy(status = status)))
        }
        assertFalse(AgentEntryAnchor.eligible(row.copy(sessionId = null)))
        assertFalse(AgentEntryAnchor.eligible(null))
    }

    @Test fun `锚点只取最后机主消息 包含作答而非最后回答`() {
        val reply = user("reply", "问题\n我的答案", "r")
        assertEquals(reply, AgentEntryAnchor.latestPrompt(listOf(user("p"), reply, entry("a", AgentTurnKind.ANSWER))))
        assertEquals(null, AgentEntryAnchor.latestPrompt(listOf(entry("a", AgentTurnKind.ANSWER))))
    }

    @Test fun `短内容末尾留白让提问首行准确落在渐隐带下方`() {
        val geometry = AgentEntryAnchor.geometry(572, 90, 120, 10)
        assertEquals(90, geometry.topPadding)
        assertEquals(372, geometry.bottomPadding)
        val scrollMax = geometry.topPadding + 120 + geometry.bottomPadding - 572
        assertTrue(scrollMax >= geometry.scroll)
        assertEquals(90, geometry.topPadding + 10 - geometry.scroll)
    }

    @Test fun `长历史锚点可达且不加无用末尾留白`() {
        val geometry = AgentEntryAnchor.geometry(572, 90, 2000, 900)
        assertEquals(0, geometry.bottomPadding)
        assertEquals(900, geometry.scroll)
        assertEquals(90, geometry.topPadding + 900 - geometry.scroll)
    }

    @Test fun `固定阅读触底或因开合钳底都不会恢复跟随 只有按钮恢复`() {
        val reading = MirrorScrollPolicy.Follow.READING
        assertEquals(reading, MirrorScrollPolicy.onValueChange(reading, 20, 100, 100))
        assertEquals(reading, MirrorScrollPolicy.onValueChange(reading, 100, 0, 0))
        assertFalse(MirrorScrollPolicy.shouldFollowNewOutput(reading))
        assertEquals(MirrorScrollPolicy.Follow.FOLLOWING, MirrorScrollPolicy.onResumeTap())
    }
}
