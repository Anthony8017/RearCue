package com.rearcue.poc.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 图标出入场日志锚词形判例（spec 0015 / 票 #147 入场、#148 退场）：契约字符串与按包名
 * 生成器均按 byte 冻结，防止后续动画/验收收口时误改；入场词形不因退场加入而变。
 */
class IconMotionLogContractTest {

    @Test
    fun `图标入场完整契约词形冻结`() {
        assertEquals("icon enter <pkg>", DashboardCore.LOG_ICON_ENTER_CONTRACT)
        assertEquals(IconMotionLogContract.ENTER, DashboardCore.LOG_ICON_ENTER_CONTRACT)
    }

    @Test
    fun `图标退场完整契约词形冻结`() {
        assertEquals("icon exit <pkg>", DashboardCore.LOG_ICON_EXIT_CONTRACT)
        assertEquals(IconMotionLogContract.EXIT, DashboardCore.LOG_ICON_EXIT_CONTRACT)
    }

    @Test
    fun `出入场合起来是同一份冻结契约`() {
        assertEquals(
            "icon enter <pkg>; icon exit <pkg>",
            IconMotionLogContract.CONTRACT,
        )
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
