package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.core.DashboardEvent.SessionLockMode

/**
 * 会话列表与 Session Lock 当前档的纯逻辑（票 #104，#234 桥唯一化）：只吃数据回数据、
 * 零 Android 依赖——JVM 单测直接跑（[com.rearcue.poc.RearCueApp] 里 `refresh` 等接线要
 * Context/Log，测不了）。
 *
 * 四类来源（ZCode / Codex / Claude / DSH）都只从 PC 桥事件/快照进入同一份
 * [AgentSessionState]。等待状态由桥事件直接送进 DashboardCore，不再有 ZCode 任务表、
 * V4 帧或 sessions-index 的第二套等待补发链。
 */
object AgentStateLogic {

    /**
     * 合并在册集：桥事件与快照共用的去重/更新口径——同 sessionId 后到状态覆盖旧值，
     * 首次出现的位置保留下来作为平局时的到达序。
     */
    fun mergeRoster(vararg rosters: List<AgentSessionState>): List<AgentSessionState> {
        val byId = LinkedHashMap<String, AgentSessionState>()
        for (roster in rosters) {
            for (session in roster) {
                if (session.sessionId.isBlank()) continue
                byId[session.sessionId] = session
            }
        }
        return byId.values.toList()
    }

    /** 在册键集：喂给 core AgentRoster 的唯一对账口径。 */
    fun rosterIds(roster: List<AgentSessionState>): Set<String> =
        roster.mapTo(linkedSetOf()) { it.sessionId }

    /**
     * 列表行会话名：真实标题优先、workspace 目录名次之、sessionId 尾 4 位兜底。
     * 单条展示与列表投影同源（[AgentSessionDisplay]）。
     */
    fun sessionName(session: AgentSessionState): String =
        AgentSessionDisplay.title(session)

    /**
     * 当前档派生：null = 自动档；非空 = 锁定档要展示的会话名。
     *
     * 会话短暂离开在册名册时用 [retained] 的锁定缓存补标题/副行来源，不让界面突然退成
     * 无意义 id；缓存也没有时只显示 sessionId 尾 4 位，完整 sessionId 永不进入界面。
     */
    fun lockTargetName(
        mode: SessionLockMode,
        roster: List<AgentSessionState>,
        retained: AgentSessionState? = null,
    ): String? {
        val id = (mode as? SessionLockMode.Locked)?.sessionId ?: return null
        AgentSessionDisplay.titles(roster)[id]?.let { return it }
        if (retained?.sessionId == id) return sessionName(retained)
        return AgentSessionDisplay.sessionTail(id)
    }

    /** 列表选中派生：自动档回 null，锁定档回锁定会话键（各行选中判据）。 */
    fun selectedSessionId(mode: SessionLockMode): String? =
        (mode as? SessionLockMode.Locked)?.sessionId

    /**
     * 一份列表投影：首行固定「自动」，其后桥来源按等待确认置顶（组内 updatedAt 降序），
     * 其余 updatedAt 降序，平局按输入到达序。主屏与背屏列表共用本方法，不各自派生
     * 标题、来源或排序。
     */
    fun projectRoster(
        roster: List<AgentSessionState>,
        mode: SessionLockMode,
    ): List<AgentListRow> {
        val merged = mergeRoster(roster)
        val titles = AgentSessionDisplay.titles(merged)
        val selectedId = selectedSessionId(mode)
        val ordered = merged.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<AgentSessionState>> {
                    it.value.status == AgentStatus.WAITING_FOR_APPROVAL
                }
                    .thenByDescending { it.value.updatedAt }
                    .thenBy { it.index },
            )
            .map { it.value }
        return buildList {
            add(
                AgentListRow(
                    sessionId = null,
                    title = "",
                    sourceLabel = null,
                    status = null,
                    selected = mode is SessionLockMode.Auto,
                    auto = true,
                ),
            )
            ordered.forEach { session ->
                add(
                    AgentListRow(
                        sessionId = session.sessionId,
                        title = titles[session.sessionId] ?: sessionName(session),
                        sourceLabel = AgentSessionDisplay.sourceLabel(session.source),
                        status = session.status,
                        selected = session.sessionId == selectedId,
                        auto = false,
                    ),
                )
            }
        }
    }
}

/** 列表投影的一行：auto 行没有 sessionId/status；UI 只渲染，不自行排序或派生标题。 */
data class AgentListRow(
    val sessionId: String?,
    val title: String,
    val sourceLabel: String?,
    val status: AgentStatus?,
    val selected: Boolean,
    val auto: Boolean,
)
