package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** VQL 通道编解码契约（票 #88 wire 实证：头数组 + 参数值双段结构，7-bit varint）。 */
class ChannelCodecTest {

    private fun header(vararg values: Any): JsonArray = buildJsonArray {
        values.forEach {
            add(
                when (it) {
                    is String -> JsonPrimitive(it)
                    is Int -> JsonPrimitive(it)
                    is Long -> JsonPrimitive(it)
                    else -> error("unsupported header value $it")
                },
            )
        }
    }

    @Test
    fun `Promise 编码回环_头与参数可还原`() {
        val bytes = ChannelCodec.encodePromise(
            id = 3,
            channel = "zcode-agent",
            name = "helloConversationV4",
            args = emptyList(),
        )
        val frame = assertIs<ChannelCodec.ServerFrame.Unknown>(ChannelCodec.decode(bytes))
        assertEquals(ChannelCodec.TYPE_PROMISE, frame.type)
        assertEquals(3L, frame.id)
        assertEquals("zcode-agent", frame.channel)
        assertEquals("helloConversationV4", frame.name)
        assertTrue(frame.data is JsonArray)
    }

    @Test
    fun `EventListen 编码回环_arg 单值`() {
        val ctx = buildJsonObject { put("workspacePath", "C:\\ws") }
        val bytes = ChannelCodec.encodeEventListen(5, "zcode-agent", "onDynamicConversationFrame", ctx)
        val frame = assertIs<ChannelCodec.ServerFrame.Unknown>(ChannelCodec.decode(bytes))
        assertEquals(ChannelCodec.TYPE_EVENT_LISTEN, frame.type)
        assertEquals(5L, frame.id)
        assertEquals(ctx, frame.data)
    }

    @Test
    fun `服务端 Success 解码`() {
        val data = buildJsonObject { put("kind", "hello") }
        val bytes = ChannelCodec.encodeRaw(listOf(header(201, 9L), data))
        val frame = assertIs<ChannelCodec.ServerFrame.Success>(ChannelCodec.decode(bytes))
        assertEquals(9L, frame.id)
        assertEquals(data, frame.data)
    }

    @Test
    fun `Initialize 解码_无参数`() {
        val frame = assertIs<ChannelCodec.ServerFrame.Initialize>(ChannelCodec.decode(ChannelCodec.encodeRaw(listOf(header(200)))))
        assertEquals(-1L, frame.id)
    }

    @Test
    fun `EventFire 解码`() {
        val event = buildJsonObject { put("topic", "conversation/s1") }
        val frame = assertIs<ChannelCodec.ServerFrame.EventFire>(
            ChannelCodec.decode(ChannelCodec.encodeRaw(listOf(header(204, 11L), event))),
        )
        assertEquals(11L, frame.id)
        assertEquals(event, frame.data)
    }

    @Test
    fun `PromiseError 解码`() {
        val err = buildJsonObject { put("message", "boom") }
        val frame = assertIs<ChannelCodec.ServerFrame.Error>(
            ChannelCodec.decode(ChannelCodec.encodeRaw(listOf(header(202, 4L), err))),
        )
        assertEquals(4L, frame.id)
        assertEquals(err, frame.data)
    }

    @Test
    fun `大 id 走多字节 varint`() {
        val bytes = ChannelCodec.encodePromise(300, "zcode-agent", "m", emptyList())
        val frame = assertIs<ChannelCodec.ServerFrame.Unknown>(ChannelCodec.decode(bytes))
        assertEquals(300L, frame.id)
    }

    @Test
    fun `Unicode 字符串回环`() {
        val arg = JsonPrimitive("等你确认 ✓")
        val bytes = ChannelCodec.encodePromise(1, "zcode-agent", "subscribeConversationV4", listOf(arg))
        val frame = assertIs<ChannelCodec.ServerFrame.Unknown>(ChannelCodec.decode(bytes))
        assertEquals(buildJsonArray { add(arg) }, frame.data)
    }

    @Test
    fun `Dispose 头仅两元素`() {
        val bytes = ChannelCodec.encodeDispose(ChannelCodec.TYPE_EVENT_DISPOSE, 7)
        val frame = assertIs<ChannelCodec.ServerFrame.Unknown>(ChannelCodec.decode(bytes))
        assertEquals(ChannelCodec.TYPE_EVENT_DISPOSE, frame.type)
        assertEquals(7L, frame.id)
        assertNull(frame.channel)
    }

    @Test
    fun `坏字节解码返回 null 不抛`() {
        assertNull(ChannelCodec.decode(byteArrayOf(9))) // 未知 tag
        assertNull(ChannelCodec.decode(ByteArray(0)))
    }
}
