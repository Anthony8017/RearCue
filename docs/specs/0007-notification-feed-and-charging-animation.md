# Spec 0007：Notification Feed（内容横幅）+ Charging Animation（充电动画）

状态：已与机主 grill 收口（2026-09-26），frontier 空；Q7/Q8 随 to-spec 按推荐定案。
本 spec 显式反转 spec 0002 的「通知内容」out-of-scope（见 Further Notes），是机主对「仅图标集」产品决策的正式修订。

## Problem Statement

Icon Set 只能回答"哪个 App 在等我"，回答不了"消息大概是什么"——机主不翻手机仍要拿起来看内容；插电时背屏无任何反馈，充电状态要翻手机确认。MRSS 的通知推送（内容显示、隐私模式、自动销毁）与充电动画两项功能经三分法判定为公共 API 类（仍有效），机主决定迁移并接受由此带来的产品决策反转。

## Solution

背屏内容从"仅图标集"扩展为三层，全部嵌在既有 Dashboard 内、不新增背屏界面：

- **Notification Feed（通知横幅）**：Dashboard 顶部横幅显示**最新一条** Allowlist 通知；Privacy Mode 默认开（仅应用名+固定文案「你有一条新消息」），关闭显示标题+内容；新通知到来刷新横幅并重新计时；Auto-dismiss 默认 10 秒、可调 5 秒～无上限，到期横幅淡出回退纯 Icon Set（图标不动）；横幅所示通知被清除时立即隐藏。
- **Charging Animation（充电动画）**：Dashboard 内嵌 2D 闪电图标动画（不做 MRSS 的重力液体）；插电是通知之外的**独立投送触发源**——无通知时插电也把 Dashboard（仅含动画）送上背屏；不受 DND Follow / Posture Gate 管；拔电且无横幅需求即退；应用内总开关默认开。
- 横幅走与图标集完全相同的自动路径门控（DND Follow、Posture Gate、手动豁免），无第二套规则。

## User Stories

1. 作为机主，我希望 Dashboard 顶部横幅显示最新一条 Allowlist 通知的应用名与内容，以便不翻手机知道消息大概是什么。
2. 作为机主，我希望 Privacy Mode 默认开启——横幅只显示应用名+「你有一条新消息」，以便背屏朝外时内容不外泄。
3. 作为机主，我希望在设置页切换 Privacy Mode（开=应用名+固定文案，关=标题+内容），以便按场合调隐私档。
4. 作为机主，我希望新通知到来时横幅立即切换为新通知并重新计时，以便横幅始终对应最新一条。
5. 作为机主，我希望 Auto-dismiss 默认 10 秒、设置页可调 5 秒～无上限（无上限=常驻直到通知被清除），销毁后回退纯 Icon Set 且图标保留，以便内容不常驻外屏、指示仍在。
6. 作为机主，我希望横幅正在显示的通知被划掉时横幅立即消失，以便背屏不显示已不存在的内容。
7. 作为机主，我希望横幅与图标集同门控——DND 开不显示且在屏的撤下、正放不投、倒扣补投，以便勿扰/倒扣语义一致。
8. 作为机主，我希望插电时 Dashboard 出现闪电图标充电动画（2D），以便充电状态背屏可见。
9. 作为机主，我希望无通知时插电也能点亮背屏显示充电动画（插电=独立投送触发、不受门控），以便床头倒扣/支架正放充电时随看随见。
10. 作为机主，我希望拔电且 Icon Set 为空且无横幅需求时，背屏自动交还原生界面，以便充电反馈不残留。
11. 作为机主，我希望充电动画有应用内总开关（默认开、可关），以便不需要时彻底静默。
12. 作为机主，我希望设置页在 Allowlist 之外新增横幅与充电区（Privacy Mode 开关、Auto-dismiss 时长、充电动画开关），以便日常调整集中一处。
13. 作为开发者，我希望横幅生命周期（刷新重计时/到期销毁/被清除即隐/隐私档切换）与充电触发（插电投/拔电退条件）以事件进 core 状态机、沿 DashboardEvent 既有 seam，以便全部语义有 JVM 单测。
14. 作为开发者，我希望投送来源标签在 auto/manual 之外扩展 charging 一类，以便「拔电∧无横幅需求即退」与「手动投的不自动撤」都是状态机结论而非特判。
15. 作为开发者，我希望通知内容仅经 NotificationListenerService extras 内存内读取，不落盘、不外传，以便隐私面最小。
16. 作为开发者，我希望 `gradlew test` 既有基线 0 失败不回退。
17. 作为开发者，我希望验收轮留痕 poc-logs（沿用 E 系列惯例），场景至少覆盖：横幅显示/刷新重计时/到期销毁/隐私档切换、所示通知被清除即隐、插电独立投送（含正放与 DND 中）、拔电退出、总开关关闭后插电无反应。

## Implementation Decisions

