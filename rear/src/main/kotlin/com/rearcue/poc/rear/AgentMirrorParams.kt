package com.rearcue.poc.rear

import com.rearcue.poc.agent.AgentStatus

/**
 * Agent Mirror 渲染参数（spec 0010 / 票 #84，纯函数——JVM 判例沿 [ChargingWater]）：
 * 状态 × 屏幕几何 → 布局参数（含票 #105 Approval Glow 的光带参数）。渲染层零决策照单执行，
 * 几何语义锁死在单测。
 *
 * 输入约定：[screenWidthPx]/[screenHeightPx] 是背屏窗口像素（任意方向，函数内部按长短边
 * 归一——小米 17 Pro 背屏 904×572 与旋转态同参）。
 */
object AgentMirrorParams {

    /** 状态标语的字号档（sp，按 572 短边基准；等确认档放大一级——插队状态要一眼可辨）。 */
    const val STATUS_SP_BASE = 18f
    const val STATUS_SP_EMPHASIS = 22f

    /** 最新回复正文的字号档（sp）：镜像的主体阅读面（票 #86 实机修订：回复区独占剩余高度＋内部滚动，
     * 不再用 maxLines 截断——历史经验：maxLines 档在小屏把核心阅读面推出视口）。 */
    const val REPLY_SP_BASE = 16f

    /**
     * 当前动作行的最大字符数（显示面二次截断；数据面截断在 :agent 的
     * ACTION_SUMMARY_MAX_CHARS，这里是背屏一行的保守上限）。
     */
    const val ACTION_MAX_CHARS = 60

    // —— Approval Glow（CONTEXT.md「Approval Glow」/ 票 #105）纯函数参数 ——

    /** 明暗起伏周期（ms）：一个「亮→暗→亮」完整来回。 */
    const val GLOW_CYCLE_MS = 2400

    /** 起伏亮度下限（alpha）：>0 ——「持续点亮」，任何相位都不熄灭。 */
    const val GLOW_ALPHA_MIN = 0.4f

    /** 起伏亮度上限（alpha ≤ 1）。 */
    const val GLOW_ALPHA_MAX = 1f

    /** 描边宽度取短边的比例（背屏尺寸各异，比例档比像素档稳）。 */
    const val GLOW_STROKE_RATIO = 0.012f

    /** 描边宽度夹紧下限（px）。 */
    const val GLOW_STROKE_MIN_PX = 4f

    /** 描边宽度夹紧上限（px）。 */
    const val GLOW_STROKE_MAX_PX = 12f

    fun statusSp(status: AgentStatus): Float = when (status) {
        AgentStatus.WAITING_FOR_APPROVAL -> STATUS_SP_EMPHASIS
        else -> STATUS_SP_BASE
    }

    /**
     * 状态 × 屏幕几何 → Approval Glow 光带参数（票 #105，照 [statusSp] 的纯函数惯例）：
     * 仅 Waiting-for-Approval 返回非 null（工作中/空闲不亮、状态离开 WAITING 即灭），
     * 其余状态一律 null——「是否亮」的判定收口在此，渲染层照单执行不自行决策。
     * 几何只决定描边宽度（短边比例折算并夹紧）；色值归设计令牌、圆角随屏运行时读取，
     * 都不进本函数。病态几何（未采集的 0×0）不抛错，退到夹紧下限。
     */
    fun approvalGlow(
        status: AgentStatus,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): ApprovalGlow? {
        if (status != AgentStatus.WAITING_FOR_APPROVAL) return null
        val shortEdge = minOf(screenWidthPx, screenHeightPx).coerceAtLeast(0)
        return ApprovalGlow(
            strokeWidthPx = (shortEdge * GLOW_STROKE_RATIO).coerceIn(GLOW_STROKE_MIN_PX, GLOW_STROKE_MAX_PX),
            alphaMin = GLOW_ALPHA_MIN,
            alphaMax = GLOW_ALPHA_MAX,
            cycleMs = GLOW_CYCLE_MS,
        )
    }

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

/**
 * Approval Glow 光带参数（票 #105，[AgentMirrorParams.approvalGlow] 的输出）：
 * 等确认存续期背屏边缘环绕灯带的渲染输入——持续点亮（[alphaMin] > 0）＋亮度周期性
 * 起伏（[cycleMs]）。不发声、不震动；与状态文字 3 秒脉冲（票 #85）分工共存，
 * 谁都不改谁的语义。
 */
data class ApprovalGlow(
    /** 环带描边宽度（px，短边比例折算后夹紧）。 */
    val strokeWidthPx: Float,
    /** 起伏亮度下限（alpha，>0——任何相位都亮着）。 */
    val alphaMin: Float,
    /** 起伏亮度上限（alpha ≤ 1）。 */
    val alphaMax: Float,
    /** 一个明暗起伏周期（ms）。 */
    val cycleMs: Int,
)
