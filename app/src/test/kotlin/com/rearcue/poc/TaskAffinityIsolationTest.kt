package com.rearcue.poc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.w3c.dom.Element

/**
 * 主屏与背屏 Activity 的 task 隔离契约（issue #262）。
 *
 * 两者共用默认 taskAffinity 时，`FLAG_ACTIVITY_NEW_TASK` / `am start --activity-reorder-to-front`
 * 会复用同一任务：主屏入口可被塞进背屏任务，Dashboard 也会成为主屏 MainActivity 的返回栈顶层。
 */
class TaskAffinityIsolationTest {

    @Test
    fun `MainActivity 与 RearDashboardActivity 必须使用不同 taskAffinity`() {
        val main = activityElement(manifestContaining(".ui.MainActivity"), ".ui.MainActivity")
        val rear = activityElement(manifestContaining("RearDashboardActivity"), REAR_ACTIVITY)

        assertEquals("com.rearcue.poc.main", main.taskAffinity())
        assertEquals("com.rearcue.poc.rear.dashboard", rear.taskAffinity())
        assertNotEquals(main.taskAffinity(), rear.taskAffinity())
    }

    private fun Element.taskAffinity(): String =
        getAttributeNS(ANDROID_NS, "taskAffinity")
            .also { check(it.isNotEmpty()) { "$this 缺少 android:taskAffinity" } }

    private fun activityElement(manifest: File, activityName: String): Element {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val activities = document.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            val activity = activities.item(index) as Element
            val name = activity.getAttributeNS(ANDROID_NS, "name")
            if (name == activityName || name == ".${activityName.substringAfterLast('.')}") return activity
        }
        throw AssertionError("$manifest 里没有声明 $activityName")
    }

    private fun manifestContaining(marker: String): File =
        listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("rear/src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
            File("../rear/src/main/AndroidManifest.xml"),
        ).firstOrNull { candidate -> candidate.isFile && candidate.readText().contains(marker) }
            ?: throw AssertionError("找不到包含 $marker 的 manifest，当前目录 ${File(".").absolutePath}")

    private companion object {
        const val REAR_ACTIVITY = "com.rearcue.poc.rear.RearDashboardActivity"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
