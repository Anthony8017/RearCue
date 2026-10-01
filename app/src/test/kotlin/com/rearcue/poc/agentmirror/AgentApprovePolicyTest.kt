package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.ActionReceipt
import com.rearcue.poc.agent.AgentApproveShape
import com.rearcue.poc.agent.AgentPendingOption
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.SessionActionKind
import com.rearcue.poc.agent.SessionActionRequest
import com.rearcue.poc.agent.SourceCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Remote Approval 判定与契约判例（spec 0018-4 / ADR 0009）：
 * - 批准入门（[AgentApprovePolicy]）：等待中 ∧（调试伪会话 ∨ 来源声明 approve）——
 *   只提醒来源不显示任何批准入口（AC4）；
 * - 动作契约（[SessionActionKind] / [SessionActionRequest]）：**恰好三类**、无自由文字
 *   入口（AC5，红线判例）；select 必带选项；
 * - 回执（[ActionReceipt]）：词表恒定、失败即终态（AC1/AC3：不悬挂、不自动重试）。
 */
class AgentApprovePolicyTest {

    private val capsWithApprove = SourceCapabilities(
        mapOf("claude" to setOf(SourceCapabilities.WAITING, SourceCapabilities.APPROVE)),
    )

    private fun session(
        id: String = "bridge:s1",
        source: String? = "claude",
        status: AgentStatus = AgentStatus.WAITING_FOR_APPROVAL,
        options: List<AgentPendingOption> = emptyList(),
    ) = AgentSessionState(
        sessionId = id,
        source = source,
        status = status,
        pendingOptions = options,
    )

    // ---------- 批准入门（AC4：只提醒来源不显示批准入口；伪来源恒可批准） ----------

    @Test
    fun `等待中且来源声明 approve 才可批`() {
        assertTrue(AgentApprovePolicy.canApprove(session(), capsWithApprove))
        // 来源没声明 approve（codex 只提醒）：入口不开
        assertFalse(
            AgentApprovePolicy.canApprove(session(source = "codex"), capsWithApprove),
            "只提醒来源不显示任何批准入口",
        )
        // 未知来源 / 空来源：缺省保守
        assertFalse(AgentApprovePolicy.canApprove(session(source = null), capsWithApprove))
        assertFalse(AgentApprovePolicy.canApprove(session(source = "watson"), capsWithApprove))
    }

    @Test
    fun `ZCode经桥也按能力表开批准入口_与其余来源同判定`() {
        val zcodeSession = session(id = "bridge:zcode-1", source = "zcode")
        val zcodeApprove = SourceCapabilities(
            mapOf("zcode" to setOf(SourceCapabilities.WAITING, SourceCapabilities.APPROVE)),
        )
        val zcodeWaitingOnly = SourceCapabilities(mapOf("zcode" to setOf(SourceCapabilities.WAITING)))

        assertTrue(AgentApprovePolicy.canApprove(zcodeSession, zcodeApprove))
        assertEquals(listOf(zcodeSession), AgentApprovePolicy.visibleApprovals(listOf(zcodeSession), zcodeApprove))
        assertFalse(AgentApprovePolicy.canApprove(zcodeSession, zcodeWaitingOnly))
        assertEquals(emptyList(), AgentApprovePolicy.visibleApprovals(listOf(zcodeSession), zcodeWaitingOnly))
    }

    @Test
    fun `DSH 按能力表开批准入口——桥声明 approve 才可批（票 176）`() {
        val dshSession = session(id = "bridge:dsh-1", source = "dsh")
        val dshApprove = SourceCapabilities(
            mapOf("dsh" to setOf(SourceCapabilities.WAITING, SourceCapabilities.APPROVE)),
        )
        val dshWaitingOnly = SourceCapabilities(mapOf("dsh" to setOf(SourceCapabilities.WAITING)))
        // 桥声明 approve（插件在线）：三处入口都该开（入口显隐走同一判定）
        assertTrue(AgentApprovePolicy.canApprove(dshSession, dshApprove))
        assertEquals(listOf(dshSession), AgentApprovePolicy.visibleApprovals(listOf(dshSession), dshApprove))
        // 插件失联 ⇒ 桥收回 approve：入口全关（缺省保守）
        assertFalse(AgentApprovePolicy.canApprove(dshSession, dshWaitingOnly))
        assertEquals(emptyList(), AgentApprovePolicy.visibleApprovals(listOf(dshSession), dshWaitingOnly))
    }

    @Test
    fun `非等待态一律无批准入口`() {
        for (status in listOf(AgentStatus.WORKING, AgentStatus.IDLE, AgentStatus.ERROR)) {
            assertFalse(
                AgentApprovePolicy.canApprove(session(status = status), capsWithApprove),
                "status=$status 不该有批准入口",
            )
        }
    }

    @Test
    fun `调试伪会话恒可批准（验收链不靠真桥）`() {
        val debug = session(id = AgentApprovePolicy.DEBUG_SESSION_ID, source = "dsh")
        assertTrue(AgentApprovePolicy.isDebugSession(debug.sessionId))
        assertTrue(
            AgentApprovePolicy.canApprove(debug, SourceCapabilities.DEFAULTS),
            "伪来源必须声明 approve 以便验收链可跑（AC4）",
        )
        // 伪会话不在等待态照旧不开（canApprove 的等待前提不豁免）
        assertFalse(
            AgentApprovePolicy.canApprove(
                debug.copy(status = AgentStatus.IDLE),
                SourceCapabilities.DEFAULTS,
            ),
        )
    }

