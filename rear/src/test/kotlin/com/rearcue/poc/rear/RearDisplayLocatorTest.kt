package com.rearcue.poc.rear

import com.rearcue.poc.core.DashboardEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 背屏识别与投送命令的行为测试：只断言「屏幕集合 → 判定结果」「效果 → shell 命令」。
 *
 * flag 用 AOSP `Display` 的**真实数值**（PRESENTATION=8、OWN_DISPLAY_GROUP=256）与本机实测的
 * 主屏/背屏掩码，而不是 `1 shl n`——否则被测代码位次写错时测试会跟着一起错（本轮踩过）。
 */
class RearDisplayLocatorTest {

    private val presentation = 8
    private val ownDisplayGroup = 256
    private val secure = 2
    private val trusted = 128

    /** 本机实测值（docs/poc-findings.md）：主屏 16515、背屏 16779。 */
    private val mainScreenFlags = 16515
    private val rearScreenFlags = 16779

    private fun mainScreen() = ScreenInfo(
        displayId = 0,
        name = "内置屏幕",
        flags = secure or trusted,
        isDefault = true,
    )

    private fun rearScreen() = ScreenInfo(
        displayId = 1,
        name = "内置屏幕",
        flags = secure or trusted or presentation or ownDisplayGroup,
        isDefault = false,
    )

    @Test
    fun `按本机实测 flag 值识别背屏`() {
        val main = mainScreen().copy(flags = mainScreenFlags)
        val rear = rearScreen().copy(flags = rearScreenFlags)

        assertEquals(rear, RearDisplayLocator.locate(listOf(main, rear)))
    }

    @Test
    fun `识别非默认且带 PRESENTATION 与 OWN_DISPLAY_GROUP 的屏幕`() {
        assertEquals(rearScreen(), RearDisplayLocator.locate(listOf(mainScreen(), rearScreen())))
    }

    @Test
    fun `只有主屏时返回 null`() {
        assertNull(RearDisplayLocator.locate(listOf(mainScreen())))
    }

    @Test
    fun `空屏幕列表返回 null`() {
        assertNull(RearDisplayLocator.locate(emptyList()))
    }

    @Test
    fun `默认屏幕即使带 flag 也不判为背屏`() {
        val weirdMain = mainScreen().copy(flags = presentation or ownDisplayGroup)

        assertNull(RearDisplayLocator.locate(listOf(weirdMain)))
    }

    @Test
    fun `缺 PRESENTATION 不判为背屏`() {
        val hdmi = ScreenInfo(displayId = 2, name = "HDMI", flags = ownDisplayGroup, isDefault = false)

        assertNull(RearDisplayLocator.locate(listOf(mainScreen(), hdmi)))
    }

    @Test
    fun `缺 OWN_DISPLAY_GROUP 不判为背屏`() {
        val cast = ScreenInfo(displayId = 2, name = "Cast", flags = presentation, isDefault = false)

        assertNull(RearDisplayLocator.locate(listOf(mainScreen(), cast)))
    }

    @Test
    fun `多个候选时取第一个匹配，不看 displayId 大小`() {
        val first = rearScreen().copy(displayId = 7)
        val second = rearScreen().copy(displayId = 2)

        assertEquals(7, RearDisplayLocator.locate(listOf(mainScreen(), first, second))?.displayId)
    }
}

class RearProjectionCommandsTest {

    private val pkg = "com.rearcue.poc"
    private val component = "com.rearcue.poc/com.rearcue.poc.ui.RearDashboardActivity"

    @Test
    fun `投送命令显式指定 display 与组件名`() {
        val plan = RearProjectionCommands.project(pkg, displayId = 1)

        assertEquals(
            listOf("am start --display 1 --activity-reorder-to-front -n $component"),
            plan.commands,
        )
    }

    @Test
    fun `投送命令带校验命令，用运行时识别到的 displayId`() {
        val plan = RearProjectionCommands.project(pkg, displayId = 3)

        assertEquals("dumpsys activity activities | grep -A2 'Display #3'", plan.verify)
        assertTrue(plan.describe.contains("--display 3"))
    }

