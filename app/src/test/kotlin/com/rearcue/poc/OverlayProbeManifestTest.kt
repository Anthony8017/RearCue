package com.rearcue.poc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * 覆盖窗口通道的准入契约守卫（票 #10 / E9）。
 *
 * 为什么值得一条测试：`adb shell appops set ... SYSTEM_ALERT_WINDOW allow` 在**未声明权限时也静默
 * 成功**，但 `Settings.canDrawOverlays()` 只看声明——缺了这条 uses-permission 时，安装步骤全绿、
 * appops 显示 allow，真机上覆盖窗口却一次也加不上去，E9 会把「未声明权限」误记成「被系统拒绝」。
 * 与 [ShizukuClientProviderManifestTest] 同类：manifest 契约只有测试能守住，编译期没人查。
 */
class OverlayProbeManifestTest {

    @Test
    fun `主 manifest 声明 SYSTEM_ALERT_WINDOW（覆盖窗口通道的准入前提）`() {
        val declared = manifestRoot()
            .getElementsByTagName("uses-permission")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            .map { it.androidAttribute("name") }

        assertTrue(
            "android.permission.SYSTEM_ALERT_WINDOW" in declared,
            "manifest 必须声明 SYSTEM_ALERT_WINDOW，实际声明：$declared（appops 授权对未声明权限静默无效）",
        )
    }

    /** 单测的工作目录是模块目录（AGP 默认），但两种都认，免得换构建方式就找不到文件。 */
    private fun manifestRoot(): org.w3c.dom.Document {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull(File::isFile)
            ?: throw AssertionError("找不到 AndroidManifest.xml，当前目录 ${File(".").absolutePath}")
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NS, name)
    }
}
