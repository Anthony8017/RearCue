package com.rearcue.poc.agentmirror

import com.rearcue.poc.agent.AgentSessionDisplay
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus
import com.rearcue.poc.agent.SessionIndexEntry
import com.rearcue.poc.core.DashboardEvent.SessionLockMode

/**
 * 会话列表与 Session Lock 当前档的纯逻辑（票 #104）：只吃数据回数据、零 Android 依赖——
 * JVM 单测直接跑（[com.rearcue.poc.RearCueApp] 里 `refresh` 等接线要 Context/Log，测不了）。
 *
 * 四个面：
 * - [merge]／[mergeRoster]／[normalizeRoster]：状态归一——任务表无「等待确认」语义，等确认来自 v4 帧与
 *   sessions-index 等待视图（[SessionIndexEntry]，锁档插队的真来源）；
 * - [sessionName]／[lockTargetName]／[selectedSessionId]／[projectRoster]：当前档派生——状态行文案与列表选中；
 * - [AgentArchiveTruth]：Archive Synchrony 的来源无关在册真值——归档移除与取消归档恢复都在进投影前收口；
 * - [indexWaitingIds]／[withIndexWaiting]／[dispatchBatch]：索引等待进出补发（票 #103 P0）。
 *
 * 接线口径（票 #104 / spec 0016 #154）：列表数据 = ZCode 任务表 ∪ 桥已见会话，
 * 经 [mergeRoster]／[normalizeRoster]／[projectRoster] 投影；
 * 写入口仍是 T1 的 [com.rearcue.poc.RearCueApp.setSessionLock]（事件进 core + 写盘）。
 */
object AgentStateLogic {

    /**
     * 单条状态归一（任务表 × v4 帧合并；原 `dispatchAgentMerged` 的内联口径抽出共用）：
     * 同一会话时 **等确认 > 任一来源工作中 > 空闲**；回复原文取 v4、当前动作取任务表标题；
     * 会话键不一致（任务刚切换、v4 尚未重订阅）时先用任务表；单边为 null 取另一边。
     *
     * **问答流（spec 0017 / 票 #169）**：v4 帧是唯一带行集的一侧（ZCode 的 `userInput` /
     * `assistantText` 行都在它的 `turns` 里），任务表恒为空——所以这一条必须从 v4 搬运，
     * 且**不能**在重建状态对象时漏掉：现场踩过，漏掉就等于问答流整类到不了屏上
     * （判例：`AgentStateLogicTest`「归一后仍带问答流」）。
     */
    fun merge(task: AgentSessionState?, v4: AgentSessionState?): AgentSessionState? = when {
        task == null -> v4
        v4 == null -> task
        task.sessionId != v4.sessionId -> task
        else -> AgentSessionState(
            sessionId = task.sessionId,
            workspace = task.workspace,
            status = when {
                v4.status == AgentStatus.WAITING_FOR_APPROVAL -> AgentStatus.WAITING_FOR_APPROVAL
                // 出错（spec 0018-3）排在等待之后、工作之前——任一侧报会话级出错即保留，
                // 不能被另一侧的工作中盖掉（否则「出错」提醒在合并口径里永远到不了）。
                task.status == AgentStatus.ERROR || v4.status == AgentStatus.ERROR -> AgentStatus.ERROR
                task.status == AgentStatus.WORKING || v4.status == AgentStatus.WORKING -> AgentStatus.WORKING
                else -> AgentStatus.IDLE
            },
            currentAction = task.currentAction ?: v4.currentAction,
            latestReply = v4.latestReply,
            updatedAt = maxOf(task.updatedAt, v4.updatedAt),
            source = task.source ?: v4.source,
            // v4 有流用 v4 的；v4 这一帧还没攒出行集时退到任务表（通常为空），不丢也不串。
            turns = v4.turns.ifEmpty { task.turns },
        )
    }

    /**
     * 合并在册集（spec 0016 / 票 #154）：ZCode 任务表与桥已见会话按 sessionId 去重；
     * 后入的非空状态覆盖同键旧值，首次出现的位置保留下来作为平局时的到达序。
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

    /** 合并在册集的键集：喂给 core AgentRoster 的唯一对账口径。 */
    fun rosterIds(roster: List<AgentSessionState>): Set<String> =
        roster.mapTo(linkedSetOf()) { it.sessionId }

    /**
     * 列表状态归一（票 #104）：任务表全量（`TaskListParser.parseAll` 原序、不重排）叠加
     * 等确认真检测——v4 帧只订一个会话（仅同会话那条可能被升级），索引等待集
     * [indexWaiting] 覆盖**全部**在册会话（任务表没有等待语义，三态只有这两处真来源；
     * 缺数据即保留任务表原态，不伪造等待）。v4 无读数（未订阅／断连）即任务表原样。
     */
    fun normalizeRoster(
        roster: List<AgentSessionState>,
        v4: AgentSessionState?,
        indexWaiting: Set<String> = emptySet(),
    ): List<AgentSessionState> {
        val upgraded = if (v4 == null) roster else roster.map { entry ->
            if (entry.sessionId == v4.sessionId) merge(entry, v4) ?: entry else entry
        }
        if (indexWaiting.isEmpty()) return upgraded
        return upgraded.map { entry -> withIndexWaiting(entry, entry.sessionId in indexWaiting) }
    }

    // ---------- sessions-index 等待视图（票 #103 P0：锁档插队的真来源） ----------

    /**
     * 索引等待集（与在册取交）：索引判等的任务表在册会话键。索引含已归档/别工作区的会话，
     * 只有在册的才可能上屏/被锁定——交集是「可插队者」的准确集合。
     */
    fun indexWaitingIds(entries: List<SessionIndexEntry>, roster: List<AgentSessionState>): Set<String> {
        if (entries.isEmpty()) return emptySet()
        val rosterIds = roster.mapTo(mutableSetOf()) { it.sessionId }
        return entries.asSequence()
            .filter { it.waiting && it.sessionId in rosterIds }
            .map { it.sessionId }
            .toSet()
    }

