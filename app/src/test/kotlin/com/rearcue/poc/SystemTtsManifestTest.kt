package com.rearcue.poc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * System TTS package-visibility guard. Android 11+ blocks TextToSpeech from binding to an
 * engine unless the calling app declares a matching query; a missing query presents as a
 * successful-looking setup with no utterance callbacks and no audio.
 */
class SystemTtsManifestTest {

    @Test
    fun `manifest exposes installed TTS engines to TextToSpeech`() {
        val queries = manifest().getElementsByTagName("queries").item(0) as? Element
        assertTrue(queries != null, "queries element is required for Android 11+ TTS engine visibility")

        val actions = queries.getElementsByTagName("action")
        val hasTtsServiceQuery = (0 until actions.length)
            .map { actions.item(it) as Element }
            .any { it.getAttributeNS(ANDROID_NS, "name") == TTS_SERVICE_ACTION }
        assertTrue(hasTtsServiceQuery, "queries must include $TTS_SERVICE_ACTION")

        val packages = queries.getElementsByTagName("package")
        val hasXiaomiEngine = (0 until packages.length)
            .map { packages.item(it) as Element }
            .any { it.getAttributeNS(ANDROID_NS, "name") == XIAOMI_TTS_ENGINE }
        assertTrue(hasXiaomiEngine, "queries must include $XIAOMI_TTS_ENGINE")
    }

    private fun manifest() = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(manifestFile())

    private fun manifestFile(): File = listOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile) ?: throw AssertionError("找不到 app 模块的 AndroidManifest.xml")

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TTS_SERVICE_ACTION = "android.intent.action.TTS_SERVICE"
        const val XIAOMI_TTS_ENGINE = "com.xiaomi.mibrain.speech"
    }
}
