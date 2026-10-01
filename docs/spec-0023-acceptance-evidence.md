# Spec 0023 验收证据：归档同步消失

日期：2026-10-01
范围：GitHub #231（验收切片 #238），基于 `c4de7e5` 的评审修复。

## 已验证的产品规则

电脑端来源给出归档或来源移除事实后，会话立即退出统一「在册集合」，并同步从主屏与背屏的「会话列表」、等待确认插队、提醒、批准/提问入口和 Session Lock 撤下。归档后的旧消息或新消息都不能重新建立在册身份；只有来源给出更新的取消归档事实，会话才按正常列表规则重新进入。

主屏与背屏使用同一份会话列表规则。主屏入口从 `AppState.agentRoster` 进入列表投影，背屏入口从 `AgentPickerRow` 进入会话选择器投影；判例分别调用两个真实入口，再比较会话键与顺序。

## 确定性 2 秒预算

| 来源 | 归档事实检测 | 手机侧处理预算 | 正常使用总预算 | 自动判例 |
|---|---:|---:|---:|---|
| ZCode | 任务表 1 秒轮询 | 1 秒 | 2 秒 | `AgentArchiveAcceptanceTest`、`AgentArchiveTruthSourceMembershipTest` |
| Codex | 目录移动扫描 0.8 秒 | 同页先归档事实、后活动 | ≤ 2 秒 | `adapters.test.mjs`、`BridgeRelayClientSnapshotTest` |
| Claude | 权威 `isArchived` 记录扫描 1 秒 | 同页先归档事实、后活动 | ≤ 2 秒 | `claude-membership.test.mjs`、`BridgeMembershipCodecTest` |
| DSH | `session/disposed` 实时事件 | 立即进入事件入口 | ≤ 2 秒 | `dsh-events.test.mjs`、`BridgeRelayClientSnapshotTest` |

以上预算由确定性判例约束，不使用脆弱的墙钟睡眠。断线期间仍按「保留最后已知列表，恢复连接后立即对账」处理，不把网络中断算作超过 2 秒的同步失败。

## 四来源证据

| 来源 | 正事实与出册事实 | 自动证据 | 当前结论 |
|---|---|---|---|
| ZCode | 任务表 `archived:false/缺省` → `ACTIVE`；`archived:true` → `ARCHIVED`；完整任务表快照缺行 → `ABSENT` | 任务表解析判例覆盖 `ACTIVE -> ARCHIVED -> ACTIVE`、`ACTIVE -> ABSENT`，并验证 1 秒任务表预算 | 自动通过；电脑操作到手机消失的实机时间戳仍需人工验收 |
| Codex | 活跃 rollout 移入归档目录 → `ARCHIVED`；反向移动 → 取消归档 | Node 判例移动真实文件布局并验证归档、取消归档与迟到活动；同原始会话键的 Codex/Claude 交叉判例证明来源隔离 | 自动通过；手机实机时间戳仍需人工验收 |
| Claude | 权威本地记录 `isArchived:false/true` → `ACTIVE/ARCHIVED/ACTIVE` | JSON 权威记录扫描判例覆盖归档、取消归档、坏记录与缺文件；显式 hook 只收 `ACTIVE|ARCHIVED|ABSENT` | 自动通过；当前环境没有捕获到真实权威记录，仍需真机来源验收 |
| DSH | `session/disposed` → `ABSENT` + 来源移除；创建事件可重新进入 | 事件流与手机侧入口判例证明无需重连即可出册，并清掉镜像状态和 Session Lock | 协议与手机逻辑通过；DSH 的“归档”是否确实对应 `session/disposed`，以及手机画面消失时间，仍需真机验收 |

## 跨屏与下游证据

- `AgentArchiveSurfaceTest`（app）覆盖会话列表、等待插队、提醒账、批准/提问入口，以及任务、V4、桥事件和等待退出占位的迟到活动。
- `AgentArchiveSurfaceTest`（core）覆盖权威移除、清锁回「自动」、断线保留与重连对账。
- `AgentArchiveAcceptanceTest` 分别走主屏 `AppState.agentRoster` 入口和背屏选择器投影入口，比较真实派生后的会话键与顺序。
- `BridgeRelayClientSnapshotTest` 保证归档事实先于同一响应中的迟到活动进入手机侧。
- `AgentArchiveReconciler` 是归档、来源对账和缓存收缩的纯逻辑入口；Android 接线层只翻译回调并派发结果。

## 明确保留的人工验收缺口

1. 本环境没有连接手机，因此四来源“电脑端操作到主屏/背屏消失”的 2 秒实机时间戳和画面证据仍需人工补拍。
2. DSH 目前只能证明来源移除语义，不能把 `session/disposed` 直接等同为产品语义「归档」。
3. Claude 需要在真机捕获权威 `isArchived` 记录，完成一次真实归档与取消归档；transcript、`Stop` 或文件缺失都不是归档证据。
4. 四来源的断线期间保留、重连立即移除，以及稳定会话身份，需要一次真机断线/重连验收。

## 记录的自动测试结果

- Gradle 全套：`BUILD SUCCESSFUL`；1,127 个测试，0 失败、0 错误、0 跳过。
- Node 桥全套：121 个测试，0 失败、0 错误、0 跳过。
- `repo-check`：9 个脚本全部合格。
- `git diff --check`：通过。
