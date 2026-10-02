package com.rearcue.poc.agentmirror

import kotlin.test.Test
import kotlin.test.assertNull

class BridgeAddressInvalidFormatRegressionTest {

    @Test
    fun `粘贴来的括号混合串不能被当成桥地址`() {
        assertNull(BridgeAddressProbeClient.normalize("[https:"))
        assertNull(BridgeAddressProbeClient.normalize("[https://x.trycloudflare.com"))
        assertNull(BridgeAddressProbeClient.normalize("https://[https:"))
    }
}

