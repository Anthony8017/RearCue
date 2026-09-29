# PC 桥链路状态三处显示 + 断线保留实机验收（票 #165 / #166）

- 日期：2026-09-29 19:43–19:47（CST）；设备与链路同前几轮（Xiaomi 17 Pro / adb `94250f9e`；PC 桥 + cloudflared 隧道）
- 机主定夺（2026-09-29）：「都加，背屏也加，状态一定要统一」→ 链路状态一份事实三处显示；「背屏只加小状态点；桥断了之后，Agent 页也一直保留」

## 判定表

| # | 条目（票） | 判定 | 证据 |
| --- | --- | --- | --- |
| 1 | #165 链路状态由客户端真实生命周期驱动 | **PASS** | logcat 次序 `bridge status connecting`（19:46:08）→ `bridge status connected`（19:46:39）；拔桥 → `bridge status retrying`（19:45:27）；判例 `BridgeRelayClientSnapshotTest` 两例（生命周期次序、请求失败=重连中） |
| 2 | #165 背屏状态点：已连接＝accent 实心点 | **PASS** | `shots/01-dot-connected-blue.png`——会话标识行左侧蓝点（会话 Ant_Nest_2） |
| 3 | #165 背屏状态点：重连中＝次要灰点 | **PASS** | `shots/02-link-down-page-retained-dot-gray.png`——同位置转灰 |
| 4 | #165 重连恢复＝回蓝 | **PASS** | `shots/03-reconnected-blue.png`（19:46:39 上报 connected 后重截） |
| 5 | #165 一份事实：主屏两处读同一值 | **PASS（结构）** | `AppState.bridgeLinkStatus` 同一字段喂设置页状态行（`bridgeStatusLine`）与主页概览行；背屏经 `AgentFeed.publishLink` 收同一值——判别逻辑单点（`AgentMirrorParams.linkDot` 判例钉语义档） |
| 6 | #165 未配置/停用不画点 | **PASS（判例）** | `AgentMirrorParamsTest`「链路状态点的语义档」：DISABLED ⇒ null |
| 7 | #166 桥断后 Agent 页保留、内容停最后一帧 | **PASS** | 19:45:27 起桥进程被杀（`bridge down，退避重连` 连续重试），无 `content page fallback`/退屏日志；`shots/02` 仍是 Agent 页 |
| 8 | #166 收回仍走既有路径（手动退出/通道降级） | **PASS（判例）** | `AgentArbitrationTest`「断线保留——桥断了仍持屏显示 Agent 页」「断线保留_投影通道降级仍交还」；`SessionLockTest`「断线保留_锁定档也保留不回落」；`AgentPickerTest`「断线保留_页面还在列表不关；手动退出才关」 |
| 9 | #166 重连后继续显示（不重复投送） | **PASS** | 19:46:39 `bridge status connected` + `agent 链路上线 → 无需重投`；`shots/03` 内容已刷新 |
| 10 | 既有判例不回归 | **PASS** | `:core:test :rear:test :agent:test :notification:test :app:testDebugUnitTest` 全绿（237 core 例） |

## 说明

- 本轮把「链路在线」从 agent 理由里摘出：理由是**有在册会话**，断线只是冻结内容并把状态点转灰（票 #166）。原 spec 0010「断连即理由消失、零打扰回落」由本票取代；不收起的路径只剩机主手动退出、投送通道降级、姿态门与投送抢回。
- 现场还发现一处**既有行为**（非本轮缺陷）：手机上若留着「锁定档 + 该会话空闲」，Agent 页本就无理由、不会上台（锁定档语义）；验收前先把档位拨回自动即可。
