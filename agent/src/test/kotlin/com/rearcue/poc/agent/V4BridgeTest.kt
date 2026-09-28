package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** V4Bridge 编排序列契约（票 #88：控制面开桥 → hello → initialize → listen → subscribe → 帧）。 */
class V4BridgeTest {

    private val clock = longArrayOf(4_200L)
    private val control = mutableListOf<String>()
    private val channel = mutableListOf<ByteArray>()
    private val states = mutableListOf<AgentSessionState>()
    private val indexViews = mutableListOf<List<SessionIndexEntry>>()
    private var bridgeSet: Pair<String, Int?>? = null
    private val logs = mutableListOf<String>()

    /** Session Lock 档位（票 #103）：非 null = 锁定该会话（测试换档驱动）。 */
    private var locked: String? = null

    private fun bridge(): V4Bridge = V4Bridge(
        sendControl = { control += it },
        sendChannel = { channel += it },
        onBridgeSession = { id, gen -> bridgeSet = id to gen },
        onState = { states += it },
        log = { logs += it },
        nowMs = { clock[0] },
        lockedTaskId = { locked },
        onIndex = { entries -> indexViews += entries },
    )

    private fun lastClientFrame(): ChannelCodec.ServerFrame.Unknown {
        val frame = ChannelCodec.decode(channel.last())
        assertTrue(frame is ChannelCodec.ServerFrame.Unknown, "出站应为客户端帧，实为 $frame")
        return frame
    }

    private fun clientFrames(): List<ChannelCodec.ServerFrame.Unknown> = channel.map {
        ChannelCodec.decode(it) as ChannelCodec.ServerFrame.Unknown
    }

    private fun serverSuccess(id: Long, data: JsonObject? = null): ByteArray =
        ChannelCodec.encodeRaw(
            listOf(
                buildJsonArray {
                    add(JsonPrimitive(ChannelCodec.TYPE_PROMISE_SUCCESS))
                    add(JsonPrimitive(id))
                },
                data,
            ),
        )

    private fun workspaceList(taskId: String, key: String = "C:\\ws"): String = buildJsonObject {
        put("zcode_type", "workspace-list-response")
        put("requestId", "w1")
        put("success", true)
        putJsonObjectResult(taskId, key)
    }.toString()

