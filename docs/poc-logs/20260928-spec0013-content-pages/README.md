# spec 0013 实机验收 — 内容页切换（票 #134）

- 设备：小米 17 Pro（25098PN5AC，HyperOS 3 / OS3.0.319.0.WBLCNXM），adb serial `94250f9e`
- 背屏：displayId=1，904×572，可用区 x≥296（相机带 296px 在左）
- 分支：`spec/0013-content-pages`
- APK：`:app:assembleDebug`，SHA-256 `9184F79AEF60D8B5E3D6569A3393C18C73E605A76426AA5B816A297E2B00848D`
- 驱动脚本：[drive-acceptance.ps1](drive-acceptance.ps1)（一键安装/重绑监听、构造内容、逐路径注入、采集 logcat 与截图）
- 状态：**2026-09-28 23:2x 实机跑通**——`failed=0 inconclusive=7`（`acceptance-summary.txt`）。
  7 项 INCONCLUSIVE = 3 项需人工目检（2c / 2d-b / 9e-d）＋ 3 项本机构造不出前置（3b / 6a / 7a）＋
  1 项需要真 AgentRoster 会话（10d）。**无 FAIL**；JVM/静态结果不替代实机结论，实机列以本目录产物为准。

## 一键复跑

```powershell
$env:JAVA_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk"
.\gradlew.bat :core:test :rear:test :notification:test :app:assembleDebug --console=plain

# 默认会安装 APK 并重绑通知监听；已装好可加 -SkipInstall
powershell -ExecutionPolicy Bypass -File docs\poc-logs\20260928-spec0013-content-pages\drive-acceptance.ps1
```

JVM 侧同轮结果：core 207、rear debug 138 + release 138、notification 23，0 失败 0 跳过。
脚本落盘：`sequence.logcat`、`acceptance-summary.txt`、各步骤 `*.png`。脚本只在 debug 构建使用既有
`DebugCommandReceiver`（`AGENT_STATE` / `SESSION_LOCK` / `POSTURE_GATE` / 通知调试动作），不发送 prompt、
不批准 agent、不触碰真实会话内容。脚本会临时关掉 Agent 链路（`AGENT_ENABLED=false`）做 fixture 隔离，
收尾恢复 `true`（原因见下「脚本首跑暴露的问题」第 5 条）。

## 判定表（2026-09-28 实机列已回填）

| # | 验收项（#134 AC / spec 0013） | 操作与判据 | 实机判定 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | 两页都有内容时默认通知页 | 构造 Agent 长会话 + 一条 shell 通知，`EXIT_REAR` 后 `PROJECT_REAR`；断言渲染锚 `crossfade start show=notification` 且窗口内无 toggle/fallback | PASS | `10-default-notification.png`（`reset` 行只在页真的变化时打，本轮缺席属契约语义，已写进脚本注释） |
| 2 | 双向切换 | 通知页空白点按 → Agent 页；Agent 页点正文/会话标识行 → 通知页；断言两条 `content page toggle` 与成对 crossfade、`durationMs` 在 150–400ms | PASS | `20-agent-after-toggle.png` / `21-notification-after-body-tap.png`（180/181ms） |
| 3 | 另一边空 no-op | Agent 断连后通知页点空白；通知清空后 Agent 页点空白；断言无 toggle、无目标页 crossfade | 3a PASS / 3b INCONCLUSIVE | 3a：`30-notification-agent-empty-noop.png`；3b：本机挂着 USB 时系统常驻通知（`android` 的 USB / USB 调试）不可撤——脚本实测 `cancelled=3` 但仍在册、`iconSet=[android]` 非空，构造不出「通知边空」 |
| 4 | 通知不抢页 | 停留在 Agent 页时新建 shell 通知；断言无 `crossfade start show=notification`、无 toggle，且 `highlight breath start` 出现 | PASS | `40-agent-notification-arrives.png`（呼吸有 30s 冷却，脚本改为连发探针直到真呼吸，冷却吞掉的通知不算失败） |
| 5 | agent 不抢页 | 停留在通知页时注入 agent 新输出；断言无 `crossfade start show=agent`、无 toggle | PASS | `50-notification-agent-output.png` |
| 6 | 当前页内容消失兜底 | 通知页清空通知 → fallback Agent；Agent 页断连 → fallback 通知 | 6b PASS / 6a INCONCLUSIVE | 6b：`61-fallback-notification.png` + `content page fallback notification`；6a 与 3b 同因（通知页无法清空） |
| 7 | 内容恢复不自动切回 | fallback 到 Agent 后恢复通知；fallback 到通知后恢复 agent；断言不自动出现反向 crossfade | 7b PASS / 7a INCONCLUSIVE | 7b：`63-notification-agent-recovered.png`；7a 依赖 6a 的前置（未发生） |
| 8 | 退屏/重投重置 | 手动切到 Agent 页（`8-pre` 前置断言）→ `EXIT_REAR` → `PROJECT_REAR`；断言重投后页 = notification 且窗口内无 toggle/fallback | PASS | `70-recast-default-notification.png` + `sequence.logcat`（`8 前置` / `8 EXIT_REAR -> PROJECT_REAR`） |
| 9 | WFA 跳—锁—回 | 通知页注入 `waiting`：`wfa enter notification` + crossfade agent；WFA 中点空白无 toggle；`agent pulse start/end`；注入 `idle`：`wfa exit notification` 且收口后页 = notification | PASS | `80-wfa-agent.png` / `81-wfa-locked.png` / `82-wfa-pulse-end.png` / `83-wfa-return-notification.png` + `9d settle` 窗口 |
| 10 | 交叉淡入 150–200ms、不响不震 | 成对 `crossfade start/done ... durationMs=`；观感（无黑底闪烁、无重影、无声、无振动、背景层不淡入） | 时长 PASS / 观感 INCONCLUSIVE | 实测 180–199ms（9d 窗口 180ms）；帧采样与实现面证据见 [25-crossfade-frames.md](25-crossfade-frames.md)，肉眼收尾仍留人工 |
| 11 | 手势边界与历史位置 | 拖动不切页；正文点按切页；↓ 只恢复跟随；暂停后小幅下拖未触底仍回看；切走后回页保留历史位置 | 9e-a/b/c/c1 PASS，9e-d INCONCLUSIVE | `30-agent-scrolled.png` / `31-after-body-tap.png` / `50-history-before.png` / `51-after-down.png` / `52-history-paused.png` / `52b-history-small-down.png` / `53-notification-between.png` / `54-history-after-return.png`（↓ 中心由 `rear-safe-geometry` 现算为 743,411） |
| 12 | 既有锚词回归 | 通知图标点按/详情开合、通知呼吸、WFA 脉冲、Session Lock 清锁分别断言 `rear-tap received`、`detail open/close`、`highlight breath start/end`、`agent pulse start/end`、`session lock cleared <sessionId>` | PASS（10d INCONCLUSIVE） | 10a/10b/10c：`90-detail-open.png` / `91-detail-close.png` / `92-highlight-done.png`（首格 app=com.android.shell）；10d 需真 ZCode 会话，本机无在册会话，按纪律不伪造 |
| 13 | 切页时充电水面与水位数字常驻（Story 23 评审修复） | 充电状态下在通知页与 Agent 页各截一图；断言切页成功，并人工核对两图右下角水面与 `%` 数字都在 | PASS（人工已比对） | `22-water-number-notification.png` / `23-water-number-agent.png`：两图水位线一致、右下 95% 均在（Agent 页长段落 fixture 会压到数字上沿，属 fixture 文字压叠，见下「遗留观察」） |
| 14 | 交叉淡出期间离场通知页不接点按（评审修复） | 单次 adb 连发切页 + 离场通知图标点按；断言有切页 toggle、无 `detail open`、无反向 toggle | PASS | `24-transition-gate.png` |

