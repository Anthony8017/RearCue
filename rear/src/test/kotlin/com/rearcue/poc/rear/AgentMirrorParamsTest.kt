package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Agent Mirror 版式参数判例（#113 AC「版式参数单元测试随布局调整同步」）：
 * 状态字号/动作行截断随状态词删除后，仅剩正文字号一档——钉死档位值，
 * 改档必过此例。
 */
class AgentMirrorParamsTest {

    @Test
    fun `会话输出正文字号档钉死`() {
        assertEquals(16f, AgentMirrorParams.REPLY_SP_BASE)
    }
}
