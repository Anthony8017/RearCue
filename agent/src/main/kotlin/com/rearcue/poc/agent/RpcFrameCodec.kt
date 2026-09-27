package com.rearcue.poc.agent

import java.util.zip.CRC32
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * rpc-frame 编解码 + 重组（E1 schema 固化，物理帧限制 maxPhysicalFrameBytes=1MiB /
 * maxMessageBytes=16MiB / maxFragments=64 / assemblyTimeoutMs=30s）。
 *
 * 物理帧（schema ofe）：`{zcode_type:"rpc-frame", bridgeSessionId, seq, messageSeq, fragmentIndex,
 * fragmentCount, messageBytes, checksum{crc32}, dataBase64}`；重组完成即应答
 * `{zcode_type:"rpc-frame-ack", bridgeSessionId, ackMessageSeq}`。
 *
 * 重组后的逻辑帧是 JSON 文本（`{wireVersion:3, kind:"complete", dataBase64, ...}`），
 * complete 的 dataBase64 解出 `{method,params}` 形态的业务消息。
 */
class RpcFrameCodec(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    /** 重组完成的输出：ack 文本帧（须原样回发）+ 逻辑消息文本。 */
    data class Assembled(val ackText: String?, val logicalMessage: String?)

    private val assemblies = HashMap<Long, MutableMap<Int, ByteArray>>()
    private val startedAtMs = HashMap<Long, Long>()

    /** 入站一个 data envelope 的 payload；重组完成返回 [Assembled]，否则 null。 */
    fun onPhysicalFrame(payload: JsonObject): Assembled? {
        if ((RelayEnvelope.primitiveOrNull(payload, "zcode_type") ?: return null) != "rpc-frame") return null
        val bridgeSessionId = RelayEnvelope.primitiveOrNull(payload, "bridgeSessionId") ?: return null
        val messageSeq = (payload["messageSeq"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            ?: return null
        val fragmentIndex = (payload["fragmentIndex"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            ?: return null
        val fragmentCount = (payload["fragmentCount"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            ?: return null
        val messageBytes = (payload["messageBytes"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            ?: return null
        if (fragmentCount < 1 || fragmentCount > MAX_FRAGMENTS) return null
        if (fragmentIndex < 0 || fragmentIndex >= fragmentCount) return null
        if (messageBytes < 0 || messageBytes > MAX_MESSAGE_BYTES) return null
        val data = RelayEnvelope.primitiveOrNull(payload, "dataBase64")
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return null

        val now = clockMs()
        purgeExpired(now)
        val assembly = assemblies.getOrPut(messageSeq) { HashMap() }
        startedAtMs.putIfAbsent(messageSeq, now)
        assembly[fragmentIndex] = data

        if (assembly.size < fragmentCount) return null

        // 齐了：按序拼装、校验总长与 CRC32（对整个逻辑消息字节）。
        // 装配曾被超时清理过（装配表里只剩部分片）→ 片数凑不齐整集，安静丢弃整条
        // （不发 ack，等对端重传或 resync）——数据路径不抛异常。
        val total = assembly.values.sumOf { it.size }.toLong()
        val checksumExpected = payload["checksum"]?.let { cs ->
            (cs as? JsonObject)?.get("value").let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
        }
        val merged = run {
            var size = 0
            for (i in 0 until fragmentCount) {
                val piece = assembly[i] ?: run {
                    assemblies.remove(messageSeq)
                    startedAtMs.remove(messageSeq)
                    return null
                }
                size += piece.size
            }
            ByteArray(size).also { buf ->
                var offset = 0
                for (i in 0 until fragmentCount) {
                    assembly[i]!!.copyInto(buf, offset)
                    offset += assembly[i]!!.size
                }
            }
        }
        val crc = CRC32().apply { update(merged) }
        val crcValue = crc.value // unsigned long
        assemblies.remove(messageSeq)
        startedAtMs.remove(messageSeq)

        val checksumMatches = checksumExpected == null || checksumExpected == crcValue
        if (total != messageBytes || !checksumMatches) return null // 校验失败：丢消息（不发 ack，等对端重传）

        val ack = buildJsonObject {
            put("zcode_type", "rpc-frame-ack")
            put("bridgeSessionId", bridgeSessionId)
            put("ackMessageSeq", messageSeq)
        }.let { RelayEnvelope.json.encodeToString(JsonObject.serializer(), RelayEnvelope.wrapData(it, clockMs())) }

        // 逻辑帧解包：complete 直接给业务消息；fragment（业务层再分片）本期不拼，交上层 resync。
        val logical = runCatching { RelayEnvelope.json.parseToJsonElement(String(merged, Charsets.UTF_8)).jsonObject }.getOrNull()
        val logicalMessage = if (logical != null && RelayEnvelope.primitiveOrNull(logical, "kind") == "complete") {
            val inner = RelayEnvelope.primitiveOrNull(logical, "dataBase64")
                ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            inner?.let { String(it, Charsets.UTF_8) }
        } else {
            null
        }
        return Assembled(ackText = ack, logicalMessage = logicalMessage)
    }

    /** 出站：把一条逻辑消息（JSON 文本）按 1MiB 切片成物理帧，返回可直接 send 的 data envelope 文本列表。 */
    fun encodeLogicalMessage(
        logicalJsonText: String,
        bridgeSessionId: String,
        firstSeq: Long,
        clientTsMs: Long,
    ): List<String> {
        val logical = buildJsonObject {
            put("wireVersion", 3)
            put("kind", "complete")
            put("fragmentIndex", 0)
            put("fragmentCount", 1)
            put("logicalBytes", logicalJsonText.toByteArray(Charsets.UTF_8).size)
            put("dataBase64", java.util.Base64.getEncoder().encodeToString(logicalJsonText.toByteArray(Charsets.UTF_8)))
        }
        val logicalBytes = logical.toString().toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(logicalBytes) }
        val fragmentSize = MAX_PHYSICAL_FRAME_PAYLOAD_BYTES
        val fragmentCount = ((logicalBytes.size + fragmentSize - 1) / fragmentSize).coerceAtLeast(1)
        require(fragmentCount <= MAX_FRAGMENTS) { "message too large for $MAX_FRAGMENTS fragments" }

        val frames = ArrayList<String>(fragmentCount)
        for (index in 0 until fragmentCount) {
            val from = index * fragmentSize
            val to = minOf(from + fragmentSize, logicalBytes.size)
            val slice = logicalBytes.copyOfRange(from, to)
            val frame = buildJsonObject {
                put("zcode_type", "rpc-frame")
                put("bridgeSessionId", bridgeSessionId)
                put("seq", firstSeq + index)
                put("messageSeq", firstSeq) // encode 侧以 firstSeq 作为本条消息的 messageSeq
                put("fragmentIndex", index)
                put("fragmentCount", fragmentCount)
                put("messageBytes", logicalBytes.size)
                putJsonObject("checksum") {
                    put("algorithm", "crc32")
                    put("value", crc.value)
                }
                put("dataBase64", java.util.Base64.getEncoder().encodeToString(slice))
            }
            frames.add(
                RelayEnvelope.json.encodeToString(JsonObject.serializer(), RelayEnvelope.wrapData(frame, clientTsMs)),
            )
        }
        return frames
    }

    private fun purgeExpired(nowMs: Long) {
        val iterator = startedAtMs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value > ASSEMBLY_TIMEOUT_MS) {
                assemblies.remove(entry.key)
                iterator.remove()
            }
        }
    }

    companion object {
        const val MAX_PHYSICAL_FRAME_PAYLOAD_BYTES = 1 shl 20
        const val MAX_MESSAGE_BYTES = (1 shl 20) * 16L
        const val MAX_FRAGMENTS = 64
        const val ASSEMBLY_TIMEOUT_MS = 30_000L
    }
}
