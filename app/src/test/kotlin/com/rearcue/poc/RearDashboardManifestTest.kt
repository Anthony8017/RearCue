package com.rearcue.poc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * RearDashboardActivity manifest 契约守卫（issue #43：锁屏首投不得唤亮主屏）。
 *
 * 为什么值得一条测试：锁屏熄屏首投的兜底链会先用默认屏 `am start -n` 把本 Activity 建在
 * Display 0（`am start --display` 被 `rearDisplay check locked -> deny` 拒，E3/E14 实测），
 * 而 `android:turnScreenOn="true"` 是**启动瞬间**系统读取的值——落在 Display 0 时它会让
 * PowerGroup group 0 唤亮主屏（`TURN_ON:handleTurnScreenOn`），违反 spec #36 story 8/12。
 * 实测证据：docs/poc-logs/20260924-174502/174806-issue37-screen-off-chain（两轮复现）。
 *
 * 期望值：manifest 不声明 turnScreenOn（运行期按落屏 displayId 条件设置，见
 * RearDashboardActivity.onCreate），showWhenLocked 保持声明（E3：锁屏不能把 Dashboard 打下去）。
 * 编译期/单测都拦不住 manifest 属性回潮，只有这条守卫能。
 */
class RearDashboardManifestTest {

    @Test
    fun `RearDashboardActivity 不得在 manifest 声明 turnScreenOn`() {
        val activity = rearDashboardActivityElement()

        assertTrue(
            activity.getAttributeNS(ANDROID_NS, "turnScreenOn").isEmpty(),
            "manifest 不能声明 android:turnScreenOn：锁屏兜底会把它建在 Display 0，启动即唤亮主屏（issue #43）",
        )
    }

    @Test
    fun `RearDashboardActivity 必须保持 showWhenLocked 声明`() {
        val activity = rearDashboardActivityElement()

        assertEquals(
            "true",
            activity.getAttributeNS(ANDROID_NS, "showWhenLocked"),
            "showWhenLocked 必须留在 manifest：锁屏稳态下 Dashboard 不能被 keyguard 打下去（E3）",
        )
    }

    /** 读 rear 模块源码 manifest；找不到声明直接失败，避免断言空对象。 */
    private fun rearDashboardActivityElement(): Element {
        val manifest = manifestFile()
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val activities = document.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            val activity = activities.item(index) as Element
            // rear 模块 manifest 用包名相对写法（`.RearDashboardActivity`），全名写法也兼容。
            val name = activity.getAttributeNS(ANDROID_NS, "name")
            if (name == ACTIVITY || name == ".${ACTIVITY.substringAfterLast('.')}") return activity
        }
        throw AssertionError("$manifest 里没有声明 $ACTIVITY")
    }

    /**
     * rear 模块的 manifest：单测工作目录不确定（app 模块目录 / 仓库根都可能），两种都试。
     */
    private fun manifestFile(): File =
        listOf(
            File("src/main/AndroidManifest.xml"),
            File("rear/src/main/AndroidManifest.xml"),
            File("../rear/src/main/AndroidManifest.xml"),
        ).firstOrNull { candidate ->
            candidate.isFile && candidate.readText().contains("RearDashboardActivity")
        } ?: throw AssertionError("找不到 rear 模块的 AndroidManifest.xml，当前目录 ${File(".").absolutePath}")

    private companion object {
        const val ACTIVITY = "com.rearcue.poc.rear.RearDashboardActivity"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