## 脚本首跑暴露的问题（都已修进 `drive-acceptance.ps1`）

脚本首版是「写好了但设备没插、从没跑过」，实机一轮下来暴露 5 类问题，逐条修掉后才有上面这张表：

1. **PS 5.1 脚本必须带 UTF-8 BOM**：无 BOM 时 Windows PowerShell 按 ANSI 解码中文注释，直接 ParserError、整脚本不执行（题注与本目录 README 的复跑命令都是 `powershell -File`）。
2. **`Invoke-Adb @('a','b')` 的实参会被压成一个字符串**：PowerShell 把数组字面量当**单个**实参传给 `[string[]]` 形参、内层元素被空格拼起来，adb 报 `unknown command install -r ...`；改成 `[object[]]` 形参先摊平。
3. **背屏截图不认逻辑 display id**：`screencap -d 1` 报 `Display Id '1' is not valid`；改用 `display uniqueId` 里的 SurfaceFlinger id（本机 `4630946949513469332`）+ `exec-out`。
4. **固定坐标会随图标枚数失效**：图标格中心随 1/2/3 枚图标移动，写死的 `(600,286)` 会落进格间空白、把「点图标」变成「点空白切页」；现在从 `rear-icon-place` / `rear-safe-geometry` 锚现算首格图标与右下 ↓ 的中心。
5. **真机 Agent 链路会自己掉线**：PC 桥/中继超时（`agent ws failed: timeout`）会触发 agent-down → 内容页兜底、甚至让 WFA 提前收口，把 5 / 8-pre / 9d / 9e-c 搅成随机失败（run 3/4/5/6 实录）。验收期间用 `AGENT_ENABLED=false` 做 fixture 隔离、Agent 页状态一律走 debug 注入（同一 core 事件入口），收尾恢复；实机链路本身由 spec 0010 的验收链覆盖。
6. 另有两处判据本身的坑：`content page reset <page>` 只在页**真的变了**时打（首投本来就默认页则无此行，不能拿它当默认页的证据）；呼吸带 30s 冷却（冷却期发的通知不呼吸是**正确**行为，断言前要先探到一次真呼吸）。
7. fixture 侧两个引号/换行坑：PowerShell 交给原生 exe 时会吃掉内层双引号（`--es reply "$x"` 变成裸 `$x`、按空格切开，实测只进去 4 字节），而含换行的整段文本交给 adb 又会被当参数分隔；最终 fixture 做成**不含空白字符的单行长文本**，整段一个 argv、全程不引号。

## 遗留观察（不阻塞本 spec，留档备查）

- 长段落 fixture（410 字无换行）在 Agent 页会压到右下水位数字上沿；真实输出带换行、行宽更短，未见同类压叠。属渲染边界的观察，不是本 spec 判据项。
- 系统常驻通知（USB 充电 / USB 调试）让「通知页清空」在本机不可构造——3b / 6a / 7a 三项要跑通，需要拔线（会失去 adb）或改用无线 adb；当前记为 INCONCLUSIVE 并写清不可构造的前置。

## 仍需人工/后续

1. 肉眼收尾：切页无黑底闪烁与重影的手感、Agent 页长输出的阅读手感（脚本已给帧采样与时长数据，见 2c / 10 行）。
2. 清锁实机项（10d）：需 PC 侧真 ZCode 会话在册时复跑，脚本会等真实 `AgentRoster`；没有真会话时记 INCONCLUSIVE，不伪造 `session lock cleared` 现场。
3. 判定表实机列与 APK SHA-256 已回填；#134 与 parent #128 均已关闭，本轮结果以追加提交与 PR 归档。
