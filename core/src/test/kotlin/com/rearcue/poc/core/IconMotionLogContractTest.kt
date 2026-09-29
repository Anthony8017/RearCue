package com.rearcue.poc.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 图标出入场日志锚词形判例（spec 0015 / 票 #147 入场、#148 退场）：契约字符串与按包名
 * 生成器均按 byte 冻结，防止后续动画/验收收口时误改；入场词形不因退场加入而变。
 */
class IconMotionLogContractTest {

    @Test
    fun `图标出入场完整契约词形冻结`() {
        assertEquals(
            "icon enter <pkg>; icon exit <pkg>",
            DashboardCore.LOG_ICON_MOTION_CONTRACT,
        )
        assertEquals(IconMotionLogContract.CONTRACT, DashboardCore.LOG_ICON_MOTION_CONTRACT)
    }

    @Test
    fun `入场锚词按包名成型`() {
        assertEquals("icon enter com.example.app", IconMotionLogContract.enter("com.example.app"))
    }

    @Test
    fun `退场锚词按包名成型`() {
        assertEquals("icon exit com.example.app", IconMotionLogContract.exit("com.example.app"))
    }
}
