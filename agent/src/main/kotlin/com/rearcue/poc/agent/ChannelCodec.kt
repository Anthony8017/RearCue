package com.rearcue.poc.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject


/**
 * V4 通道编解码（spec 0010 / 票 #88）：workspace-bridge 之上跑 VSCode 风格二进制通道协议。
 *
 * wire 实证（官方 web 客户端 ju/Bu/zu + app.asar ChannelServer 逆向，2026-09-27）：
 * 一条逻辑消息 = 若干个顺序 VQL 值——先 `[type, id, channelName, name]` 头数组，再一个参数值。
 *
 * - 客户端→服务端：`100 Promise`（args 为位置数组）/ `102 EventListen`（arg 单值）/
 *   `101 PromiseCancel`、`103 EventDispose`（arg = undefined）
 * - 服务端→客户端：`200 Initialize`（无 data）/ `201 PromiseSuccess` / `202 PromiseError` /
 *   `203 PromiseErrorObj` / `204 EventFire`（后四者带 data 值）
 *
 * VQL 值编码：tag 字节（0 Undefined / 1 String / 2 Buffer / 4 Array / 5 Object / 6 Int）+
 * 载荷；String/Buffer/Array/Object 前置 7-bit varint 长度，Object 载荷为 UTF-8 JSON 文本。
 */
object ChannelCodec {

    const val TYPE_PROMISE = 100
    const val TYPE_PROMISE_CANCEL = 101
    const val TYPE_EVENT_LISTEN = 102
    const val TYPE_EVENT_DISPOSE = 103

    const val TYPE_INITIALIZE = 200
    const val TYPE_PROMISE_SUCCESS = 201
    const val TYPE_PROMISE_ERROR = 202
    const val TYPE_PROMISE_ERROR_OBJ = 203
    const val TYPE_EVENT_FIRE = 204

    const val CHANNEL_ZCODE_AGENT = "zcode-agent"

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 服务端帧（解码结果）。data 为 null 表示该消息没有 data 值（Initialize / 空成功）。 */
    sealed interface ServerFrame {
        val id: Long

        data class Initialize(override val id: Long = -1) : ServerFrame

        data class Success(override val id: Long, val data: JsonElement?) : ServerFrame

        data class Error(override val id: Long, val data: JsonElement?) : ServerFrame

        data class EventFire(override val id: Long, val data: JsonElement?) : ServerFrame

        /** 非服务端应答帧（客户端消息回环，测试检视用）：带原始头与参数值。 */
        data class Unknown(
            override val id: Long,
            val type: Int,
            val channel: String? = null,
            val name: String? = null,
            val data: JsonElement? = null,
        ) : ServerFrame
    }

    // ---------- 出站编码 ----------

    /** `100 Promise`：args 是位置参数数组（service.call(ctx, name, args)）。 */
    fun encodePromise(id: Long, channel: String, name: String, args: List<JsonElement>): ByteArray =
        encodeMessage(
            listOf(
                header(TYPE_PROMISE, id, channel, name),
                buildJsonArray { args.forEach { add(it) } },
            ),
        )

    /** `102 EventListen`：arg 是单个值（事件过滤/上下文），服务端不回执。 */
    fun encodeEventListen(id: Long, channel: String, name: String, arg: JsonElement?): ByteArray =
        encodeMessage(listOf(header(TYPE_EVENT_LISTEN, id, channel, name), arg))

    /** `103 EventDispose` / `101 PromiseCancel`：`[type, id]` 头 + undefined（官方 sendCancelOrDispose 形态）。 */
    fun encodeDispose(type: Int, id: Long): ByteArray = encodeMessage(
        listOf(
            buildJsonArray {
                add(JsonPrimitive(type))
                add(JsonPrimitive(id))
            },
            null,
        ),
    )

    private fun header(type: Int, id: Long, channel: String, name: String): JsonArray = buildJsonArray {
        add(JsonPrimitive(type))
        add(JsonPrimitive(id))
        add(JsonPrimitive(channel))
        add(JsonPrimitive(name))
    }

    private fun encodeMessage(values: List<JsonElement?>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (v in values) encodeValueInto(out, v)
        return out.toByteArray()
    }

