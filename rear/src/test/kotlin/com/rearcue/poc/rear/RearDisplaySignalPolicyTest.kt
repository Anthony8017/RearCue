package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RearDisplaySignalPolicyTest {

    @Test
    fun `原生背屏亮起时即使已有 Dashboard 也必须重投`() {
        assertTrue(
            RearDisplaySignalPolicy.shouldRetake(
                RearDisplaySignals.SUB_SCREEN_ON,
                dashboardInstanceCount = 1,
            ),
        )
    }

    @Test
    fun `其他归属信号在 Dashboard 仍存在时不重复投送`() {
        assertFalse(
            RearDisplaySignalPolicy.shouldRetake(
                RearDisplaySignals.SUB_SCREEN_OFF,
                dashboardInstanceCount = 1,
            ),
        )
        assertFalse(
            RearDisplaySignalPolicy.shouldRetake(
                "android.intent.action.SCREEN_OFF",
                dashboardInstanceCount = 1,
            ),
        )
    }

    @Test
    fun `Dashboard 已消失时所有归属信号都允许恢复`() {
        assertTrue(
            RearDisplaySignalPolicy.shouldRetake(
                RearDisplaySignals.SUB_SCREEN_OFF,
                dashboardInstanceCount = 0,
            ),
        )
        assertTrue(
            RearDisplaySignalPolicy.shouldRetake(
                "android.intent.action.SCREEN_ON",
                dashboardInstanceCount = 0,
            ),
        )
    }

    @Test
    fun `合并窗口内保留首次原生背屏亮起信号`() {
        assertFalse(
            RearDisplaySignalPolicy.shouldForward(
                action = RearDisplaySignals.SUB_SCREEN_OFF,
                sinceLastForwardMs = 100,
                lastForwardedAction = RearDisplaySignals.SUB_SCREEN_OFF,
                debounceMs = 1_000,
            ),
        )
        assertTrue(
            RearDisplaySignalPolicy.shouldForward(
                action = RearDisplaySignals.SUB_SCREEN_ON,
                sinceLastForwardMs = 100,
                lastForwardedAction = RearDisplaySignals.SUB_SCREEN_OFF,
                debounceMs = 1_000,
            ),
        )
        assertFalse(
            RearDisplaySignalPolicy.shouldForward(
                action = RearDisplaySignals.SUB_SCREEN_ON,
                sinceLastForwardMs = 100,
                lastForwardedAction = RearDisplaySignals.SUB_SCREEN_ON,
                debounceMs = 1_000,
            ),
        )
        assertTrue(
            RearDisplaySignalPolicy.shouldForward(
                action = RearDisplaySignals.SUB_SCREEN_ON,
                sinceLastForwardMs = 1_001,
                lastForwardedAction = RearDisplaySignals.SUB_SCREEN_ON,
                debounceMs = 1_000,
            ),
        )
    }
}
