package com.rearcue.poc.rear

import com.rearcue.poc.core.VoiceBroadcastFollow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceBroadcastFollowProjectionTest {
    private fun follow(id: Long) = VoiceBroadcastFollow(id, "s$id", null, null, "句子", 0, 1, true)

    @Test fun `用户暂停视觉后同组下一条不恢复跟随_新组恢复`() {
        AgentFeed.publishVoiceFollow(null, false)
        AgentFeed.publishVoiceFollow(follow(1), true)
        assertTrue(AgentFeed.voiceFollow.value!!.active)
        AgentFeed.pauseVoiceFollow()
        AgentFeed.publishVoiceFollow(null, true)
        AgentFeed.publishVoiceFollow(follow(2), true)
        assertFalse(AgentFeed.voiceFollow.value!!.active)
        AgentFeed.publishVoiceFollow(null, false)
        AgentFeed.publishVoiceFollow(follow(3), true)
        assertTrue(AgentFeed.voiceFollow.value!!.active)
        AgentFeed.publishVoiceFollow(null, false)
    }

    @Test fun `批准临时暂停仍可恢复不算用户取消跟随`() {
        AgentFeed.publishVoiceFollow(null, false)
        AgentFeed.publishVoiceFollow(follow(1), true)
        AgentFeed.pauseVoiceFollowForApproval()
        assertFalse(AgentFeed.voiceFollow.value!!.active)
        AgentFeed.resumeVoiceFollowAfterApproval()
        assertTrue(AgentFeed.voiceFollow.value!!.active)
        AgentFeed.publishVoiceFollow(null, false)
    }
}
