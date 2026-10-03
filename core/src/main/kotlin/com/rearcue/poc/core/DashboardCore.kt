package com.rearcue.poc.core

import com.rearcue.poc.agent.AgentSessionKeys
import com.rearcue.poc.agent.AgentSessionState
import com.rearcue.poc.agent.AgentStatus

/** 输入事件：Android 层胶水把系统信号翻译成这些事件喂给 DashboardCore。 */
sealed interface DashboardEvent {

    /**
     * 新增一枚（票 #66 起携带内容快照）：[key] 是 notification key（「所示通知被清除自动收起」
     * 与「每 App 最新一条」镜像的对账键），[title]/[text] 是 NLS extras 内存读出的内容
     * （spec 0007 story 15 边界：只随事件搬运、不落盘不外传）。缺省空串 = 旧形态调用
     * （判例与旧接线），core 只按「无内容可镜像」处理，既有语义一概不变。
     */
    data class NotificationPosted(
        val pkg: String,
        val key: String = "",
        val title: String = "",
        val text: String = "",
        /**
         * 快照重放标记（票 #65 评审定案）：重连/重启时 :notification 快照差分补报——
         * 是「重建在册事实」不是「到达」，只重建图标面、**不呼吸**、不消耗冷却
         * （同「Degrade 恢复重建不呼吸」语义）。真实到达（监听回调）恒为 false。
         */
        val fromSnapshot: Boolean = false,
    ) : DashboardEvent

    data class NotificationRemoved(val pkg: String, val key: String = "") : DashboardEvent

    /**
     * 同 key 内容更新（spec 0008 / 票 #65）：Notification Highlight 的触发源之一——
     * 「新通知到达（含同 key 内容更新）⇒ 整屏呼吸约 3 秒」（CONTEXT.md「Notification Highlight」）。
     * Icon Set **不重计**（key 对账在 :notification，集合成员没变；票 #50 判例继续成立）。
     * 票 #66 起携带 [key]/[title]/[text]（同 [NotificationPosted] 的快照口径）。
     */
    data class NotificationUpdated(
        val pkg: String,
        val key: String = "",
        val title: String = "",
        val text: String = "",
        /** 快照重放标记，语义同 [NotificationPosted.fromSnapshot]（重建不是到达，不呼吸）。 */
        val fromSnapshot: Boolean = false,
    ) : DashboardEvent

    /** 当前允许进入 Icon Set 的应用包名；只做应用级筛选（无系统可见性探测）。 */
    data class Allowlist(val apps: Set<String>) : DashboardEvent

    /**
     * 点按 Icon Set 中某枚图标（票 #66 Detail View 的统一入口；点按卡片本身同形——卡片收起
     * 就是「再点按同一 App」的特例）：
     *
     * - 无 Detail 打开 → 打开该 App 的 Detail（该 App **最新一条** Active Notification 的
     *   title+text 快照，打开即冻结）；
     * - Detail 已打开且是同一 App → 收起（再点按同一图标/卡片）；
     * - Detail 已打开且是别的 App → 切换到新 App（同一时刻至多一个 Detail）。
     *
     * Icon Set 之外的 App 点不开（无 Active Notification）——
     * 防御判例。无时限、无隐私档、无列表（spec 0008 Detail View 语义）。
     */
    data class DetailToggled(val app: String) : DashboardEvent

    /**
     * 背屏非交互区域点按（spec 0013 / 票 #132）：请求在通知页与 Agent 页之间切换。
     * UI 只上报原始点按，能否切、切到哪页由 core 按当前页内容与 Waiting-for-Approval
     * 例外判决；图标、Detail 卡片、Agent 回底按钮的专属点按不走本事件。
     */
    data object ContentPageToggle : DashboardEvent

    /**
     * 会话标识行点按（spec 0016 / 票 #156）：背屏 Agent 页的会话标识行单击 = 开/关全窗会话列表。
     * 语义位同 [DetailToggled]——只进状态、决策在状态机（[DashboardCore.agentPicker] 投影）；
     * 列表的选中动作不走本事件（复用 Session Lock 写入口，[SessionLock]）。
     */
    data object AgentPickerToggle : DashboardEvent

    /**
     * 点开即消的撤销执行失败回执（票 #111）：接线层调用通知监听的 key 级撤销被拒/监听
     * 未连接时回报本事件——core 解除该 key 的自发消除豁免布防（[DashboardCore.detailCloseIfShown]）。
     * 布防解除后，所示 key 若被外部清除仍走既有自动收起（「外部清除仍自动收起」在失败边成立）；
     * 成功路径不发本事件，豁免留给真实的 Removed 回执消费。幂等：key 不在布防中即无效果。
     */
    data class SelfCancelFailed(val key: String) : DashboardEvent

    /**
     * 投送通道就绪：运行时识别到背屏（见 CONTEXT.md「投送通道」）。
     *
     * 应用内投送不需要 Shizuku（票 #4 的 E1 实测），所以通道可用性只看背屏在不在；
     * Shizuku 掉线/恢复的兜底语义由票 #6 收口。
     */
    data object ProjectionReady : DashboardEvent

    /** 投送通道不可用（未识别到背屏）→ Degrade。 */
    data object ProjectionUnavailable : DashboardEvent

    /**
     * 兜底通道（Shizuku）恢复可用：授权成功或 server 重新上线后按当前 Icon Set 幂等重投一次
     * （票 #6 / E8，票 #8 收口）。掉线不发事件——投送通道判据只看背屏（CONTEXT.md「投送通道」）。
     */
    data object FallbackAvailable : DashboardEvent

    data object TakeoverDetected : DashboardEvent

    /** Dashboard 实例在未收到主动退出请求时消失，按当前通知状态判断是否需要恢复。 */
    data object DashboardDetached : DashboardEvent

    /**
     * 自启动状态实测读数（票 #28 横幅输入）：Android 层读 AppOpsManager 10008/10053 后
     * 经 [AutostartJudge] 判定的 [AutostartState]，进入页面/从 MIUI 设置页返回时复查。
     */
    data class AutostartStatus(val state: AutostartState) : DashboardEvent

    /** 通知监听健康（票 #28 横幅输入）：监听服务连接/断开的系统信号。 */
    data class ListenerHealth(val healthy: Boolean) : DashboardEvent

    /**
     * 监听探针：同时携带通知使用权与监听连接状态；仅已授权但未连接时请求系统重绑。
     *
     * 探针不代表健康状态，横幅仍只由 [ListenerHealth] 的系统连接信号驱动。
     */
    data class ListenerProbe(val enabled: Boolean, val listenerConnected: Boolean) : DashboardEvent

    /**
     * 手动投送请求（Debug Bypass；spec 0006 的 Quick Tile Entry 复用同事件）：发起即记
     * [CastSource.MANUAL]，豁免门控，也不被自动逻辑撤下——退出只能由 [ManualExit] 触发。
     */
    data object ManualCast : DashboardEvent

    /** 手动退出请求：结束在屏（不论来源）；不影响通知驱动的自动流转，新通知仍会自动投送。 */
    data object ManualExit : DashboardEvent

    /**
     * 退屏宽限到期唤醒（spec 0015 / 票 #146）：core 是同步「事件 → 效果」，而宽限到期
     * 没有系统事件也会发生；接线层按 [DashboardCore.exitGraceDeadlineMs] 到点补派本事件。
     * 早到是幂等 no-op，迟到则立即收口；退屏判定仍全在 core，不新增效果契约。
     */
    data object ExitGraceElapsed : DashboardEvent

    /**
     * Posture Gate 输入（spec 0006）：app 层接近传感器读数经稳定窗防抖后翻译的布尔——
     * 倒扣（主屏朝下，传感器「近」）= true。开关开启时它是自动投送的唯一门：倒扣才投；
     * 由开转关撤下 auto 在屏，由关转开且 Icon Set 非空补投。MANUAL 在屏全程豁免。
     * （DND Follow 门已随票 #99 删除——勿扰不再影响投送。）
     * 票 #100 起本事件还受 [PostureGateEnabled] 开关管辖：**默认关（旁路）**——开关关着时
     * 姿态照常进状态（状态行读数），但不参与门控（不拦、不撤）。
     */
    data class PostureGate(val faceDown: Boolean) : DashboardEvent

    /**
     * 姿态门控开关（票 #100，设置页「倒扣才显示」）：**默认关**（[DashboardCore.POSTURE_GATE_DEFAULT]），
     * 出厂与升级后同档（存储缺键即默认，进程启动首读是一次幂等对齐）。
     *
     * - 关（默认）= 门控旁路：正放/倒扣都照常自动投送，在屏内容不因姿态撤下——开机即用，
     *   不需要理解门控概念；
     * - 开 = 恢复 Posture Gate 语义（spec 0006 现行为）：正放关（拦新投、撤 auto/AGENT 在屏），
     *   倒扣开（Icon Set 非空补投）；MANUAL/CHARGING 豁免与 AGENT 受门语义不变。
     *
     * 切换即时生效：档位变化即过一遍门（门开合结果没变则无效果，如倒扣中开开关）；同档幂等
     * （存储首读常态：与 core 初值相同则不产生任何效果）。
     */
    data class PostureGateEnabled(val enabled: Boolean) : DashboardEvent

    // ---------- Agent 页正文档位（spec 0017 / 票 #169） ----------

    /**
     * Agent 页正文档位（spec 0017 / 票 #169，主屏首页 Agent 卡片里的三档单选）：
     * **默认中档**（[MirrorTextSize.DEFAULT]），出厂与升级后同档（存储缺键即默认，
     * 进程启动首读是一次幂等对齐）。
     *
     * 这是**纯呈现偏好**：core 只把它当一个可读事实存着（设置页读它、背屏经 `AgentFeed` 读它），
     * 它**不参与**任何投送/撤屏/内容页仲裁——改档不会触发投送，也不会改屏上内容页。
     * 同档幂等（与 core 当前值相同即无效果）。
     */
    data class MirrorTextSizeChanged(val size: MirrorTextSize) : DashboardEvent

    // ---------- 角部避让开关（spec 0019 / 票 #194） ----------

    /**
     * 角部避让（spec 0019 / 票 #194，主屏 Agent 设置区的开关，管 Agent 会话页与会话列表两边）：
     * **默认关**（[DashboardCore.CORNER_AVOIDANCE_DEFAULT]＝贴满，角部缺字认了——机主定夺
     * 「默认贴满」），出厂与升级后同档（存储缺键即默认，进程启动首读是一次幂等对齐）。
     *
     * 这是**纯呈现偏好**：core 只把它当一个可读事实存着（设置页读它、背屏经 `AgentFeed` 读它），
     * 它**不参与**任何投送/撤屏/内容页仲裁。同值幂等。
     */
    data class CornerAvoidanceChanged(val enabled: Boolean) : DashboardEvent

    // ---------- Charging Animation（spec 0007 / 票 #57：插电即投 + 门控豁免 + 退出合取） ----------

    /**
     * 插电（`ACTION_POWER_CONNECTED`）：通知之外的**独立投送触发源**——无通知时插电也把
     * Dashboard（可只含充电动画）送上背屏。与 [ManualCast] 同形：不过 `reconcile()` 的门控判据，
     * 正放中照样投；投出后记 [CastSource.CHARGING]，此后门控不撤它。
     * 总开关（[ChargingAnimation]）关闭时本事件只是记录插电态、不投（关了就该没反应）。
     */
    data object PowerConnected : DashboardEvent

    /**
     * 拔电（`ACTION_POWER_DISCONNECTED`）：充电理由结束——退出条件「拔电 ∧ Icon Set 空」
     * （spec 0008 定案：横幅项随横幅退役删除）的第一项由此满足；后一项由统一出口按当下状态判
     * （不是本事件的特判）。
     */
    data object PowerDisconnected : DashboardEvent

    /**
     * 充电动画总开关（spec 0007 story 11，设置页充电区）：**默认开**（[DashboardCore.CHARGING_ANIMATION_DEFAULT]）。
     * 关闭 = 充电理由结束（等价拔电，充电屏按合取条件收口）；开启且在充电 = 理由恢复，
     * 即时生效。存储层缺键即默认，进程启动首读是一次幂等对齐。
     */
    data class ChargingAnimation(val enabled: Boolean) : DashboardEvent

