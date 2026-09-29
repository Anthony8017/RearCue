# Spec 0016：背屏会话选择——三来源合并列表 + 断线保锁对账

状态：已与机主 grill 收口（2026-09-29，三轮 Q1–Q12，frontier 空，全部按推荐定案）；#153（桥来源 + 在册快照）/#154（三来源合并在册集与统一列表）/#155（断线保锁与对账清锁）/#156（背屏会话选择器）均已实现并合并入 main（PR [#158](https://github.com/Anthony8017/RearCue/pull/158)，merge `226f0cd`），JVM 判例全绿；**#157 收口：回环冒烟（判定表 14 项：PASS 11 / 部分 PASS 1 / INCONCLUSIVE 1 / 未跑 1）与 cloudflared 真隧道复测（判定表 8 项：PASS 6 / 见注 1 / 未跑 1）均已跑**，「第二条 Codex CLI 会话」单独立项未跑（不擅自动机主额度）；证据归档 [回环](../poc-logs/20260929-174000-spec0016-session-picker/README.md) · [真隧道](../poc-logs/20260929-175900-spec0016-tunnel/README.md)。术语采用 CONTEXT.md 的 Agent Mirror、Session Lock、Content Page、Waiting-for-Approval 与「Agent 页会话标识行」。编号说明：0014 已被 #135 占用（正文只存在于 issue）、0015 已被 #145 占用，故本 spec 取 0016。

