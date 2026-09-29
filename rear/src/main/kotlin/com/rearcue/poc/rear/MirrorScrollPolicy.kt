package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentTurn

/**
 * Agent Mirror 实时滚动跟随状态机（2026-09-28 grilling #115，纯函数——JVM 判例沿
 * [ChargingWater] / [IconGrid]）：**跟随（FOLLOWING）/ 回看（PAUSED）** 两态，
 * 渲染层零决策照单执行。
 *
 * 转移语义（Q6/Q7 定案）：
 * - 上滑回看：滚动值**减小**即入 PAUSED（任何上滑打断自动跟随，可查历史）；
 * - 回到底部：滚动值**增大且触底**即回 FOLLOWING（手动滚回底部等效点恢复按钮）；
 * - 恢复按钮：点按即 FOLLOWING（调用方同时滚到底）；
 * - 新输出到达：仅 FOLLOWING 时滚到底（PAUSED 不打断、不提示）。
 *
 * 回看范围仅限当前会话（CONTEXT.md「Agent Mirror」）；程序化滚动只增不减，
 * 不会误触暂停。
 */
object MirrorScrollPolicy {

    /** 跟随状态：FOLLOWING = 自动跟随滚底；PAUSED = 用户回看历史中。 */
    enum class Follow { FOLLOWING, PAUSED }

    /**
     * 滚动值变化事件：[prev]→[next]（px），[maxValue] 为当前可滚最大值。
     * - 减小且**未触底** = 上滑回看 → PAUSED；
     * - 触底（next ≥ maxValue，无论增减）→ FOLLOWING——减小方向的触底是「内容变短被
     *   钳回底」（新回复比旧文本短时滚动域收缩，评审修复：不能误判成上滑）；
     * - 其余保持。
     */
    fun onValueChange(state: Follow, prev: Int, next: Int, maxValue: Int): Follow = when {
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
     * 两个退路：还没冻过（[frozen] 为空）时回落到 [live]，免得回看一开始屏就空；
     * [contentAvailable] 为假（内容层已撤：断连兜底、投送降级等）时不给冻结快照——
     * 那会留一屏残影，与「内容层撤了就撤了」的口径冲突。
     */
    fun effectiveTurns(
        state: Follow,
        frozen: List<AgentTurn>,
        live: List<AgentTurn>,
        contentAvailable: Boolean = true,
    ): List<AgentTurn> = when {
        state == Follow.FOLLOWING -> live
        !contentAvailable -> live
        frozen.isEmpty() -> live
        else -> frozen
    }
}