    @Test
    fun `退出命令结束本应用任务`() {
        assertEquals(listOf("am force-stop $pkg"), RearProjectionCommands.exit(pkg).commands)
    }

    @Test
    fun `LaunchDashboard 映射为投送`() {
        val plan = RearProjectionCommands.forEffect(
            DashboardEffect.LaunchDashboard(setOf("com.tencent.mm")),
            packageName = pkg,
            displayId = 1,
        )

        assertEquals(RearProjectionCommands.project(pkg, 1), plan)
    }

    @Test
    fun `UpdateIconSet 不需要 shell 动作`() {
        assertNull(
            RearProjectionCommands.forEffect(
                DashboardEffect.UpdateIconSet(setOf("com.tencent.mm")),
                packageName = pkg,
                displayId = 1,
            ),
        )
    }

    @Test
    fun `ExitDashboard 映射为退出`() {
        assertEquals(
            RearProjectionCommands.exit(pkg),
            RearProjectionCommands.forEffect(DashboardEffect.ExitDashboard, pkg, displayId = 1),
        )
    }

    @Test
    fun `Degrade 不产生投送动作`() {
        assertNull(RearProjectionCommands.forEffect(DashboardEffect.Degrade, pkg, displayId = 1))
    }
}

/**
 * 校验「真的上屏了吗」：`am start --display` 的系统日志里 aborted 和成功都打印 Starting，
 * 只有 dumpsys 的任务栈能区分，所以按 Display 分块找组件名。
 */
class RearProjectionVerifierTest {

    private val component = "com.rearcue.poc/com.rearcue.poc.rear.RearDashboardActivity"

    private val dumpsysWithDashboard = """
        Display #1 (activities from top to bottom):
          * Task{5b4bb8e #12808 type=standard A=10332:com.rearcue.poc}
            topResumedActivity=ActivityRecord{192297616 u0 $component t12808}
        Display #0 (activities from top to bottom):
          * Task{fb1b689 #3 type=home}
    """.trimIndent()

    private val dumpsysNativeOnly = """
        Display #1 (activities from top to bottom):
          * Task{fb1b689 #3 type=home}
            * Task{3be36cb #4 type=home A=10205:com.xiaomi.subscreencenter}
              mLastPausedActivity: ActivityRecord{43766460 u0 com.xiaomi.subscreencenter/.SubScreenLauncher t4}
        Display #0 (activities from top to bottom):
          * Task{91dd8ab #12798 type=standard A=10332:com.rearcue.poc}
    """.trimIndent()

    @Test
    fun `背屏任务栈里有 Dashboard 组件即视为上屏`() {
        assertTrue(RearProjectionVerifier.isOnDisplay(dumpsysWithDashboard, 1, component))
    }

    @Test
    fun `背屏只有原生 SubScreen 时不算上屏`() {
        assertFalse(RearProjectionVerifier.isOnDisplay(dumpsysNativeOnly, 1, component))
    }

    @Test
    fun `组件只在主屏块时查背屏必须返回 false`() {
        val dashboardOnMainOnly = """
            Display #1 (activities from top to bottom):
              * Task{fb1b689 #3 type=home}
                * Task{3be36cb #4 type=home A=10205:com.xiaomi.subscreencenter}
            Display #0 (activities from top to bottom):
              * Task{91dd8ab #12798 type=standard A=10332:com.rearcue.poc}
                topResumedActivity=ActivityRecord{29215013 u0 $component t12798}
        """.trimIndent()

        assertFalse(RearProjectionVerifier.isOnDisplay(dashboardOnMainOnly, 1, component))
        assertTrue(RearProjectionVerifier.isOnDisplay(dashboardOnMainOnly, 0, component))
    }

    @Test
    fun `指定不存在的 display 返回 false`() {
        assertFalse(RearProjectionVerifier.isOnDisplay(dumpsysWithDashboard, 3, component))
    }

    @Test
    fun `空输出返回 false`() {
        assertFalse(RearProjectionVerifier.isOnDisplay("", 1, component))
    }
}