跟踪：[Issue #152](https://github.com/Anthony8017/RearCue/issues/152)，子票 [#153](https://github.com/Anthony8017/RearCue/issues/153)、[#154](https://github.com/Anthony8017/RearCue/issues/154)、[#155](https://github.com/Anthony8017/RearCue/issues/155)、[#156](https://github.com/Anthony8017/RearCue/issues/156)、[#157](https://github.com/Anthony8017/RearCue/issues/157)；PR [#158](https://github.com/Anthony8017/RearCue/pull/158)。
## Problem Statement

机主想在背屏上看 agent 会话来直接换会话，但现在做不到：

- Session Lock（会话选择）的入口只在主屏 Agent 设置区，换会话必须掏出主屏、进设置；
- 可选列表只有 ZCode 来源的会话；Codex / Claude（PC 桥来源）的会话只进「显示谁」的核心状态、不进列表，即使强行锁定也会被下一次 ZCode 任务表对账清掉；
- 因此「用当前 Codex 测连通」只能验证被动镜像（自动档选到谁看谁），验证不了选择与切换。

## Solution

- 背屏 Agent 页的会话标识行单击 → 弹出全窗会话列表；点一条即锁定。
- 锁与主屏是同一把 Session Lock：持久、含「自动」档，不新增临时调阅档。
- 列表合并 ZCode / Codex / Claude 三来源（来源标记区分）；等待确认置顶、其余最近活跃在前。
- 桥来源的会话进入统一列表并可锁定；锁不被跨来源对账或断线误清——断线期间保锁，重连拿到「在册快照」对账、确认确实不在册才清。
- 真链路验收：先 adb reverse 回环冒烟、再 cloudflared 真隧道复测；当前 Codex 桌面会话与第二条 CLI 会话要能在背屏完成选择与切换。

## User Stories

1. 作为机主，我想在背屏上直接换一个要看的会话，以便不掏主屏就能在多任务间切换。
2. 作为机主，我想点一下会话标识行就打开会话列表，以便入口明确、不用学新手势。
3. 作为机主，我想列表第一条是「自动」，以便随时回到「谁忙看谁」的默认档。
4. 作为机主，我想背屏选中的会话与主屏锁的是同一档，以便两屏状态永远一致、不出现两套事实。
5. 作为机主，我想背屏的选择跨 App 重启保留，以便第二天不用重选。
6. 作为机主，我想在列表里看到 Codex 与 Claude 的会话，以便选到 PC 桥上的任务。
7. 作为机主，我想每条会话标明来源（ZCode / Codex / Claude），以便一眼分清它在哪台机器/哪条通道上跑。
8. 作为机主，我想等待确认的会话排在列表最上面并带标记，以便优先处理卡住的那条。
9. 作为机主，我想条目显示工作区目录名，以便按项目认出会话。
10. 作为机主，我想同一目录下的多个会话能区分（附会话号尾 4 位），以便不选错。
11. 作为机主，我想会话标识行没有工作区信息时显示会话号尾 4 位，以便入口永远存在。
12. 作为机主，我想列表打开后点列表外或再点标识行就能关掉，以便随时退出并回到镜像内容。
13. 作为机主，我想列表不会超时自动关闭，以便可以从容选择。
14. 作为机主，我想有会话进入等待确认时列表自动关闭并直接显示那条会话，以便不漏掉待批准。
15. 作为机主，我想选中会话后立刻从最新输出开始跟随，以便不困在旧会话的回看位置。
16. 作为机主，我想网络抖动或桥重启期间锁不丢，以便恢复后继续看同一条会话。
17. 作为机主，我想只有电脑端会话确实不在册时才清锁退回自动，以便锁不会永久指向幽灵会话。
18. 作为机主，我想列表只列各来源「当前在册」的会话而不是全部历史，以便列表短而准确。
19. 作为机主，我想切换会话不响不震，以便背屏保持安静。
20. 作为机主，我想点正文或空白仍然切换通知页 / Agent 页，以便原有习惯不变。
21. 作为机主，我想主屏的会话列表同样显示三来源会话，以便主屏也能锁定 Codex / Claude 会话。
22. 作为机主，我想锁定与清锁在断电重启后不丢不错，以便可靠。
23. 作为机主，我想充电、通知插队、退屏、保活等既有行为不变，以便新功能不影响老习惯。
24. 作为机主，我想用真实 Codex 链路验收（当前桌面会话 + 第二条 CLI 会话），以便确认不是纸上功能。

## Implementation Decisions

- **来源维度**：统一会话模型新增「来源」（ZCode / Codex / Claude）；PC 桥的统一会话事件契约新增 source 字段（由桥的两条适配器填充），ZCode 链路的会话在手机侧标为 ZCode。来源是列表标记与标题消歧的依据。
- **一份列表，两屏同源**：主屏列表与背屏列表使用同一份投影——合并三来源的在册会话；排序统一为「等待确认置顶（组内最近活跃降序）→ 其余按最近活跃降序（平局按到达序）」。主屏列表现状是 wire 原序、且只含 ZCode，本 spec 起改为统一规则。
- **标题派生一处**：workspace 目录名（非全路径）；无 workspace 用 sessionId 尾 4 位；同工作区重名时附尾号。主屏条目、背屏条目、背屏会话标识行共用同一派生。
- **在册口径**：ZCode = 任务表在册；Codex / Claude = 桥在册（桥进程当前会话表；桥冷启动只回放近 30 分钟活跃的会话文件，沿现状）。不做全历史会话浏览。
- **桥新增只读「在册快照」接口**：返回当前在册会话键集；手机每次链路重新连上后对账一次。桥来源的锁只在快照确认缺失时清除；桥断线期间保锁（背屏按既有断连语义回落，恢复后自动续看）。
- **清锁分源**：ZCode 沿既有「任务表消失即清」；桥来源按快照对账清。清锁仍全部由状态机决策、同帧写盘（既有三段式，不新增第二份事实）。
- **背屏入口**：会话标识行单击 = 打开全窗浮层列表（沿 Detail 卡的全窗浮层先例）；正文 / 空白点按仍 = 切内容页（既有语义不变）。**（票 #161 修订：标识行固定渲染在屏幕顶部、不随正文滚动——长正文跟随时入口不再被滚走；Detail View 的「标题 + 正文整体居中」不变。）**
- **选择器状态**：打开 / 关闭与「等待确认插队即关」的判定收口在状态机的投影（沿 Detail View / Content Page 先例）；选择动作复用既有 setSessionLock 单入口（事件 + 偏好写盘），不新增存储。
- **背屏列表视觉**：全窗浮层；条目 = 标题 + 来源标记 + 等待确认标记 + 选中标记；每行不小于 48dp；一屏约 4 行、超出滚动；首行「自动」。点条目 = 选定 + 关闭 + 回实时跟随；点列表外或再点标识行 = 关闭；不自动超时；不响不震。**（票 #160 修订：一屏由 4 行改为 3 行 + 底部可见关闭带——原判在 ≥4 条时「点列表外」只剩相机带那条看不见的空白。）**
- **接线**：背屏宿主新增一个「选中会话」回调（与既有图标点按 / 内容页点按同型）；Agent 内容推流增发列表数据（列表投影 + 当前档）；桥客户端在链路连上后触发一次快照对账；状态机的名册对账口径改用「合并在册集」。
- **日志锚**：新增选择器打开 / 选定 / 关闭与对账清锁的 LOG 词形（ASCII 前缀，供实机验收链断言）；既有锚词形不变。
- **不新增**：新 ADR、新权限、新手势（仅单击）、遥控 / 发消息能力、会话历史翻页、打码档、设置项、依赖。

## Testing Decisions

- 好测试只测外部行为：事件序列 → 效果序列 + 只读投影；不断言内部字段、Compose 节点或动画实现。
- **状态机 seam（最高 seam）**：状态机判例（Prior art：SessionLockTest / AgentArbitrationTest / DashboardCoreTest）覆盖：合并名册下的仲裁；桥会话锁不被 ZCode 名册清掉；断线保锁；快照对账缺失才清；跨来源等待插队与回锁；自动档选择不回归。
- **投影 seam**：列表投影纯逻辑判例（Prior art：AgentStateLogicTest / SessionLockIndexTest）覆盖：三来源合并与去重；标题派生与尾号消歧；来源标记；等待置顶排序；「自动」行；选中态；在册交集。
- **桥 seam**：桥 node 判例（Prior art：bridge.test.mjs / adapters.test.mjs）覆盖：事件 source 字段（codex / claude）；在册快照接口契约与容错；游标 / 事件环不回归。手机侧解码判例（Prior art：BridgeEventCodecTest）覆盖 source 与快照解析。
- **背屏 UI 不写 JVM / Compose 测试**（先例：spec 0004 / 0013）：入口、浮层、关闭与插队关闭走实机日志锚 + 截图验收，沿 drive-*.ps1 惯例。
- **端到端验收**：adb reverse 冒烟（桥 → 手机 → 背屏：当前 Codex 桌面会话出现、选中、切到 CLI 第二条会话）→ cloudflared 真隧道复测；证据归档 docs/poc-logs（截图 + logcat + 判定表）。

## Out of Scope

- 遥控 / 发消息 / 批准操作；对话历史跨会话翻页；会话归档 / 删除 / 重命名；打码档。
- ZCode 私有协议与 ADR 0005 通道决策；隧道实现替换（cloudflared / tunwg 选型不变）；桥鉴权（保持现状）。
- Claude Desktop 的单独实机验收（同一适配器顺带验证，不单独立项）。
- 通知页 / 充电 / 投送 / 姿态门 / 保活等既有行为。
- 新设置项、新手势（长按 / 双击 / 滑动选台）、声音 / 震动反馈。

## Further Notes

- 2026-09-29 grill-with-docs 三轮 Q1–Q12 全部按推荐定案：Q1 点标识行开列表；Q2 与主屏同一把锁；Q3 同一份列表同排序；Q4 桌面 App 为必测对象；Q5 先冒烟后补齐；Q6 三来源合并；Q7 单击开列表 + 尾号兜底；Q8 全窗浮层、不自动关、插队即关；Q9 CLI 第二条会话 + 回环再真隧道；Q10 断线保锁、快照对账才清；Q11 精简条目；Q12 立 spec + 拆票 + 实现到实机冒烟。
- CONTEXT.md 已同步（Session Lock 条目：背屏入口、同一把锁、三来源合并列表）；无新 ADR（改动可逆、不违 ADR 0006）。
- 桥现状与缺口（事实）：桥已实现且本机 notify 已接线；无鉴权、无在册快照接口、统一事件无 source——本 spec 的桥改动只加 source 与只读快照，不动其余契约。
- 文件镜像（docs/specs/0016-*.md）由收口票产出（沿 0013 / 0015 惯例）。

## 收口记录（票 #157）

- **实现**：#153 桥统一事件加 `source` + 只读 `/snapshot`；#154 会话模型带来源、合并在册集、单处列表投影（`AgentStateLogic.projectRoster`）；#155 `BridgeRelayClient` 每条链路对账一次在册快照（取件前静置窗跨过桥侧补读去抖、按链路世代作废旧线程），`DashboardEvent.AgentRoster.bridgeRosterKnown` 分源清锁（桥来源缺席不清、清锁仍单入口单份事实）；#156 `AgentPickerToggle` 事件 + `agentPicker` 投影（插队即关、切页/退屏即关、不超时、插队期不开）+ 背屏全窗浮层 `AgentPickerLayer`（行 ≥48dp、滚动、点外关闭、不响不震）+ 选定走 `setSessionLock` 单入口 + 换会话回实时跟随。合并入 main：PR #158（merge `226f0cd`）。
- **日志锚**：`agent picker open|close <reason>|select <sessionId>`（reason ∈ toggle/wfa/page/select）与 `session lock held <sessionId> bridge-roster-unknown`；词形由 `AgentPickerLogContract` 与 `DashboardCore.LOG_SESSION_LOCK_HELD_CONTRACT` 冻结，判例 `AgentPickerLogContractTest`。
- **验收**：回环冒烟判定表 14 项（PASS 11、部分 PASS 1、INCONCLUSIVE 1、未跑 1）见 [回环归档](../poc-logs/20260929-174000-spec0016-session-picker/README.md)；cloudflared 真隧道复测判定表 8 项（PASS 6、见注 1、未跑 1）见 [真隧道归档](../poc-logs/20260929-175900-spec0016-tunnel/README.md)——隧道 URL 由桥自动 adb 推送，列表 / 选中即锁 / 拔桥保锁 / 重启续看在真隧道下与回环一致；断线兜底回通知页后不自动切回 Agent 页（spec 0013 既有口径，非本 spec 偏离）。验收中发现并修复一处真缺陷——`BridgeEventCodec` 把显式 JSON `null` 解成字符串 `"null"`（背屏列表出现标题「null」），已补判例。
- **未跑**：「第二条 Codex CLI 会话」单独立项（会真跑一次模型调用、动到机主额度，未擅自发起）；保锁锚 `session lock held …` 的实机构造（需 ZCode 配对产生名册事件，行为已由 JVM 判例钉住）。
- **未改（待机主定夺）**：列表溢出（≥4 条）时「点列表外」只剩左侧相机带空白可用、「再点标识行」被浮层盖住；长正文会把会话标识行带出视口。
- **收口后修订（机主定夺，2026-09-29 实机验收当天）**：上述两条观察各立一票并已落地——**#160** 列表最多完整展示 3 行（`AgentPickerParams.VISIBLE_ROWS`），其余在列表内滚动，底部恒留可见的空白关闭带（实测点带即 `agent picker close toggle`）；**#161** 会话标识行固定屏幕顶部（正文视口按 `topReservePx` 下移），长正文跟随/回看时入口不被滚走，Detail View 的「标题 + 正文整体居中」不受影响（CONTEXT.md「Agent Mirror」条目已同步改写）。两条修订的实机证据见 [docs/poc-logs/20260929-183000-spec0016-picker-revisions](../poc-logs/20260929-183000-spec0016-picker-revisions/README.md)。
