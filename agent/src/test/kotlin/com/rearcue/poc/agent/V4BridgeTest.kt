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
        val subscribe = frames.last()
        assertEquals("subscribeConversationV4", subscribe.name)
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

        // 时钟不推进（仍在 30s 节流窗内）：锁定换向仍立即退旧订新
        locked = "sess_lock"
        b.onSessionLockChanged()
        val frames = clientFrames()
        val unsub = frames.last { it.name == "unsubscribeConversationV4" }
        assertEquals("sub-1", (unsub.data as JsonArray)[0].jsonObject["subscriptionId"]!!.jsonPrimitive.content)
        val resub = frames.last()
        assertEquals("subscribeConversationV4", resub.name)
        assertEquals("sess_lock", (resub.data as JsonArray)[0].jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertEquals("sess_lock", b.subscribedTask())
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
        val ids = handshakeThroughSubscribe(b) // ghost 不在表：开桥与首订仍是最近活跃 sess_1
        assertEquals("sess_1", b.subscribedTask())
        val subscribe = clientFrames().last()
        assertEquals("subscribeConversationV4", subscribe.name)
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
        locked = "sess_lock"
        b.onSessionLockChanged()
        assertEquals("sess_lock", b.subscribedTask())

        locked = null
        channel.clear()
        b.onSessionLockChanged()
        assertTrue(channel.isEmpty(), "自动档不主动退订，等下一轮任务表按节流跟随")
    }
}
