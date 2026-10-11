package com.rearcue.poc.ui

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.agent.CodexRemoteRequest
import com.rearcue.poc.agent.CodexRemoteResult
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live transport check for the real phone bridge credential path. */
@RunWith(AndroidJUnit4::class)
class BridgeCredentialLiveTest {
    @Test
    fun followupTransportUsesCurrentCredentialOnRealDevice() {
        val threadId = InstrumentationRegistry.getArguments().getString("diagnosticThreadId")
        assumeTrue("Live bridge test requires an idle diagnosticThreadId", threadId != null)
        require(threadId!!.matches(Regex("[0-9a-f-]{36}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as RearCueApp).container
        val client = BridgeCodexConversationClient(container.bridgeClient)
        val marker = "REARCUE_CREDENTIAL_" + UUID.randomUUID().toString().take(8)
        val model = InstrumentationRegistry.getArguments().getString("diagnosticModel")
        val completed = CountDownLatch(1)
        var result: CodexRemoteResult? = null
        client.send(
            "bridge:codex:$threadId",
            CodexRemoteRequest("rc-live-" + UUID.randomUUID(), "Reply only $marker", model = model),
        ) {
            result = it
            completed.countDown()
        }
        assertTrue("live bridge request did not complete", completed.await(45, TimeUnit.SECONDS))
        Log.i("BridgeCredentialLiveTest", "result=$result")
        println("BridgeCredentialLiveTest result=$result")
        val liveResult = result
        assertTrue("live bridge request returned no result", liveResult != null)
        if (model != null) {
            assertTrue(
                "manual model should be rejected before delivery, got $liveResult",
                liveResult is CodexRemoteResult.Failed && liveResult.message == "没换成，所以没发",
            )
        } else {
            assertTrue(
                "default-model followup should be accepted, got $liveResult",
                liveResult is CodexRemoteResult.Accepted,
            )
        }
    }
}
