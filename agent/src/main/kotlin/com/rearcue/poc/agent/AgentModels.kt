package com.rearcue.poc.agent

/**
 * Agent Mirror 的会话状态模型（spec 0010 / CONTEXT.md「Agent Mirror」「Waiting-for-Approval」）。
 *
 * 纯 Kotlin：决策在 [:core] 的 DashboardCore，本模块只产出事实；渲染在 [:rear]。
 */

enum class AgentStatus {
    /** 回合进行中（工具运行或流式输出）。 */
    WORKING,

    /** agent 停下等待用户批准/输入——背屏永远优先插队的状态。 */
    WAITING_FOR_APPROVAL,

    /** 无进行中回合且无等待。 */
    IDLE,

    /**
     * 会话级失败/崩溃（来源报错，spec 0018-3 / CONTEXT.md「Agent Alert」）：
     * 「出错」提醒的来源语义——工具在回合内的失败是常态，不算本态，只有来源明说
     * 会话级出错（桥词表 `error`）才进这里。仲裁选取不为它设档（提醒负责叫人）。
     */
    ERROR,
}

/** 单个会话的镜像事实。多会话并存时的选择（最近活跃、等确认插队）是 [:core] 的仲裁。 */
data class AgentSessionState(
    val sessionId: String,
    val workspace: String? = null,
    val status: AgentStatus = AgentStatus.IDLE,
    /** 最近运行中工具的单行摘要，如「edit src/App.kt」。 */
    val currentAction: String? = null,
    /** 最新一条助手回复原文（不打码，spec 定案）。 */
    val latestReply: String? = null,
    val updatedAt: Long = 0L,
    /** 来源（[AgentSources] 的 zcode / codex / claude / dsh）；旧事件缺省 null。 */
    val source: String? = null,
    /**
     * 一句话摘要（spec 0018-1 契约留位，#173 提醒与 #174 批准上下文消费）：如「想修改 xx 文件」。
     * 可缺省（来源未给即 null，功能退化不崩）；桥侧回填链按会话粘住。
     */
    val summary: String? = null,
    /**
     * 问答流（spec 0017 / 票 #169）：机主提问与 agent 输出按时间顺序同流。
     *
     * 空列表 ＝ 该来源只给了旧的单条 [latestReply]（桥未升级或旧事件），渲染层据此回落旧口径；
     * 非空时**以本列表为准**，[latestReply] 不再是渲染输入。
     */
    val turns: List<AgentTurn> = emptyList(),
)

/** 问答流里的一条。 */
data class AgentTurn(
    val role: AgentTurnRole,
    /** 原文（不打码；Markdown 标记在渲染层剥离）。 */
    val text: String,
    /** 到达时间（epoch ms）；缺省 0 ＝ 来源未给。 */
    val ts: Long = 0L,
    /**
     * 这一条**仍在增长**（Claude `MessageDisplay` 的中间批、ZCode 的流式增量）：
     * 手机端对同一条做追加而不是新增一行。
     */
    val open: Boolean = false,
)

/** 说话人：机主提问 / agent 输出。 */
enum class AgentTurnRole { USER, AGENT }


object AgentSources {
    const val ZCODE = "zcode"
    const val CODEX = "codex"
    const val CLAUDE = "claude"
    /** DSH（DeepSeek Harness，ADR 0010 / spec 0018-1）：经 PC 桥的第四来源。 */
    const val DSH = "dsh"
}

/**
 * 会话键的来源归属（spec 0016 / 票 #155）：桥来源的键由 [BridgeEventCodec.SESSION_PREFIX]
 * 隔离在两源共用的键空间里，故「这条锁属于哪个来源」可由键本身判定——清锁分源
 * （ZCode 沿「任务表消失即清」、桥按在册快照对账清）据此判，不新增第二份来源记账。
 */
object AgentSessionKeys {

    /** 是否桥来源（`bridge:` 前缀）。 */
    fun isBridge(sessionId: String): Boolean = sessionId.startsWith(BridgeEventCodec.SESSION_PREFIX)
}

/**
 * 会话行（Conversation V4 snapshot/delta 的归一化中间形态）。
 *
 * 行模型按 ZCode part 结构固化（spec 0010 调研：type = text|reasoning|tool|step-start|step-finish；
 * tool 的 state.status = inputStreaming|pendingApproval|running|success|error|cancelled）。
 * 精确 wire 字段待 T1 phase B 实测回填——[AgentRowCodec] 是唯一调整点。
 */
data class AgentRow(
    val rowId: Long,
    val type: String,
    val role: String? = null,
    val text: String? = null,
    val toolName: String? = null,
    val toolStatus: String? = null,
    val toolInputSummary: String? = null,
)

/** 当前动作摘要的上限：背屏一行，超长截断。 */
const val ACTION_SUMMARY_MAX_CHARS = 120
