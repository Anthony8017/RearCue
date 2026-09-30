package com.rearcue.poc.agentmirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 手填桥地址的入口校验（票 #171）：手打一长串随机域名很容易错一位，
 * [BridgeAddressProbeClient.normalize] 是保存前的第一道闸——归一化错了，
 * 后面探活再准也没用（会把一个好地址判成格式错，或者反过来）。
 *
 * 这里只测纯逻辑（不碰网络、不碰 Context）：探活本身是 IO，留给真机 e2e。
 */
class BridgeAddressProbeTest {

    @Test
    fun `裸域名按 https 补全`() {
        assertEquals("https://x.trycloudflare.com", BridgeAddressProbeClient.normalize("x.trycloudflare.com"))
    }

    @Test
    fun `已是 https 的原样保留`() {
        val url = "https://focuses-transcripts-choose-educators.trycloudflare.com"
        assertEquals(url, BridgeAddressProbeClient.normalize(url))
    }

    @Test
    fun `本机调试的 http 地址不被强行升成 https`() {
        // adb reverse 调试通道填的就是 http://127.0.0.1:18787；升成 https 会直接连不上。
        assertEquals("http://127.0.0.1:18787", BridgeAddressProbeClient.normalize("http://127.0.0.1:18787"))
    }

    @Test
    fun `前后空白与结尾斜杠都去掉`() {
        assertEquals(
            "https://x.trycloudflare.com",
            BridgeAddressProbeClient.normalize("  https://x.trycloudflare.com/  "),
        )
    }

    @Test
    fun `大小写 scheme 也认`() {
        assertEquals("HTTPS://x.trycloudflare.com", BridgeAddressProbeClient.normalize("HTTPS://x.trycloudflare.com"))
    }

    @Test
    fun `空、纯空白、null 一律判为没有地址`() {
        assertNull(BridgeAddressProbeClient.normalize(null))
        assertNull(BridgeAddressProbeClient.normalize(""))
        assertNull(BridgeAddressProbeClient.normalize("   "))
        assertNull(BridgeAddressProbeClient.normalize("/"))
    }

    @Test
    fun `主机名里带空格判为格式错（粘贴带进来的换行与空格）`() {
        assertNull(BridgeAddressProbeClient.normalize("https://x trycloudflare.com"))
    }

    @Test
    fun `带路径的地址保留路径，只取主机段判形状`() {
        // 形状判定只看主机段；带路径不该被误判为格式错（探活时拼成 <url>/health）。
        assertEquals("https://host/lan", BridgeAddressProbeClient.normalize("https://host/lan"))
    }

    @Test
    fun `来源枚举按名回读，未知值给 null（旧值与坏值不崩）`() {
        assertEquals(BridgeAddressSource.PUSHED, BridgeAddressSource.fromName("PUSHED"))
        assertEquals(BridgeAddressSource.MANUAL, BridgeAddressSource.fromName("manual"))
        assertEquals(BridgeAddressSource.DEBUG_BYPASS, BridgeAddressSource.fromName("debug_bypass"))
        assertNull(BridgeAddressSource.fromName("DEBUG")) // 旧名（改名前的落盘值）：读不出就当没有，退回手填
        assertNull(BridgeAddressSource.fromName(null))
        assertNull(BridgeAddressSource.fromName("nonsense"))
    }
}