    /**
     * 电量读数（spec 0008 / 票 #67，Charging Animation 显示面数据）：Android 层从
     * BatteryManager 的 `ACTION_BATTERY_CHANGED` 既有广播链取数（level/scale → 百分比，
     * sticky 注册即得首读），变化以本事件进 core。显示面是**状态投影不是投送效果**
     * （同 [DetailToggled] 口径）：本事件不产出效果、不动投撤，接线层 refresh 重发
     * [DashboardCore.batteryPercent] 给背屏 Feed——比例数字随电量事件刷新（68→69）。
     * 越界读数收口到 0..100（防御判例），同值幂等（无变化即无刷新）。
     */
    data class BatteryLevel(val percent: Int) : DashboardEvent

    // ---------- Agent Mirror（spec 0010 / 票 #83：AGENT 源 + 等确认插队 + 断连回落） ----------

    /**
     * Agent 会话状态更新（spec 0010）：:agent 归一化出的单会话事实（status ∈ Working /
     * WaitingForApproval / Idle，多会话并存时的选择在 core——等确认 > 最近活跃）。
     * 语义同 [BatteryLevel] 的投影口径：在屏内容随更新刷新（接线层重发投影），
     * 投/撤只在「agent 理由」出现/消失的边界发生（见 [DashboardCore.agentReason]）。
     */
    data class AgentSessionUpdated(val state: AgentSessionState) : DashboardEvent

    /**
     * Agent 中继连接状态（spec 0010）：断连 = 镜像事实失联——理由整体消失，在屏 AGENT
     * 按「空闲回落」同路径交还（零打扰回落，不弹错）；重连 = 恢复监听，投不投看下一条
     * 会话更新（恢复不是到达，同 [NotificationPosted.fromSnapshot] 的重建口径）。
     */
    data class AgentConnectionChanged(val connected: Boolean) : DashboardEvent

    // ---------- Session Lock（票 #103：锁定偏好 + 任务表在册对账解锁） ----------

    /**
     * Session Lock 档位（票 #103，CONTEXT.md「Session Lock」）：只定「显示谁」，不改变接管门槛。
     * 缺省/存储缺键即 [Auto]（＝spec 0010 现状仲裁：最近活跃＋等确认插队，行为逐字不变）。
     */
    sealed interface SessionLockMode {
        /** 自动档（默认）：按 spec 0010 现状仲裁选择所示会话。 */
        data object Auto : SessionLockMode

        /** 锁定档：显示锁定的 [sessionId] 会话（空闲仍回落常规内容——锁会话不锁屏）。 */
        data class Locked(val sessionId: String) : SessionLockMode
    }

    /**
     * 锁定偏好（票 #103）：[SessionLockMode.Auto] ＝ 自动仲裁（现状行为），[SessionLockMode.Locked]
     * ＝ 锁定某会话。语义位同 [ChargingAnimation] 的档位输入——只进状态、决策在状态机
     * （[agentReason] / [agentState] 按档判决）；本事件本身不产出投送效果，接线层 refresh
     * 重发投影。同档幂等（存储首读常态）。
     */
    data class SessionLock(val mode: SessionLockMode) : DashboardEvent

    /**
     * 在册名册对账（票 #103 立、spec 0016 / 票 #154–#155 扩到三来源）：接线层把**合并在册集**
     * （ZCode 任务表 ∪ 桥在册）的全量会话键喂进来——**锁定的会话离开在册名册 ⇒ core 自动清锁
     * 退回 [SessionLockMode.Auto]**（清锁决策在 core，JVM 可测；写盘跟随由接线层读
     * [DashboardCore.sessionLock] 收口）。不在锁定档时本事件幂等无效果；空集同样有效
     * （电脑端任务表清空即「都不在册」）。
     *
     * [bridgeRosterKnown] 是**分源口径**（票 #155）：合并在册集里的桥那一半是否为当下事实。
     * 桥链路在线且「在册快照」对账成功 → true（缺席才算确实不在册）；断线/未对账 → false
     * ——此时桥来源（[com.rearcue.poc.agent.AgentSessionKeys.isBridge]）的锁**缺席不算数**，
     * 保锁等下一次对账（桥断线期间锁不丢；重连拿到快照后仍缺席才清）。ZCode 来源不受它影响，
     * 沿「任务表消失即清」既有口径。
     */
    data class AgentRoster(
        val sessionIds: Set<String>,
        val bridgeRosterKnown: Boolean = false,
    ) : DashboardEvent

    /**
     * 统一归档/出册真值给出的**权威移除**（spec 0023 / 票 #237）：与普通的名册缺席不同，
     * 它无条件撤下这些会话的全部 core 派生态——镜像仲裁、Waiting-for-Approval 插队、
     * 批准/提问入口与 Session Lock——锁定其中任一会话即退回 [SessionLockMode.Auto]。
     *
     * 接线层只在 [com.rearcue.poc.agentmirror.AgentArchiveTruth.currentRoster] 的键集收缩时发；
     * 断线期间没有新的移除事实就保留最后一帧，重连对账拿到移除事实后立即发本事件。
     */
    data class AgentSessionsRemoved(val sessionIds: Set<String>) : DashboardEvent
}

/**
 * 投送来源（spec 0006 扩 spec 0007 / spec 0010）：通知驱动记 [AUTO]，Debug Bypass（及 QS tile）
 * 记 [MANUAL]，插电独立投送记 [CHARGING]（豁免门控，退出只认「拔电 ∧ Icon Set 空」合取——
 * spec 0008 横幅退役后合取只剩两项）。[AGENT] 记 Agent Mirror 独立触发：
 * 受 Posture Gate 管；**理由 = 连接在线且有在册会话**（2026-09-28 grilling #112：
 * 在线即显示、空闲也持屏，不再要求非 Idle），理由在身时插电不改记（充电不抢 agent）、
 * 理由消失（断连）即交还 auto 规则（门关着撤、开着按 Icon Set 判退）。
 * 投送记账只管「屏在不在、谁负责退」；**屏上显示哪个内容页**由 [contentPage] 定
 * （通知页 / Agent 页，Waiting-for-Approval 临时插队），两者不混。
 */
enum class CastSource { AUTO, MANUAL, CHARGING, AGENT }

/**
 * 背屏内容页（spec 0013 / CONTEXT.md「Content Page」）：通知页（Icon Set + Detail View）与
 * Agent 页（Agent Mirror）互斥、平级。两页都有内容时默认通知页；只有一边有内容时显示该页；
 * 手动切换与内容消失兜底会改写当前页，Waiting-for-Approval 是唯一自动插队例外。
 */
enum class ContentPage { NOTIFICATION, AGENT }

/** 默认通知白名单：微信、QQ、飞书、本应用、PC 自动化测试通道。 */
object PocAllowlist {
    val APPS: Set<String> = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.ss.android.lark",
        "com.rearcue.poc",
        "com.android.shell",
    )
}

private val ContentPage.other: ContentPage
    get() = if (this == ContentPage.NOTIFICATION) ContentPage.AGENT else ContentPage.NOTIFICATION

/**
 * 一条 Active Notification 的内容快照（spec 0008 / 票 #66）：core 自 Posted/Updated 事件镜像、
 * 供「该 App 最新一条」Detail 选择的落点。[key] 是 notification key（清除自动收起与最新一条
 * 选择的对账键）；title/text 只随事件在内存内搬运（NLS extras 读出的既有隐私边界，
 * spec 0007 story 15 沿袭：不落盘、不经剪贴板/外部存储、不外传）。
 */
data class NotificationContent(val key: String, val title: String, val text: String)

/**
 * Detail View 当前所示（spec 0008 / 票 #66）：打开那一刻冻结的快照——**快照语义**：同 key
 * 内容更新与该 App 新通知到达都不刷新卡片；[key] 被清除自动收起；同一时刻至多一个。
 * 只读投影 [DashboardCore.detail]（DetailFeed 的发布源），变更经接线层 refresh 重发。
 */
data class NotificationDetail(val app: String, val key: String, val title: String, val text: String)

/**
 * Detail 标题行显示口径（grill #89 定案：正文界面不显示软件名称）：
 * 标题与应用名同值且正文非空时省略标题行——「飞书」这类标题即软件名的通知不留废话行，
 * 且正文非空是前提（否则整卡无内容可读）；标题是联系人/群名（「张三」「家庭群」）照常
 * 显示，那条信息正文里没有。[appLabel] 解析不到（包不可见/已卸载）按原样显示——采集失败不牺牲信息。
 */
fun detailDisplayTitle(title: String, appLabel: String?, text: String): String =
    if (
        title.isNotBlank() && text.isNotBlank() && appLabel != null &&
        title.trim().equals(appLabel.trim(), ignoreCase = true)
    ) {
        ""
    } else {
        title
    }

/**
 * 在屏 Dashboard 的核心记账：来源与已投出的 Icon Set 同生同灭（data clump 收拢成一个类型，
 * 撤下路径只置一次 null，不再三个字段各自清）。
 */
private data class OnScreen(val source: CastSource, val iconSet: Set<String>)

/** 输出效果：Android 层胶水按序执行（投送/更新/退出/降级/监听重绑）。 */
sealed interface DashboardEffect {
    /** 投送 Dashboard 到背屏（含首投与重投，幂等）。 */
    data class LaunchDashboard(val iconSet: Set<String>) : DashboardEffect

    /** Dashboard 已在背屏上时更新 Icon Set。 */
    data class UpdateIconSet(val iconSet: Set<String>) : DashboardEffect

    /** 末条通知消失，退出 Dashboard，恢复原生背屏。 */
    data object ExitDashboard : DashboardEffect

    /** 投送通道不可用：停止投送，通知监听与 Icon Set 照常维护（见 CONTEXT.md「Degrade」）。 */
    data object Degrade : DashboardEffect

    /**
     * 显示可用性引导横幅（票 #28），携带触发原因；含 [UsabilityReason.AUTOSTART_IN_DOUBT]
     * 即降级形态（仅手动跳转 + 明示文案，绝不显示「健康」）。原因集变化时重发一次（内容更新）。
     */
    data class ShowUsabilityBanner(val reasons: Set<UsabilityReason>) : DashboardEffect

    /** 恢复健康（自启动已放行 + 监听健康）：隐藏可用性引导横幅。 */
    data object HideUsabilityBanner : DashboardEffect

    /** 通知使用权已开启但监听尚未连接：请求系统重绑，等待真实连接信号确认健康。 */
    data object RequestRebind : DashboardEffect

    /**
     * Notification Highlight 呼吸指令（spec 0008 / 票 #65）：整屏呼吸**一次**（非循环）。
     * [untilMs] 是呼吸窗截止（epoch ms，core 时钟给出）——背屏晚挂载时按剩余时长播放、
     * 已过期不播。（[apps] 高亮集快照随图标高亮退役删除，2026-09-28 grilling 定案。）
     */
    data class HighlightBreath(val untilMs: Long) : DashboardEffect

    /**
     * 等待确认的视觉强调指令（spec 0010 / 票 #85）：整屏脉冲**一次**（非循环、约 3 秒、
     * 不响不震——沿 Notification Highlight 的呼吸语言与 30 秒冷却语义）。
     * [untilMs] 是强调窗截止（epoch ms），背屏晚挂载按剩余时长播放、已过期不播。
     */
    data class AgentPulse(val untilMs: Long) : DashboardEffect

    /**
     * 消除所示通知（票 #111「点开即消」）：打开 Detail 时由状态机连带发出（所示即所消，
     * 仅此最新一条），执行侧经通知监听的 key 级撤销把该条从系统通知栏划掉。随之回来的
     * [DashboardEvent.NotificationRemoved] 回执由 [DashboardCore.detailCloseIfShown] 的
     * 自发消除豁免接住——详情保留供阅读，不因自己发起的清除闪收。
     */
    data class CancelNotification(val key: String) : DashboardEffect

