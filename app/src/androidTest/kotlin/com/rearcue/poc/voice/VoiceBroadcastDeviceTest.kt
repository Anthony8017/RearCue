package com.rearcue.poc.voice

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.notify.RearNotificationListener
import com.rearcue.poc.ui.CodexConversationTestActivity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceBroadcastDeviceTest {
    @Test
    fun xiaomiSpeechCompletesRealSentencesAndFinishesTheBatch() = withPlayback {
        enqueue("device-speech", "这是小爱语音真机测试。两句话已经完成。")
        val media = awaitMediaController()
        await("小爱没有完成第一句") {
            journal.states.any {
                it.follow?.sessionId == "device-speech" && it.follow?.spokenSentenceIndex == 1
            }
        }
        await("小爱播报没有结束") {
            !journal.latest.get().batchActive && media.playbackState?.state == PlaybackState.STATE_STOPPED
        }
        assertNull(journal.latest.get().follow)
        assertNoSpeechFailure()
    }

    @Test
    fun mediaButtonsPauseResumeSkipWholeItemAndDiscardTheQueuedBatch() = withPlayback {
        enqueue("device-first", CONTROL_BODY)
        enqueue("device-next", CONTROL_BODY)
        val media = awaitMediaController()
        await("小爱没有开始第二句") {
            journal.latest.get().follow?.let {
                it.sessionId == "device-first" && it.spokenSentenceIndex == 1
            } == true
        }
        val current = requireNotNull(journal.latest.get().follow)

        tap(media, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        await("单击没有暂停") { media.playbackState?.state == PlaybackState.STATE_PAUSED }
        assertStable("暂停没有保留当前句和队列", 700) {
            journal.latest.get().let {
                it.batchActive && it.follow?.itemId == current.itemId &&
                    it.follow?.spokenSentenceIndex == current.spokenSentenceIndex
            } && media.playbackState?.state == PlaybackState.STATE_PAUSED
        }
        tap(media, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        await("再次单击没有继续") { media.playbackState?.state == PlaybackState.STATE_PLAYING }
        assertEquals(current.itemId, journal.latest.get().follow?.itemId)
        assertEquals(current.spokenSentenceIndex, journal.latest.get().follow?.spokenSentenceIndex)

        tap(media, KeyEvent.KEYCODE_MEDIA_NEXT)
        await("下一首没有跳过当前整条并继续后续内容") {
            journal.latest.get().follow?.let {
                it.sessionId == "device-next" && it.itemId != current.itemId
            } == true && media.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        enqueue("device-discard", CONTROL_BODY)
        assertStable("下一条没有保持播放", 700) {
            journal.latest.get().batchActive &&
                journal.latest.get().follow?.sessionId == "device-next"
        }
        tap(media, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        SystemClock.sleep(100)
        tap(media, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        await("双击没有清空整批") {
            !journal.latest.get().batchActive && journal.latest.get().follow == null &&
                media.playbackState?.state == PlaybackState.STATE_STOPPED
        }
        media.transportControls.play()
        assertStable("清空后仍然恢复了旧队列", 700) {
            !journal.latest.get().batchActive && journal.latest.get().follow == null &&
                media.playbackState?.state == PlaybackState.STATE_STOPPED
        }
        assertFalse(journal.states.any { it.follow?.sessionId == "device-discard" })
        assertNoSpeechFailure()
    }

    @Test
    fun transientDuckFocusWaitsAtSentenceBoundaryAndGainContinuesTheBatch() = withPlayback {
        enqueue("device-focus", CONTROL_BODY)
        val media = awaitMediaController()
        await("小爱没有进入真实发声的第二句") {
            journal.latest.get().follow?.let {
                it.sessionId == "device-focus" && it.spokenSentenceIndex == 1
            } == true && audioManager.isMusicActive
        }
        val current = requireNotNull(journal.latest.get().follow)
        val interruption = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
            .build()
        try {
            assertEquals(
                "系统没有授予测试中断焦点",
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                audioManager.requestAudioFocus(interruption),
            )
            await("当前句没有在中断期间播完") { !audioManager.isMusicActive }
            assertStable("CAN_DUCK 后越过了句边界", 1_000) {
                journal.latest.get().let {
                    it.batchActive && it.follow?.itemId == current.itemId &&
                        it.follow?.spokenSentenceIndex == current.spokenSentenceIndex
                } && media.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            assertEquals(
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                audioManager.abandonAudioFocusRequest(interruption),
            )
            await("归还焦点后没有继续下一句") {
                journal.states.any {
                    it.follow?.itemId == current.itemId &&
                        it.follow?.spokenSentenceIndex == current.spokenSentenceIndex + 1
                }
            }
            await("焦点恢复后没有完成队列") {
                !journal.latest.get().batchActive && media.playbackState?.state == PlaybackState.STATE_STOPPED
            }
            assertNoSpeechFailure()
        } finally {
            audioManager.abandonAudioFocusRequest(interruption)
        }
    }

    private fun withPlayback(test: DevicePlayback.() -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        val sessionManager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, RearNotificationListener::class.java)
        val command = "am start -W -n ${context.packageName}/${CodexConversationTestActivity::class.java.name}"
        val launch = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
        assertTrue(launch, launch.contains("Status: ok"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var broadcast: VoiceBroadcastController? = null
        try {
            val originalSessions = try {
                sessionManager.getActiveSessions(listener)
            } catch (error: SecurityException) {
                assumeTrue("真机音频测试需要通知监听授权：${error.message}", false)
                return
            }
            assumeTrue(
                "已有真实媒体或播报正在播放，跳过以免打断机主",
                originalSessions.none { it.playbackState?.state == PlaybackState.STATE_PLAYING } &&
                    !audioManager.isMusicActive,
            )
            val journal = RuntimeJournal()
            instrumentation.runOnMainSync {
                broadcast = VoiceBroadcastController(context, scope, journal::record).apply {
                    updateSettings(VoiceBroadcastSettings(enabled = true, cueEnabled = false))
                }
            }
            DevicePlayback(
                context,
                requireNotNull(broadcast),
                journal,
                audioManager,
                sessionManager,
                listener,
                originalSessions.map { it.sessionToken }.toSet(),
            ).test()
        } finally {
            try {
                instrumentation.runOnMainSync { broadcast?.close() }
            } finally {
                scope.cancel()
                instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<CodexConversationTestActivity>().forEach { it.finish() }
                }
            }
        }
    }

    private class RuntimeJournal {
        val latest = AtomicReference(VoiceBroadcastRuntimeState())
        val states = CopyOnWriteArrayList<VoiceBroadcastRuntimeState>()

        fun record(state: VoiceBroadcastRuntimeState) {
            latest.set(state)
            states += state
        }
    }

    private class DevicePlayback(
        private val context: Context,
        private val broadcast: VoiceBroadcastController,
        val journal: RuntimeJournal,
        val audioManager: AudioManager,
        private val sessionManager: MediaSessionManager,
        private val listener: ComponentName,
        private val originalTokens: Set<MediaSession.Token>,
    ) {
        fun enqueue(sessionId: String, body: String) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                broadcast.enqueue(
                    VoiceBroadcastKind.DONE,
                    body,
                    source = VoiceBroadcastSource(sessionId = sessionId, body = body),
                )
            }
        }

        fun awaitMediaController(): MediaController {
            var media: MediaController? = null
            await("找不到独立测试播报的系统 MediaSession") {
                val candidates = sessionManager.getActiveSessions(listener).filter {
                    it.packageName == context.packageName && it.sessionToken !in originalTokens
                }
                assertTrue("出现多个新会话，无法安全区分测试播报", candidates.size <= 1)
                media = candidates.singleOrNull()
                media != null
            }
            return requireNotNull(media)
        }

        fun tap(media: MediaController, keyCode: Int) {
            assertTrue(media.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)))
            assertTrue(media.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)))
        }

        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 45_000
            while (!condition()) {
                if (SystemClock.uptimeMillis() >= deadline) {
                    throw AssertionError("$message；runtime=${journal.latest.get()}")
                }
                SystemClock.sleep(25)
            }
        }

        fun assertStable(message: String, durationMs: Long, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + durationMs
            do {
                assertTrue("$message；runtime=${journal.latest.get()}", condition())
                SystemClock.sleep(25)
            } while (SystemClock.uptimeMillis() < deadline)
        }

        fun assertNoSpeechFailure() {
            assertTrue(
                "小爱实际播报失败：${journal.states.mapNotNull { it.note }.distinct()}",
                journal.states.none { it.note != null },
            )
        }
    }

    private companion object {
        const val CONTROL_BODY = "准备开始。" +
            "现在正在进行媒体控制的真机验证，请保持这段语音持续播放，以便验证暂停以后能够从原句继续，同时下一首按钮应当跳过当前整条内容并保留后续内容。" +
            "这一条剩余的句子应当被下一首跳过。"
    }
}
