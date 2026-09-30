package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * sessions-index 帧归一（票 #103 P0）：真实 wire 形态（官方消费面 schema，2026-09-28 逆向）
 * 构造快照/delta，锁死等待判据（pendingInteraction / summary 计数）、序列断裂与 topic 守卫——
 * 锁档下他会话进等待的真检测源就靠这层，判据错＝插队链路假。
 */
class SessionIndexFeedTest {

    private val path = "C:\\ws"
    private val feed = SessionIndexFeed(path)

    private fun frame(
        fromSeq: Long,
        toSeq: Long,
        payload: String,
        topic: String = feed.topic,
    ): kotlinx.serialization.json.JsonElement = RelayEnvelope.json.parseToJsonElement(
        """
        {
          "wireVersion": 3,
          "kind": "complete",
          "deliveryKind": "online",
          "topic": "${jsonEscape(topic)}",
          "subscriptionId": "sub-idx-1",
          "frame": {
            "topic": "${jsonEscape(topic)}",
            "subscriptionId": "sub-idx-1",
            "fromSeq": $fromSeq,
            "toSeq": $toSeq,
            "sentAt": 1790573810726,
            "payload": $payload
          }
        }
        """.trimIndent(),
    )

    /** JSON 文本里的工作区路径转义（Windows 反斜杠不是合法 JSON 转义）。 */
    private fun jsonEscape(text: String): String = text.replace("\\", "\\\\")

    /** 快照载荷（四会话：在跑、等批准、等输入、空闲——字段全名与 wire schema 一致）。 */
    private fun snapshotPayload() = """
        {
          "kind": "snapshot",
          "snapshot": {
            "protocolVersion": 1,
            "workspaceId": "ws-1",
            "logEpoch": "epoch-1",
            "sessions": [
              {
                "sessionId": "sess_running",
                "workspaceId": "ws-1",
                "title": "正在改文件",
                "phase": "running",
                "sessionEnded": false,
                "hasBackgroundWork": false,
                "pendingInteraction": null,
                "lastActivityAt": 1790573800000,
                "createdAt": 1790570000000
              },
              {
                "sessionId": "sess_permission",
                "workspaceId": "ws-1",
                "title": "写 README",
                "phase": "running",
                "sessionEnded": false,
                "hasBackgroundWork": false,
                "pendingInteraction": {
                  "interactionId": "perm_74c1263",
                  "kind": "permission",
                  "toolName": "Write"
                },
                "pendingInteractionSummary": {"permissionCount": 1, "userInputCount": 0},
                "lastActivityAt": 1790573810000,
                "createdAt": 1790570001000
              },
              {
                "sessionId": "sess_userinput",
                "workspaceId": "ws-1",
                "title": "等你回话",
                "phase": "running",
                "sessionEnded": false,
                "hasBackgroundWork": false,
                "pendingInteractionSummary": {"permissionCount": 0, "userInputCount": 2},
                "lastActivityAt": 1790573811000,
                "createdAt": 1790570002000
              },
              {
                "sessionId": "sess_idle",
                "workspaceId": "ws-1",
                "title": "跑完了",
                "phase": "completedSuccess",
                "sessionEnded": true,
                "hasBackgroundWork": false,
                "lastActivityAt": 1790573000000,
                "createdAt": 1790570003000
              }
            ]
          }
        }
    """

    private fun entriesById(): Map<String, SessionIndexEntry> = feed.entries().associateBy { it.sessionId }

    // ---------- 快照 ----------

    @Test
    fun `快照解析_等待判据与官方索引消费面一致`() {
        val outcome = feed.applyFrame(frame(0, 4, snapshotPayload()))
        assertEquals(IndexFrameOutcome.Applied(changed = true), outcome)

        val byId = entriesById()
        assertEquals(4, byId.size)
        // title 是 ZCode 会话索引的真标题（issue #213），解析链必须保留。
        assertEquals("正在改文件", byId.getValue("sess_running").title)
        assertEquals("写 README", byId.getValue("sess_permission").title)
        // pendingInteraction 在（permission）＝等批准 → 等待确认
        assertTrue(byId.getValue("sess_permission").waiting)
        assertEquals(1_790_573_810_000L, byId.getValue("sess_permission").lastActivityAt)
        // summary 计数和 > 0（userInput）＝等输入 → 等待确认（CONTEXT「等待用户批准/输入」）
        assertTrue(byId.getValue("sess_userinput").waiting)
        // 无 pendingInteraction、无计数 → 非等待（running/ended 都不虚构等待）
        assertEquals(false, byId.getValue("sess_running").waiting)
        assertEquals(false, byId.getValue("sess_idle").waiting)
        // explicit null 不算「有 pendingInteraction」
        assertEquals(false, byId.getValue("sess_running").waiting)
    }

    @Test
    fun `快照重放_内容相同不算变化`() {
        assertIs<IndexFrameOutcome.Applied>(feed.applyFrame(frame(0, 4, snapshotPayload())))
        val again = feed.applyFrame(frame(0, 4, snapshotPayload()))
        assertEquals(IndexFrameOutcome.Applied(changed = false), again)
    }