- **模块改动**：`:core`（feed 与 charging 的事件、来源标签扩展、销毁计时语义）；`:app`（设置页两区、NLS extras 接线、充电广播接线）；`:rear`（Dashboard 布局加横幅与动画，ProjectionSession 零改动）。
- **Notification Feed 语义**：只显示最新一条；刷新即重计时；到期或所示通知被清除 → 横幅隐去；Icon Set 语义不变（每 App 一枚、无角标）。Auto-dismiss 计时由 core 驱动（虚拟时钟），不依赖 UI。〔「无角标」半句由 issue #101（2026-09-28）反转：≥2 条通知的网格档每格带 Active Notification 计数角标，见 Out of Scope 注。〕
- **Privacy Mode**：二档（开/关），默认开；档位切换即时生效于当前横幅。
- **Charging Animation**：`ACTION_POWER_CONNECTED/DISCONNECTED` 触发；投送来源标签 charging，不受两道门控与手动豁免规则约束（不因翻正/勿扰撤下）；退出条件 = 拔电 ∧ Icon Set 空 ∧ 无横幅。2D Compose 动画，不引入重力传感器。
- **门控复用**：横幅属自动路径内容，spec 0006 的 DND Follow / Posture Gate / 来源标签原样适用，无新增门控。
- **决策反转留痕**：spec 0002 的 Out of Scope「通知内容与未读数」——「通知内容」半句反转，「未读数」半句仍有效（不引入数字角标/计数）；spec 0005 的「单条通知增删/屏蔽 永不实现」**不反转**——feed 只读显示最新一条，不提供任何单条管理对象；两处均补注指向本 spec。〔issue #101（2026-09-28）再反转「数字角标/计数」半句：Icon Set 网格档引入每格 Active Notification 计数角标；「单条管理」半句仍不反转。〕
- **不引入 MRSS 代码**（ADR 0002 / spec 0001 story 24），仅复用机制事实。

## Testing Decisions

- 好测试只测事件→效果。`:core` JVM 判例覆盖：横幅刷新重计时、到期销毁、所示通知清除即隐、隐私档切换、充电投/退条件（含拔电∧Icon Set 空∧无横幅的合取）、与门控交叠（DND 撤下时横幅随 Dashboard 撤、倒扣补投时横幅恢复）、来源标签（charging 投的不被翻正撤）。
- 接线层（传感器/广播/extras 解析/Compose）不写 JVM 测试（无决策）；以实机验收链替代（story 17）。
- Prior art：DashboardCoreTest 的 Allowlist 三态判例、spec 0006 的门控判例——同 seam、同风格。
- 基线：`gradlew test` 既有基线 0 失败不回退；验收轮归档 poc-logs。

## Out of Scope

- **未读数/数字角标**——spec 0002 该半句仍有效，永不实现。〔反转留痕：issue #101（2026-09-28）在 Icon Set 网格档（≥2 条通知）引入每格角标，数字 = 该 App 的 Active Notification 条数，语义判例以 CONTEXT.md「Icon Set」为准。〕
- **单条通知增删/屏蔽**——spec 0005 不反转；Active Notification 仍是系统事实，Allowlist 增删粒度是应用。
- 多条通知堆叠、历史回看、横幅点击交互（背屏触控不触发动作）。
- MRSS 式全屏 3D 充电动画与重力液体效果。
- URI 外部触发、监控式 Takeover 恢复——沿 spec 0006 口径。

## Further Notes

- 术语已随本 spec 落 CONTEXT.md：Notification Feed、Privacy Mode、Auto-dismiss、Charging Animation；Icon Set 条目改写（不再是背屏唯一内容）。
- 决策反转的准确边界：反转的只有「背屏是否显示通知内容」这一件事；未读数、单条管理、数字角标维持原判。未来读到 spec 0002/0005 的 out-of-scope 行时以本 spec 的注记为准。〔issue #101（2026-09-28）再反转「数字角标」半句——仅限 Icon Set 网格档的 Active Notification 计数角标；单条管理维持不实现。〕
- MRSS 参考事实：通知内容经 NLS extras、充电经 BatteryManager 广播，均为公共 API，与 MRSS 停更原因（系统接口漂移）无交集。
- 充电动画成为第二投送触发源后，Wake Keep-alive 在充电在屏期间照常注入（插电态无耗电顾虑）。
- **快照对账改 key 判据 + 同 key 内容更新语义**（实现期决策，判例在 `:notification` 测试与 DashboardCoreTest）：Active Notification 携带 title/text 之后，按整条相等的对账会把「内容变了」误判成「消失又出现」——在屏图标抖一轮、横幅凭空重投。故存在判定只认 notification key：重连快照里消失的 key 才报 Removed、新 key 报 Posted、既有 key 只就地刷新内容；内容字段变化以 `Updated` 单独报出且**只**喂 FeedPosted（横幅刷新并重新计时），不喂 NotificationPosted——Icon Set「每 App 一枚」不因内容更新而重排/重计。同 key 同内容仍无事件（幂等）。
- **manual×charging 交叠的三条结论**（状态机推导、非特判，判例在 DashboardCoreTest）：ManualCast 覆盖 charging 标签（手动意图后到者获胜，此后充电理由与两道门都不改记在屏来源）；ManualExit 后不因仍在充电自动重投（退出只由下一次 ManualCast/通知/插电等事件触发）；Degrade 抹掉在屏记账后，通道恢复时按充电理由重投且**不过**两道门（插电是通知之外的独立触发源）。
