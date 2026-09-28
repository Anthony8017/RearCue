# issue #106 实机端到端验收：Session Lock + Approval Glow（spec 0010 收尾）

- 时间：2026-09-28 13:31–13:50（本机时区）
- 设备：小米 17 Pro（serial `94250f9e`），背屏 logical displayId=1、SurfaceFlinger id `4630946949513469332`
- 构建：worktree `agent-a752cda29abfb2720` @ `28063d9`（= `origin/spec/session-lock-glow`，含 T1–T3 全部），现场 `assembleDebug` 现装现验
- 链路：**ZCode 真中继在线**（`agentLinkStatus=CONNECTED`，真任务表 6 会话在册）；Debug Bypass 只用于造「他会话」的伪状态（`AGENT_STATE`，sessionId=`debug`）。
- 只读红线：全程未发送任何 prompt/批准/按键；桌面端 `APPROVAL_TEST.txt` 会话的等待→完成由桌面侧自行推进，本验收未触碰。

## 步骤与证据

| # | 验收项 | 操作（Debug Bypass） | 判据（AppState/logcat 词形） | 证据 |
|---|---|---|---|---|
| 1 | 默认自动·空闲回落 | `AGENT_STATE --es status idle`＋`EXIT_REAR` | `castSource=null agentState=null sessionLock=Auto` | `1-baseline-idle-fallback.png`、`log-s1-baseline.txt` |
| 2 | 默认自动·自动投送 | `POSTURE --ez faceDown true`＋`AGENT_STATE --es status working` | `castSource=AGENT`、`agentState=debug WORKING`，背屏显「工作中」 | `2-auto-working.png`、`log-s2-auto-working.txt` |
| 3 | 锁定生效（锁会话不锁屏） | `SESSION_LOCK --es sessionId sess_66d51fa3-…`（在册真会话、空闲） | `lastEvent=session-lock=Locked(…)`；锁定会话空闲 ⇒ 理由消失回落常规（`castSource=CHARGING`），锁档跨 ≥12s（含一次任务表轮询）保持、无 `cleared` | `3-lock-idle-fallback.png`、`log-s3-lock.txt` |
| 4 | Approval Glow 亮 | `AGENT_STATE --es status waiting` | 背屏边缘环绕光带；相隔 ~1s 两帧亮度不同（alpha 0.4–1 周期起伏） | `4a-waiting-insert-glow-a.png`、`4b-waiting-insert-glow-b.png` |
| 5 | Approval Glow 灭 | `AGENT_STATE --es status working` | 离开 WAITING 即灭，背屏回落充电动画 | `5-after-handled-glow-off.png`、`log-s5-relock.txt` |
| 6 | 不在册自动清锁 | `SESSION_LOCK --es sessionId sess-gone-106-test` | 下一次轮询（8.4s 内）core 打词形 **`session lock cleared sess-gone-106-test`**、接线层 `session lock cleared：锁定会话不在任务表 → 自动档`、`sessionLock=Auto`、自动档恢复显示 | `log-s6b-clear-word.txt`、`log-s6-clear.txt` |
| 7 | 主屏会话列表＋状态行 | 打开 MainActivity，`uiautomator dump`＋截屏 | 状态行「已连接 ZCode · 自动 · RearCue · 等你确认」；「背屏显示谁（会话锁定）」区 自动档＋会话行；重启后另一形态「已断开，正在自动重连… · 已锁定 · sess_659df9a4-…」＋空表提示 | `main-ui-dump.xml`、`6-main-agent-section.png` |
| 8 | 锁定到真·等确认会话 | `SESSION_LOCK --es sessionId sess_659df9a4-…`（桌面端该会话正等确认） | `castSource=AGENT`、`agentState=sess_659df9a4 WAITING_FOR_APPROVAL`、`sessionLock=Locked(same)`；背屏显示该会话真实内容＋光带 | `7a-lock-real-waiting.png` |
| 9 | 他会话等待插队 | 锁定不解除，注入 `AGENT_STATE --es status waiting --es action other-session-e2e` | STATE（13:38:19）：`agentState=debug WAITING_FOR_APPROVAL`、`sessionLock=Locked(sess_659df9a4-…)`——插队改显示不改锁档 | `7b-other-waiting-insert.png` |
| 10 | 处理回锁 | 注入 `AGENT_STATE --es status idle` | STATE（13:38:34）：`agentState=null`、`sessionLock=Locked(sess_659df9a4-…)`、`castSource=CHARGING`——插队撤下、锁档保持（回锁语义） | `7c-back-to-locked-session.png` |
| 11 | 锁定跨重启保留 | `am force-stop` → 重启 → `STATE` | `sessionLock=Locked(sess_659df9a4-…)`（SessionLockStore 首读还原） | `log-s8-restart.txt` |
| 12 | 断连安静回落（附带观察） | 重启后中继进入 `RECONNECTING` 循环 | 背屏回落充电动画、无崩溃无弹错、总开关/配对不受影响 | `log-s8-restart.txt`、`log-s9-restore-auto.txt` |

## 待人工（勿伪造）

1. **「插队 → 处理完 → 回显锁定会话」的同屏连续镜头**：需要一个真会话在插队前后持续处于等待确认。本次拍摄窗口内锁定会话恰被桌面端处理完转空闲（真会话自行推进，非注入），只拍到步骤 9/10 两段。回显路径本身见步骤 8（锁定档显示该会话），JVM 判例 `SessionLockTest` 覆盖「回锁后显示锁定会话」。
2. **真会话「从任务表消失 → 自动清锁」的现场转场**：桌面端归档/删除会话才发生；本次以「锁定不在册会话 → 下一轮询清锁」走同一条 `AgentRoster` 事件路径（步骤 6，词形已验）。
3. **断连后重连失败**（13:39 起 `RECONNECTING` 循环，ZCode 桌面进程在跑、手机曾短暂 `CONNECTED`）：疑似中继一次一机/桌面远程控制需重新打开，非本票范围。

## 复现命令（adb）

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$n   = "com.rearcue.poc/.DebugCommandReceiver"
# 注入伪会话状态（working|waiting|idle）
& $adb shell am broadcast -n $n -a com.rearcue.poc.action.AGENT_STATE --es status waiting --es action demo
# 锁定 / 自动（<id> = 任务表会话键；auto = 自动档）
& $adb shell am broadcast -n $n -a com.rearcue.poc.action.SESSION_LOCK --es sessionId auto
# 姿态（AGENT 源受 Posture Gate 管，倒扣才投）
& $adb shell am broadcast -n $n -a com.rearcue.poc.action.POSTURE --ez faceDown true
# 状态快照（sessionLock / agentState / agentRoster / castSource）
& $adb shell am broadcast -n $n -a com.rearcue.poc.action.STATE
# 背屏截图（SurfaceFlinger id）
& $adb shell screencap -p -d 4630946949513469332 /sdcard/shot.png
```

清锁词形验收：`logcat -s RearCue | findstr "session lock cleared"` 应出现
`session lock cleared <sessionId>`（契约见 `DashboardCore.LOG_SESSION_LOCK_CONTRACT`）。

## 收尾

- 设备已复位：`sessionLock=Auto`、Agent 总开关开、伪状态 `idle`（步骤见 `log-s9-restore-auto.txt`）。
- `gradlew test`：551 项 0 失败（与基线一致）。
