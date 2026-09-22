package com.rearcue.poc.rear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RearTaskLocatorTest {

    // 逐字来自真机 `dumpsys activity activities`（session docs/poc-logs/20260922-181414-task-move，
    // E14 任务搬运轮）：同一份文本里本应用有两个任务，只有 t12988 带 RearDashboardActivity。
    private val realDump = """
        Display #0 (activities from top to bottom):
          * Task{7c20902 #12988 type=standard A=10333:com.rearcue.poc U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
            mLastPausedActivity: ActivityRecord{63006180 u0 com.rearcue.poc/.rear.RearDashboardActivity t12988}
            topResumedActivity=ActivityRecord{63006180 u0 com.rearcue.poc/.rear.RearDashboardActivity t12988}
            * Hist  #0: ActivityRecord{63006180 u0 com.rearcue.poc/.rear.RearDashboardActivity t12988}
              rootOfTask=true task=Task{7c20902 #12988 type=standard A=10333:com.rearcue.poc}
          * Task{3e6e9d0 #12986 type=standard A=10333:com.rearcue.poc U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
            * Hist  #0: ActivityRecord{24377149 u0 com.rearcue.poc/.ui.MainActivity t12986}
              rootOfTask=true task=Task{3e6e9d0 #12986 type=standard A=10333:com.rearcue.poc}
        ResumedActivity: ActivityRecord{63006180 u0 com.rearcue.poc/.rear.RearDashboardActivity t12988}
    """.trimIndent()

    // 逐字同源（session docs/poc-logs/20260923-001402-lock-survive）：只剩 MainActivity 任务的场景
    // （界面被回收、任务还在）——搬这个任务是任务搬运事务的既定语义。
    private val mainOnlyDump = """
        Display #0 (activities from top to bottom):
          * Task{cf23743 #13015 type=standard A=10336:com.rearcue.poc U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
            * Hist  #0: ActivityRecord{209104795 u0 com.rearcue.poc/.ui.MainActivity t13015}
              rootOfTask=true task=Task{cf23743 #13015 type=standard A=10336:com.rearcue.poc}
        ResumedActivity: ActivityRecord{209104795 u0 com.rearcue.poc/.ui.MainActivity t13015}
    """.trimIndent()

    @Test
    fun `优先取带 Dashboard 的任务（票 #22，取错任务即红）`() {
        assertEquals(12988, RearTaskLocator.findRootTaskId(realDump, "com.rearcue.poc"))
    }

    @Test
    fun `没有 Dashboard 任务时退回首任务，没有本应用任务时返回 null（绝不猜 id）`() {
        assertEquals(13015, RearTaskLocator.findRootTaskId(mainOnlyDump, "com.rearcue.poc"))
        assertNull(RearTaskLocator.findRootTaskId("empty dump", "com.rearcue.poc"))
        assertNull(RearTaskLocator.findRootTaskId(realDump, "com.other.app"))
    }

    @Test
    fun `任务是否带 Dashboard 可自检`() {
        assertTrue(RearTaskLocator.taskContainsDashboard(realDump, 12988))
        assertFalse(RearTaskLocator.taskContainsDashboard(realDump, 12986))
        assertFalse(RearTaskLocator.taskContainsDashboard(mainOnlyDump, 13015))
    }
}
