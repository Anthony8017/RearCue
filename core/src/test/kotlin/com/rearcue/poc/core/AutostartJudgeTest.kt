package com.rearcue.poc.core

import com.rearcue.poc.core.AppOpMode.ALLOWED
import com.rearcue.poc.core.AppOpMode.IGNORED
import com.rearcue.poc.core.AppOpMode.OTHER
import com.rearcue.poc.core.AutostartState.DENIED
import com.rearcue.poc.core.AutostartState.GRANTED
import com.rearcue.poc.core.AutostartState.IN_DOUBT
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 降级判定测试（findings 票 #27「降级判定」）：两 op 读数 → 自启动状态。
 *
 * 口径：`MIUIOP(10008)` + `MIUIOP(10053)` 双 allow ⇔ 在册；写岔/第三态/读不到 ⇒ 状态存疑，
 * 绝不报健康。
 */
class AutostartJudgeTest {

    @Test
    fun `双 allow 判为已放行`() {
        assertEquals(GRANTED, AutostartJudge.judge(ALLOWED, ALLOWED))
    }

    @Test
    fun `双 ignore 判为未放行`() {
        assertEquals(DENIED, AutostartJudge.judge(IGNORED, IGNORED))
    }

    @Test
    fun `两 op 写岔判为状态存疑`() {
        assertEquals(IN_DOUBT, AutostartJudge.judge(ALLOWED, IGNORED))
        assertEquals(IN_DOUBT, AutostartJudge.judge(IGNORED, ALLOWED))
    }

    @Test
    fun `allow ignore 之外的第三态判为状态存疑`() {
        assertEquals(IN_DOUBT, AutostartJudge.judge(OTHER, OTHER))
        assertEquals(IN_DOUBT, AutostartJudge.judge(ALLOWED, OTHER))
        assertEquals(IN_DOUBT, AutostartJudge.judge(OTHER, IGNORED))
    }

    @Test
    fun `读不到判为状态存疑`() {
        assertEquals(IN_DOUBT, AutostartJudge.judge(null, null))
        assertEquals(IN_DOUBT, AutostartJudge.judge(ALLOWED, null))
        assertEquals(IN_DOUBT, AutostartJudge.judge(null, ALLOWED))
        assertEquals(IN_DOUBT, AutostartJudge.judge(IGNORED, null))
    }
}