    // ---------- delta ----------

    @Test
    fun `delta_进等待与离开等待_逐条改写视图`() {
        feed.applyFrame(frame(0, 4, snapshotPayload()))

        val entered = feed.applyFrame(
            frame(
                4,
                5,
                """
                {
                  "kind": "deltas",
                  "deltas": [
                    {"op": "session.upserted", "session": {
                      "sessionId": "sess_running",
                      "workspaceId": "ws-1",
                      "title": "正在改文件",
                      "phase": "running",
                      "sessionEnded": false,
                      "hasBackgroundWork": false,
                      "pendingInteraction": {"interactionId": "perm_2", "kind": "permission", "toolName": "Bash"},
                      "lastActivityAt": 1790573900000,
                      "createdAt": 1790570000000
                    }}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(IndexFrameOutcome.Applied(changed = true), entered)
        assertTrue(entriesById().getValue("sess_running").waiting)

        val left = feed.applyFrame(
            frame(
                5,
                6,
                """
                {
                  "kind": "deltas",
                  "deltas": [
                    {"op": "session.upserted", "session": {
                      "sessionId": "sess_running",
                      "workspaceId": "ws-1",
                      "title": "正在改文件",
                      "phase": "completedSuccess",
                      "sessionEnded": true,
                      "hasBackgroundWork": false,
                      "pendingInteractionSummary": {"permissionCount": 0, "userInputCount": 0},
                      "lastActivityAt": 1790573905000,
                      "createdAt": 1790570000000
                    }},
                    {"op": "session.removed", "sessionId": "sess_idle"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(IndexFrameOutcome.Applied(changed = true), left)
        val byId = entriesById()
        assertEquals(false, byId.getValue("sess_running").waiting)
        assertEquals(false, byId.containsKey("sess_idle"))
    }

    @Test
    fun `delta_标题更新实时替换_空白标题退回null`() {
        feed.applyFrame(frame(0, 4, snapshotPayload()))

        feed.applyFrame(
            frame(
                4,
                5,
                """
                {
                  "kind": "deltas",
                  "deltas": [
                    {"op": "session.upserted", "session": {
                      "sessionId": "sess_running",
                      "title": "标题已改",
                      "lastActivityAt": 1790573900000
                    }}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("标题已改", entriesById().getValue("sess_running").title)

        feed.applyFrame(
            frame(
                5,
                6,
                """
                {
                  "kind": "deltas",
                  "deltas": [
                    {"op": "session.upserted", "session": {
                      "sessionId": "sess_running",
                      "title": "   ",
                      "lastActivityAt": 1790573901000
                    }}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertNull(entriesById().getValue("sess_running").title)
    }

    @Test
    fun `delta_序列断裂_回快照基线缺失或fromSeq对不上判Gap`() {
        // 无基线（没订上快照就来 delta）→ Gap
        assertEquals(IndexFrameOutcome.Gap, feed.applyFrame(frame(0, 1, deltasNone())))
        feed.applyFrame(frame(0, 4, snapshotPayload()))
        // fromSeq 断了（丢帧）→ Gap
        assertEquals(IndexFrameOutcome.Gap, feed.applyFrame(frame(9, 10, deltasNone())))
        // toSeq 不前进（重放/过期）→ 跳过，不算断
        assertEquals(IndexFrameOutcome.Ignored, feed.applyFrame(frame(4, 4, deltasNone())))
    }

    @Test
    fun `topic守卫_别的订阅的帧静默跳过`() {
        val other = frame(0, 4, snapshotPayload(), topic = "sessions-index/D:\\other")
        assertEquals(IndexFrameOutcome.Ignored, feed.applyFrame(other))
        assertTrue(feed.entries().isEmpty())
        // conversation 帧混进索引监听同样不认
        val convo = RelayEnvelope.json.parseToJsonElement(
            """{"topic":"conversation/sess_x","subscriptionId":"sub-1","payload":{"kind":"snapshot"}}""",
        )
        assertEquals(IndexFrameOutcome.Ignored, feed.applyFrame(convo))
    }

    @Test
    fun `不可识别帧_无payload或坏载荷_跳过不抛`() {
        assertEquals(IndexFrameOutcome.Ignored, feed.applyFrame(null))
        assertEquals(IndexFrameOutcome.Ignored, feed.applyFrame(RelayEnvelope.json.parseToJsonElement("[]")))
        assertEquals(
            IndexFrameOutcome.Ignored,
            feed.applyFrame(frame(0, 4, """{"kind":"unknown-kind"}""")),
        )
    }

    @Test
    fun `clear_清零基线与视图`() {
        feed.applyFrame(frame(0, 4, snapshotPayload()))
        assertTrue(feed.entries().isNotEmpty())
        feed.clear()
        assertTrue(feed.entries().isEmpty())
        // 清零后无基线，delta 判 Gap（等新快照重建）
        assertEquals(IndexFrameOutcome.Gap, feed.applyFrame(frame(4, 5, deltasNone())))
    }

    private fun deltasNone() = """{"kind":"deltas","deltas":[]}"""
}
