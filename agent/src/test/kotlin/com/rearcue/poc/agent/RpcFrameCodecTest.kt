package com.rearcue.poc.agent

import java.util.Base64
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 票 #88 语义修正后的契约：逻辑消息 = 原始字节（通道消息），checksum value = 8 位小写 hex，
 * seq/messageSeq 由 codec 内部双计数器推进（桌面 assembler 从 1 起严格递增）。
 */
class RpcFrameCodecTest {

    private val clock = longArrayOf(1_000L)

    private fun codec() = RpcFrameCodec(clockMs = { clock[0] })

    private fun payload(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject["payload"]!!.jsonObject

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `小消息往返——单物理帧重组出原字节并产 ack`() {
        val codec = codec()
        val message = """{"method":"v4/conversation/subscribe","params":{"rows":50}}"""
        val frames = codec.encodeMessage(bytes(message), bridgeSessionId = "rearcue-bridge-1", bridgeGeneration = 1, clientTsMs = 5L)
        assertEquals(1, frames.size)

        val frame = payload(frames[0])
        assertEquals("rpc-frame", frame["zcode_type"]!!.jsonPrimitive.content)
        assertEquals("rearcue-bridge-1", frame["bridgeSessionId"]!!.jsonPrimitive.content)
        assertEquals(1, frame["bridgeGeneration"]!!.jsonPrimitive.content.toInt())
        assertEquals(1L, frame["messageSeq"]!!.jsonPrimitive.content.toLong())
        assertEquals(1L, frame["seq"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, frame["fragmentCount"]!!.jsonPrimitive.content.toInt())
        // wire 实证：checksum value 是 8 位小写 hex 字符串
        val checksum = frame["checksum"]!!.jsonObject["value"]!!.jsonPrimitive.content
        assertTrue(Regex("^[0-9a-f]{8}$").matches(checksum), "checksum 应为 8 位 hex，实为 $checksum")

        val assembled = codec.onPhysicalFrame(frame)!!
        val ack = Json.parseToJsonElement(assembled.ackText!!).jsonObject["payload"]!!.jsonObject
        assertEquals("rpc-frame-ack", ack["zcode_type"]!!.jsonPrimitive.content)
        assertEquals(1L, ack["ackMessageSeq"]!!.jsonPrimitive.content.toLong())
        assertTrue(assembled.logicalBytes!!.contentEquals(bytes(message)))
    }

    @Test
    fun `ack 携带桥身份三要素_桌面 rawIdentityMatches 全等才收`() {
        // 桌面 acceptPayload 对 ack 做 {bridgeSessionId, bridgeGeneration, recoveryId} 严格全等，
        // 缺 bridgeGeneration → 静默丢 ack → 桌面出站 10s 重放直至降级（#88 实机事故）。
        val codec = codec()
        val frames = codec.encodeMessage(bytes("m"), "rearcue-bridge-9", bridgeGeneration = 3, clientTsMs = 5L)
        val assembled = codec.onPhysicalFrame(payload(frames[0]))!!
        val ack = Json.parseToJsonElement(assembled.ackText!!).jsonObject["payload"]!!.jsonObject
        assertEquals("rearcue-bridge-9", ack["bridgeSessionId"]!!.jsonPrimitive.content)
        assertEquals(3, ack["bridgeGeneration"]!!.jsonPrimitive.content.toInt())
        assertEquals(1L, ack["ackMessageSeq"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `多消息序号推进——messageSeq 与 seq 各自单调`() {
        val codec = codec()
        codec.encodeMessage(bytes("m1"), "b1", 1, 5L)
        codec.encodeMessage(bytes("m2"), "b1", 1, 5L)
        val second = payload(codec.encodeMessage(bytes("m3-longer"), "b1", 1, 5L)[0])
        assertEquals(3L, second["messageSeq"]!!.jsonPrimitive.content.toLong())
        assertEquals(3L, second["seq"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `大消息多分片重组_物理序号跨消息连续`() {
        val codec = codec()
        val big = ByteArray(RpcFrameCodec.FRAGMENT_PAYLOAD_BYTES + 100) { 'x'.code.toByte() }
        val frames = codec.encodeMessage(big, "b1", bridgeGeneration = 1, clientTsMs = 5L)
        assertEquals(2, frames.size)
        val f0 = payload(frames[0])
        val f1 = payload(frames[1])
        assertEquals(0, f0["fragmentIndex"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, f1["fragmentIndex"]!!.jsonPrimitive.content.toInt())
        assertEquals(1L, f0["seq"]!!.jsonPrimitive.content.toLong())
        assertEquals(2L, f1["seq"]!!.jsonPrimitive.content.toLong())
        assertEquals(1L, f1["messageSeq"]!!.jsonPrimitive.content.toLong())

        var result: RpcFrameCodec.Assembled? = null
        for (frame in frames) {
            result = codec.onPhysicalFrame(payload(frame)) ?: result
        }
        assertTrue(result!!.logicalBytes!!.contentEquals(big))

        // 下一条消息：messageSeq=2、seq 从 3 继续（严格递增不破）
        val next = payload(codec.encodeMessage(bytes("next"), "b1", 1, 5L)[0])
        assertEquals(2L, next["messageSeq"]!!.jsonPrimitive.content.toLong())
        assertEquals(3L, next["seq"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `分片乱序到达仍能重组且不重复产消息`() {
        val codec = codec()
        val big = ByteArray(RpcFrameCodec.FRAGMENT_PAYLOAD_BYTES + 100) { 'y'.code.toByte() }
        val frames = codec.encodeMessage(big, "b1", 1, 5L)
        var result: RpcFrameCodec.Assembled? = null
        for (frame in frames.reversed()) {
            result = codec.onPhysicalFrame(payload(frame)) ?: result
        }
        assertTrue(result!!.logicalBytes!!.contentEquals(big))
        assertNull(codec.onPhysicalFrame(payload(frames[0])), "重复投喂不得再产出")
    }

    @Test
    fun `CRC 校验失败丢消息不发 ack`() {
        val codec = codec()
        val frames = codec.encodeMessage(bytes("""{"a":1}"""), "b1", 1, 5L)
        val broken = mutateFrameData(payload(frames[0])) { it[0] = (it[0].toInt() xor 0x55).toByte() }
        assertNull(codec.onPhysicalFrame(broken))
    }

    @Test
    fun `总长不符丢消息`() {
        val codec = codec()
        val frames = codec.encodeMessage(bytes("""{"a":1}"""), "b1", 1, 5L)
        val original = payload(frames[0])
        val data = Base64.getDecoder().decode(original["dataBase64"]!!.jsonPrimitive.content)
        val truncated = buildJsonObject {
            original.forEach { (k, v) -> put(k, v) }
            put("dataBase64", JsonPrimitive(Base64.getEncoder().encodeToString(data.copyOf(data.size / 2))))
        }
        assertNull(codec.onPhysicalFrame(truncated), "数据截短但 messageBytes 不变 → 必须整条丢弃")
    }

    @Test
    fun `入站接受十进制 checksum_旧帧兼容`() {
        val codec = codec()
        val data = bytes("""{"a":1}""")
        val crc = CRC32().apply { update(data) }.value
        val frame = buildJsonObject {
            put("zcode_type", "rpc-frame")
            put("bridgeSessionId", "b1")
            put("seq", 1L)
            put("messageSeq", 1L)
            put("fragmentIndex", 0)
            put("fragmentCount", 1)
            put("messageBytes", data.size)
            putJsonObject("checksum") {
                put("algorithm", "crc32")
                put("value", JsonPrimitive(crc.toString()))
            }
            put("dataBase64", Base64.getEncoder().encodeToString(data))
        }
        assertTrue(codec.onPhysicalFrame(frame)!!.logicalBytes!!.contentEquals(data))
    }

    @Test
    fun `30 秒未齐片超时清理后不再误拼`() {
        val codec = codec()
        val big = ByteArray(RpcFrameCodec.FRAGMENT_PAYLOAD_BYTES + 100) { 'z'.code.toByte() }
        val frames = codec.encodeMessage(big, "b1", 1, 5L)
        codec.onPhysicalFrame(payload(frames[0])) // 只到一片
        clock[0] += RpcFrameCodec.ASSEMBLY_TIMEOUT_MS + 1
        for (frame in frames.drop(1)) {
            val r = codec.onPhysicalFrame(payload(frame))
            assertNull(r?.logicalBytes, "超时后旧装配应已清理")
        }
    }

    @Test
    fun `帧尺寸与分片数超限拒绝`() {
        val codec = codec()
        val tooManyFragments = buildPhysicalFrame(fragmentCount = 65, fragmentIndex = 0, messageSeq = 1)
        assertNull(codec.onPhysicalFrame(tooManyFragments))
        val badIndex = buildPhysicalFrame(fragmentCount = 1, fragmentIndex = 1, messageSeq = 2)
        assertNull(codec.onPhysicalFrame(badIndex))
    }

    /** 篡改 dataBase64 一字节后重打包（checksum 声明不变 → 校验必败）。 */
    private fun mutateFrameData(
        frame: JsonObject,
        mutation: (ByteArray) -> Unit,
    ): JsonObject {
        val data = Base64.getDecoder().decode(frame["dataBase64"]!!.jsonPrimitive.content)
        mutation(data)
        return buildJsonObject {
            frame.forEach { (k, v) -> put(k, v) }
            put("dataBase64", JsonPrimitive(Base64.getEncoder().encodeToString(data)))
        }
    }

    private fun buildPhysicalFrame(
        fragmentCount: Int,
        fragmentIndex: Int,
        messageSeq: Long,
        bytes: ByteArray = ByteArray(4),
    ): JsonObject = buildJsonObject {
        put("zcode_type", JsonPrimitive("rpc-frame"))
        put("bridgeSessionId", JsonPrimitive("b1"))
        put("seq", JsonPrimitive(messageSeq))
        put("messageSeq", JsonPrimitive(messageSeq))
        put("fragmentIndex", JsonPrimitive(fragmentIndex))
        put("fragmentCount", JsonPrimitive(fragmentCount))
        put("messageBytes", JsonPrimitive(bytes.size))
        putJsonObject("checksum") {
            put("algorithm", JsonPrimitive("crc32"))
            put("value", JsonPrimitive(String.format("%08x", CRC32().apply { update(bytes) }.value)))
        }
        put("dataBase64", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
    }
}
