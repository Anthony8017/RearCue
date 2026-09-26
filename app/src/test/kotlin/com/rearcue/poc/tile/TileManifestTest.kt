package com.rearcue.poc.tile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Quick Tile Entry 的 manifest 契约守卫（spec 0006 / 票 #54）。
 *
 * TileService 的三个声明缺一不可，且编译期拦不住回潮（manifest 合并器不校验语义）：
 * - `BIND_QUICK_SETTINGS_TILE` 权限：没它系统不绑定，tile 不出现在控制中心；
 * - `QS_TILE` action 的 intent-filter：同上；
 * - `exported=true`：有 intent-filter 的 service 在 Android 12+ 必须显式声明。
 * 图标/label 走资源引用（合并器对非 ASCII 注释有解码问题，票 #4——这里只守结构）。
 */
class TileManifestTest {

    @Test
    fun `tile service 必须声明 BIND_QUICK_SETTINGS_TILE 权限`() {
        assertEquals(
            "android.permission.BIND_QUICK_SETTINGS_TILE",
            tileServiceElement().getAttributeNS(ANDROID_NS, "permission"),
            "缺 BIND_QUICK_SETTINGS_TILE：系统拒绝绑定，控制中心不会出现该 tile",
        )
    }

    @Test
    fun `tile service 必须声明 QS_TILE action 且 exported`() {
        val service = tileServiceElement()

        assertEquals("true", service.getAttributeNS(ANDROID_NS, "exported"), "有 intent-filter 的 service 必须显式 exported")
        val actions = service.getElementsByTagName("action")
        val hasQsTileAction = (0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(ANDROID_NS, "name") == QS_TILE_ACTION }
        assertTrue(hasQsTileAction, "intent-filter 必须包含 $QS_TILE_ACTION")
    }

    private fun tileServiceElement(): Element {
        val manifest = appManifestFile()
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val services = document.getElementsByTagName("service")
        for (index in 0 until services.length) {
            val service = services.item(index) as Element
            // manifest 用包名相对写法（`.tile.RearCueTileService`），全名写法也兼容。
            val name = service.getAttributeNS(ANDROID_NS, "name")
            if (name == TILE_SERVICE || TILE_SERVICE.endsWith(name)) return service
        }
        throw AssertionError("$manifest 里没有声明 $TILE_SERVICE")
    }

    /** app 模块 manifest：单测工作目录不确定（模块目录 / 仓库根都可能），两种都试。 */
    private fun appManifestFile(): File =
        listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { candidate ->
            candidate.isFile && candidate.readText().contains("RearCueTileService")
        } ?: throw AssertionError("找不到 app 模块的 AndroidManifest.xml，当前目录 ${File(".").absolutePath}")

    private companion object {
        const val TILE_SERVICE = "com.rearcue.poc.tile.RearCueTileService"
        const val QS_TILE_ACTION = "android.service.quicksettings.action.QS_TILE"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