    /** 短名：日志与调试页展示用（`posted com.tencent.mm → LaunchDashboard(2)`）。 */
    val label: String
        get() = when (this) {
            is LaunchDashboard -> "LaunchDashboard(${iconSet.size})"
            is UpdateIconSet -> "UpdateIconSet(${iconSet.size})"
            ExitDashboard -> "ExitDashboard"
            Degrade -> "Degrade"
            is ShowUsabilityBanner -> "ShowUsabilityBanner(" +
                reasons.sortedBy { it.name }.joinToString("+") + ")"
            HideUsabilityBanner -> "HideUsabilityBanner"
            RequestRebind -> "RequestRebind"
            is HighlightBreath -> "HighlightBreath"
            is AgentPulse -> "AgentPulse"
            is CancelNotification -> "CancelNotification"
        }
}

/**
 * 决策核心：事件序列 → 效果序列的纯 Kotlin 状态机，唯一 JVM 测试 seam。
 *
 * 不持有任何 Android 框架引用；单测断言 [onEvent] 返回的效果序列与只读投影（iconSet/
 * detail/batteryPercent 等公共契约），不断言私有内部状态。
 *
 * [nowMs] 是虚拟时钟（JVM 判例可拨）：Highlight 的呼吸窗/冷却窗按它计时（票 #65）。
 * [log] 是 Highlight 日志锚的注入口，词形契约见 [LOG_HIGHLIGHT_CONTRACT]：纯 Kotlin 面
 * 不引 `android.util.Log`（README seam），logcat 实现（`Log.i`，TAG=RearCue）由构造方注入
 * （进程内收口在 app 层 AppContainer——DashboardCore 由它构造，同 WakeKeepAlive 的注入口径）。
 */
