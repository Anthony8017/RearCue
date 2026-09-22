package com.rearcue.poc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Shizuku 客户端 provider 的 manifest 契约守卫（票 #8）。
 *
 * 为什么值得一条测试：Shizuku server 跑在 shell uid（uid 2000）里，它把 binder 交回本应用时
 * **要打开本应用的 provider**，而 provider 的 `android:permission` 是调用方必须持有的权限。
 * 写成 `moe.shizuku.manager.permission.API_V23`（dangerous，shell 不持有）时，server 侧稳定报
 * `Permission Denial: ... uid=2000 requires moe.shizuku.manager.permission.API_V23`，
 * 应用侧只表现为 `Shizuku.pingBinder()` 恒 false、兜底通道永远不可用——编译期、单测、安装都不报错，
 * 只有真机实验才看得见（票 #4 起一路遗留到 #8）。
 *
 * 期望值取自 Shizuku 官方文档（Shizuku-API README「Acquire the Binder」），不是从本仓库的写法反推。
 */
class ShizukuClientProviderManifestTest {

    @Test
    fun `Shizuku 客户端 provider 用 shell uid 持有的权限保护`() {
        val provider = shizukuProviderElement()

        assertEquals(
            "android.permission.INTERACT_ACROSS_USERS_FULL",
            provider.androidAttribute("permission"),
            "provider 权限必须是 shell uid 持有的 INTERACT_ACROSS_USERS_FULL：server（uid 2000）要靠它回调本应用",
        )
    }

    @Test
    fun `Shizuku 客户端 provider 的 authority 与可见性符合官方契约`() {
        val provider = shizukuProviderElement()
        val authorities = provider.androidAttribute("authorities")

        // 源码 manifest 里写 `${applicationId}` 占位符（官方示例的写法），合并后才变成本应用的包名。
        assertTrue(
            authorities == "\${applicationId}.shizuku" || authorities == "${BuildConfig.APPLICATION_ID}.shizuku",
            "authority 必须是 <applicationId>.shizuku，实际 $authorities",
        )
        assertEquals("true", provider.androidAttribute("exported"), "server 在别的进程里，provider 必须 exported")
        assertEquals("false", provider.androidAttribute("multiprocess"), "ShizukuProvider 自己会拒绝 multiprocess")
    }

    /** 从 manifest 里取 ShizukuProvider 声明；找不到就直接失败，避免后面断言空对象。 */
    private fun shizukuProviderElement(): Element {
        val manifest = manifestFile()
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val providers = document.getElementsByTagName("provider")
        for (index in 0 until providers.length) {
            val provider = providers.item(index) as Element
            if (provider.androidAttribute("name") == SHIZUKU_PROVIDER) return provider
        }
        throw AssertionError("$manifest 里没有声明 $SHIZUKU_PROVIDER")
    }

    /**
     * 读**源码** manifest（本模块手写的那份，不是合并产物）：守的就是这里的声明。
     * 合并结果由设备实验背书（票 #8 的 `ShizukuProvider: binder received`）。
     *
     * 单测的工作目录通常是模块目录（AGP 默认），从仓库根跑就是另一种，所以两种都试。
     */
    private fun manifestFile(): File =
        listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull(File::isFile)
            ?: throw AssertionError("找不到 AndroidManifest.xml，当前目录 ${File(".").absolutePath}")

    private companion object {
        const val SHIZUKU_PROVIDER = "rikka.shizuku.ShizukuProvider"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NS, name)
    }
}
