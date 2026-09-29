package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.agent.AgentTurnRole
import com.rearcue.poc.rear.MirrorScrollPolicy.Follow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 实时滚动跟随状态机测试（grilling #115）：上滑打断回看、滚到底/按钮恢复、
 * 新输出仅在跟随态滚底（回看不打断）。只断言纯函数的事件 → 状态转移。
 */
class MirrorScrollPolicyTest {

    @Test
    fun `上滑（滚动值减小）打断跟随进入回看`() {
        assertEquals(Follow.PAUSED, MirrorScrollPolicy.onValueChange(Follow.FOLLOWING, prev = 100, next = 60, maxValue = 500))
        assertEquals(Follow.PAUSED, MirrorScrollPolicy.onValueChange(Follow.PAUSED, prev = 60, next = 20, maxValue = 500))
    }

    @Test
    fun `手动滚到底（增大且触底）恢复跟随`() {
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onValueChange(Follow.PAUSED, prev = 420, next = 500, maxValue = 500))
    }

    @Test
    fun `内容变短被钳回底（减小且触底）不误判为上滑`() {
        // 新回复比旧文本短：滚动域收缩把 value 钳到新底——这不是用户上滑（评审修复）。
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onValueChange(Follow.FOLLOWING, prev = 500, next = 300, maxValue = 300))
        // 回看态遇到钳底同样回跟随（内容已变，停留旧位置无意义）。
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onValueChange(Follow.PAUSED, prev = 500, next = 300, maxValue = 300))
    }

    @Test
    fun `向前滚动未触底保持回看`() {
        assertEquals(Follow.PAUSED, MirrorScrollPolicy.onValueChange(Follow.PAUSED, prev = 20, next = 80, maxValue = 500))
    }

    @Test
    fun `上滑暂停后小幅下拖未触底仍保持回看`() {
        // 回归：长驻滚动监听若闭包捕获旧 FOLLOWING，会把这次下拖误判回跟随（评审修复）。
        val paused = MirrorScrollPolicy.onValueChange(
            Follow.FOLLOWING,
            prev = 100,
            next = 60,
            maxValue = 500,
        )
        assertEquals(Follow.PAUSED, paused)
        assertEquals(
            Follow.PAUSED,
            MirrorScrollPolicy.onValueChange(paused, prev = 60, next = 80, maxValue = 500),
        )
    }

    @Test
    fun `程序化滚底（跟随态增大触底）不改变状态`() {
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onValueChange(Follow.FOLLOWING, prev = 400, next = 500, maxValue = 500))
    }

    @Test
    fun `无滚动空间（max=0）时不因零值变化打断跟随`() {
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onValueChange(Follow.FOLLOWING, prev = 0, next = 0, maxValue = 0))
    }

    @Test
    fun `恢复按钮点按回跟随`() {
        assertEquals(Follow.FOLLOWING, MirrorScrollPolicy.onResumeTap())
    }

    @Test
    fun `新输出仅跟随态滚底_回看态不打断`() {
        assertTrue(MirrorScrollPolicy.shouldFollowNewOutput(Follow.FOLLOWING))
        assertFalse(MirrorScrollPolicy.shouldFollowNewOutput(Follow.PAUSED))
    }

    // ---- 回看时屏上静止（spec 0017 / 票 #169） ----

    private fun ask(text: String) = AgentTurn(role = AgentTurnRole.USER, text = text)

    @Test
    fun `跟随态下版式与内容都跟最新`() {
        val live = listOf(ask("问一"), AgentTurn(AgentTurnRole.AGENT, "答一"))
        assertTrue(MirrorScrollPolicy.shouldApplyLayoutUpdate(Follow.FOLLOWING))
        assertEquals(live, MirrorScrollPolicy.effectiveTurns(Follow.FOLLOWING, frozen = emptyList(), live = live))
    }

    @Test
    fun `回看态下版式冻住_新内容不进屏`() {
        val frozen = listOf(ask("问一"), AgentTurn(AgentTurnRole.AGENT, "答一"))
        val live = frozen + listOf(ask("问二"), AgentTurn(AgentTurnRole.AGENT, "答二"))
        assertFalse(
            MirrorScrollPolicy.shouldApplyLayoutUpdate(Follow.PAUSED),
            "回看中不许改字号档位等版式参数——那会让整屏重排、视线被拉走",
        )
        assertEquals(
            frozen,
            MirrorScrollPolicy.effectiveTurns(Follow.PAUSED, frozen = frozen, live = live),
            "回看中屏上一字不动：新内容只在恢复跟随后一次性接上",
        )
    }

    @Test
    fun `回看态但还没冻过_回到实时内容（不把屏看空）`() {
        val live = listOf(ask("问一"), AgentTurn(AgentTurnRole.AGENT, "答一"))
        assertEquals(
            live,
            MirrorScrollPolicy.effectiveTurns(Follow.PAUSED, frozen = emptyList(), live = live),
        )
    }

    @Test
    fun `没内容可渲染时不给冻结快照（避免屏上出现空窗）`() {
        val frozen = listOf(ask("旧的"))
        assertEquals(
            emptyList<AgentTurn>(),
            MirrorScrollPolicy.effectiveTurns(
                Follow.PAUSED,
                frozen = frozen,
                live = emptyList(),
                contentAvailable = false,
            ),
            "内容层已撤（断连兜底等）时显示冻结快照就会留一屏残影",
        )
    }
}
