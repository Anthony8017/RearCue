package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentTurn
import com.rearcue.poc.core.MirrorTextSize

/**
 * Agent Mirror 实时滚动跟随状态机（2026-09-28 grilling #115，纯函数——JVM 判例沿
 * [ChargingWater] / [IconGrid]）：跟随（FOLLOWING）、回看（PAUSED）与未阅入场固定阅读（READING），
 * 渲染层零决策照单执行。
 *
 * 转移语义（Q6/Q7 定案）：
 * - 上滑回看：滚动值**减小**即入 PAUSED（任何上滑打断自动跟随，可查历史）；
 * - 回到底部：滚动值**增大且触底**即回 FOLLOWING（手动滚回底部等效点恢复按钮）；
 * - 恢复按钮：点按即 FOLLOWING（调用方同时滚到底）；
 * - 新输出到达：仅 FOLLOWING 时滚到底（PAUSED 不打断、不提示）。
 *
 * 回看范围仅限当前会话（CONTEXT.md「Agent Mirror」）；调用方屏蔽程序化滚动，
 * 折叠或恢复造成的布局钳底不当作人工回看。
 */
object MirrorScrollPolicy {

    /** READING 只由 ↓ 恢复；触底与布局钳底都不改变固定阅读状态（spec 0028）。 */
    enum class Follow { FOLLOWING, PAUSED, READING }

    /**
     * 滚动值变化事件：[prev]→[next]（px），[maxValue] 为当前可滚最大值。
     * - 减小且**未触底** = 上滑回看 → PAUSED；
     * - 触底（next ≥ maxValue，无论增减）→ FOLLOWING——减小方向的触底是「内容变短被
     *   钳回底」（新回复比旧文本短时滚动域收缩，评审修复：不能误判成上滑）；
     * - 其余保持。
     */
    fun onValueChange(state: Follow, prev: Int, next: Int, maxValue: Int): Follow = when {
        state == Follow.READING -> Follow.READING // 未阅入场固定阅读，只由 ↓ 恢复跟随。
        next >= maxValue && next != prev -> Follow.FOLLOWING
        next < prev -> Follow.PAUSED
        else -> state
    }

    /** 恢复按钮点按：回 FOLLOWING（调用方随即滚到底）。 */
    fun onResumeTap(): Follow = Follow.FOLLOWING

    /** 新输出到达：是否该滚到底（仅 FOLLOWING；PAUSED 不打断回看）。 */
    fun shouldFollowNewOutput(state: Follow): Boolean = state == Follow.FOLLOWING

    // —— 回看时屏上静止（spec 0017 / 票 #169） ——

    /**
     * 版式参数（字号档位等）该不该在本次组合里生效：**回看中不生效**。
     *
     * 回看是「我已经停下来读这一段」的状态，此刻换字号会让整屏重排、视线被拉走。
     * 档位偏好仍照常存下来，恢复跟随（或下次进页面）时自然用上——只是不在回看当口生效。
     */
    fun shouldApplyLayoutUpdate(state: Follow): Boolean = state == Follow.FOLLOWING

    /**
     * 这一次组合该渲染哪一份问答流：跟随态用实时的 [live]；回看态用 [frozen]（进入回看的
     * 那一刻冻下的快照）——新输出在后台攒着，点 ↓ 或滚回底部才一次性接上最新，
     * 「读着读着整屏跳走」就此消失。
     *
     * 退路：还没冻过（[frozen] 为空）时回落到 [live]，免得回看一开始屏就空。
     * 内容层整体撤走（[live] 为空：断连兜底、投送降级等）时也回落——显示冻结快照会留一屏残影，
     * 与「内容层撤了就撤了」的口径冲突；「该不该给残影」由 [live] 本身表达，不再另设布尔。
     */
    fun effectiveTurns(
        state: Follow,
        frozen: List<AgentTurn>,
        live: List<AgentTurn>,
    ): List<AgentTurn> = when {
        state == Follow.FOLLOWING -> live
        live.isEmpty() -> live
        frozen.isEmpty() -> live
        else -> frozen
    }

    /**
     * 这一次组合该用哪一档字号：[shouldApplyLayoutUpdate] 为真（跟随态）用设置里的档位；
     * 回看态用进入回看那一刻冻下的档位——回看是「我已经停下来读这一段」的状态，
     * 此刻换字号会让整屏重排、视线被拉走。偏好照常存着，恢复跟随后自然用上。
     */
    fun effectiveTextSize(
        state: Follow,
        frozen: MirrorTextSize,
        configured: MirrorTextSize,
    ): MirrorTextSize = if (shouldApplyLayoutUpdate(state)) configured else frozen
}
