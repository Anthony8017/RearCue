package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 票 #88 wire 实证契约：EventFire 帧 = `{topic, subscriptionId, fromSeq, toSeq, payload}`，
 * payload.kind ∈ snapshot（snapshot.rows.window 全量）/ deltas（op 逐条）。
 * 行用真实 kind（assistantText / toolCall / turnHeader）。
 */
class ConversationFeedTest {

    private val clock = longArrayOf(1_000L)

    private fun feed(sessionId: String = "sess_1") = ConversationFeed(sessionId = sessionId, nowMs = { clock[0] })

    private fun assistantRow(id: Long, text: String, state: String = "complete") =
        """{"rowId":$id,"turnId":"t1","kind":"assistantText","text":"$text","state":"$state","createdAt":1}"""

    private fun toolRow(id: Long, tool: String, status: String, input: String) =
        """{"rowId":$id,"turnId":"t1","kind":"toolCall","toolCallId":"c$id","toolName":"$tool","status":"$status","inputText":"$input","createdAt":1}"""

    private fun turnHeader(id: Long, state: String) =
        """{"rowId":$id,"turnId":"t1","kind":"turnHeader","origin":"userInput","state":"$state","createdAt":1}"""

    private fun snapshotFrame(vararg rows: String): String = buildJsonObject {
        put("topic", "conversation/sess_1")
        put("subscriptionId", "sub-1")
        put("fromSeq", 1)
        put("toSeq", 1)
        put("sentAt", 1)
        putJsonObject("payload") {
            put("kind", "snapshot")
            putJsonObject("snapshot") {
                putJsonObject("rows") {
                    putJsonArray("window") {
                        rows.forEach { add(Json.parseToJsonElement(it)) }
                    }
                }
            }
        }
    }.toString()

    private fun deltasFrame(vararg ops: String): String = buildJsonObject {
        put("topic", "conversation/sess_1")
        put("subscriptionId", "sub-1")
        put("fromSeq", 2)
        put("toSeq", 3)
        put("sentAt", 2)
        putJsonObject("payload") {
            put("kind", "deltas")
            putJsonArray("deltas") {
                ops.forEach { add(Json.parseToJsonElement(it)) }
            }
        }
    }.toString()

    private fun apply(feed: ConversationFeed, frameJson: String): AgentSessionState? =
        feed.applyFrame(Json.parseToJsonElement(frameJson))

    @Test
    fun `snapshot 全量替换_产出回复与状态`() {
        val feed = feed()
        val state = apply(feed, snapshotFrame(assistantRow(1, "在改文件")))
        assertNotNull(state)
        assertEquals("sess_1", state.sessionId)
        assertEquals("在改文件", state.latestReply)
        assertEquals(AgentStatus.IDLE, state.status)
    }

    @Test
    fun `toolCall running 触发 Working_pendingApproval 触发等待确认`() {
        val feed = feed()
        apply(feed, snapshotFrame(assistantRow(1, "好的")))
        val working = apply(
            feed,
            deltasFrame("""{"op":"row.appended","row":${toolRow(2, "edit", "running", "A.kt")}}"""),
        )
        assertEquals(AgentStatus.WORKING, working?.status)

        val waiting = apply(
            feed,
            deltasFrame("""{"op":"row.upserted","row":${toolRow(3, "bash", "pendingApproval", "rm -rf")}}"""),
        )
        assertEquals(AgentStatus.WAITING_FOR_APPROVAL, waiting?.status)
    }

    @Test
    fun `turnHeader running 视为进行中_完成后回落`() {
        val feed = feed()
        val working = apply(feed, snapshotFrame(turnHeader(1, "running")))
        assertEquals(AgentStatus.WORKING, working?.status)

        val done = apply(
            feed,
            deltasFrame("""{"op":"row.upserted","row":${turnHeader(1, "completedSuccess")}}"""),
        )
        assertEquals(AgentStatus.IDLE, done?.status)
    }

    @Test
    fun `row delta 文本增量拼接回复`() {
        val feed = feed()
        apply(feed, snapshotFrame(assistantRow(1, "前半", state = "streaming")))
        val state = apply(feed, deltasFrame("""{"op":"row.delta","rowId":1,"path":"text","append":"后半"}"""))
        assertEquals("前半后半", state?.latestReply)
    }

    @Test
    fun `row removed 按 fromRowId 截断`() {
        val feed = feed()
        apply(feed, snapshotFrame(assistantRow(1, "旧回复"), assistantRow(2, "新回复")))
        val state = apply(feed, deltasFrame("""{"op":"row.removed","fromRowId":2}"""))
        assertEquals("旧回复", state?.latestReply)
    }

    @Test
    fun `重复 upsert 幂等_最新回复不漂移`() {
        val feed = feed()
        val row = assistantRow(1, "同一条")
        apply(feed, snapshotFrame(row))
        apply(feed, deltasFrame("""{"op":"row.upserted","row":$row}"""))
        val state = apply(feed, deltasFrame("""{"op":"row.upserted","row":$row}"""))
        assertEquals("同一条", state?.latestReply)
    }

    @Test
    fun `wire 包裹层帧实机形态_帧在 frame 键下`() {
        // 实机 EventFire（2026-09-27 23:22 dump 形态）：data = {wireVersion, kind:"complete",
        // topic, subscriptionId, frame:{topic, ..., payload:{kind, snapshot:{rows:{window}}}}}
        val feed = feed()
        val wire = buildJsonObject {
            put("wireVersion", 3)
            put("kind", "complete")
            put("deliveryKind", "initial")
            put("topic", "conversation/sess_1")
            put("subscriptionId", "sub-x")
            put(
                "frame",
                buildJsonObject {
                    put("topic", "conversation/sess_1")
                    put("subscriptionId", "sub-x")
                    put("fromSeq", 0)
                    put("toSeq", 761)
                    put(
                        "payload",
                        buildJsonObject {
                            put("kind", "snapshot")
                            put(
                                "snapshot",
                                buildJsonObject {
                                    put(
                                        "rows",
                                        buildJsonObject {
                                            put(
                                                "window",
                                                buildJsonArray {
                                                    add(
                                                        buildJsonObject {
                                                            put("rowId", 192)
                                                            put("kind", "assistantText")
                                                            put("text", "主页没看到按钮")
                                                            put("state", "complete")
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val state = feed.applyFrame(wire)
        assertEquals("主页没看到按钮", state?.latestReply)
        assertEquals(AgentStatus.IDLE, state?.status)
    }

    @Test
    fun `非帧输入与坏输入忽略返回 null`() {
        val feed = feed()
        assertNull(feed.applyFrame(null))
        assertNull(feed.applyFrame(Json.parseToJsonElement(""""not-obj"""")))
        assertNull(
            feed.applyFrame(
                Json.parseToJsonElement("""{"topic":"x","payload":{"kind":"unknown-kind"}}"""),
            ),
        )
    }

    @Test
    fun `rebind 换会话清行集`() {
        val feed = feed("sess_1")
        apply(feed, snapshotFrame(assistantRow(1, "旧会话")))
        val bound = feed.rebind("sess_2")
        assertEquals("sess_2", bound.boundSessionId)
        assertNull(bound.snapshotState().latestReply) // 新实例行集为空
        assertEquals(AgentStatus.IDLE, bound.snapshotState().status)
    }
}
