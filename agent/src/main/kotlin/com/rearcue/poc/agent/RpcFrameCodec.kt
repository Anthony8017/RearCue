package com.rearcue.poc.agent

import java.util.Base64
import java.util.zip.CRC32
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * rpc-frame 编解码 + 重组（E1 schema 固化 + 票 #88 语义修正：**逻辑消息 = 二进制通道消息字节**，
 * 不是 JSON 文本——官方 web 客户端 uh/Kee 与桌面 AcknowledgedRelayProtocol 双向实证：
 * `messageBytes = e.byteLength`，物理帧 dataBase64 = 原始字节直切，无 wireVersion JSON 外壳）。
 *
 * 物理帧（schema ofe）：`{zcode_type:"rpc-frame", bridgeSessionId, bridgeGeneration?, seq, messageSeq,
 * fragmentIndex, fragmentCount, messageBytes, checksum{crc32, value:<8位小写hex>}, dataBase64}`；
 * 重组完成即应答 `{zcode_type:"rpc-frame-ack", bridgeSessionId, ackMessageSeq}`。
 *
 * 序号纪律（桌面 assembler 从 1 起严格递增校验）：seq 按物理帧全局连续、messageSeq 按消息
 * 递增，双计数器由本 codec 持有；一次连接一个 codec 实例（新桥 = 新实例 = 从 1 重来）。
 */
class RpcFrameCodec(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    /** 重组完成的输出：ack 文本帧（须原样回发）+ 逻辑消息字节（V4 通道消息）。 */
    data class Assembled(val ackText: String?, val logicalBytes: ByteArray?) {
        override fun equals(other: Any?): Boolean =
            other is Assembled && ackText == other.ackText &&
                (logicalBytes == null && other.logicalBytes == null ||
                    logicalBytes != null && other.logicalBytes.contentEquals(other.logicalBytes))

        override fun hashCode(): Int = 31 * (ackText?.hashCode() ?: 0) + (logicalBytes?.contentHashCode() ?: 0)
    }

    private val assemblies = HashMap<Long, MutableMap<Int, ByteArray>>()
    private val startedAtMs = HashMap<Long, Long>()

    private var nextPhysicalSeq: Long = 1
    private var nextMessageSeq: Long = 1

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
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
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
            (cs as? JsonObject)?.get("value").let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }?.let { parseChecksum(it) }
        // 超时清理后的残缺装配：凑不齐整集就安静丢弃（不发 ack）。
        val pieces = arrayOfNulls<ByteArray>(fragmentCount)
        var totalMerged = 0
        for (i in 0 until fragmentCount) {
            val piece = assembly[i]
            if (piece == null) {
                assemblies.remove(messageSeq)
                startedAtMs.remove(messageSeq)
                return null
            }
            pieces[i] = piece
            totalMerged += piece.size
        }
        val merged = ByteArray(totalMerged).also { buf ->
            var offset = 0
            for (piece in pieces) {
                piece!!.copyInto(buf, offset)
                offset += piece.size
            }
        }
        val crc = CRC32().apply { update(merged) }
        assemblies.remove(messageSeq)
        startedAtMs.remove(messageSeq)

        val checksumMatches = checksumExpected == null || checksumExpected == crc.value
        if (total != messageBytes || !checksumMatches) return null // 校验失败：丢消息（不发 ack，等对端重传）

        // ⚠️ 桌面 rawIdentityMatches 是 {bridgeSessionId, bridgeGeneration, recoveryId} 严格
        // 全等（缺字段=undefined≠值 即拒）——ack 少带 bridgeGeneration 会被静默丢弃，
        // 桌面出站永远等不到确认 → 10s 周期重放 + replayGraceExceeded 降级（#88 实机实证）。
        val ackGeneration = RelayEnvelope.primitiveOrNull(payload, "bridgeGeneration")
        val ack = buildJsonObject {
            put("zcode_type", "rpc-frame-ack")
            put("bridgeSessionId", bridgeSessionId)
            ackGeneration?.toIntOrNull()?.let { put("bridgeGeneration", it) }
            put("ackMessageSeq", messageSeq)
        }.let { RelayEnvelope.json.encodeToString(JsonObject.serializer(), RelayEnvelope.wrapData(it, clockMs())) }

        return Assembled(ackText = ack, logicalBytes = merged)
    }

    /**
     * 出站：把一条逻辑消息（原始字节）按 envelope 预算切片成物理帧，返回可直接 send 的
     * data envelope 文本列表。seq/messageSeq 由内部计数器推进（桥会话生命周期内单调）。
     */
    fun encodeMessage(
        messageBytes: ByteArray,
        bridgeSessionId: String,
        bridgeGeneration: Int?,
        clientTsMs: Long,
    ): List<String> {
        require(messageBytes.isNotEmpty()) { "empty message" }
        require(messageBytes.size <= MAX_MESSAGE_BYTES) { "message too large" }

        val crc = CRC32().apply { update(messageBytes) }
        val checksumValue = String.format("%08x", crc.value)
        val firstMessageSeq = nextMessageSeq
        val firstPhysicalSeq = nextPhysicalSeq

        val fragmentSize = FRAGMENT_PAYLOAD_BYTES
        val fragmentCount = ((messageBytes.size + fragmentSize - 1) / fragmentSize).coerceAtLeast(1)
        require(fragmentCount <= MAX_FRAGMENTS) { "message too large for $MAX_FRAGMENTS fragments" }

        val frames = ArrayList<String>(fragmentCount)
        for (index in 0 until fragmentCount) {
            val from = index * fragmentSize
            val to = minOf(from + fragmentSize, messageBytes.size)
            val slice = messageBytes.copyOfRange(from, to)
            val frame = buildJsonObject {
                put("zcode_type", "rpc-frame")
                put("bridgeSessionId", bridgeSessionId)
                if (bridgeGeneration != null) put("bridgeGeneration", bridgeGeneration)
                put("seq", firstPhysicalSeq + index)
                put("messageSeq", firstMessageSeq)
                put("fragmentIndex", index)
                put("fragmentCount", fragmentCount)
                put("messageBytes", messageBytes.size)
                putJsonObject("checksum") {
                    put("algorithm", "crc32")
                    put("value", checksumValue)
                }
                put("dataBase64", Base64.getEncoder().encodeToString(slice))
            }
            frames.add(
                RelayEnvelope.json.encodeToString(JsonObject.serializer(), RelayEnvelope.wrapData(frame, clientTsMs)),
            )
        }
        nextMessageSeq += 1
        nextPhysicalSeq += fragmentCount
        return frames
    }

    /** checksum value 解析：8 位 hex（wire 实形态）优先，退化接受十进制（旧帧兼容）。 */
    private fun parseChecksum(raw: String): Long? =
        if (RAW_HEX8.matches(raw)) raw.toLongOrNull(16)?.let { it and 0xFFFFFFFFL }
        else raw.toLongOrNull()

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
        /** 物理帧 envelope JSON 预算（maxPhysicalFrameBytes=1MiB）下的单片负载上限（base64 4/3 膨胀 + 开销）。 */
        const val FRAGMENT_PAYLOAD_BYTES = 780_000
        const val MAX_MESSAGE_BYTES = (1 shl 20) * 16L
        const val MAX_FRAGMENTS = 64
        const val ASSEMBLY_TIMEOUT_MS = 30_000L
        private val RAW_HEX8 = Regex("^[0-9a-f]{8}$")
    }
}
