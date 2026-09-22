package com.rearcue.poc.rear

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
    private val component = "com.rearcue.poc/com.rearcue.poc.rear.RearDashboardActivity"

    @Test
    fun `组件名写死为真实包路径`() {
        // 手写串写错包（.ui. 而非 .rear.）在票 #4 逃过测试，这里按字面量断言，写错就红。
        assertEquals(
            "com.rearcue.poc/com.rearcue.poc.rear.RearDashboardActivity",
            RearProjectionCommands.dashboardComponent(pkg),
        )
    }

    @Test
    fun `组件名与 RearDashboardActivity 的真实类名一致`() {
        // 反向守漂移：Activity 改名/换包时这里会红，提醒同步 DASHBOARD_ACTIVITY_CLASS。
        assertEquals(RearDashboardActivity::class.java.name, RearProjectionCommands.DASHBOARD_ACTIVITY_CLASS)
    }

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

        assertEquals("dumpsys activity activities | grep -A8 'Display #3'", plan.verify)
        assertTrue(plan.describe.contains("--display 3"))
    }

    @Test
    fun `保活注入是定向背屏的唤醒键（票 #21，写错定向即红）`() {
        assertEquals("input -d 1 keyevent KEYCODE_WAKEUP", RearProjectionCommands.wakeKeyCommand(1))
        assertEquals("input -d 3 keyevent KEYCODE_WAKEUP", RearProjectionCommands.wakeKeyCommand(3))
    }
}

/**
 * 校验「真的上屏了吗」：`am start --display` 的系统日志里 aborted 和成功都打印 Starting，
 * 只有 dumpsys 的任务栈能区分，所以按 Display 分块找组件名。
 */
class RearProjectionVerifierTest {

    private val pkg = "com.rearcue.poc"
    private val component = "com.rearcue.poc/com.rearcue.poc.rear.RearDashboardActivity"

    /** 真机打印形态（`ComponentName.flattenToShortString()`）：fixture 按它写，别用全名。 */
    private val onDeviceComponent = "com.rearcue.poc/.rear.RearDashboardActivity"

    private val dumpsysWithDashboard = """
        Display #1 (activities from top to bottom):
          * Task{5b4bb8e #12808 type=standard A=10332:com.rearcue.poc}
            topResumedActivity=ActivityRecord{192297616 u0 $onDeviceComponent t12808}
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
                topResumedActivity=ActivityRecord{29215013 u0 $onDeviceComponent t12798}
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

    /**
     * 真机输出（小米 17 Pro，2026-09-22，票 #8 实测：
     * `dumpsys activity activities | grep -A8 'Display #1'`），**逐行照抄，没有改写**。
     *
     * 两个要点是合成 fixture 学不到的：任务头在前、`topResumedActivity` 在第 3 行之后；
     * 而且组件名是**短名** `com.rearcue.poc/.rear.RearDashboardActivity`——`ActivityRecord`
     * 用 `ComponentName.flattenToShortString()` 打印，全名 `pkg/pkg.rear.RearDashboardActivity`
     * 在真机输出里根本不出现。照抄之前的 fixture 写成全名，这条校验就永远是 false。
     */
    private val realRearBlockLines = listOf(
        "Display #1 (activities from top to bottom):",
        "  * Task{9ddf778 #12945 type=standard A=10333:com.rearcue.poc U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}",
        "    mLastNonFullscreenBounds=Rect(320, 76 - 585, 496)",
        "    isSleeping=false",
        "    topResumedActivity=ActivityRecord{16856554 u0 com.rearcue.poc/.rear.RearDashboardActivity t12945}",
        "    * Hist  #0: ActivityRecord{16856554 u0 com.rearcue.poc/.rear.RearDashboardActivity t12945}",
        "      packageName=com.rearcue.poc processName=com.rearcue.poc",
        "      launchedFromUid=2000 launchedFromPackage=com.android.shell launchedFromFeature=null userId=0",
    )

    @Test
    fun `真机背屏块里只有短组件名时同样算上屏`() {
        assertTrue(RearProjectionVerifier.isOnDisplay(realRearBlockLines.joinToString("\n"), 1, component))
    }

    @Test
    fun `校验命令的窗口要够大，真机背屏块的 Dashboard 行必须落在窗口里`() {
        val plan = RearProjectionCommands.project(pkg, displayId = 1)
        val verify = plan.verify ?: throw AssertionError("投送计划没有校验命令")
        val window = Regex("""grep -A(\d+)""").find(verify)?.groupValues?.get(1)?.toInt()
        requireNotNull(window) { "校验命令里没有 grep -A<n>：$verify" }

        // grep -A<n> 的输出 = 标题行 + 其后 n 行；照这个形状模拟真机管道，再看校验器认不认。
        val piped = realRearBlockLines.take(window + 1).joinToString("\n")

        assertTrue(
            RearProjectionVerifier.isOnDisplay(piped, 1, plan.component),
            "窗口 -A$window 里看不到 Dashboard：真机把它打在第 3 行之后（票 #8）",
        )
    }
}