    /** 等确认升级（只上调状态位，其余字段——工作区名/动作/回复——沿基态）。 */
    fun withIndexWaiting(state: AgentSessionState, waiting: Boolean): AgentSessionState =
        if (waiting && state.status != AgentStatus.WAITING_FOR_APPROVAL) {
            state.copy(status = AgentStatus.WAITING_FOR_APPROVAL)
        } else {
            state
        }

    /**
     * 索引等待**进出**补发集（票 #103 P0）：单条状态源（[merge] 的任务表最新 × v4）只覆盖
     * 一个会话，锁档下 B 的插队/回锁若只靠它会缺一半，故按索引等待集的进出各补一条：
     * - 进（在册 ∩ 索引判等 − 上次已发）→ 以等确认入 core（锁档下他人插队的到达）；
     * - 出（上次已发 − 现索引判等）→ 以任务表 × v4 基态回 core（处理完回锁）；会话已不在册
     *   （索引移除/任务表除名）补一条空闲，不让等确认滞留 core。
     * [v4] 属别的会话时 [merge] 自然取任务表（会话键不一致先用任务表的既有口径）。
     */
    private fun waitingTransitions(
        roster: List<AgentSessionState>,
        v4: AgentSessionState?,
        waitingNow: Set<String>,
        dispatched: Set<String>,
    ): List<AgentSessionState> {
        if (waitingNow.isEmpty() && dispatched.isEmpty()) return emptyList()
        val byId = roster.associateBy { it.sessionId }
        val entering = (waitingNow - dispatched).mapNotNull { id ->
            byId[id]?.let { base -> withIndexWaiting(merge(base, v4) ?: base, waiting = true) }
        }
        val exiting = (dispatched - waitingNow).map { id ->
            byId[id]?.let { base -> merge(base, v4) ?: base }
                ?: AgentSessionState(sessionId = id, status = AgentStatus.IDLE)
        }
        return entering + exiting
    }

    /**
     * 一次会话状态派发批次（票 #103 P0，**接线层与 JVM 判例共用同一处计算**）：
     * - 单条口径逐字不变——[merge] 的「任务表最新 × v4」，只在该会话索引判等时上调等确认位；
     * - 叠加 [waitingTransitions] 的进出补发（单条源只覆盖一个会话，锁档插队/回锁靠它闭合）；
     * - 返回更新后的「已以等确认入 core」集，供下一次进出判定。
     * [states] 为空＝无可派发（与既有「单条源缺席即不刷」口径一致）。
     */
    fun dispatchBatch(
        roster: List<AgentSessionState>,
        task: AgentSessionState?,
        v4: AgentSessionState?,
        indexEntries: List<SessionIndexEntry>,
        dispatchedWaiting: Set<String>,
    ): SessionDispatchBatch {
        val waitingNow = indexWaitingIds(indexEntries, roster)
        val single = task?.let { withIndexWaiting(it, it.sessionId in waitingNow) }
        val transitions = waitingTransitions(roster, v4, waitingNow, dispatchedWaiting)
            .filter { it.sessionId != single?.sessionId }
        return SessionDispatchBatch(
            states = listOfNotNull(single) + transitions,
            waitingDispatched = waitingNow,
        )
    }

    /**
     * 列表行会话名：workspace 目录名优先，空/缺回退 sessionId 尾 4 位（永不空串）。
     * 单条展示与列表投影同源（[AgentSessionDisplay]）。
     */
    fun sessionName(session: AgentSessionState): String =
        AgentSessionDisplay.title(session)

    /**
     * 当前档派生（票 #104 状态行）：null = 自动档；非空 = 锁定档要展示的会话名——
     * 在册取统一标题（workspace 目录名 / 尾 4 位），不在册回退会话键（存储首读到对账清锁之间的
     * 空窗仍显示「锁了谁」）。文案前缀（「已锁定·」）由 UI 拼，这里只出名字。
     */
    fun lockTargetName(mode: SessionLockMode, roster: List<AgentSessionState>): String? {
        val id = (mode as? SessionLockMode.Locked)?.sessionId ?: return null
        return AgentSessionDisplay.titles(roster)[id] ?: id
    }

    /** 列表选中派生：自动档回 null，锁定档回锁定会话键（各行选中判据）。 */
    fun selectedSessionId(mode: SessionLockMode): String? =
        (mode as? SessionLockMode.Locked)?.sessionId

    /** Archive Synchrony 的统一投影入口：只吃 [AgentArchiveTruth.currentRoster]。 */
    fun projectRoster(
        truth: AgentArchiveTruth,
        mode: SessionLockMode,
    ): List<AgentListRow> = projectRoster(truth.currentRoster(), mode)

    /**
     * 一份列表投影（spec 0016 / 票 #154）：首行固定「自动」，其后合并三来源、
     * 等待确认置顶（组内 updatedAt 降序）→ 其余 updatedAt 降序，平局按输入到达序。
     * 主屏与后续背屏列表都只消费本方法，不各自派生标题、来源或排序。
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

/** [AgentStateLogic.dispatchBatch] 的结果：要进 core 的状态序列 + 更新后的已发等待集。 */
data class SessionDispatchBatch(
    /** 按序派发（单条在前、进出补发在后）；空＝无可派发。 */
    val states: List<AgentSessionState>,
    /** 本次已以「等确认」入 core 的在册会话键（下次进出判定的基线）。 */
    val waitingDispatched: Set<String>,
)
