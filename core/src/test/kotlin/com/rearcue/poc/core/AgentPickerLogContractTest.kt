package com.rearcue.poc.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 0016 新增日志锚的词形判例（票 #155 保锁锚 / 票 #156 会话选择器锚）：契约字符串与
 * 生成器均按 byte 冻结（沿 [ContentPageLogContractTest] 判例），实机验收链按词形断言，
 * 文档收口时不得误改。
 */
class AgentPickerLogContractTest {

    @Test
    fun `选择器完整契约词形冻结`() {
        assertEquals(
            "agent picker open; agent picker close <reason>; agent picker select <sessionId>",
            DashboardCore.LOG_AGENT_PICKER_CONTRACT,
        )
        assertEquals(AgentPickerLogContract.CONTRACT, DashboardCore.LOG_AGENT_PICKER_CONTRACT)
    }

    @Test
    fun `选择器锚词按理由与会话键成型`() {
        assertEquals("agent picker open", AgentPickerLogContract.open())
        assertEquals("agent picker close toggle", AgentPickerLogContract.close(AgentPickerLogContract.REASON_TOGGLE))
        assertEquals("agent picker close wfa", AgentPickerLogContract.close(AgentPickerLogContract.REASON_WFA))
        assertEquals("agent picker close page", AgentPickerLogContract.close(AgentPickerLogContract.REASON_PAGE))
        assertEquals("agent picker close select", AgentPickerLogContract.close(AgentPickerLogContract.REASON_SELECT))
        assertEquals("agent picker select bridge:codex-1", AgentPickerLogContract.select("bridge:codex-1"))
        assertEquals("agent picker select auto", AgentPickerLogContract.select(AgentPickerLogContract.SELECT_AUTO))
    }

    @Test
    fun `桥来源保锁锚词形冻结`() {
        assertEquals(
            "session lock held <sessionId> bridge-roster-unknown",
            DashboardCore.LOG_SESSION_LOCK_HELD_CONTRACT,
        )
        // 清锁锚词形不因 #155 分源口径改动（实机验收链两条锚并存）
        assertEquals("session lock cleared <sessionId>", DashboardCore.LOG_SESSION_LOCK_CONTRACT)
    }
}
