package com.rearcue.poc.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 图标入场日志锚词形判例（spec 0015 / 票 #147）：契约字符串与按包名生成器
 * 均按 byte 冻结，防止后续动画/验收收口时误改。
 */
class IconMotionLogContractTest {

    @Test
    fun `图标入场完整契约词形冻结`() {
        assertEquals("icon enter <pkg>", DashboardCore.LOG_ICON_ENTER_CONTRACT)
        assertEquals(IconMotionLogContract.CONTRACT, DashboardCore.LOG_ICON_ENTER_CONTRACT)
    }

    @Test
    fun `入场锚词按包名成型`() {
        assertEquals("icon enter com.example.app", IconMotionLogContract.enter("com.example.app"))
    }
}
