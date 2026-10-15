package com.rearcue.poc.agentmirror

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.agent.BridgeEndpoint
import com.rearcue.poc.ui.MainActivity
import com.rearcue.poc.ui.SettingsActivity
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real focused Activity and system clipboard, not a separately constructed importer. */
@RunWith(AndroidJUnit4::class)
class ClipboardRejectLiveTest {
    @Test
    fun plainTextAndBadCredentialDoNotSaveAndFailureDoesNotRepeat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as RearCueApp).container
        val originalAddress = requireNotNull(runBlocking { BridgeLinkStore.loadAddress(context) })
        val originalEnabled = runBlocking { AgentMirrorSettingsStore.loadMirrorEnabled(context) }
        val clipboardPrefs = context.getSharedPreferences("bridge_clipboard", Context.MODE_PRIVATE)
        val originalFingerprint = clipboardPrefs.getString("last-content", null)
        val marker = File(context.noBackupFilesDir, "bridge-clipboard-installation")
        val originalMarker = marker.takeIf { it.exists() }?.readBytes()
        var originalClip: ClipData? = null
        var capturedClip = false
        val home = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        val bridge = RejectingBridge()
        try {
            awaitFocus(home)
            instrumentation.runOnMainSync {
                originalClip = home.getSystemService(ClipboardManager::class.java).primaryClip
                    ?.takeUnless(::isAcceptanceClip)
                capturedClip = true
                container.setAgentMirrorEnabled(false)
            }
            val baseline = "${bridge.url}#token=clipboard-baseline"
            instrumentation.runOnMainSync {
                container.setBridgeAddress(baseline, BridgeAddressSource.MANUAL)
            }
            await("临时配置没有写入") { runBlocking { BridgeLinkStore.load(context) } == baseline }
            val plain = "RearCue ordinary clipboard ${SystemClock.uptimeMillis()}"
            instrumentation.runOnMainSync {
                home.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("RearCue acceptance", plain))
                container.setAgentMirrorEnabled(true)
            }
            await("主页没有实际检查普通剪贴板") {
                clipboardPrefs.getString("last-content", null) == fingerprint(plain)
            }
            assertEquals("普通内容不应发起凭据验证", 0, bridge.verifications.get())
            assertTrue("普通内容替换了保存地址", runBlocking { BridgeLinkStore.load(context) } == baseline)
            Log.i("ClipboardDeviceAcceptance", "plain: inspected, verify_count=0, config_unchanged=true")
            val configuration = File(context.filesDir, "datastore/bridge_link.preferences_pb").readBytes()

            val bad = "${bridge.url}#token=clipboard-wrong"
            instrumentation.runOnMainSync {
                home.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("RearCue acceptance", bad))
            }
            await("错误凭据没有经过真实验证请求并返回 401") {
                bridge.verifications.get() == 1 && container.state.value.bridgeClipboardNote?.contains("401") == true
            }
            assertTrue("验证请求没有携带错误凭据", bridge.sawWrongCredential)
            assertTrue("错误凭据替换了保存地址", runBlocking { BridgeLinkStore.load(context) } == baseline)

            Log.i("ClipboardDeviceAcceptance", "bad_credential: status=401, verify_count=1, config_unchanged=true")
            repeat(2) {
                val settings = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("section", "connection")
                }) as SettingsActivity
                await("设置页没有取得焦点") { settings.hasWindowFocus() }
                instrumentation.runOnMainSync { settings.finish() }
                awaitFocus(home)
            }
            val until = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < until) {
                assertEquals("焦点恢复后对失败的同一内容循环验证", 1, bridge.verifications.get())
                SystemClock.sleep(150)
            }
            assertTrue("失败或恢复焦点修改了配置文件", configuration.contentEquals(
                File(context.filesDir, "datastore/bridge_link.preferences_pb").readBytes(),
            ))

            Log.i("ClipboardDeviceAcceptance", "focus_recovery: repeats=2, observation_seconds=8, verify_count=1")
            // Supplemental production check; the UI assertions above use a counted local HTTP fixture.
            val wrongProduction = "${BridgeEndpoint.parse(originalAddress.url).baseUrl}#token=clipboard-acceptance-wrong"
            assertEquals("生产桥没有拒绝错误凭据", BridgeAddressProbe.HttpStatus(401),
                runBlocking { BridgeAddressProbeClient.verifyConnection(wrongProduction) })
        } finally {
            // Restore while imports are disabled; never leave a test address or replace the user's clipboard.
            instrumentation.runOnMainSync {
                container.setAgentMirrorEnabled(false)
                container.setBridgeAddress(originalAddress.url, originalAddress.source)
            }
            await("原桥地址没有还原") { runBlocking { BridgeLinkStore.load(context) } == originalAddress.url }
            runBlocking { BridgeLinkStore.save(context, originalAddress) }
            try {
                if (capturedClip) {
                    awaitFocus(home)
                    instrumentation.runOnMainSync {
                        val clipboard = home.getSystemService(ClipboardManager::class.java)
                        // A new user copy during the test wins; only replace our own test content.
                        if (clipboard.primaryClip?.let(::isAcceptanceClip) == true) {
                            originalClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
                        }
                    }
                }
                val editor = clipboardPrefs.edit()
                if (originalFingerprint == null) editor.remove("last-content")
                else editor.putString("last-content", originalFingerprint)
                assertTrue("剪贴板摘要没有还原", editor.commit())
                if (originalMarker == null) {
                    if (marker.exists()) assertTrue("安装期标记没有还原", marker.delete())
                } else marker.writeBytes(originalMarker)
            } finally {
                instrumentation.runOnMainSync { container.setAgentMirrorEnabled(originalEnabled) }
                await("镜像开关没有还原") {
                    runBlocking { AgentMirrorSettingsStore.loadMirrorEnabled(context) } == originalEnabled
                }
                assertTrue("保存的桥配置没有完整还原",
                    runBlocking { BridgeLinkStore.loadAddress(context) } == originalAddress)
                bridge.close()
            }
        }
    }

    private fun awaitFocus(home: MainActivity) {
        await("主屏主页没有取得焦点") { home.hasWindowFocus() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }

    private fun isAcceptanceClip(clip: ClipData): Boolean {
        val raw = clip.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString() ?: return false
        if (raw.startsWith("RearCue ordinary clipboard ")) return true
        val endpoint = runCatching { BridgeEndpoint.parse(raw) }.getOrNull() ?: return false
        return endpoint.baseUrl.startsWith("http://127.0.0.1:") &&
            endpoint.accessToken in setOf("clipboard-baseline", "clipboard-wrong")
    }

    private fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private class RejectingBridge : Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 300 }
        val url = "http://127.0.0.1:${socket.localPort}"
        val verifications = AtomicInteger()
        @Volatile var sawWrongCredential = false
        @Volatile private var closed = false
        private val worker = thread(name = "clipboard-acceptance-http", isDaemon = true) {
            while (!closed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 1_000
                        val reader = client.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        var wrong = false
                        var headers = 0
                        while (headers++ < 64) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.equals("Authorization: Bearer clipboard-wrong", ignoreCase = true)) wrong = true
                        }
                        val verify = request.startsWith("GET /connection/verify ")
                        if (verify) { sawWrongCredential = wrong; verifications.incrementAndGet() }
                        val status = if (verify) "401 Unauthorized" else "503 Service Unavailable"
                        val body = "{\"ok\":false}"
                        client.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                    }
                } catch (_: SocketTimeoutException) {
                    // Poll the close flag; a normal bridge event request is deliberately unavailable.
                } catch (_: SocketException) {
                    if (!closed) throw IllegalStateException("fixture socket failed")
                }
            }
        }
        override fun close() {
            closed = true
            socket.close()
            worker.join(2_000)
        }
    }
}
