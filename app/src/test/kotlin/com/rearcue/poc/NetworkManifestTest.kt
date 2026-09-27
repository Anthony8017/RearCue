package com.rearcue.poc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Document

/**
 * Agent Mirror 网络能力守卫（spec 0010 / ticket #80）：INTERNET 是全 App 首个网络权限，
 * 是 :agent 中继客户端的硬前提；单测拦不住 manifest 回潮，只有这条守卫能
 * （判例 RearDashboardManifestTest）。
 */
class NetworkManifestTest {

    @Test
    fun `manifest 必须声明 INTERNET 权限`() {
        val document = parse()
        val permissions = document.getElementsByTagName("uses-permission")
        val declared = (0 until permissions.length)
            .map { permissions.item(it) as org.w3c.dom.Element }
            .any { it.getAttributeNS(ANDROID_NS, "name") == "android.permission.INTERNET" }
        assertTrue(declared, "INTERNET 权限不得移除：Agent Mirror 中继连接（spec 0010）依赖它")
    }

    private fun parse(): Document {
        val manifest = manifestFile()
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        assertNotNull(document.documentElement, "manifest 解析失败：$manifest")
        return document
    }

    /** app 模块 manifest：单测工作目录在 app/，跨目录兜底照抄 RearDashboardManifestTest。 */
    private fun manifestFile(): File = listOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull { it.isFile } ?: throw AssertionError("找不到 app 模块的 AndroidManifest.xml")
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