class DashboardCore(
    initialAllowlist: Set<String> = PocAllowlist.APPS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    private var allowlist = initialAllowlist

    /**
     * 每个 pkg 的 Active Notification 数（Icon Set 只看 >0 与否，角标数字取本值——issue #101）。
     * LinkedHashMap 的迭代序 = 最近一次 Posted 在尾（Posted 时 remove+重插），投影时倒过来即
     * **时间倒序**（最新通知的 App 排最前，见 [iconSet]）；移除只减数不挪位，倒序不被打乱。
     */
    private val activeCounts = LinkedHashMap<String, Int>()

    /**
     * 每 App 的通知内容镜像（票 #66）：pkg → (key → 快照)，按到达序（LinkedHashMap 保位）——
     * 「该 App 最新一条」＝该 pkg 映射的最后一个条目。Posted 入册、Updated 就地刷新（不挪位，
     * 更新不改「最新」序）、Removed 摘除（最新一条被清后次新一条顶上）。与 :notification 的
     * 集合对账同源同调：key 是唯一对账键，title/text 只作内容搬运。
     */
    private val contentsByPkg = LinkedHashMap<String, LinkedHashMap<String, NotificationContent>>()

    /**
     * 当前 Detail View（spec 0008 / 票 #66）：null = 纯图标常态。打开/收起/切换/自动收的
     * 决策全在状态机；只读投影 [detail] 供接线层 refresh 重发 [com.rearcue.poc.rear.DetailFeed]
     * Dashboard 撤下/降级/抢回重投时随之清——屏上没有卡片可残留。
     */
    private var detailView: NotificationDetail? = null

    /**
     * 自发消除豁免键（票 #111「点开即消」）：打开 Detail 时对所示 key 布防——[detailCloseIfShown]
     * 首次见到该 key 的 Removed 回执时只解除布防、不收详情（自己发起的清除不能把刚点开的
     * 详情闪关）。随详情关闭/切换即清（不变量：本字段非空 ⇒ [detailView] 非空）；空 key
     * （旧形态无内容事件）不布防——无 key 可消，外部清除仍走自动收起。
     */
    private var selfCancelKey: String? = null

    /** 当前呼吸窗/冷却窗的截止（epoch ms）：呼吸窗（3s）⊂ 冷却窗（30s），只记后者即可判「能否呼吸」。 */
    private var highlightCooldownUntilMs = 0L

    /**
     * 退屏宽限的截止（epoch ms，spec 0015 / 票 #146）：非 null = 最后一条通知清空后
     * 屏还在留，等退场窗口结束；null = 没有待收口的宽限。核心只记这一项计时事实。
     */
    private var exitGraceUntilMs: Long? = null

    private var projectionReady = false

    /**
     * 在屏 Dashboard 的核心记账（spec 0006）：投送来源 + 已投出的 Icon Set，三者同生同灭。
     * null = 核心认为不在屏。注意这是「投出后」的模型——真正的在屏事实（Presence）以
     * Android 层的实例证据为准，两者由 DashboardDetached/TakeoverDetected 事件对齐。
     */
    private var onScreen: OnScreen? = null

    /**
     * 当前内容页（spec 0013 / 票 #132）：手动切换、内容消失兜底与每次重新投送的默认页都落在这里。
     * Waiting-for-Approval 存续期不改写本值——它由 [contentPage] 投影临时压到 Agent 页，结束后
     * 自然回原页；一次连续投屏内保持，退屏/重投由 [resetContentPage] 回默认。
     */
    private var selectedContentPage = ContentPage.NOTIFICATION

    /**
     * 当前页是否由机主手动点选（票 #171 返修）：手动选的页**不受内容兜底推翻**——
     * 手动切到空的 Agent 页要能停在那儿（背屏画空态说明），否则同一次事件里的
     * [fallbackContentPage] 会立刻把它踢回去，机主还是看到"点了没反应"。
     * 手动选择一直有效到下一次手动选择，或退出投送（[resetContentPage] 清标记）。
     */
    private var manualContentPage = false

    /** Waiting-for-Approval 是否正处于存续期（进入时记录原页，全部解决后恢复）。 */
    private var waitingForApprovalActive = false

    /** Waiting-for-Approval 进入前的原页；仅 [waitingForApprovalActive] 为真时有效。 */
    private var pageBeforeWaitingForApproval: ContentPage? = null

    /**
     * 背屏会话选择器是否展开（spec 0016 / 票 #156）：会话标识行单击开、再点/点列表外关。
     * 只在 Agent 页有意义；**等确认插队、离开 Agent 页、退屏/重投**都会把它收掉
     * （[reconcileAgentPicker]），不超时自动关（打开后可从容选择）。投影见 [agentPicker]。
     */
    private var agentPickerOpen = false

    /**
     * Posture 门控（spec 0006 / 票 #100）：倒扣才放行自动投送——但**开关默认关（旁路）**，
     * 见 [postureGateEnabled]。初值倒扣（true，放行）——门在收到 app 层首个防抖提交前不拦截
     * （进程启动后 <1s 即提交），正放判定一到立即收口；无接近传感器的设备不提交，姿态恒倒扣。
     */
    private var faceDown = true

    /** 可用性横幅输入：null = 尚无实测读数（不打扰，也绝不冒充健康）。 */
    private var autostartState: AutostartState? = null
    private var listenerHealthy: Boolean? = null

    /** 当前横幅原因集；空集 = 横幅隐藏。 */
    private var bannerReasons: Set<UsabilityReason> = emptySet()

    /**
     * 充电动画总开关（spec 0007 story 11）：默认开（[CHARGING_ANIMATION_DEFAULT]）。可读不可写——
     * 设置页与调试页读它，改档只能经 [DashboardEvent.ChargingAnimation] 事件（决策仍在状态机）。
     */
    var chargingAnimationEnabled: Boolean = CHARGING_ANIMATION_DEFAULT
        private set

    /**
     * 姿态门控开关（票 #100）：默认关（[POSTURE_GATE_DEFAULT]）。可读不可写——设置页读它，
     * 改档只能经 [DashboardEvent.PostureGateEnabled] 事件（决策仍在状态机）。关着时姿态
     * 仍进 [faceDown]（状态行读数），但不参与门控。
     */
    var postureGateEnabled: Boolean = POSTURE_GATE_DEFAULT
        private set

    /**
     * Agent 页正文档位（spec 0017 / 票 #169）：默认中档（[MirrorTextSize.DEFAULT]）。
     * 可读不可写——设置页读它，改档只能经 [DashboardEvent.MirrorTextSizeChanged] 事件。
     * **纯呈现偏好**：不参与投送/撤屏/内容页仲裁。
     */
    var mirrorTextSize: MirrorTextSize = MirrorTextSize.DEFAULT
        private set

    /**
     * 角部避让开关（spec 0019 / 票 #194）：默认关（[CORNER_AVOIDANCE_DEFAULT]＝贴满）。
     * 可读不可写——设置页读它，改档只能经 [DashboardEvent.CornerAvoidanceChanged] 事件。
     * **纯呈现偏好**：不参与投送/撤屏/内容页仲裁。
     */
    var cornerAvoidanceEnabled: Boolean = CORNER_AVOIDANCE_DEFAULT
        private set

    /**
     * 插电态（spec 0007 / 票 #57）：[DashboardEvent.PowerConnected]/[DashboardEvent.PowerDisconnected]
     * 的记录。与 [chargingAnimationEnabled] 合取才是「充电理由」（见 [chargingReason]）。
     */
    private var plugged = false

    // ---------- Agent Mirror 记账（spec 0010 / 票 #83） ----------

    /**
     * Session Lock 当前档位（票 #103）：可读不可写——改档只能经 [DashboardEvent.SessionLock]
     * 事件（同 [chargingAnimationEnabled] 口径，决策仍在状态机）。接线层（写盘跟随/持久化
     * 首读回放）与 V4Bridge（锁订阅跟随）经它取锁定会话键。
     */
    var sessionLock: DashboardEvent.SessionLockMode = DashboardEvent.SessionLockMode.Auto
        private set

    /** 在册 agent 会话（sessionId → 最新事实）：[DashboardEvent.AgentSessionUpdated] 的记账。 */
    private val agentSessions = LinkedHashMap<String, AgentSessionState>()

    /** 中继连接状态：断连即镜像失联（[agentReason] 必假、在屏 AGENT 交还）。 */
    private var agentConnected = false

    /**
     * 等待确认强调的冷却截止（spec 0010 / 票 #85）：覆盖强调窗（3s ⊂ 30s）——强调中/冷却中
     * 再入等确认不重复强调（沿 Notification Highlight 的冷却语言）。
     */
    private var agentPulseCooldownUntilMs = 0L

    /**
     * 当前电量百分比（spec 0008 / 票 #67，Charging Animation 显示面数据）：null = 尚无读数
     * （进程启动后 sticky 广播首读到达前）。只被 [DashboardEvent.BatteryLevel] 更新——
     * 绿色比例填充与白色大号数字的唯一数据源；充电显示面只在 [chargingOnScreen] 时呈现，
     * 读数本身与是否在屏无关（先到先记）。
     */
    var batteryPercent: Int? = null
        private set

    /** 充电理由 = 插电 ∧ 总开关开：出现在屏记账里（[CastSource.CHARGING]）即持有 Dashboard。 */
    private val chargingReason: Boolean
        get() = plugged && chargingAnimationEnabled

    /**
     * Agent Mirror 理由（grilling #112 重定义 × 票 #103 锁定档 × 票 #166 断线保留）：**有在册会话**
     * 即按档位成立——
     * 自动档＝有会话即显示（空闲也显示最近会话输出，不再要求任一会话非 Idle；无在册会话的
     * 纯连接不成立——无内容可镜像，等首条会话事实到达再投）；
     * 锁定档＝**锁定会话**非 Idle（锁会话不锁屏：它空闲即无理由、回落常规内容，别的会话
     * 再忙也不顶班），或**任何会话**处于等确认（CONTEXT.md「Waiting-for-Approval」在背屏内容
     * 选择中永远优先——临时插队，处理完回锁）。出现在屏记账里（[CastSource.AGENT]）即持有
     * Dashboard（语义位同 [chargingReason]，门控语义不同：受姿态门）。
     *
     * **断线保留**（票 #166，2026-09-29 机主定夺）：理由不再要求链路在线——桥断时内容停在
     * 最后一帧继续显示，背屏用会话状态点的灰档提示已断开；对账清空在册（[agentSessions]
     * 空）、机主手动退出、系统抢回等既有路径照旧收回。原「断连即理由消失（零打扰回落）」被取代。
     */
    private val agentReason: Boolean
        get() {
            if (agentSessions.isEmpty()) return false
            return when (val lock = sessionLock) {
                DashboardEvent.SessionLockMode.Auto -> true

                is DashboardEvent.SessionLockMode.Locked ->
                    agentSessions[lock.sessionId]?.status?.let { it != AgentStatus.IDLE } == true ||
                        agentSessions.values.any { it.status == AgentStatus.WAITING_FOR_APPROVAL }
            }
        }

    /**
     * 镜像所示会话（仲裁选择，投影面；票 #103 锁定档 × grilling #112 在线即显示）：
     * 等确认永远插队（多会话并存取最近活跃）；自动档再按 工作中 → 全体（含空闲残影，
     * 同档取最近活跃：updatedAt 大者，平局按到达序取后到）选取，null = 尚无在册会话。
     * 锁定档：任何会话等确认 ⇒ 临时插队显示该等待会话，处理完回锁定会话；锁定会话
     * **在册即显示（空闲也显示最后输出，与自动档同一口径——票 #197，机主定夺 2026-09-30：
     * 「锁会话不锁屏」只管自动收放〔见 agentReason〕，不管「显示什么」）**；不在册 ⇒ null
     * （不虚构显示）。接线层每次 refresh 重发给 AgentFeed（同 [iconSet] 口径）。
     */
    val agentState: AgentSessionState?
        get() {
            if (agentSessions.isEmpty()) return null
            val waiting = agentSessions.values.filter { it.status == AgentStatus.WAITING_FOR_APPROVAL }
            val lock = sessionLock
            if (lock is DashboardEvent.SessionLockMode.Locked) {
                waiting.maxByOrNull { it.updatedAt }?.let { return it }
                return agentSessions[lock.sessionId]
            }
            waiting.maxByOrNull { it.updatedAt }?.let { return it }
            val working = agentSessions.values.filter { it.status == AgentStatus.WORKING }
            val pool = working.ifEmpty { agentSessions.values }
            return pool.maxByOrNull { it.updatedAt }
        }

    /**
     * Agent 投送记账在屏（**不是** Agent 内容页是否在显）：记账来源是 [CastSource.AGENT] 即为真。
     * 内容页是否显示 Agent Mirror 看 [contentPage]，两者刻意分离（投送记账只管屏在不在、谁负责退）。
     */
    val agentOnScreen: Boolean
        get() = onScreen?.source == CastSource.AGENT

    /**
     * **背屏当前内容页**（spec 0013 / 票 #132，唯一内容页面决策出口）：
     * null = Dashboard 不在屏；非空 = 通知页或 Agent 页。
     *
     * 默认/兜底规则由 [resetContentPage] 与 [reconcileContentPage] 落在 [selectedContentPage]：
     * 两页都有内容默认通知页、只有一边有内容显示该页、当前页内容消失兜底到有内容的另一边、
     * 恢复不自动切回；Waiting-for-Approval 存续期无视手动选择强制 Agent 页，全部解决后回原页。
     * 接线层 refresh 把本投影重发给 AgentFeed 作图层开关；充电水位不参与本选择
     * （它是背景层，见 [chargingOnScreen]）。
     */
    val contentPage: ContentPage?
        get() = when {
            onScreen == null -> null
            waitingForApprovalNow -> ContentPage.AGENT
            else -> selectedContentPage
        }

    /**
     * 会话选择器是否展开（spec 0016 / 票 #156，投影面）：打开态 ∧ 当前内容页是 Agent 页
     * （退屏为 null 即关）。背屏接线层按本投影挂/撤全窗列表；打开与关闭的判定全在状态机，
     * UI 只渲染。选择动作不走本投影——点条目复用 Session Lock 写入口。
     */
    val agentPicker: Boolean
        get() = agentPickerOpen && contentPage == ContentPage.AGENT

    /** 中继连接投影（主屏 Agent 设置区状态行消费）。 */
    val agentLinkUp: Boolean
        get() = agentConnected

    /**
     * 当前 Icon Set：存在 Active Notification 的应用，**时间倒序**（issue #101——
     * 最新通知的 App 排最前，背屏网格左上；重复通知把该 App 挪到最前，移除只减数不挪位）。
     *
     * 只显示白名单内存在 Active Notification 的应用。与投送无关的只读视图——主屏调试页直接展示它；投送效果仍由 [onEvent] 产出。
     */
    val iconSet: List<String>
        get() = activeCounts.keys.toList().asReversed().filter { it in allowlist }

    /**
     * 每个在 [iconSet] 内的 App 的 Active Notification 条数（issue #101「未读数角标」的
     * 唯一数据源：数字＝系统事实的 Active Notification 计数，不代表 App 内部未读数）。
     * 键集与键序同 [iconSet]（时间倒序）；App 的通知清零即从键集消失（角标随之消失）。
     * 单条/多条（≥2 条切纯图标网格）切换也读本投影求和——接线层 refresh 重发给背屏 Feed。
     */
    val unreadCounts: Map<String, Int>
        get() = iconSet.associateWith { activeCounts.getValue(it) }

    /** 在屏 Dashboard 的投送来源（spec 0006，调试页展示用）：null = 核心认为不在屏。 */
    val castSource: CastSource?
        get() = onScreen?.source

    /**
     * 退屏宽限截止（epoch ms，spec 0015 / 票 #146）：非 null = 最后一条通知清空后仍在
     * 留屏，接线层按它安排 [DashboardEvent.ExitGraceElapsed] 的到期派发；null = 无宽限。
     * 只读投影，不新增 [DashboardEffect] 契约。
     */
    val exitGraceDeadlineMs: Long?
        get() = exitGraceUntilMs

    /**
     * Posture 门控当前读数（spec 0006，状态行展示用）：true = 倒扣。只反映姿态事实，
     * 是否参与门控看 [postureGateEnabled]。
     */
    val postureFaceDown: Boolean
        get() = faceDown

    /**
     * 充电动画在屏面（2026-09-28 grilling #112/#114 背景层语义）：`充电理由 ∧ Dashboard 在屏`——
     * **背景事实投影**（不是内容选择的一档）：充电期间长垫底，Icon Set、Detail View、
     * Agent Mirror 照常叠其上（不再因 AGENT 持有而隐去水位）。接线层每次刷新按它重发，
     * 投送/更新/退出等一切路径统一收口；充电理由消失（拔电/关开关）或 Dashboard 撤下即隐。
     * 比例数据经 [batteryPercent] 单独重发（[DashboardEvent.BatteryLevel] 事件面）；
     * 满电贴顶波浪线是水位到顶的渲染特例，不进本投影。
     */
    val chargingOnScreen: Boolean
        get() = onScreen != null && chargingReason

    /**
     * 处理一个事件，返回本事件引发的效果（可能为空）。
     *
     * 固有效果 + 退出合取判定（[reconcileExit]）在统一出口收口——充电内容与投送状态变化的
     * 每条路径都经过这里，不依赖各分支各自记得补效果。
     * （spec 0008：Notification Feed 横幅面整体退役——原 FeedPosted/AutoDismissTick/PrivacyMode/
     * AutoDismiss 事件与 Show/Hide 横幅效果已删除；票 #65 起 [DashboardEvent.NotificationUpdated]
     * 接管同 key 内容更新的消费面——Highlight 触发源，Icon Set 仍不重计。）
     */
    fun onEvent(event: DashboardEvent): List<DashboardEffect> {
        val effects = handle(event)
        // 每次新的 LaunchDashboard（首投/手动重投/Takeover 重投/通道恢复重投）都从默认页开始；
        // 同一次连续投屏内的 UpdateIconSet 不重置，手动选择因此保持到退屏或重投。
        if (effects.any { it is DashboardEffect.LaunchDashboard }) {
            resetContentPage()
        }
        reconcileContentPage()
        val exitEffects = reconcileExit()
        // 会话选择器的对齐（spec 0016 / 票 #156）放在退出判定之后：判退会撤下在屏记账，
        // 列表必须与屏同拍收掉（插队/切页/退屏），不留在屏上等下一个事件。
        reconcileAgentPicker()
        return effects + exitEffects
    }

    /** 事件的固有效果（状态更新 + 投送决策）；退出合取判定在 [onEvent] 的统一出口。 */
    private fun handle(event: DashboardEvent): List<DashboardEffect> = when (event) {
        is DashboardEvent.Allowlist -> {
            allowlist = event.apps
            reconcile()
        }

        is DashboardEvent.NotificationPosted -> {
            recordContent(event.pkg, event.key, event.title, event.text)
            // 时间倒序（issue #101）：新到（含重复通知）的 App 挪到最新——remove+重插让它
            // 落到迭代尾，[iconSet] 投影倒序后即「最新在左上」。
            val count = (activeCounts[event.pkg] ?: 0) + 1
            activeCounts.remove(event.pkg)
            activeCounts[event.pkg] = count
            reconcile() + highlightTrigger(event.pkg, event.fromSnapshot)
        }

        is DashboardEvent.NotificationUpdated -> {
            // 同 key 内容更新：集合成员没变，Icon Set 不重计；Highlight 语义的触发源（票 #65）。
            // 票 #66：内容镜像就地刷新（key 不挪位，Detail 的「最新」序不动；已打开的卡片按
            // 快照语义不刷新）。
            recordContent(event.pkg, event.key, event.title, event.text)
            highlightTrigger(event.pkg, event.fromSnapshot)
        }

        is DashboardEvent.NotificationRemoved -> {
            val count = activeCounts[event.pkg] ?: 0
            if (count > 0) {
                if (count == 1) activeCounts.remove(event.pkg) else activeCounts[event.pkg] = count - 1
            }
            dropContent(event.pkg, event.key)
            reconcile() + detailCloseIfShown(event.key)
        }

        DashboardEvent.ProjectionReady -> {
            projectionReady = true
            // 通道就绪/恢复**不触发呼吸**（呼吸只由通知到达触发，重建不是到达）。
            // 通道恢复/首次就绪：按优先级重投（spec 0010：agent 理由 > 充电理由 > Icon Set；
            // 姿态门关着不投 agent），否则按当前 Icon Set 上屏。
            when {
                onScreen == null && agentReason && gatesOpen() -> launchAgent()
                onScreen == null && chargingReason -> launchCharging()
                else -> reconcile()
            }
        }

        DashboardEvent.ProjectionUnavailable -> degrade()

        DashboardEvent.FallbackAvailable -> retryProjection()

        DashboardEvent.TakeoverDetected, DashboardEvent.DashboardDetached -> retake()

        is DashboardEvent.AutostartStatus -> {
            autostartState = event.state
            reconcileUsability()
        }

        is DashboardEvent.ListenerHealth -> {
            listenerHealthy = event.healthy
            reconcileUsability()
        }

        is DashboardEvent.ListenerProbe ->
            if (event.enabled && !event.listenerConnected) {
                listOf(DashboardEffect.RequestRebind)
            } else {
                emptyList()
            }

        is DashboardEvent.PostureGate -> {
            // 门开合结果变了才对齐（票 #100）：开关关着时姿态门恒开，翻转只进读数、零效果。
            val gateBefore = gatesOpen()
            faceDown = event.faceDown
            if (gatesOpen() == gateBefore) emptyList() else onGateChanged()
        }

        is DashboardEvent.PostureGateEnabled -> {
            // 同档幂等（存储首读常态：与 core 初值相同则不产生任何效果）。
            if (postureGateEnabled == event.enabled) {
                emptyList()
            } else {
                val gateBefore = gatesOpen()
                postureGateEnabled = event.enabled
                // 切换即时生效（票 #100）：门开合结果随之变了才对齐——正放中开开关立即拦/撤，
                // 倒扣中开开关门本就开着（无事可做），关开关则一律开门补投。
                if (gatesOpen() == gateBefore) emptyList() else onGateChanged()
            }
        }

        is DashboardEvent.MirrorTextSizeChanged -> {
            // 同档幂等（存储首读常态：与 core 初值相同则不产生任何效果）。纯呈现偏好——
            // 不产生任何效果、不碰投送与内容页，只把事实记下来给设置页与背屏读。
            if (mirrorTextSize == event.size) emptyList() else { mirrorTextSize = event.size; emptyList() }
        }

        is DashboardEvent.CornerAvoidanceChanged -> {
            // 同值幂等（存储首读常态）。纯呈现偏好——同 [MirrorTextSizeChanged] 口径：
            // 只把事实记下来给设置页与背屏读，不产生任何效果。
            if (cornerAvoidanceEnabled == event.enabled) {
                emptyList()
            } else {
                cornerAvoidanceEnabled = event.enabled
                emptyList()
            }
        }

        DashboardEvent.ManualCast ->
            // 无通知时投空集（纯黑常态，spec 0008：无时间无横幅）。已在屏（不论来源）
            // 重投并改记 manual——最新意图获胜，此后自动撤下对它失效，直到手动退出。
            if (projectionReady) {
                val icons = projectedIconSet()
                onScreen = OnScreen(CastSource.MANUAL, icons)
                listOf(DashboardEffect.LaunchDashboard(icons))
            } else {
                emptyList()
            }

        DashboardEvent.ManualExit ->
            if (onScreen != null) {
                onScreen = null
                clearExitGrace()
                clearDetailOnScreenGone() // 手动撤屏 Detail 随之清（卡片宿主没了）
                listOf(DashboardEffect.ExitDashboard)
            } else {
                emptyList()
            }

        // 到期唤醒只借统一出口再判一次；是否真的到点由 [reconcileExit] 按截止决定。
        DashboardEvent.ExitGraceElapsed -> emptyList()

        // Charging Animation：插电/拔电/总开关只改充电理由，投撤决策在 [onChargingReasonChanged]。

        DashboardEvent.PowerConnected -> {
            plugged = true
            onChargingReasonChanged()
        }

        DashboardEvent.PowerDisconnected -> {
            plugged = false
            onChargingReasonChanged()
        }

        is DashboardEvent.ChargingAnimation -> {
            // 同档幂等（存储首读常态：与 core 初值相同则不产生任何效果）。
            if (chargingAnimationEnabled == event.enabled) {
                emptyList()
            } else {
                chargingAnimationEnabled = event.enabled
                onChargingReasonChanged()
            }
        }

        is DashboardEvent.BatteryLevel -> {
            // 充电显示面数据（spec 0008 / 票 #67）：只进状态、不产效果（同 DetailToggled 的
            // 状态投影口径）——不动投撤、不触发呼吸；接线层 refresh 把新读数重发给背屏 Feed，
            // 比例填充与数字随事件刷新（68→69）。越界收口 0..100，同值幂等。
            val percent = event.percent.coerceIn(0, 100)
            if (percent != batteryPercent) batteryPercent = percent
            emptyList()
        }

        // ---------- Agent Mirror（spec 0010 / 票 #83） ----------

        is DashboardEvent.AgentSessionUpdated -> {
            // 会话事实到达即连接证据（事实只能从中继上来；Debug 注入同理）——
            // 显式断连（AgentConnectionChanged(false)）是唯一的失联路径。
            agentConnected = true
            agentSessions[event.state.sessionId] = event.state
            agentPulseTrigger(event.state) + onAgentReasonChanged()
        }

        is DashboardEvent.AgentConnectionChanged -> {
            val wasConnected = agentConnected
            agentConnected = event.connected
            // 断 → 通边沿：Agent 有内容就把内容页带回 Agent 页（2026-09-29 机主定夺，票 #163）——
            // 桥断线时 Agent 理由消失、页按兜底回通知页，恢复后不该再要机主手点一下空白。
            // 只在**边沿**上切一次，不改「内容消失兜底后不自动切回」的既有口径（另一条路径）。
            onAgentReasonChanged() +
                restoreAgentPageOnLinkRecovery(edge = !wasConnected && event.connected)
        }

        // ---------- Session Lock（票 #103：档位只改「显示谁」，投撤仍走理由/门控统一出口） ----------

        is DashboardEvent.SessionLock -> {
            // 选定即关（spec 0016 / 票 #156：点条目 = 锁定 + 关闭 + 回实时跟随）——同档重选
            // （点已选中的那条）也要把列表收掉，故清列表不看档位是否变化。
            closeAgentPickerIfOpen(AgentPickerLogContract.REASON_SELECT)
            // 同档幂等（存储首读常态：与 core 初值相同则不产生任何效果）；换档后理由可能
            // 翻转（锁到空闲会话 ⇒ 理由消失回落，锁到忙碌会话 ⇒ 理由出现补投），统一对齐。
            if (sessionLock == event.mode) {
                emptyList()
            } else {
                sessionLock = event.mode
                onAgentReasonChanged()
            }
        }

        is DashboardEvent.AgentSessionsRemoved -> {
            val removed = event.sessionIds.filterTo(mutableSetOf()) { agentSessions.remove(it) != null }
            val clearedLockId = (sessionLock as? DashboardEvent.SessionLockMode.Locked)
                ?.sessionId
                ?.takeIf { it in event.sessionIds }
            if (clearedLockId != null) {
                sessionLock = DashboardEvent.SessionLockMode.Auto
                logAgent("session lock cleared $clearedLockId")
            }
            if (removed.isEmpty() && clearedLockId == null) emptyList() else onAgentReasonChanged()
        }

        is DashboardEvent.AgentRoster -> {
            // 锁定的会话离开在册名册 ⇒ 自动清锁退回自动（CONTEXT.md「Session Lock」）；
            // 清锁同时重判理由（锁定会话不在册时理由通常已不成立）。空在册同样清锁。
            // 分源例外（票 #155）：桥来源的锁在桥名册非当下事实（断线/未对账）时保锁——
            // 不拿「我们没听到桥的消息」当「桥那边没了」。
            val lock = sessionLock
            when {
                lock !is DashboardEvent.SessionLockMode.Locked -> emptyList()
                lock.sessionId in event.sessionIds -> emptyList()
                !event.bridgeRosterKnown && AgentSessionKeys.isBridge(lock.sessionId) -> {
                    logAgent("session lock held ${lock.sessionId} bridge-roster-unknown")
                    emptyList()
                }
                else -> {
                    sessionLock = DashboardEvent.SessionLockMode.Auto
                    logAgent("session lock cleared ${lock.sessionId}")
                    onAgentReasonChanged()
                }
            }
        }

        is DashboardEvent.DetailToggled -> detailToggle(event.app)

        DashboardEvent.ContentPageToggle -> toggleContentPage()

        DashboardEvent.AgentPickerToggle -> toggleAgentPicker()

        is DashboardEvent.SelfCancelFailed -> {
            if (selfCancelKey == event.key) selfCancelKey = null
            emptyList()
        }
    }

    /** Icon Set：每个有 Active Notification 的 Allowlist App 恰好一枚图标（时间倒序同 [iconSet]）。 */
    private fun projectedIconSet(): Set<String> = iconSet.toSet()

    // ---------- Content Page（spec 0013 / 票 #132：通知页与 Agent 页平权切换） ----------

    /** 通知页有内容 = Icon Set 非空或 Detail View 打开（点开即消后图标可空、卡片还在）。 */
    private val notificationPageHasContent: Boolean
        get() = iconSet.isNotEmpty() || detailView != null

    /** Waiting-for-Approval 自动例外是否成立：连接在线且任一在册会话处于等待确认。 */
    private val waitingForApprovalNow: Boolean
        get() = agentConnected &&
            agentSessions.values.any { it.status == AgentStatus.WAITING_FOR_APPROVAL }

    private fun contentPageHasContent(page: ContentPage): Boolean = when (page) {
        ContentPage.NOTIFICATION -> notificationPageHasContent
        ContentPage.AGENT -> agentReason
    }

    /**
     * 默认页（首投/重投重置用）：两页都有内容或都无内容→通知页，只有一边有内容→该页。
     * 「都无内容」不会投出普通 Dashboard；若 manual/charging 持有空屏，通知页只是占位值，
     * UI 两页都不画内容。
     */
    private fun defaultContentPage(): ContentPage = when {
        notificationPageHasContent -> ContentPage.NOTIFICATION
        agentReason -> ContentPage.AGENT
        else -> ContentPage.NOTIFICATION
    }

    /** 重新投送/退屏后的默认页；Waiting-for-Approval 存续时同步更新其恢复目标。 */
    private fun resetContentPage() {
        val page = defaultContentPage()
        val changed = selectedContentPage != page
        selectedContentPage = page
        // 重投/退屏后的默认页不是机主的选择：手动标记随之清掉，自动路径继续受内容兜底管。
        manualContentPage = false
        if (waitingForApprovalNow) {
            pageBeforeWaitingForApproval = page
        } else if (waitingForApprovalActive) {
            // 新投送已经取代本次投屏会话：WFA 也刚结束则默认页优先，不再恢复上次退屏前的页。
            waitingForApprovalActive = false
            pageBeforeWaitingForApproval = null
        }
        if (changed) logContentPage(ContentPageLogContract.reset(page))
    }

    /**
     * 背屏非交互区域点按：**无条件切到另一边**（票 #171 返修，反转 spec 0013 的
     * 「只切到有内容的另一边」）；Waiting-for-Approval 存续期仍忽略（防批准/输入请求被手动隐藏）、
     * 不在屏时无意义。Detail/图标/↓ 的专属点按不走本路径。
     *
     * 为什么反转：空页被拒是**完全静默**的——Agent 页没有在册会话时点空白毫无反应，
     * 机主只能得出「背屏坏了」这一种结论（2026-09-30 实测：点按与切页逻辑都正常，
     * 只是被规则挡下）。空页现在切得过去，由背屏自己画一行空态说明（[EmptyAgentPage]）。
     * 被拒的锚（[ContentPageLogContract.toggleRejected]）保留：Waiting-for-Approval 期间仍会打。
     */
    private fun toggleContentPage(): List<DashboardEffect> {
        if (onScreen == null) return emptyList()
        if (waitingForApprovalNow) {
            logContentPage(ContentPageLogContract.toggleRejected(selectedContentPage.other))
            return emptyList()
        }
        selectedContentPage = selectedContentPage.other
        manualContentPage = true
        logContentPage(ContentPageLogContract.toggle(selectedContentPage))
        // 手动从通知页切到 Agent 页时，默认展开会话列表（2026-10-03 机主定夺）；
        // 无在册会话仍走空态页，不制造只有「自动」一项的空列表。
        if (selectedContentPage == ContentPage.AGENT && agentSessions.isNotEmpty()) {
            openAgentPicker()
        }
        return emptyList()
    }

    /**
     * 每个事件后的内容页对齐：Waiting-for-Approval 进入时记原页并强制 Agent；存续期不改写
     * 手动选择；全部解决后恢复原页，原页内容已消失则再走兜底。普通路径下当前页内容消失且
     * 另一边有内容时自动兜底，并把兜底结果变成当前页——之后旧页恢复不自动切回。
     */
    private fun reconcileContentPage() {
        if (waitingForApprovalNow) {
            if (!waitingForApprovalActive) {
                waitingForApprovalActive = true
                pageBeforeWaitingForApproval = selectedContentPage
                logContentPage(ContentPageLogContract.wfaEnter(selectedContentPage))
            }
            return
        }
        if (waitingForApprovalActive) {
            waitingForApprovalActive = false
            val restore = pageBeforeWaitingForApproval ?: selectedContentPage
            pageBeforeWaitingForApproval = null
            selectedContentPage = restore
            logContentPage(ContentPageLogContract.wfaExit(restore))
        }
        fallbackContentPage()
    }

    /**
     * 内容页兜底（当前页内容消失时自动切到有内容的另一边）。
     *
     * **手动选的页不兜底**（票 #171 返修）：空态能被切过去了，兜底若照旧生效，手动切到空的
     * Agent 页会被同一次事件里的本函数立刻踢回通知页——机主看到的仍是"点了没反应"
     * （日志里是 toggle agent 紧跟 fallback notification，2026-09-30 实测撞到）。
     * 手动选择要一直站到下一次手动选择、或 Waiting-for-Approval 插队；自动选的页（首投/重投、
     * 兜底结果）照旧受内容兜底管，退出投送（`resetContentPage`）时清掉手动标记。
     */
    private fun fallbackContentPage() {
        if (manualContentPage) return
        if (contentPageHasContent(selectedContentPage)) return
        val other = selectedContentPage.other
        if (!contentPageHasContent(other)) return
        selectedContentPage = other
        logContentPage(ContentPageLogContract.fallback(other))
    }

    /** 内容页日志锚注入口（词形契约见 [LOG_CONTENT_PAGE_CONTRACT]）。 */
    private fun logContentPage(line: String) = log(line)

    /**
     * 链路恢复回 Agent 页（票 #163，2026-09-29 机主定夺）：断 → 通**边沿**上，若在屏、Agent 有内容
     * （[agentReason]）且当前不在 Agent 页，就把内容页切回 Agent 页并打锚 `content page recover agent`。
     *
     * 与「内容消失兜底后不自动切回」的分工：那条管**页内内容消失**的兜底结果（不抢机主手动选择），
     * 本条只管**链路恢复**这一次边沿——恢复后 Agent 又有输出了，页该跟着回来。无内容/不在屏/
     * 已在 Agent 页都幂等无效果；Waiting-for-Approval 存续期由 [contentPage] 投影强制 Agent 页，
     * 这里不重复切。
     */
    private fun restoreAgentPageOnLinkRecovery(edge: Boolean): List<DashboardEffect> {
        if (!edge || onScreen == null || waitingForApprovalNow) return emptyList()
        if (!agentReason || selectedContentPage == ContentPage.AGENT) return emptyList()
        selectedContentPage = ContentPage.AGENT
        logContentPage(ContentPageLogContract.recover(ContentPage.AGENT))
        return emptyList()
    }

    // ---------- 会话选择器（spec 0016 / 票 #156：标识行单击开列表、插队/切页即关） ----------

    /**
     * 会话标识行点按（[DashboardEvent.AgentPickerToggle]）：关着则开（仅 Agent 页在显且**不在
     * 等确认插队期**——插队期列表让位，同 [toggleContentPage] 的「WFA 存续期忽略」判例）、
     * 开着则关（同 [DashboardEvent.DetailToggled] 的「再点按收起」口径）。列表浮层是纯展示面
     * ——本投影不产出投送效果，退屏/理由消失仍走内容页既有路径。
     */
    private fun toggleAgentPicker(): List<DashboardEffect> {
        if (agentPickerOpen) {
            closeAgentPicker(AgentPickerLogContract.REASON_TOGGLE)
        } else if (contentPage == ContentPage.AGENT && !waitingForApprovalNow) {
            openAgentPicker()
        }
        return emptyList()
    }

    /** 手动切换与标识行入口共用的打开路径；只负责状态与词形锚，不做投送决策。 */
    private fun openAgentPicker() {
        if (agentPickerOpen) return
        agentPickerOpen = true
        logAgentPicker(AgentPickerLogContract.open())
    }

    /**
     * 每个事件后的选择器对齐（spec 0016 / 票 #156）：等确认插队（列表让位给插队会话）与
     * 离开 Agent 页（切页/兜底/退屏/重投）都自动关；**不超时自动关**——打开后可从容选择。
     */
    private fun reconcileAgentPicker() {
        if (!agentPickerOpen) return
        when {
            waitingForApprovalNow -> closeAgentPicker(AgentPickerLogContract.REASON_WFA)
            contentPage != ContentPage.AGENT -> closeAgentPicker(AgentPickerLogContract.REASON_PAGE)
        }
    }

    private fun closeAgentPicker(reason: String) {
        agentPickerOpen = false
        logAgentPicker(AgentPickerLogContract.close(reason))
    }

    /** 列表开着才关（选定路径用：没开列表就不该打关闭锚）。 */
    private fun closeAgentPickerIfOpen(reason: String) {
        if (agentPickerOpen) closeAgentPicker(reason)
    }

    /** 选择器日志锚注入口（词形契约见 [LOG_AGENT_PICKER_CONTRACT]）。 */
    private fun logAgentPicker(line: String) = log(line)

    // ---------- Notification Highlight（spec 0008 / 票 #65：呼吸 + 冷却） ----------

    /**
     * 呼吸触发（[DashboardEvent.NotificationPosted] / [DashboardEvent.NotificationUpdated]）：
     * 通道就绪、冷却窗（[HIGHLIGHT_COOLDOWN_MS]，覆盖呼吸窗）外，且**非快照重放**
     * （[fromSnapshot]＝重连/重启的重建补报，同「恢复不是到达」：不呼吸、不消耗冷却）。
     * （图标高亮退役后本触发只管呼吸，2026-09-28 grilling 定案。）
     *
     * 呼吸是**视图级效果、不绑姿态门**：票面对 Posture Gate 只说「正放不投/翻正撤下语义不变」
     * （投/撤语义，且仅在开关 #100 开着时生效），正放手动/充电等豁免源在屏时到达照常呼吸（屏是合法渲染面，同 Icon Set
     * 内容更新口径）；无屏时效果自然无处渲染、到期即失效（无害）。
     * （票 #99：原「DND 中到达不呼吸」随 DND Follow 一并删除——勿扰不再影响呼吸。）
     */
    private fun highlightTrigger(pkg: String, fromSnapshot: Boolean = false): List<DashboardEffect> {
        if (pkg !in allowlist) return emptyList()
        if (fromSnapshot || !projectionReady) return emptyList()
        val now = nowMs()
        if (now < highlightCooldownUntilMs) return emptyList()
        highlightCooldownUntilMs = now + HIGHLIGHT_COOLDOWN_MS
        logHighlight("highlight breath start")
        return listOf(DashboardEffect.HighlightBreath(now + HIGHLIGHT_BREATH_MS))
    }

    // ---------- Detail View（spec 0008 / 票 #66：打开/收起/切换/自动收 + 最新一条选择） ----------

    /**
     * 当前 Detail View 的只读投影（spec 0008 / 票 #66）：DetailFeed 的发布源与调试观测面。
     * null = 纯图标常态；非空 = 卡片所示快照（打开即冻结）。接线层每次 refresh 重发，
     * 渲染层不另设第二事实。
     */
    val detail: NotificationDetail?
        get() = detailView

    /**
     * 点按图标/卡片（[DashboardEvent.DetailToggled]）的三分决策：
     *
     * - 同一 App 再点按 → 收起（卡片点按同形——收起就是「再点按同一 App」的特例）；
     * - Icon Set 之外的 App（无 Active Notification）→ 点不开，无效果（防御判例；
     *   点按只能发生在在屏图标上，这里拦的是状态机面的脏输入）；
     * - 其余（未打开或切换到别的 App）→ 打开：取该 App **最新一条**的快照（[latestContentOf]，
     *   最新有内容的一条）；打开即冻结——之后同 key 更新与新通知到达都不刷新卡片（快照语义），
     *   只有 [detailCloseIfShown] 的 key 对账能自动收它。
     *
     * **点开即消**（票 #111）：打开同时对所示 key 布防自发消除豁免并发出
     * [DashboardEffect.CancelNotification]——所示即所消（系统通知栏同步划掉、仅此最新一条），
     * 回执到达不自收详情；无 key 可消（旧形态空事件）则不发效果、豁免不布防。
     * 无时限、无隐私档、无列表。Detail 是状态投影，本事件除消链外不产出投送效果——
     * 接线层 refresh 重发 [detail]。
     */
    private fun detailToggle(app: String): List<DashboardEffect> {
        val current = detailView
        if (current != null && current.app == app) {
            detailView = null
            selfCancelKey = null
            logDetail("detail close $app")
            return emptyList()
        }
        if (app !in projectedIconSet()) return emptyList()
        val content = latestContentOf(app) ?: NotificationContent("", "", "")
        detailView = NotificationDetail(app = app, key = content.key, title = content.title, text = content.text)
        selfCancelKey = content.key.ifEmpty { null }
        logDetail("detail open $app")
        return if (content.key.isEmpty()) {
            emptyList()
        } else {
            listOf(DashboardEffect.CancelNotification(content.key))
        }
    }

    /**
     * 所示 notification key 被清除 → 自动收起（spec 0008 story 11）：对账只认打开时冻结的
     * [NotificationDetail.key]——该 App 别的通知被清、乃至图标整个摘除（全清路径）之外的
     * 异 key 清除都不收。空 key 对空 key 亦同形（旧形态事件的自洽路径）。
     * 票 #111 豁免：所示 key 的首次清除是**自发消除回执**（打开时布防的 [selfCancelKey]）——
     * 只解除布防、详情保留（否则点开即消会让详情闪现即关，根本读不到）；
     * 豁免一次性消费，外部清除语义对未布防的 key（如旧形态空 key）照旧。
     */
    private fun detailCloseIfShown(key: String): List<DashboardEffect> {
        val current = detailView ?: return emptyList()
        if (key == selfCancelKey) {
            // 不打 detail 日志锚：LOG_DETAIL_CONTRACT 的词形只认 open/close，回执的可观测性
            // 由效果短名 CancelNotification（进 lastEvent/logcat）承担。
            selfCancelKey = null
            return emptyList()
        }
        if (key != current.key) return emptyList()
        detailView = null
        logDetail("detail close ${current.app}")
        return emptyList()
    }

    /**
     * Dashboard 撤下/降级/抢回重投时 Detail 随之清：卡片是「在屏 Dashboard」上的临时视图，
     * 屏没了它就没有宿主——重投回的是纯图标常态，不留过期卡片（挂载方也不吃 DetailFeed 的
     * 旧值）。仅 [detailView] 非空时打收起锚（幂等路径静默）。
     */
    private fun clearDetailOnScreenGone() {
        detailView?.let { logDetail("detail close ${it.app}") }
        detailView = null
        selfCancelKey = null
    }

    /** 内容镜像记账：Posted 入册、Updated 就地刷新（LinkedHashMap 保位，「最新」序不被更新挪动）。 */
    private fun recordContent(pkg: String, key: String, title: String, text: String) {
        if (key.isEmpty() && title.isEmpty() && text.isEmpty()) return // 旧形态事件：无内容可镜像
        contentsByPkg.getOrPut(pkg) { LinkedHashMap() }[key] = NotificationContent(key, title, text)
    }

    /**
     * 「该 App 最新一条」选择（[detailToggle] 的落点）：镜像序里**最新有内容**的一条——
     * 组摘要等系统聚合件（本机实测 MIUI 会以 com.android.shell 名义维护
     * `g:Aggregate_AlertingSection`，随每条通知刷新、title/text 恒空；微信/QQ 等真实应用
     * 同样有组摘要件）不是用户要读的「消息」，跳过不选。全部都空（极端态）退化回严格
     * 最新一条，如实显示空卡——不编造内容。
     */
    private fun latestContentOf(pkg: String): NotificationContent? {
        val entries = contentsByPkg[pkg] ?: return null
        entries.values.lastOrNull { it.title.isNotEmpty() || it.text.isNotEmpty() }?.let { return it }
        return entries.values.lastOrNull()
    }

    /** 内容镜像随清除回收：key 摘除，App 条目空了整条撤（次新一条自然顶上成「最新」）。 */
    private fun dropContent(pkg: String, key: String) {
        val keys = contentsByPkg[pkg] ?: return
        keys -= key
        if (keys.isEmpty()) contentsByPkg.remove(pkg)
    }

    /** Detail 日志锚注入口（词形契约见 [LOG_DETAIL_CONTRACT]）。 */
    private fun logDetail(line: String) = log(line)

    /**
     * Highlight 日志锚注入口（词形契约，[LOG_HIGHLIGHT_CONTRACT]）。
     */
    private fun logHighlight(line: String) = log(line)

    /** 退屏宽限日志锚注入口（词形契约见 [LOG_EXIT_GRACE_CONTRACT]）。 */
    private fun logExitGrace(line: String) = log(line)

    /**
     * 自动投送的门（spec 0006；票 #99 后只剩姿态一道，票 #100 给它加了用户开关）：
     * **开关关（默认）= 旁路恒开**——姿态不参与门控；开关开 = 倒扣放行、正放关。
     * 门控只作用于自动路径（manual/charging 豁免不变）。
     */
    private fun gatesOpen() = !postureGateEnabled || faceDown

    /**
     * 门状态变化后的统一对齐：
     * 门开 → 优先看 agent 理由（spec 0010：理由在身且未在屏 AGENT 即按 agent 投——姿态门
     * 刚回来要能补投镜像），否则 reconcile（Icon Set 非空且不在屏则补投，记 auto）；
     * 门关 → 撤下 auto 与 AGENT 在屏。manual 豁免一切。
     *
     * 撤下只认 `source == AUTO` 或 `source == AGENT`：manual 手动投的手动撤，**charging
     * 不被翻正撤下**（spec 0007 票 #57）——充电理由持有 Dashboard 期间门控对它整体无效。
     */
    private fun onGateChanged(): List<DashboardEffect> =
        if (gatesOpen()) {
            when {
                agentReason && onScreen?.source != CastSource.AGENT &&
                    (onScreen == null || onScreen?.source != CastSource.MANUAL) -> launchAgent()
                else -> reconcile()
            }
        } else {
            when (onScreen?.source) {
                CastSource.AUTO, CastSource.AGENT -> withdrawAuto()
                else -> emptyList()
            }
        }

    /** 撤下 auto 在屏（门关路径）：Detail 同宿主同灭（票 #66）。 */
    private fun withdrawAuto(): List<DashboardEffect> {
        onScreen = null
        clearExitGrace()
        clearDetailOnScreenGone()
        return listOf(DashboardEffect.ExitDashboard)
    }

    /**
     * 充电理由（插电 ∧ 总开关开）变化后的统一对齐（spec 0007 票 #57；spec 0010 补 AGENT 优先级）：
     *
     * - 理由出现且不在屏 → [launchCharging]：独立投送触发，绕过门控（[DashboardEvent.ManualCast] 同形）；
     * - 理由出现且已在屏 auto → 只改记 charging（内容不动，已投出的界面不用重投；动画面由
     *   [chargingOnScreen] 投影）；**AGENT 在屏不改记**——优先级链 WaitingForApproval > Working >
     *   Charging（spec 0010：充电不抢 agent，充电屏等 agent 理由消失后在统一出口自然回归）；
     *   manual 在屏不改记——手动意图后到者获胜；
     * - 理由消失且记账是 charging → 改记 auto 交还自动规则，随后过一遍门
     *   （门关着即撤、开着保留），空 Icon Set 的判退由统一出口 [reconcileExit] 收口
     *   （spec 0008：合取只剩「拔电 ∧ Icon Set 空」两项）。
     */
    private fun onChargingReasonChanged(): List<DashboardEffect> {
        val current = onScreen
        return when {
            chargingReason && current == null ->
                if (projectionReady) launchCharging() else emptyList()

            chargingReason && current != null && current.source == CastSource.AUTO -> {
                // 改记 charging：此后门控撤不掉它（充电在屏不被翻正撤下）。
                onScreen = current.copy(source = CastSource.CHARGING)
                emptyList()
            }

            chargingReason && current != null && current.source == CastSource.AGENT -> emptyList()

            !chargingReason && current != null && current.source == CastSource.CHARGING -> {
                onScreen = current.copy(source = CastSource.AUTO)
                onGateChanged()
            }

            else -> emptyList()
        }
    }

    /**
     * Agent 理由（连接在线 ∧ 有在册会话，grilling #112）变化后的统一对齐：
     *
     * - 理由出现且不在屏 → [launchAgent]：通知之外的**独立投送触发源**——受姿态门（倒扣才投，
     *   正放不投也不补投）；
     * - 理由出现且在屏 auto/charging → 只改记 AGENT（持有权插队：**屏上内容**显示哪层由
     *   [contentPage] 定——默认按内容页规则，WFA 才自动插队；记账只管退出/门控归属）；
     * - 理由出现且 MANUAL 在屏 → 不动——手动意图不被自动逻辑抢（同充电语义）；
     * - 理由消失（断连）且记账是 AGENT → 改记 auto 交还自动规则再过一遍门：
     *   门关（姿态翻正）即撤；门开则留屏判 Icon Set（非空留、空判退）——「断连回落」
     *   就是这条交还路径，无专属特判（空闲不再回落：在线即显示）。
     */
    private fun onAgentReasonChanged(): List<DashboardEffect> {
        val current = onScreen
        return when {
            agentReason && current == null ->
                if (projectionReady && gatesOpen()) launchAgent() else emptyList()

            agentReason && current != null &&
                (current.source == CastSource.AUTO || current.source == CastSource.CHARGING) -> {
                onScreen = current.copy(source = CastSource.AGENT)
                emptyList()
            }

            !agentReason && current != null && current.source == CastSource.AGENT -> {
                // 交还：充电理由在身优先收回充电（优先级链的回边），否则交 auto 过门。
                onScreen = current.copy(source = if (chargingReason) CastSource.CHARGING else CastSource.AUTO)
                onGateChanged()
            }

            else -> emptyList()
        }
    }

    /** 按 agent 理由投送：记 [CastSource.AGENT] + [DashboardEffect.LaunchDashboard]（spec 0010）。 */
    private fun launchAgent(): List<DashboardEffect> {
        val icons = projectedIconSet()
        onScreen = OnScreen(CastSource.AGENT, icons)
        return listOf(DashboardEffect.LaunchDashboard(icons))
    }

    /**
     * 等待确认的视觉强调触发（spec 0010 / 票 #85）：会话进入 WaitingForApproval 且冷却窗外
     * （[agentPulseCooldownUntilMs]，覆盖强调窗）⇒ 整屏脉冲一次（约 3 秒，不响不震——
     * 沿 Notification Highlight 的呼吸语言：强调是视图级效果，不绑门控、不绑是否在屏；
     * 无屏时无处渲染、到期即失效）。强调中/冷却中再入等确认不重复强调。
     */
    private fun agentPulseTrigger(state: AgentSessionState): List<DashboardEffect> {
        if (state.status != AgentStatus.WAITING_FOR_APPROVAL) return emptyList()
        val now = nowMs()
        if (now < agentPulseCooldownUntilMs) return emptyList()
        agentPulseCooldownUntilMs = now + AGENT_PULSE_COOLDOWN_MS
        logAgent("agent pulse start")
        return listOf(DashboardEffect.AgentPulse(now + AGENT_PULSE_MS))
    }

    /** Agent 强调日志锚注入口（词形契约见 [LOG_AGENT_PULSE_CONTRACT]）。 */
    private fun logAgent(line: String) = log(line)

    /**
     * 按充电理由投送：记 [CastSource.CHARGING] + [DashboardEffect.LaunchDashboard]
     * （无通知时投空集，与 [DashboardEvent.ManualCast] 的空集投送同款）。不看门控——插电是
     * 通知之外的独立触发源（spec 0007）；执行侧仍走 `project()`，Wake Keep-alive 照常注入。
     */
    private fun launchCharging(): List<DashboardEffect> {
        val icons = projectedIconSet()
        onScreen = OnScreen(CastSource.CHARGING, icons)
        return listOf(DashboardEffect.LaunchDashboard(icons))
    }

    /**
     * 把「当前应显示的 Icon Set」与「背屏现状」对齐，产出效果：
     * 空集 → 无投送效果（判退是退出合取的事，统一出口 [reconcileExit] 收口——充电屏可以
     * 持着空 Icon Set 在屏，spec 0007）；有集合且未投 → LaunchDashboard（门未开不投，
     * spec 0006）；集合变化 → UpdateIconSet（不论来源，内容更新不是投/撤）。
     * 通道不可用期间只维护状态、不产出投送效果。
     */
    private fun reconcile(): List<DashboardEffect> {
        if (!projectionReady) return emptyList()
        val icons = projectedIconSet()
        val current = onScreen
        return when {
            icons.isEmpty() -> emptyList()
            current == null -> {
                if (!gatesOpen()) return emptyList()
                onScreen = OnScreen(CastSource.AUTO, icons)
                listOf(DashboardEffect.LaunchDashboard(icons))
            }
            icons == current.iconSet -> emptyList()
            else -> {
                onScreen = current.copy(iconSet = icons)
                listOf(DashboardEffect.UpdateIconSet(icons))
            }
        }
    }

    /**
     * 退出合取判定（spec 0007 票 #57 立、spec 0008 反转收口；spec 0015 / 票 #146 加宽限，
     * 统一出口）：`退出 ⇐ 在屏 ∧ 非 manual ∧ 非 AGENT/充电/Detail 持有 ∧ Icon Set 空 ∧
     * 宽限到期`——原先立即交还的「Icon Set 空」项，现在只在**最后一条通知清空且无持有
     * 理由**时先记 [exitGraceUntilMs] 并留屏；窗满由接线层派 [DashboardEvent.ExitGraceElapsed]
     * 唤醒本出口再判。宽限内新通知清截止；持有理由出现则各自优先返回，不启动/继续宽限，
     * 保持 manual / 充电 / AGENT / Detail 的既有撤屏时机不变。
     *
     * - **manual**：手动投的手动撤（退出只能由 [DashboardEvent.ManualExit] 触发）；
     * - **充电持有**（[chargingReason]）：拔电是退出的必要条件，插电期间永不退出；
     * - **Icon Set 空**：有通知内容就该留在屏上；清空后留出退场宽限。
     * - （spec 0008 反转：原「无横幅」第三项随 Notification Feed 退役删除——横幅不再是
     *   把 Dashboard 挂在屏上的内容，判据见 docs/specs/0008-rear-visual-notification-highlight.md。）
     *
     * 每个事件后都跑一遍，所以合取的任一项变化都会触发判退，不依赖投送分支自己记得补
     * ExitDashboard。
     *
     * 屏不退时（持有理由或退屏宽限）图标面照常对齐（[syncIconSet]）：内容变了屏还留着，
     * 留着的屏不能显示已经不存在的图标。
     */
    private fun reconcileExit(): List<DashboardEffect> {
        if (!projectionReady) {
            clearExitGrace()
            return emptyList()
        }
        val current = onScreen ?: run {
            clearExitGrace()
            return emptyList()
        }
        // manual 不走对齐也不判退：沿票 #52 判例，手动屏是投出那一刻的快照、只能手动撤。
        if (current.source == CastSource.MANUAL) {
            clearExitGrace()
            return emptyList()
        }

        // AGENT 持有（spec 0010）：理由在身就不判退（等确认/工作期间通知清空也不退屏，
        // 图标面照常对齐）；理由已消失的漏改记（理论上 onAgentReasonChanged 已交还）按
        // auto 规则补判，不在屏留过期镜像记账。
        if (current.source == CastSource.AGENT) {
            clearExitGrace()
            if (agentReason) return syncIconSet(current)
            onScreen = current.copy(source = CastSource.AUTO)
            return onGateChanged()
        }
        if (chargingReason) {
            clearExitGrace()
            return syncIconSet(current)
        }
        // 票 #111：详情在屏不判退——点开即消可能把 Icon Set 清空，但卡片还等着用户读完点按
        // 收起；图标面照常对齐（UpdateIconSet 是内容更新），判退推迟到收起那刻的统一出口
        // （外部清除走 detailCloseIfShown 先收卡，随后同一事件的判定即正常判退）。
        if (detailView != null) {
            clearExitGrace()
            return syncIconSet(current)
        }

        val icons = projectedIconSet()
        if (icons.isNotEmpty()) {
            // 空集被新通知填回来：取消尚未到期的退屏宽限；幂等路径静默。
            cancelExitGrace()
            return emptyList()
        }
        if (current.iconSet.isNotEmpty()) {
            // 最后一条通知刚被清掉且无持有理由：从空集起始时刻记宽限，屏上内容照常刷空；
            // 到期由 ExitGraceElapsed 唤醒统一出口（持有理由出现时会在上面各自收口）。
            exitGraceUntilMs = nowMs() + EXIT_GRACE_MS
            logExitGrace("exit grace start")
        }
        val deadline = exitGraceUntilMs
        if (deadline != null && nowMs() < deadline) return syncIconSet(current)
        if (deadline != null) {
            exitGraceUntilMs = null
            logExitGrace("exit grace end")
        }
        onScreen = null
        clearDetailOnScreenGone() // 末条通知退屏 Detail 随之清（key 对账路径通常已先行收起）
        return listOf(DashboardEffect.ExitDashboard)
    }

    /** 新通知把空集填回来：取消未到期宽限；只有真有宽限时才留 cancel 锚。 */
    private fun cancelExitGrace() {
        if (exitGraceUntilMs == null) return
        exitGraceUntilMs = null
        logExitGrace("exit grace cancel")
    }

    /** 撤屏/手动交还等不再需要宽限的路径：清计时，不产生新锚。 */
    private fun clearExitGrace() {
        exitGraceUntilMs = null
    }

    /**
     * 屏不退时把图标面刷成当前 Icon Set：充电持有屏上通知清空 → 图标行清空、屏照留
     * （`UpdateIconSet` 是内容更新，不是投/撤）。集合未变时无效果。
     */
    private fun syncIconSet(current: OnScreen): List<DashboardEffect> {
        val icons = projectedIconSet()
        if (icons == current.iconSet) return emptyList()
        onScreen = current.copy(iconSet = icons)
        return listOf(DashboardEffect.UpdateIconSet(icons))
    }

    /** 通道不可用：仅在 Dashboard 在屏时产出一次 Degrade（停止投送）；记账（含来源标签）一并清零。 */
    private fun degrade(): List<DashboardEffect> {
        projectionReady = false
        clearExitGrace()
        clearDetailOnScreenGone() // 屏要降级撤下，卡片无宿主（票 #66）
        if (onScreen == null) return emptyList()
        onScreen = null
        return listOf(DashboardEffect.Degrade)
    }

    /** Takeover 或 Dashboard 意外消失后重投，幂等：同一 Icon Set 重新 LaunchDashboard，来源标签保持。 */
    private fun retake(): List<DashboardEffect> =
        if (projectionReady) {
            // 抢回重投回纯图标常态：被抢走/意外销毁过的屏不带旧卡片（票 #66，重投即新常态）。
            clearDetailOnScreenGone()
            onScreen?.let { listOf(DashboardEffect.LaunchDashboard(it.iconSet)) } ?: emptyList()
        } else {
            emptyList()
        }

    /**
     * 兜底通道恢复后重投当前 Icon Set，幂等；门未开不重投（spec 0006：自动重投路径同样过门）。
     *
     * 充电理由在身时按充电重投、不过门（插电是独立触发，spec 0007）；manual 在屏不改记，
     * 仍走下面的既有判据。
     *
     * 与 [retake]（被抢回后重投）的分工：这里只看「通道就绪 + 有通知」，不看核心是否认为界面在屏。
     * 当前状态机里 `projectionReady + Icon Set 非空 + 门开` 已经蕴含在屏，所以两者今天效果相同；
     * 分开写是为了让「通道恢复」这条路径不依赖那个不变量——将来界面自愈逻辑变了也不会静默漏投。
     */
    private fun retryProjection(): List<DashboardEffect> {
        if (!projectionReady) return emptyList()
        if (agentReason && onScreen?.source != CastSource.MANUAL && gatesOpen()) return launchAgent()
        if (chargingReason && onScreen?.source != CastSource.MANUAL) return launchCharging()
        if (!gatesOpen()) return emptyList()
        val icons = projectedIconSet()
        return if (icons.isEmpty()) emptyList() else listOf(DashboardEffect.LaunchDashboard(icons))
    }

    // ---------- 可用性引导横幅（票 #28：出现/消失/健康不打扰的纯决策） ----------

    /** 当前触发原因：自启动非 GRANTED、监听不健康各自独立触发；无读数（null）不触发。 */
    private fun usabilityReasons(): Set<UsabilityReason> = buildSet {
        when (autostartState) {
            AutostartState.DENIED -> add(UsabilityReason.AUTOSTART_DENIED)
            AutostartState.IN_DOUBT -> add(UsabilityReason.AUTOSTART_IN_DOUBT)
            AutostartState.GRANTED, null -> Unit // 已放行/尚无读数：不触发、也不显示健康
        }
        if (listenerHealthy == false) add(UsabilityReason.LISTENER_UNHEALTHY)
    }

    /**
     * 横幅显隐对齐：原因集变化才产出效果——出现（隐藏→任一异常）/ 消失（异常清空→隐藏）/
     * 健康不打扰（健康且未显示 → 无效果）；原因集变化但横幅在屏时重发 Show（内容更新）。
     */
    private fun reconcileUsability(): List<DashboardEffect> {
        val reasons = usabilityReasons()
        if (reasons == bannerReasons) return emptyList()
        bannerReasons = reasons
        return if (reasons.isEmpty()) {
            listOf(DashboardEffect.HideUsabilityBanner)
        } else {
            listOf(DashboardEffect.ShowUsabilityBanner(reasons))
        }
    }

    companion object {
        /** 充电动画总开关默认档（spec 0007 story 11）：开——设置层与 core 同源，不各记一份。 */
        const val CHARGING_ANIMATION_DEFAULT = true

        /**
         * 姿态门控开关默认档（票 #100）：**关**（门控旁路，开机即用）——出厂与升级后同档，
         * 设置层与 core 同源，不各记一份。
         */
        const val POSTURE_GATE_DEFAULT = false

        /**
         * 角部避让开关默认档（spec 0019 / 票 #194）：**关**（＝贴满，角部缺字认了——机主定夺
         * 「默认贴满」）——出厂与升级后同档，设置层与 core 同源，不各记一份。
         */
        const val CORNER_AVOIDANCE_DEFAULT = false

        /**
         * 退屏宽限（spec 0015 / 票 #146）：最后一条通知清空后的留屏窗，约 0.25 秒——
         * 大于一次退场动效、小于用户可感知迟滞；spec 上限为 1 秒，当前取 250ms。
         */
        const val EXIT_GRACE_MS = 250L

        /**
         * Notification Highlight 呼吸窗（spec 0008 / 票 #65）：约 3 秒、一次性非循环。
         * 时长决策在 core（效果携带 [DashboardEffect.HighlightBreath.untilMs]），渲染层按剩余时长播放。
         */
        const val HIGHLIGHT_BREATH_MS = 3_000L

        /**
         * Notification Highlight 冷却窗（spec 0008 / 票 #65）：30 秒——呼吸中/冷却中再触发
         * 不重复呼吸。呼吸窗 ⊂ 冷却窗，状态机只记冷却截止。
         */
        const val HIGHLIGHT_COOLDOWN_MS = 30_000L

        /**
         * Highlight 日志锚词形契约（票 #65，同 `wake-keep-alive` / `task-move word=` 惯例）：
         * `highlight breath start` / `highlight breath end`——ASCII 前缀，tools/ex 验收链按词形读——
         * **byte 不可改**。（`highlight add/remove` 随图标高亮退役删除，2026-09-28 grilling 定案。）
         * `breath start` 由 DashboardCore 打（经构造注入的 [log]）；
         * `breath end` 由背屏动画播完打（RearDashboardActivity）；logcat 实现统一 TAG=RearCue。
         */
        const val LOG_HIGHLIGHT_CONTRACT = "highlight breath start|end"

        /**
         * 退屏宽限日志锚词形契约（spec 0015 / 票 #146，同 [LOG_HIGHLIGHT_CONTRACT] 惯例）：
         * `exit grace start`（末条通知清空、进入宽限）/ `exit grace cancel`（新通知取消）/
         * `exit grace end`（宽限到期交还）——均经构造注入的 [log] 打，tools/ex 验收链按词形读，
         * **byte 不可改**。logcat 实现统一 TAG=RearCue。
         */
        const val LOG_EXIT_GRACE_CONTRACT = "exit grace start; exit grace cancel; exit grace end"

        /**
         * Detail 日志锚词形契约（票 #66，同 [LOG_HIGHLIGHT_CONTRACT] 惯例）：
         * `detail open <pkg>` / `detail close <pkg>`——打开（含切换到新 App）、再点按收起、
         * 所示 key 清除自动收、撤屏随之清都走同一对词形（收起原因看前后的伴随日志），
         * tools/ex 验收链按词形读——**byte 不可改**。logcat 实现统一 TAG=RearCue。
         */
        const val LOG_DETAIL_CONTRACT = "detail open <pkg>; detail close <pkg>"

        /**
         * Agent 等待确认强调的日志锚词形契约（spec 0010 / 票 #85，同 [LOG_HIGHLIGHT_CONTRACT]
         * 惯例）：`agent pulse start`（core 打）/ `agent pulse end`（背屏动画播完打，
         * RearDashboardActivity）——tools/ex 验收链按词形读，**byte 不可改**。logcat 统一 TAG=RearCue。
         */
        const val LOG_AGENT_PULSE_CONTRACT = "agent pulse start; agent pulse end"

        /**
         * Session Lock 自动清锁的日志锚词形契约（票 #103，同 [LOG_AGENT_PULSE_CONTRACT] 惯例）：
         * `session lock cleared <sessionId>`（锁定会话从任务表消失、core 清锁退回自动时打，
         * 经构造注入的 [log]）——tools/ex 验收链按词形读，**byte 不可改**。logcat 统一 TAG=RearCue。
         */
        const val LOG_SESSION_LOCK_CONTRACT = "session lock cleared <sessionId>"

        /**
         * 桥来源锁保锁的日志锚词形契约（spec 0016 / 票 #155，同 [LOG_SESSION_LOCK_CONTRACT]
         * 惯例）：`session lock held <sessionId> bridge-roster-unknown`——桥名册不是当下事实
         * （断线/未对账）时，桥来源的锁因缺席而**不**被清，打本锚；tools/ex 验收链按词形读，
         * **byte 不可改**。logcat 统一 TAG=RearCue。
         */
        const val LOG_SESSION_LOCK_HELD_CONTRACT = "session lock held <sessionId> bridge-roster-unknown"

        /**
         * Content Page 日志锚词形契约（spec 0013 / 票 #132/#133/#134，同
         * [LOG_SESSION_LOCK_CONTRACT] 惯例）：core 打 reset/toggle/fallback/wfa enter/wfa exit；
         * rear 打 crossfade start/done；page ∈ {notification, agent}。词形由
         * [ContentPageLogContract] 冻结，tools/ex 验收链按词形读——**byte 不可改**。
         * logcat 实现统一 TAG=RearCue。
         */
        const val LOG_CONTENT_PAGE_CONTRACT = ContentPageLogContract.CONTRACT

        /**
         * 会话选择器日志锚词形契约（spec 0016 / 票 #156，同 [LOG_CONTENT_PAGE_CONTRACT] 惯例）：
         * core 打 open/close（`<reason>` ∈ {toggle, wfa, page, select}），app 接线打 select
         * （`<sessionId>`，选自动档时为 `auto`）；词形由 [AgentPickerLogContract] 冻结，
         * tools/ex 验收链按词形读——**byte 不可改**。logcat 实现统一 TAG=RearCue。
         */
        const val LOG_AGENT_PICKER_CONTRACT = AgentPickerLogContract.CONTRACT

        /**
         * 图标出入场触发日志锚词形契约（spec 0015 / 票 #147 入场、#148 退场，同
         * [LOG_CONTENT_PAGE_CONTRACT] 惯例）：`icon enter <pkg>` 在背屏检测到某包名从上一帧
         * 缺席变为当前可见、即将播放入场时打一条；`icon exit <pkg>` 在某包名从上一帧可见变为
         * 当前缺席、且当时画在网格里、即将播收缩淡出时打一条（持续退场中的条目跨帧不重复打）。
         * 词形由 [IconMotionLogContract] 冻结，tools/ex 验收链按词形读，**byte 不可改**。
         * logcat 实现统一 TAG=RearCue。
         */
        const val LOG_ICON_MOTION_CONTRACT = IconMotionLogContract.CONTRACT

        /**
         * 等待确认强调窗（spec 0010 / 票 #85）：约 3 秒、一次性非循环、不响不震。
         */
        const val AGENT_PULSE_MS = 3_000L

        /**
         * 等待确认强调冷却窗（spec 0010 / 票 #85）：30 秒——强调窗 ⊂ 冷却窗，语义同
         * [HIGHLIGHT_COOLDOWN_MS] 的判例。
         */
        const val AGENT_PULSE_COOLDOWN_MS = 30_000L
    }
}
