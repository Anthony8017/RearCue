# 链路恢复回 Agent 页实机验收（票 #163）

- 日期：2026-09-29 18:53–18:56（CST）；设备与链路同前几轮（Xiaomi 17 Pro / adb `94250f9e`；PC 桥 `node tools/bridge/bridge.mjs` + cloudflared quick tunnel，隧道 URL 由桥自动 adb 推给手机）
- 触发：机主反馈「Codex 在跑但背屏不显示」——根因是 PC 桥没在跑（清理时被停），但暴露出口径缺口：桥恢复后内容页不自动回 Agent 页，要手点一下空白
- 机主定夺（2026-09-29）：**链路恢复且 Agent 有内容时自动切回 Agent 页**

## 判定表

| # | 条目 | 判定 | 证据 |
| --- | --- | --- | --- |
| 1 | 断链 ⇒ Agent 理由消失、内容页兜底回通知页 | **PASS** | 18:55:46.321 `content page fallback notification` → 18:55:46.572 `content page crossfade done show=notification durationMs=183` |
| 2 | 链路恢复（断→通边沿）且 Agent 有内容 ⇒ 自动回 Agent 页 | **PASS** | 18:56:22.615 `content page recover agent` + `agent 链路上线` → `content page crossfade start show=agent` → `content page crossfade done show=agent durationMs=184` |
| 3 | 屏上确实回到 Agent 页并显示会话输出 | **PASS** | `shots/01-recovered-agent-page.png`（Ant_Nest_2 的 Codex 会话正文，标识行固定屏顶） |
| 4 | 无内容/不在屏/已在 Agent 页/非边沿重复在线 不切页 | **PASS（判例）** | `ContentPageTest` 三例：「链路断再通_Agent有内容时自动回Agent页」（含同向重复在线不切）「链路恢复但Agent无内容_不回Agent页」「链路恢复时不在屏_不切页」；另「Agent 页内容消失兜底通知页_非链路原因恢复不切回」锁住原口径 |
| 5 | 锚词形冻结 | **PASS** | `content page recover <page>` 入 `ContentPageLogContract.CONTRACT`，`ContentPageLogContractTest` 逐字断言 |

## 说明

- 本票只加一条**边沿规则**：`AgentConnectionChanged(false → true)` 且 Agent 有内容时切回 Agent 页；「内容消失兜底后不自动切回」的原口径（spec 0013）不受影响——那条管的是页内内容消失，不管链路恢复。
- CONTEXT.md「Content Page」条目、README「内容页切换」段、`docs/specs/0013-content-pages.md` 均已补第三条例外。
