package com.rearcue.poc.agent

import java.util.Base64
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class RpcFrameCodecTest {

    private val clock = longArrayOf(1_000L)

    private fun codec() = RpcFrameCodec(clockMs = { clock[0] })

    private fun payload(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject["payload"]!!.jsonObject

    @Test
    fun `小消息往返——单物理帧重组出原逻辑消息并产 ack`() {
        val codec = codec()
        val logical = """{"method":"v4/conversation/subscribe","params":{"rows":50}}"""
        val frames = codec.encodeLogicalMessage(logical, bridgeSessionId = "d_test", firstSeq = 1, clientTsMs = 5L)
        assertEquals(1, frames.size)

        val frame = payload(frames[0])
        assertEquals("rpc-frame", frame["zcode_type"]!!.jsonPrimitive.content)
        assertEquals(1L, frame["messageSeq"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, frame["fragmentCount"]!!.jsonPrimitive.content.toInt())

        val assembled = codec.onPhysicalFrame(frame)!!
        val ack = Json.parseToJsonElement(assembled.ackText!!).jsonObject["payload"]!!.jsonObject
        assertEquals("rpc-frame-ack", ack["zcode_type"]!!.jsonPrimitive.content)
        assertEquals(1L, ack["ackMessageSeq"]!!.jsonPrimitive.content.toLong())
        assertEquals(logical, assembled.logicalMessage)
    }

    @Test
    fun `大消息多分片重组`() {
        val codec = codec()
        val big = """{"method":"m","params":{"blob":"${"x".repeat(RpcFrameCodec.MAX_PHYSICAL_FRAME_PAYLOAD_BYTES * 2)}"}}"""
        val frames = codec.encodeLogicalMessage(big, "d_test", firstSeq = 7, clientTsMs = 5L)
        assertEquals(3, frames.size) // 逻辑帧比两片负载略大，落在第三片

        var result: RpcFrameCodec.Assembled? = null
        for (frame in frames) {
            result = codec.onPhysicalFrame(payload(frame)) ?: result
        }
        assertEquals(big, result!!.logicalMessage)
    }

    @Test
    fun `分片乱序到达仍能重组且不重复产消息`() {
        val codec = codec()
        val big = """{"p":"${"y".repeat(RpcFrameCodec.MAX_PHYSICAL_FRAME_PAYLOAD_BYTES * 2)}"}"""
        val frames = codec.encodeLogicalMessage(big, "d_test", firstSeq = 1, clientTsMs = 5L)
        var result: RpcFrameCodec.Assembled? = null
        for (frame in frames.reversed()) {
            result = codec.onPhysicalFrame(payload(frame)) ?: result
        }
        assertEquals(big, result!!.logicalMessage)
        assertNull(codec.onPhysicalFrame(payload(frames[0])), "重复投喂不得再产出")
    }

    @Test
    fun `CRC 校验失败丢消息不发 ack`() {
        val codec = codec()
        val frames = codec.encodeLogicalMessage("""{"a":1}""", "d_test", firstSeq = 1, clientTsMs = 5L)
        val broken = mutateFrameData(payload(frames[0])) { it[0] = (it[0].toInt() xor 0x55).toByte() }
        assertNull(codec.onPhysicalFrame(broken))
    }

    @Test
    fun `总长不符丢消息`() {
        val codec = codec()
        val frames = codec.encodeLogicalMessage("""{"a":1}""", "d_test", firstSeq = 1, clientTsMs = 5L)
        val original = payload(frames[0])
        val data = Base64.getDecoder().decode(original["dataBase64"]!!.jsonPrimitive.content)
        val truncated = buildJsonObject {
            original.forEach { (k, v) -> put(k, v) }
            put("dataBase64", JsonPrimitive(Base64.getEncoder().encodeToString(data.copyOf(data.size / 2))))
        }
        assertNull(codec.onPhysicalFrame(truncated), "数据截短但 messageBytes 不变 → 必须整条丢弃")
    }

    @Test
    fun `30 秒未齐片超时清理后不再误拼`() {
        val codec = codec()
        val big = """{"p":"${"z".repeat(RpcFrameCodec.MAX_PHYSICAL_FRAME_PAYLOAD_BYTES * 2)}"}"""
        val frames = codec.encodeLogicalMessage(big, "d_test", firstSeq = 1, clientTsMs = 5L)
        codec.onPhysicalFrame(payload(frames[0])) // 只到一片
        clock[0] += RpcFrameCodec.ASSEMBLY_TIMEOUT_MS + 1
        for (frame in frames.drop(1)) {
            val r = codec.onPhysicalFrame(payload(frame))
            assertNull(r?.logicalMessage, "超时后旧装配应已清理")
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
        put("bridgeSessionId", JsonPrimitive("d_test"))
        put("seq", JsonPrimitive(messageSeq))
        put("messageSeq", JsonPrimitive(messageSeq))
        put("fragmentIndex", JsonPrimitive(fragmentIndex))
        put("fragmentCount", JsonPrimitive(fragmentCount))
        put("messageBytes", JsonPrimitive(bytes.size))
        putJsonObject("checksum") {
            put("algorithm", JsonPrimitive("crc32"))
            put("value", JsonPrimitive(CRC32().apply { update(bytes) }.value))
        }
        put("dataBase64", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
    }
}
