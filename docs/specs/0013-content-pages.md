# Spec 0013：内容页切换——通知与 Agent 平权、空白点按切换

状态：已与机主 grill 收口（2026-09-28，两轮 11 问，frontier 空，均按推荐定案）；#132/#133 已实现，自动化测试全绿；#134 非设备收口完成，实机验收待设备接入（PENDING）。本 spec 反转票 #112 的「通知 > agent」自动内容选择；#112 的「连接在线即显示、空闲残影、门控/断连交还」不反转。CONTEXT.md 词条由 #134 回填。

跟踪：[Issue #128](https://github.com/Anthony8017/RearCue/issues/128)，子票 [#132](https://github.com/Anthony8017/RearCue/issues/132)、[#133](https://github.com/Anthony8017/RearCue/issues/133)、[#134](https://github.com/Anthony8017/RearCue/issues/134)；PR [#140](https://github.com/Anthony8017/RearCue/pull/140)。
验收记录：[docs/poc-logs/20260928-spec0013-content-pages/README.md](../poc-logs/20260928-spec0013-content-pages/README.md)；驱动脚本：[drive-acceptance.ps1](../poc-logs/20260928-spec0013-content-pages/drive-acceptance.ps1)。

## Problem Statement

机主在背屏上同时有通知和 Agent Mirror 时，现有自动优先级「Waiting-for-Approval > 通知内容 > Agent Mirror」会让通知始终盖住 Agent：只要通知在，agent 就不可见；机主要看 agent，只能等通知清空。手机扣在电脑旁时，agent 是长时间盯的内容，通知是偶发内容；这个自动选择不符合机主实际想看的顺序，也剥夺了机主的选择权。

## Solution

- 把背屏内容改为两套互斥、平级的 Content Page（内容页）：通知页（Icon Set + Detail View）与 Agent 页（Agent Mirror）。
- 两页都有内容时默认显示通知页；机主在背屏非交互区域点按（Rear Tap）可在两页间切换。
- 删除票 #112 的自动内容优先级：通知到达、agent 新输出都不自动翻页。
- 唯一自动例外是 Waiting-for-Approval：任何会话等确认时自动切到 Agent 页；等确认解决前不允许手动切走，解决后回原页（原页若还开着 Detail View，回到那张详情卡片）。
- 当前页内容全部消失时自动兜底切到另一页；另一边没内容时点按无操作；两边都空按既有规则退屏。
- 兜底切页后，内容恢复不自动切回；想回原页再点一次空白。
- 切换用约 150–200ms 交叉淡入淡出，不响不震；Agent 页历史回看位置在切换后保持。
- Agent 页与通知页各自的内部行为不变（滚动/跟随/↓、Session Lock、脉冲/光带；图标/角标/高亮/详情点开即消等）。
- 充电水位仍是背景层，不参与页面选择。

## User Stories

1. 作为机主，我希望通知和 Agent Mirror 成为两套平级内容，以便通知不再自动盖住 agent。
2. 作为机主，我希望两页都有内容时默认先显示通知页，以便不改变我第一眼看到通知的习惯。
3. 作为机主，我希望在背屏通知页点按空白区域切到 Agent 页，以便不翻转手机就能查看 agent。
4. 作为机主，我希望在 Agent 页点按正文或会话标识行切回通知页，以便随时查看通知。
5. 作为机主，我希望只有一边有内容时点按空白不显示空白页，以便避免黑屏困惑。
6. 作为机主，我希望当前页内容全部消失时自动切到还有内容的一页，以便背屏不卡在空页。
7. 作为机主，我希望兜底切到另一页后，内容恢复时不要自动切回去，以便遵守「新内容不抢页」。
8. 作为机主，我希望新通知到达时不自动离开 Agent 页，以便不打断我正在读的 agent 输出。
9. 作为机主，我希望新通知到达时仍保留现有呼吸光晕提示，以便知道有通知但不被打断。
10. 作为机主，我希望 agent 新输出到达时不自动离开通知页，以便不打断我查看通知。
11. 作为机主，我希望任何会话进入 Waiting-for-Approval 时自动切到 Agent 页，以便不会错过批准/输入请求。
12. 作为机主，我希望等确认存续期间不能手动切走，以便批准/输入请求不会被隐藏。
13. 作为机主，我希望等确认解决后回到我原来选的那页，以便回到中断前的阅读位置。
14. 作为机主，我希望等确认打断时若原页还开着 Detail View，解决后回到那张详情卡片，以便继续读完。
15. 作为机主，我希望手动选择的内容页在本次连续投屏期间保持，以便反复切换后停在我最后选的那页。
16. 作为机主，我希望退屏、被原生背屏顶掉或重新投送后回到默认页，以便每次投送行为可预期。
17. 作为机主，我希望 Agent 页上下拖动仍然滚动历史，只有点按才切换页面，以便回看时不误触。
18. 作为机主，我希望 Agent 页右下 ↓ 按钮仍然只负责恢复实时跟随，不触发页面切换，以便继续沿用现有回看语言。
19. 作为机主，我希望通知图标点按仍然打开 Detail View，不触发页面切换，以便保持现有通知交互。
20. 作为机主，我希望 Detail View 点按仍然先收起详情；若这张详情就是最后一条通知，收起后按兜底规则自动切到 Agent 页，以便不显示空的通知页。
21. 作为机主，我希望通知页的图标、角标、高亮和详情语义不变，以便这次改动只动两页之间的切换。
22. 作为机主，我希望 Agent 页的在线即显示、空闲残影、Session Lock、等确认脉冲/Approval Glow、滚动跟随不变，以便不破坏既有 Agent Mirror 行为。
23. 作为机主，我希望充电水位仍然是背景层，切页时水面和水位数字不被撤掉，以便充电信息始终可见。
24. 作为机主，我希望切页用短交叉淡入淡出、不响不震，以便保持背屏克制、无打扰的语言。
25. 作为机主，我希望从 Agent 历史回看位置切走再切回时位置不丢，以便继续读同一段输出。
26. 作为机主，我希望在 Agent 页时通知图标集仍在后台更新，切回通知页立刻看到最新状态，以便不出现旧画面。
27. 作为机主，我希望在通知页时 agent 状态仍在后台更新，切回 Agent 页立刻看到最新输出，以便不出现旧画面。
28. 作为机主，我希望这套切换在自动投送、手动投送、充电或 Agent 持有等所有 Dashboard 在屏场景都可用，以便行为一致。
29. 作为机主，我希望不新增设置项、不持久化页面选择、不引入双击/长按/滑动等新手势，以便这次改动保持简单。
30. 作为机主，我希望主屏不增加操作提示 UI，只在 README/spec 记录手势，以便背屏保持极简。

## Implementation Decisions

- **单一决策 seam**：内容页的当前页、默认、切换、兜底、WFA 例外、生命周期重置全部收口在 DashboardCore 的「事件 → 可见内容页 + 效果」出口；背屏 UI 零决策，只上报空白点按并按 core 投影渲染。
- **内容页模型**：新增明确的当前内容页状态与切换事件；投影从既有 `agentContentOnScreen`（WFA > 通知 > agent 的布尔仲裁）改为「当前页 = 通知页 / Agent 页」。通知页有内容 = Icon Set 非空或 Detail View 打开；Agent 页有内容 = 既有 Agent Mirror 可显示事实（在线且有可镜像会话 / 等确认）。不改变 Agent Mirror 接入链路（ADR 0005/0006）与通知来源（ADR 0007）。
- **默认与生命周期**：一次连续投屏内记住机主手动选择；Dashboard 退出、被 Takeover 或重新投送后清回默认（两页都有内容时通知页；只有一页有内容时显示那一页）。不新增持久化字段。
- **自动例外**：Waiting-for-Approval 记录进入前的当前页并强制 Agent 页；WFA 存续期间忽略切换事件；WFA 全部解决后恢复记录页（含仍打开的 Detail View）。多会话等确认沿用既有选择规则（最近活跃/锁定档插队）。
- **兜底**：当前页内容消失 → 自动切到另一页并成为当前页；另一边内容恢复不自动切回；两边都空按既有退屏规则。
- **与投送记账正交**：CastSource、姿态门、手动/充电/agent 理由的投/撤规则不变；内容页选择只决定屏上显示哪页。
- **背屏 UI**：图标、详情卡片、右下 ↓ 保留各自点按语义；其余区域上报切换事件。Agent 页正文与会话标识行点按即切换，拖动仍滚动；交叉淡入约 150–200ms；Agent 历史 ScrollState 提升到页面级以跨切换保持位置。
- **日志**：既有 `rear-tap received`、`detail open/close`、`agent pulse start/end`、`session lock cleared` 等锚词形不变；新增内容页变化锚词（按项目 LOG_*_CONTRACT 惯例冻结词形），供实机验收链断言 toggle / WFA / fallback / reset 路径。
- **不新增**：设置项、DataStore 字段、新手势（仅单击）、新 ADR。ADR 0005/0006/0007 均不受影响。

## Testing Decisions

- 好测试只测外部行为：事件序列 → 效果序列 + 只读投影；不断言内部字段、Compose 节点嵌套或动画实现。
- 主 seam：DashboardCore。Prior art：AgentArbitrationTest、SessionLockTest、DashboardCoreTest。新增或扩展内容页判例覆盖：默认页、双向切换、另一边空 no-op、通知到达不抢页、agent 输出不抢页、当前页消失兜底、恢复不自动切回、WFA 插队/期间忽略切换/结束回原页（含 Detail 仍开）、多会话 WFA、退屏/重投重置、投送持有规则不回归。
- 既有测试更新：AgentArbitrationTest 中原「通知 > agent」内容层断言改为内容页模型；不得丢失既有语义回归（在线即显示、空闲残影、门控、断连交还、充电背景）。
- 渲染/手势不写 JVM 测试：交叉淡入、拖动 vs 点按、↓ 排除、图标/详情路由、历史位置保持按 spec 0008/0009/0012 判例走实机验收，证据归档 docs/poc-logs。
- 新增内容页变化日志锚词后，实机验收链按词形断言 toggle / WFA / fallback / reset 各路径；既有锚词回归检查。

## Out of Scope

- 改变 Agent Mirror 接入路径、Codex/Claude PC 桥、ZCode 中继（ADR 0005/0006）。
- 改变通知来源/可见性（ADR 0007）、Allowlist 历史语义、Active Notification / Shade-visible Notification 语义。
- 改变 Detail View 的点开即消、外部清除自动收起、高亮熄灭规则。
- 改变 Agent 会话选择、Session Lock、Waiting-for-Approval 脉冲、Approval Glow、滚动跟随规则。
- 改变 Charging Animation、电量数字、背景层语义。
- 改变投送/撤屏、Posture Gate、Wake Keep-alive、Shizuku 兜底。
- 新增页面选择持久化、设置项、背屏操作提示 UI、双击/长按/滑动手势。
- 新增 ADR。

## Further Notes

- 反转票 #112 的内容选择部分；#112 的「连接在线即显示、空闲残影、门控/断连交还」保持不变。票 #113 的「去状态词、正文最大化」不受影响。
- 2026-09-28 grill-with-docs 两轮 Q1–Q11 全部按推荐定案：Q1 WFA 例外；Q2 默认通知页；Q3 本次投屏记住；Q4 新内容不翻页 + 呼吸提示；Q5 广义非交互点按；Q6 详情先收起；Q7 空边 no-op + 兜底；Q8 恢复不自动切回；Q9 交叉淡入；Q10 历史位置保持；Q11 不加提示 UI。
- CONTEXT.md 已更新 Content Page / Waiting-for-Approval / Rear Tap / Agent Mirror。spec 文档镜像与实机验收归档由收口票完成。
