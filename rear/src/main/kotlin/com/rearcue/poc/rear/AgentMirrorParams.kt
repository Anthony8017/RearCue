package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentStatus
import kotlin.math.max

/**
 * Agent Mirror 渲染参数（spec 0010 / 票 #84，纯函数——JVM 判例沿 [ChargingWater]）：
 * 状态 × 屏幕几何 → 布局参数。渲染层零决策照单执行，几何语义锁死在单测。
 *
 * 输入约定：[screenWidthPx]/[screenHeightPx] 是背屏窗口像素（任意方向，函数内部按长短边
 * 归一——小米 17 Pro 背屏 904×572 与旋转态同参）。
 */
object AgentMirrorParams {

    /** 状态标语的字号档（sp，按 572 短边基准；等确认档放大一级——插队状态要一眼可辨）。 */
    const val STATUS_SP_BASE = 18f
    const val STATUS_SP_EMPHASIS = 22f

    /** 最新回复正文的字号档（sp）：镜像的主体阅读面。 */
    const val REPLY_SP_BASE = 16f

    /** 回文最大行数：短屏少显、长屏多显（回复不是历史回看，spec 0010 Out of Scope）。 */
    const val REPLY_MAX_LINES_SHORT = 8
    const val REPLY_MAX_LINES_LONG = 14

    /** 长短边判定阈值（px）：低于此短边按小屏参数。 */
    const val SMALL_SCREEN_SHORT_EDGE_PX = 400

    /**
     * 当前动作行的最大字符数（显示面二次截断；数据面截断在 :agent 的
     * ACTION_SUMMARY_MAX_CHARS，这里是背屏一行的保守上限）。
     */
    const val ACTION_MAX_CHARS = 60

    fun statusSp(status: AgentStatus): Float = when (status) {
        AgentStatus.WAITING_FOR_APPROVAL -> STATUS_SP_EMPHASIS
        else -> STATUS_SP_BASE
    }

    fun replyMaxLines(shortEdgePx: Int): Int =
        if (shortEdgePx < SMALL_SCREEN_SHORT_EDGE_PX) REPLY_MAX_LINES_SHORT else REPLY_MAX_LINES_LONG

    /** 内容水平留白（px）：短边的 8%，不低于 24dp×2 的等价安全量由调用方兜底。 */
    fun horizontalPaddingPx(shortEdgePx: Int): Int = max(48, (shortEdgePx * 0.08f).toInt())

    /**
     * 当前动作行的显示截断：null/空 → null（不显示该行）；超长 → 截断加省略号。
     */
    fun actionLine(action: String?): String? {
        if (action.isNullOrBlank()) return null
        val trimmed = action.trim()
        if (trimmed.length <= ACTION_MAX_CHARS) return trimmed
        return trimmed.take(ACTION_MAX_CHARS - 1) + "…"
    }
}
