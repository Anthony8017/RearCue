package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals

/** 在屏事实（CONTEXT.md「Presence」）：三态判定逐条钉死——「已发出」不等于「已上屏」。 */
class DashboardPresenceTest {

    @Test
    fun `实例存在就是 ON_SCREEN，压过上屏在途`() {
        assertEquals(Presence.ON_SCREEN, DashboardPresence.of(instanceCount = 1, launchPending = false))
        assertEquals(Presence.ON_SCREEN, DashboardPresence.of(instanceCount = 2, launchPending = true))
    }

    @Test
    fun `上屏在途的空档是 LAUNCH_PENDING，既不算在屏也不算不在屏`() {
        assertEquals(Presence.LAUNCH_PENDING, DashboardPresence.of(instanceCount = 0, launchPending = true))
    }

    @Test
    fun `实例与在途都没有才是 ABSENT`() {
        assertEquals(Presence.ABSENT, DashboardPresence.of(instanceCount = 0, launchPending = false))
    }
}
