# Spec 0006：通知场景门控与快捷入口（DND Follow + Posture Gate + Quick Tile Entry）

状态：已与机主 grill 收口（2026-09-26），frontier 空。

## Problem Statement

MRSS（已归档，GPL-3.0）有一批通知场景功能可借鉴，但需要一个判据区分「机制仍有效」与「随系统漂移已失效」。经三分法筛选（公共 API 类视为仍有效、系统接口事实类须过 POC、对抗 hack 类默认无效——`screencap -d 1` 失效、`activity_task` 50→51 漂移即后两类实证），三个公共 API 类功能定案迁移：勿扰跟随、倒扣门控、QS 快捷开关。当前实现缺口：DND 期间照常投送（打扰面）、正放时也投（背屏没人看）、手动投送/退出只能开 App（Debug Bypass 在 App 内）。

## Solution

两道门控 + 一个正式入口，全部只作用于**通知驱动的自动投送路径**：

- **DND Follow**：DND 开启 → 不投送 + 撤下已在屏 Dashboard（hand-back）；DND 结束且 Icon Set 非空 → 补投。
- **Posture Gate**：接近传感器持续判定主屏朝下；非倒扣不投，倒扣且 Icon Set 非空补投，翻正撤下。投与撤都过姿态稳定窗（数值实现期定）。
- **Quick Tile Entry**：单 QS tile 切换（无 Dashboard→投送，有→退出），锁屏态可用；手动投送**豁免两道门控**、不被自动逻辑撤下——自动投的自动撤，手动投的手动撤。

全部自实现（spec 0001 story 24 / ADR 0002），仅复用 MRSS 的机制事实（勿扰跟随语义、接近传感器判倒扣在 17 Pro 上可行、tile 触发投送）。

## User Stories

1. 作为机主，我希望 DND 开启时不投送 Dashboard、已在屏的也撤下，以便勿扰期间背屏完全静默。
2. 作为机主，我希望 DND 结束后若 Icon Set 非空则自动补投，以便勿扰结束后指示自动恢复。
3. 作为机主，我希望手机正放时来了 Allowlist 通知不投送，之后倒扣时补投，以便指示只在「背屏看得见」的姿态出现。
4. 作为机主，我希望倒扣指示中拿起翻正后 Dashboard 撤下、背屏交还原生界面，以便状态干净、不白耗保活。
5. 作为机主，我希望翻面抖动不产生频繁投送/撤下（姿态稳定窗，投与撤都防抖），以便不出现高频任务搬运震荡。
6. 作为机主，我希望 QS tile 一键投送/退出（无→投，有→退），锁屏态也能用，以便不开 App 完成手动操作。
7. 作为机主，我希望手动（tile）投送不受 DND/倒扣门控限制、也不被自动逻辑撤下，退出由我手动触发，以便明确意图不被拦截。
8. 作为开发者，我希望 DND/姿态以事件进入 core 状态机（同 `DashboardEvent.Allowlist` 的既有模式），以便门控→投送/撤下语义有 JVM 单测安全网。
9. 作为开发者，我希望在屏投送携带来源（自动/手动）作为撤下判定的输入，以便「手动投的不自动撤」是状态机结论而非特判补丁。
10. 作为开发者，我希望 DND 跟随复用既有 NotificationListenerService 的 interruption filter 回调（公共 API，零新权限），以便不另立监听 seam。
11. 作为开发者，我希望接近传感器「倒扣=近」的判定在本机做一次 smoke 确认（MRSS 同型传感器已验证，属仍有效证据，不立 POC 票），以便阈值/行为差异早暴露。
12. 作为开发者，我希望 `gradlew test` 既有基线 0 失败不回退。
13. 作为开发者，我希望验收轮留痕 poc-logs（沿用 E 系列惯例），场景至少覆盖：DND 开→不投+撤下、DND 关→补投、正放→不投、倒扣→补投、翻正→撤下、tile 投/退、手动豁免（DND/正放下 tile 投送成功且不自动撤）。

## Implementation Decisions

- **模块改动**：`:app`（DND 回调接线、SensorManager 接近传感器 + 稳定窗、TileService）+ `:core`（门控事件与来源标签进状态机）；`:rear` 投送会话机制零改动，tile 与自动路径复用同一 ProjectionSession。
- **门控规则**：自动投送需 DND 关 **且** 倒扣同时成立；任一门由通过转不通过 → 撤下（仅自动投的）；由不通过转通过且 Icon Set 非空 → 补投。两道门相互独立、判定顺序无关。
- **来源标签**：投送发起即标记 auto/manual；自动撤下逻辑只作用于 auto 在屏。tile 与 Debug Bypass 同属 manual。
- **DND 监听**：`onInterruptionFilterChanged`（NLS 既有回调），不引入 `NotificationManager.DND` 轮询。
- **姿态**：TYPE_PROXIMITY 持续监听；判定「倒扣=近」，稳定窗内状态翻转不触发动作（去抖由 app 层完成后再发 core 事件）。
- **QS tile**：`TileService`，状态反映 Presence（OnScreen→退出语义）；点击走既有投送命令路径，无需 Shizuku。
- **不引入 MRSS 代码**：仅复用机制事实（ADR 0002 / spec 0001 story 24）。

## Testing Decisions

- **JVM 单测**：门控→投送/撤下的状态机语义（两道门 × 自动/手动来源 × 补投）在 core 层新增判例；传感器/回调/DND 接线不写 JVM 测试（无决策）。
- **实机验收链**：见 story 13，归档 poc-logs。
- **基线**：`gradlew test` 既有基线 0 失败不回退。

## Out of Scope

- **URI 外部触发**（`rearcue://cast|exit`）：缓——adb 自动化已有 debug broadcast 覆盖，机内 Tasker 需求未证实；观察项。
- **监控式 Takeover 恢复**：归票 #7；MRSS 的 2s 背屏前台监控记为该票可借鉴机制，本轮不实现。
- MRSS 其余功能（任意应用切背屏、背屏截图/录屏、充电动画、杀 Launcher、DPI/旋转、隐私模式、多语言）：按三分法判为对抗 hack（默认无效）、系统接口已漂移（`screencap -d 1`）或与「仅图标集」产品决策相悖，不迁移。
- Wake Keep-alive 间隔 UI、防冻结实现（沿 spec 0005 口径）。

## Further Notes

- 术语已随本 spec 落 CONTEXT.md：DND Follow、Posture Gate、Quick Tile Entry。
- 三分法判据本身不立 ADR：是筛选原则而非难逆架构决策，自实现原则已被 ADR 0002 覆盖。
- MRSS 参考事实：三个迁移项全部走公共 API，与 MRSS 停更原因（对抗系统接口漂移）无交集；`_research/` 克隆不入库。
- 「自动投的自动撤，手动投的手动撤」依赖来源标签：若后续出现第三种来源（如 URI 触发），需先回 CONTEXT.md 定归属再动状态机。
