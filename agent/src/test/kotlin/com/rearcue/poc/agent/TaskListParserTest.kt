package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskListParserTest {

    /** 真实 wire 形态（票 #86 phase B capture 摘录，字段名逐字）。 */
    private val payload = """
        {"requestId":"probe-b1","result":{
          "desktopAppVersion":"3.14.3",
          "activeWorkspaceKey":"C:\\\\Users\\\\13691\\\\Desktop\\\\RearCue",
          "tasks":[
            {"createdAt":1,"displayStatus":"completed","taskId":"s_old","title":"旧任务","updatedAt":100,"workspaceKind":"local","workspaceLabel":"old","workspacePath":"p","archived":true},
            {"createdAt":2,"displayStatus":"running","taskId":"s_run","title":"跑着的长任务","updatedAt":300,"workspaceKind":"local","workspaceLabel":"RearCue","workspacePath":"p"},
            {"createdAt":3,"displayStatus":"completed","taskId":"s_done","title":"已完成","updatedAt":200,"workspaceKind":"local","workspaceLabel":"default","workspacePath":"p"}
          ]}}
    """.trimIndent()

    @Test
    fun `解析任务表_取未归档最近活跃_映射状态`() {
        val state = TaskListParser.parse(payload)!!
        assertEquals("s_run", state.sessionId)
        assertEquals(AgentStatus.WORKING, state.status)
        assertEquals("跑着的长任务", state.currentAction)
        assertEquals("RearCue", state.workspace)
        assertEquals(300L, state.updatedAt)
        assertNull(state.latestReply)
    }

    @Test
    fun `全部归档或空表返回 null`() {
        assertNull(TaskListParser.parse("""{"result":{"tasks":[{"archived":true,"taskId":"a","displayStatus":"running","title":"t","updatedAt":1,"workspaceLabel":"w"}]}}"""))
        assertNull(TaskListParser.parse("""{"result":{"tasks":[]}}"""))
    }

    @Test
    fun `非任务响应忽略`() {
        assertNull(TaskListParser.parse("""{"zcode_type":"rpc-frame"}"""))
        assertNull(TaskListParser.parse("not json"))
    }

    @Test
    fun `请求帧形态`() {
        assertEquals("""{"zcode_type":"workspace-list-request","requestId":"w1"}""", TaskListParser.listRequest("w1"))
        assertEquals("""{"zcode_type":"bootstrap-request","requestId":"b1"}""", TaskListParser.bootstrapRequest("b1"))
    }
}