    /** 多任务任务表响应（票 #103）：首项 updatedAt 最大＝无锁时 selectTask 的选择。 */
    private fun workspaceListTasks(vararg taskIds: String): String = buildJsonObject {
        put("zcode_type", "workspace-list-response")
        put("requestId", "w1")
        put("success", true)
        put(
            "result",
            buildJsonObject {
                put("activeWorkspaceKey", "C:\\ws")
                put("activeTaskId", taskIds.first())
                put(
                    "tasks",
                    buildJsonArray {
                        taskIds.forEachIndexed { index, id ->
                            add(
                                buildJsonObject {
                                    put("taskId", id)
                                    put("displayStatus", "running")
                                    put("updatedAt", 1_000 - index)
                                },
                            )
                        }
                    },
                )
            },
        )
    }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObjectResult(taskId: String, key: String) {
        put(
            "result",
            buildJsonObject {
                put("activeWorkspaceKey", key)
                put("activeTaskId", taskId)
                putJsonArrayTasks(taskId)
            },
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArrayTasks(taskId: String) {
        put(
            "tasks",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("taskId", taskId)
                        put("displayStatus", "running")
                        put("updatedAt", 100)
                    },
                )
            },
        )
    }

    private fun bridgeReady(id: String = "rearcue-bridge-1", path: String = "C:\\ws"): String = buildJsonObject {
        put("zcode_type", "workspace-bridge-ready")
        put("requestId", "rb-1")
        put("bridgeSessionId", id)
        put("bridgeGeneration", 1)
        put(
            "bridge",
            buildJsonObject {
                put("bridgeSessionId", id)
                put("bridgeGeneration", 1)
                put("kind", "local")
                put("workspaceKey", "C:\\ws")
                put("workspacePath", path)
            },
        )
    }.toString()

    private fun eventFrameObject(text: String): JsonObject {
        return buildJsonObject {
            put("topic", "conversation/sess_1")
            put("subscriptionId", "sub-1")
            put("fromSeq", 1)
            put("toSeq", 1)
            put("sentAt", 1)
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
                                                    put("rowId", 1)
                                                    put("turnId", "t1")
                                                    put("kind", "assistantText")
                                                    put("text", text)
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
        }
    }

    private fun eventFire(id: Long, frame: JsonObject): ByteArray =
        ChannelCodec.encodeRaw(
            listOf(
                buildJsonArray {
                    add(JsonPrimitive(ChannelCodec.TYPE_EVENT_FIRE))
                    add(JsonPrimitive(id))
                },
                frame,
            ),
        )

    /** 推进握手到 subscribe 已发出；返回 (helloId, initId, listenId, subscribeId)。 */
    private fun handshakeThroughSubscribe(b: V4Bridge, taskId: String = "sess_1"): LongArray {
        b.onControl(workspaceList(taskId))
        val open = Json.parseToJsonElement(control.last()).jsonObject
        assertEquals("workspace-bridge-open", open["zcode_type"]!!.jsonPrimitive.content)
        assertEquals(taskId, open["taskId"]!!.jsonPrimitive.content)
        assertEquals(1, open["bridgeGeneration"]!!.jsonPrimitive.content.toInt())

        b.onControl(bridgeReady())
        assertEquals("rearcue-bridge-1" to 1, bridgeSet)
        val hello = lastClientFrame()
        assertEquals("helloConversationV4", hello.name)

        b.onChannel(serverSuccess(hello.id, buildJsonObject { put("clientMode", "web-remote-replayable") }))
        val init = lastClientFrame()
        assertEquals("initializeConversationV4", init.name)
        val initArgs = (init.data as JsonArray)[0].jsonObject
        assertEquals("web", initArgs["clientKind"]!!.jsonPrimitive.content)
        assertEquals(3, initArgs["protocolVersion"]!!.jsonPrimitive.content.toInt())

        b.onChannel(serverSuccess(init.id))
        val frames = clientFrames()
        val listen = frames.first { it.type == ChannelCodec.TYPE_EVENT_LISTEN }
        assertEquals("onDynamicConversationFrame", listen.name)
        assertEquals("C:\\ws", listen.data!!.jsonObject["workspacePath"]!!.jsonPrimitive.content)
        // 握手尾部还有 sessions-index 的监听+订阅（票 #103 P0），会话订阅按名取不按帧序。
        val subscribe = frames.first { it.name == "subscribeConversationV4" }
        assertEquals(taskId, (subscribe.data as JsonArray)[0].jsonObject["sessionId"]!!.jsonPrimitive.content)
        return longArrayOf(hello.id, init.id, listen.id, subscribe.id)
    }

    @Test
    fun `全链握手序列_帧归一产出回复状态`() {
        val b = bridge()
        val ids = handshakeThroughSubscribe(b)

        // subscribe ack 记录订阅键
        b.onChannel(
            serverSuccess(
                ids[3],
                buildJsonObject {
                    put("ack", buildJsonObject { put("subscriptionId", "sub-1"); put("mode", "snapshot") })
                },
            ),
        )
        assertEquals("sess_1", b.subscribedTask())

        // EventFire（用真实 listenId 构造）
        b.onChannel(eventFire(ids[2], eventFrameObject("真实回复")))
        assertEquals(1, states.size)
        assertEquals("真实回复", states.last().latestReply)
        assertEquals(AgentStatus.IDLE, states.last().status)
        assertEquals(4_200L, states.last().updatedAt)
    }

    @Test
    fun `换任务先退订旧订阅再订新`() {
        val b = bridge()
        val ids = handshakeThroughSubscribe(b)
        b.onChannel(
            serverSuccess(ids[3], buildJsonObject { put("ack", buildJsonObject { put("subscriptionId", "sub-1") }) }),
        )

        clock[0] += V4Bridge.TASK_SWITCH_THROTTLE_MS + 1_000 // 越过换向节流窗
        b.onControl(workspaceList("sess_2"))
        val frames = clientFrames()
        val unsub = frames.last { it.name == "unsubscribeConversationV4" }
        assertEquals("sub-1", (unsub.data as JsonArray)[0].jsonObject["subscriptionId"]!!.jsonPrimitive.content)
        val resub = frames.last()
        assertEquals("subscribeConversationV4", resub.name)
        assertEquals("sess_2", (resub.data as JsonArray)[0].jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertEquals("sess_2", b.subscribedTask())
    }

    @Test
    fun `reset 清空桥状态后重新开桥`() {
        val b = bridge()
        handshakeThroughSubscribe(b)
        control.clear()
        channel.clear()
        b.reset()
        assertEquals(null, b.subscribedTask())
        b.onControl(workspaceList("sess_1"))
        assertEquals(1, control.size, "reset 后应重新开桥")
    }

    @Test
    fun `无 workspaceKey 的任务响应不开桥`() {
        val b = bridge()
        val text = buildJsonObject {
            put("zcode_type", "workspace-list-response")
            put("requestId", "w1")
            put("success", true)
            put("result", buildJsonObject { put("tasks", buildJsonArray { }) })
        }.toString()
        b.onControl(text)
        assertTrue(control.isEmpty())
    }

    @Test
    fun `bridge error 记日志不抛`() {
        val b = bridge()
        b.onControl(
            buildJsonObject {
                put("zcode_type", "workspace-bridge-error")
                put("requestId", "rb-9")
                put("reason", "workspace-closed")
                put("error", "gone")
            }.toString(),
        )
        assertTrue(logs.any { it.contains("bridge-error") })
    }

    // ---------- Session Lock 订阅跟随（票 #103） ----------

    @Test
    fun `锁定换向即时改订_绕过换向节流`() {
        val b = bridge()
        val ids = handshakeThroughSubscribe(b)
        b.onChannel(
            serverSuccess(ids[3], buildJsonObject { put("ack", buildJsonObject { put("subscriptionId", "sub-1") }) }),
        )
        assertEquals("sess_1", b.subscribedTask())

        // 锁目标先入册（换向跟随的在册前提）；时钟不推进（仍在 30s 节流窗内）仍立即退旧订新
        b.onControl(workspaceListTasks("sess_1", "sess_lock"))
        locked = "sess_lock"
        b.onSessionLockChanged()
        val frames = clientFrames()
        val unsub = frames.last { it.name == "unsubscribeConversationV4" }
        assertEquals("sub-1", (unsub.data as JsonArray)[0].jsonObject["subscriptionId"]!!.jsonPrimitive.content)
        val resub = frames.last { it.name == "subscribeConversationV4" }
        assertEquals("sess_lock", (resub.data as JsonArray)[0].jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertEquals("sess_lock", b.subscribedTask())
    }

    @Test
    fun `锁定换向锁不在册_不改订幽灵会话`() {
        val b = bridge()
        handshakeThroughSubscribe(b)
        val before = clientFrames().size

        // 最近一次任务表没有 ghost：换向跟随按在册校验拦下（与 workspace-list 路径同口径），
        // 既不改 activeTaskId 也不发任何订阅帧。
        locked = "ghost-lock"
        b.onSessionLockChanged()
        assertEquals("sess_1", b.subscribedTask())
        assertEquals(before, clientFrames().size, "幽灵锁不得产生订阅帧")
        assertTrue(logs.any { it.contains("v4 lock-follow skip task=ghost-lock") }, "logs=$logs")
    }

    @Test
    fun `任务表响应锁定优先_节流窗内仍改订`() {
        val b = bridge()
        val ids = handshakeThroughSubscribe(b)
        b.onChannel(
            serverSuccess(ids[3], buildJsonObject { put("ack", buildJsonObject { put("subscriptionId", "sub-1") }) }),
        )

        // 无锁时 updatedAt 最大仍是 sess_1（粘滞不换）；锁定 sess_2 → 本轮响应即改订
        locked = "sess_2"
        b.onControl(workspaceListTasks("sess_1", "sess_2"))
        assertEquals("sess_2", b.subscribedTask())
        assertEquals("sess_2", clientFrames().last().let {
            (it.data as JsonArray)[0].jsonObject["sessionId"]!!.jsonPrimitive.content
        })
    }

    @Test
    fun `锁不在册回退最近活跃_不订幽灵会话`() {
        locked = "ghost"
        val b = bridge()
        handshakeThroughSubscribe(b) // ghost 不在表：开桥与首订仍是最近活跃 sess_1
        assertEquals("sess_1", b.subscribedTask())
        val subscribe = clientFrames().first { it.name == "subscribeConversationV4" }
        assertEquals("sess_1", (subscribe.data as JsonArray)[0].jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertEquals(
            "sess_1",
            Json.parseToJsonElement(control.last()).jsonObject["taskId"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `切回自动档不主动换订_回既有节流跟随`() {
        val b = bridge()
        handshakeThroughSubscribe(b)
        b.onControl(workspaceListTasks("sess_1", "sess_lock")) // 锁目标入册（换向跟随前提）
        locked = "sess_lock"
        b.onSessionLockChanged()
        assertEquals("sess_lock", b.subscribedTask())

        locked = null
        channel.clear()
        b.onSessionLockChanged()
        assertTrue(channel.isEmpty(), "自动档不主动退订，等下一轮任务表按节流跟随")
    }

    // ---------- sessions-index 等待视图（票 #103 P0） ----------

    @Test
    fun `握手即订sessions-index_帧回调全量等待视图`() {
        val b = bridge()
        handshakeThroughSubscribe(b)

        // 索引监听 + 索引订阅与会话面并存（订阅工作区＝桥工作区）
        val frames = clientFrames()
        val indexListen = frames.first { it.name == "onDynamicSessionsIndexFrame" }
        assertEquals(ChannelCodec.TYPE_EVENT_LISTEN, indexListen.type)
        assertEquals("C:\\ws", indexListen.data!!.jsonObject["workspacePath"]!!.jsonPrimitive.content)
        val indexSubscribe = frames.first { it.name == "subscribeSessionsIndexV4" }
        assertEquals("C:\\ws", (indexSubscribe.data as JsonArray)[0].jsonObject["workspacePath"]!!.jsonPrimitive.content)
        // 会话监听仍是第一枚（帧序：会话 listen → 会话 subscribe → 索引 listen → 索引 subscribe）
        assertEquals("onDynamicConversationFrame", frames.first { it.type == ChannelCodec.TYPE_EVENT_LISTEN }.name)

        // 索引订阅 ack 记订阅键
        b.onChannel(
            serverSuccess(
                indexSubscribe.id,
                buildJsonObject { put("ack", buildJsonObject { put("subscriptionId", "sub-idx-1") }) },
            ),
        )
        assertTrue(logs.any { it.contains("v4 sessions-index ack path=C:\\ws subId=sub-idx-1") }, "logs=$logs")

        // 真实形态索引快照（含一个等确认会话）→ 全量视图回调
        b.onChannel(eventFire(indexListen.id, indexFrameSnapshot()))
        assertEquals(1, indexViews.size, "快照应外发一次全量视图")
        assertEquals(
            setOf("sess_lock" to false, "sess_wait" to true),
            indexViews.last().map { it.sessionId to it.waiting }.toSet(),
        )
        // 与会话面互不串线：会话订阅仍是握手时那条，索引帧不进会话状态回调
        assertEquals("sess_1", b.subscribedTask())
        assertTrue(states.isEmpty(), "索引帧不得进会话状态回调")
    }

    @Test
    fun `索引快照重放_视图未变不重复回调`() {
        val b = bridge()
        handshakeThroughSubscribe(b)
        val frames = clientFrames()
        val indexListen = frames.first { it.name == "onDynamicSessionsIndexFrame" }
        b.onChannel(eventFire(indexListen.id, indexFrameSnapshot()))
        b.onChannel(eventFire(indexListen.id, indexFrameSnapshot()))
        assertEquals(1, indexViews.size, "内容未变不外发（接线层免空刷）")
    }

    /** 真实形态 sessions-index 快照帧（schema 依据见 [SessionIndexFeed]）。 */
    private fun indexFrameSnapshot(): JsonObject = buildJsonObject {
        put("wireVersion", 3)
        put("kind", "complete")
        put("deliveryKind", "initial")
        put("topic", "sessions-index/C:\\ws")
        put("subscriptionId", "sub-idx-1")
        put(
            "frame",
            buildJsonObject {
                put("topic", "sessions-index/C:\\ws")
                put("subscriptionId", "sub-idx-1")
                put("fromSeq", 0)
                put("toSeq", 2)
                put("sentAt", 1_000)
                put(
                    "payload",
                    buildJsonObject {
                        put("kind", "snapshot")
                        put(
                            "snapshot",
                            buildJsonObject {
                                put("protocolVersion", 1)
                                put("workspaceId", "ws-1")
                                put("logEpoch", "epoch-1")
                                put(
                                    "sessions",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("sessionId", "sess_lock")
                                                put("workspaceId", "ws-1")
                                                put("phase", "running")
                                                put("sessionEnded", false)
                                                put("lastActivityAt", 900)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("sessionId", "sess_wait")
                                                put("workspaceId", "ws-1")
                                                put("phase", "running")
                                                put("sessionEnded", false)
                                                put(
                                                    "pendingInteraction",
                                                    buildJsonObject {
                                                        put("interactionId", "perm_1")
                                                        put("kind", "permission")
                                                    },
                                                )
                                                put(
                                                    "pendingInteractionSummary",
                                                    buildJsonObject {
                                                        put("permissionCount", 1)
                                                        put("userInputCount", 0)
                                                    },
                                                )
                                                put("lastActivityAt", 950)
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
}
