# Spec 0016 实机验收：背屏会话选择（票 #157）

- 日期：2026-09-29 17:35–17:47（CST）
- 机主设备：Xiaomi 17 Pro（`25098PN5AC` / pandora，HyperOS 3），adb serial `94250f9e`；背屏 displayId=1（SurfaceFlinger `local:4630946949513469332`，904×572）
- 构建：集成分支 `codex/spec-0016-session-picker` debug APK（首轮 17:35:02 安装；「null 标题」修复后 17:45:5x 重装复验）
- 链路：PC 桥 `node tools/bridge/bridge.mjs --no-tunnel`（端口 18787；codex 适配器 `~/.codex/sessions`、claude 适配器 `~/.claude/projects`）+ `adb reverse tcp:18787 tcp:18787` + debug 广播 `BRIDGE_URL=http://127.0.0.1:18787`（**回环冒烟**；cloudflared 真隧道未跑，见判定 12）
- 在册会话：本机真实 Codex / Claude 会话（机主当时正在跑的两条 Claude 会话 + Codex rollout）+ 两条 `POST /inject` 合成会话（仅用于「点外关闭」「插队即关」两条确定性链；桥是会话事实的唯一来源，注入走的是适配器同一条事件入口）
- 证据：`shots/*.png`（背屏截屏，按发生顺序编号）、`anchors.logcat`（logcat 关键锚摘录，TAG=RearCue）

## 判定表

| # | 条目（票 / AC） | 判定 | 证据 |
| --- | --- | --- | --- |
| 1 | #155 拔桥 → 锁在 | **PASS** | 17:37:35 kill 桥 → 17:37:38 起 `bridge 请求失败 IOException` + `bridge down，退避重连`；17:37:5x 状态转储仍 `sessionLock=Locked(bridge:01a0ec84-…203f)`，屏上仍是该会话 |
| 2 | #155 重启桥 → 续看 | **PASS** | 17:38:17 桥重启 → 17:38:19 `bridge snapshot in-roster=2 dropped=3 cleared=false`（锁定会话在快照内 → 不清锁）；状态转储 `agentState=…203f` 不变，背屏续显同一条 |
| 3 | #155 快照确认缺失 → 清锁退回自动 | **PASS** | 起一条无适配器的桥（`BRIDGE_PORT=18788 … --no-codex --no-claude`，`/snapshot={"sessions":[]}`）并把手机指过去：17:40:45.862 `bridge snapshot in-roster=0` → 17:40:45.867 `session lock cleared bridge:01a0ec84-…203f` → 17:40:45.868 `lock auto-cleared: locked session left the merged roster → auto`（同帧写盘）→ `bridge snapshot reconcile in-roster=0 dropped=3 cleared=true` |
| 4 | #155 保锁锚 `session lock held …`（桥名册未知时缺席不清） | **INCONCLUSIVE（实机）** | 本锚只在「桥名册未知期间名册事件到达」时打；本轮 ZCode 未配对（`agentLinkStatus=UNPAIRED`），无任务表名册事件，构造不出。行为由 JVM 判例钉住：`SessionLockTest`「桥来源锁_桥名册非当下事实_缺席保锁」 |
| 5 | #156 标识行单击开列表 | **PASS** | 17:36:52.976 `rear-tap received area=agent-session-line` + `agent picker open`；`shots/03-picker-open.png` |
| 6 | #156 条目=标题+来源标记+等待/选中标记、首行「自动」、同目录重名附尾号 | **PASS** | `shots/03`（自动行带选中圆点；行右侧 `Codex`/`Claude` 来源标记）、`shots/07`（同目录两条会话显示为 `RearCue · se-1` / `RearCue · fa-1`，尾 4 位消歧） |
| 7 | #156 选中即锁 + 关闭 + 回实时 | **PASS** | 17:37:06.650 `agent picker select bridge:01a0ec84-…203f` + `agent picker close select`；状态转储 `sessionLock=Locked(…203f)`、`agentState=…203f`；`shots/04-picked-mirror.png`（回到镜像） |
| 8 | #156 点列表外关闭 | **PASS** | 17:44:02.814 `agent picker open` → 点阅读视口左侧面板空白（150,300）→ 17:44:03.913 `agent picker close toggle` |
| 9 | #156 等确认插队 → 列表自动关、显示插队会话 | **PASS** | 17:44:15.051 `agent picker open` → 17:44:17.607 `bridge event … status=waiting_for_approval` → 17:44:17.608 `agent picker close wfa` + `agent pulse start`；`shots/06-wfa-insertion-closed.png`（背屏显示插队会话 + Approval Glow 光带） |
| 10 | #156 不超时自动关 | **PASS（JVM）+ 实机未观察到自动关** | 列表打开后至下一次用户动作（≥1 分钟，含通知刷新与充电水位变化）列表保持展开；判例 `AgentPickerTest`「不超时自动关_无事件即保持展开」 |
| 11 | #156 锁定跨 App 重启保留 | **PASS** | 17:45:5x 重装（进程重启）后 17:46:11 状态转储 `sessionLock=Locked(bridge:accept-close-1)`，背屏直接回到该会话 |
| 12 | #157 真隧道（cloudflared）复测 | **未跑（NOT RUN）** | 本轮只做 adb reverse 回环；quick tunnel URL 每次重启都变、需要 adb 在场推送，留待机主在场的下一轮 |
| 13 | #157 第二条 CLI 会话选择/切回 | **部分 PASS** | 用桥在册的两条真实会话（Claude 与 Codex）完成「选中另一条 → 切回」；未单独起一条 Codex CLI 会话复跑 |
| 14 | #156 不响不震 | **PASS（观察）** | 全链无声音/震动；实现无反馈调用（`indication = null`、无 `performHapticFeedback`） |

## 验收发现的缺陷（已修，含判例）

- **显式 JSON `null` 被解成字符串 `"null"`**：桥侧统一事件的可选字段显式发 `null` 时，`BridgeEventCodec` 的 `str()` 取到 `JsonNull.content`（＝字符串 `"null"`），背屏列表上出现一行标题「null」（`shots/05` 可见，`shots/07` 为修复后）。修法：`JsonNull` 与缺键同义；判例 `BridgeEventCodecTest`「显式 JSON null 与缺键同义——不变成字符串 null」。

## 观察（未改，待机主定夺）

1. **列表溢出时的「点列表外」**：在册 ≥4 条时行占满整个阅读视口，`点列表外` 只剩左侧相机带那条空白可用（本机按判定 8 可行），`再点标识行` 则被浮层盖住不可达。建议后续票二选一：溢出时留一条底部关闭带，或调整行高让「4 行 + 关闭带」同时成立。
2. **长正文把会话标识行带出视口**：本机真实会话正文很长，跟随滚动会把标识行滚到视口外，此时要先上滑回顶才能点开列表（spec 0016 story 11「入口永远存在」的语义边界）。

## 环境清理

- 注入的两条合成会话随桥停止即散（不进任何持久状态）；测试锁已用 `SESSION_LOCK=auto` 拨回自动档。
- 本轮 `adb reverse tcp:18787/tcp:18788` 已移除；本轮启动的桥进程已停止；手机端 `BRIDGE_URL` 恢复为验收前的 `http://127.0.0.1:18787`。