    private fun encodeValueInto(out: java.io.ByteArrayOutputStream, value: JsonElement?) {
        when {
            value == null || value is JsonNull -> out.write(0)
            value is JsonPrimitive && value.isString -> {
                val bytes = value.content.toByteArray(Charsets.UTF_8)
                out.write(1)
                writeVarint(out, bytes.size)
                out.write(bytes)
            }

            value is JsonPrimitive && value.longOrNull != null -> {
                out.write(6)
                writeVarint(out, value.longOrNull!!)
            }

            value is JsonPrimitive && value.doubleOrNull != null -> {
                // 非整数按 JSON 对象处理（服务端 Object tag 解析）
                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                out.write(5)
                writeVarint(out, bytes.size)
                out.write(bytes)
            }

            value is JsonArray -> {
                out.write(4)
                writeVarint(out, value.size)
                for (item in value) encodeValueInto(out, item)
            }

            value is JsonObject -> {
                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                out.write(5)
                writeVarint(out, bytes.size)
                out.write(bytes)
            }

            else -> out.write(0)
        }
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Int) {
        writeVarint(out, value.toLong())
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        do {
            var b = (v % 128).toInt()
            v /= 128
            if (v > 0) b = b or 0x80
            out.write(b)
        } while (v > 0)
    }

    // ---------- 入站解码 ----------

    /** 解一条完整逻辑消息；格式不合法返回 null（调用方只丢弃不崩）。 */
    fun decode(bytes: ByteArray): ServerFrame? = runCatching { decodeInternal(bytes) }.getOrNull()

    /** 测试/调试用：任意消息编码（值序列原样落字节）。 */
    fun encodeRaw(values: List<JsonElement?>): ByteArray = encodeMessage(values)

    private fun decodeInternal(bytes: ByteArray): ServerFrame? {
        var cursor = Cursor(bytes)
        val header = readValue(cursor) as? JsonArray ?: return null
        val type = (header.getOrNull(0) as? JsonPrimitive)?.longOrNull?.toInt() ?: return null
        val id = (header.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: -1L
        val channel = (header.getOrNull(2) as? JsonPrimitive)?.contentOrNull
        val name = (header.getOrNull(3) as? JsonPrimitive)?.contentOrNull
        val data = if (cursor.pos < bytes.size) readValue(cursor) else null
        return when (type) {
            TYPE_INITIALIZE -> ServerFrame.Initialize()
            TYPE_PROMISE_SUCCESS -> ServerFrame.Success(id, data)
            TYPE_PROMISE_ERROR, TYPE_PROMISE_ERROR_OBJ -> ServerFrame.Error(id, data)
            TYPE_EVENT_FIRE -> ServerFrame.EventFire(id, data)
            else -> ServerFrame.Unknown(id, type, channel, name, data)
        }
    }

    private class Cursor(val bytes: ByteArray) { var pos: Int = 0 }

    private fun readValue(cursor: Cursor): JsonElement? {
        val b = cursor.bytes.getOrNull(cursor.pos++) ?: return null
        return when (b.toInt()) {
            0 -> JsonNull
            1 -> {
                val n = readVarint(cursor).toInt()
                val s = String(cursor.bytes, cursor.pos, n, Charsets.UTF_8)
                cursor.pos += n
                JsonPrimitive(s)
            }

            2, 3 -> {
                val n = readVarint(cursor).toInt()
                // 二进制值仅在 data 里出现（本项目场景不会出现）——按 base64 字符串保底承载。
                val raw = cursor.bytes.copyOfRange(cursor.pos, cursor.pos + n)
                cursor.pos += n
                JsonPrimitive(java.util.Base64.getEncoder().encodeToString(raw))
            }

            4 -> {
                val n = readVarint(cursor).toInt()
                buildJsonArray { repeat(n) { add(readValue(cursor) ?: JsonNull) } }
            }

            5 -> {
                val n = readVarint(cursor).toInt()
                val text = String(cursor.bytes, cursor.pos, n, Charsets.UTF_8)
                cursor.pos += n
                runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
            }

            6 -> JsonPrimitive(readVarint(cursor))
            else -> null
        }
    }

    private fun readVarint(cursor: Cursor): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = cursor.bytes.getOrNull(cursor.pos++) ?: return result
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b.toInt() and 0x80 == 0) return result
            shift += 7
            if (shift > 63) return result
        }
    }
}