    @Test
    fun `待批准列表只留可批的等待会话`() {
        val roster = listOf(
            session(id = "bridge:ok"),
            session(id = "bridge:codex", source = "codex"),
            session(id = "bridge:busy", status = AgentStatus.WORKING),
            session(id = AgentApprovePolicy.DEBUG_SESSION_ID, source = null),
        )
        val visible = AgentApprovePolicy.visibleApprovals(roster, capsWithApprove)
        assertEquals(listOf("bridge:ok", AgentApprovePolicy.DEBUG_SESSION_ID), visible.map { it.sessionId })
    }

    @Test
    fun `提问类与确认类的入口形态分流`() {
        assertTrue(
            AgentApprovePolicy.isQuestion(session(options = listOf(AgentPendingOption("1", "方案 A")))),
        )
        assertFalse(AgentApprovePolicy.isQuestion(session()))
    }

    // ---------- 动作契约（AC5：恰好三类、无自由文字入口） ----------

    @Test
    fun `动作恰好三类——自由文字入口在契约里不存在`() {
        assertEquals(
            setOf(SessionActionKind.APPROVE, SessionActionKind.REJECT, SessionActionKind.SELECT),
            SessionActionKind.entries.toSet(),
        )
        // wire 词 round-trip；自由文字/未知词一律认不出（回执走 bad-request）
        for (kind in SessionActionKind.entries) {
            assertEquals(kind, SessionActionKind.fromWire(kind.wire()))
        }
        for (bad in listOf("free-text", "prompt", "type here", "", "  ", null)) {
            assertEquals(null, SessionActionKind.fromWire(bad), "不该认出 $bad")
        }
    }

    @Test
    fun `动作请求 JSON——select 带选项，其余不带`() {
        assertEquals(
            """{"sessionId":"bridge:s1","requestId":"ra-1","action":"approve"}""",
            SessionActionRequest("bridge:s1", "ra-1", SessionActionKind.APPROVE).toJson(),
        )
        assertEquals(
            """{"sessionId":"bridge:s1","requestId":"ra-2","action":"select","optionId":"opt-7"}""",
            SessionActionRequest("bridge:s1", "ra-2", SessionActionKind.SELECT, optionId = "opt-7").toJson(),
        )
        // 特殊字符转义不炸（引号/换行）
        val escaped = SessionActionRequest("s\"1", "r\n1", SessionActionKind.REJECT).toJson()
        assertTrue(escaped.contains("\\\""))
        assertTrue(escaped.contains("\\n"))
    }

    // ---------- 回执（AC1/AC3：词表恒定、不悬挂、失败即终态） ----------

    @Test
    fun `回执词表恒定——受理与失败两态分明`() {
        assertTrue(ActionReceipt.ACCEPTED.accepted)
        for (fail in listOf(
            ActionReceipt.UNKNOWN_SESSION,
            ActionReceipt.UNSUPPORTED,
            ActionReceipt.BAD_REQUEST,
            ActionReceipt.MALFORMED,
            ActionReceipt.TIMEDOUT,
        )) {
            assertFalse(fail.accepted, "${fail.name} 应是失败终态")
        }
    }

    @Test
    fun `回执 wire 归一——认不出的词一律 MALFORMED（当失败处理不悬挂）`() {
        assertEquals(ActionReceipt.ACCEPTED, ActionReceipt.fromWire("accepted"))
        assertEquals(ActionReceipt.UNKNOWN_SESSION, ActionReceipt.fromWire("unknown-session"))
        assertEquals(ActionReceipt.UNSUPPORTED, ActionReceipt.fromWire("unsupported"))
        assertEquals(ActionReceipt.BAD_REQUEST, ActionReceipt.fromWire("bad-request"))
        for (bad in listOf("oops", "", "  ", null)) {
            assertEquals(ActionReceipt.MALFORMED, ActionReceipt.fromWire(bad))
        }
    }

    // ---------- 远程批准开关（spec 0018 §五 / review 2026-09-30） ----------

    @Test
    fun `远程批准开关关掉后三处入口全关（伪会话同口径不豁免）`() {
        assertFalse(AgentApprovePolicy.canApprove(session(), capsWithApprove, approveEnabled = false))
        assertEquals(
            emptyList(),
            AgentApprovePolicy.visibleApprovals(listOf(session()), capsWithApprove, approveEnabled = false),
        )
        val debug = session(id = AgentApprovePolicy.DEBUG_SESSION_ID)
        assertFalse(AgentApprovePolicy.canApprove(debug, capsWithApprove, approveEnabled = false))
        // 开关开＝照常可批（默认档不回归）
        assertTrue(AgentApprovePolicy.canApprove(session(), capsWithApprove, approveEnabled = true))
    }

    // ---------- 按钮组形状（review 2026-09-30：分流一处收口） ----------

    @Test
    fun `按钮组形状分流一处收口_确认类与提问类`() {
        assertEquals(AgentApproveShape.Confirm, AgentApprovePolicy.shapeFor(session()))
        val question = session(options = listOf(AgentPendingOption("1", "方案 A")))
        assertEquals(
            AgentApproveShape.Question(listOf(AgentPendingOption("1", "方案 A"))),
            AgentApprovePolicy.shapeFor(question),
        )
    }
}
