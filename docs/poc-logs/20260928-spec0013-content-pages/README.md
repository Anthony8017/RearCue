# spec 0013 实机验收 — 内容页切换（票 #134）

- 设备：小米 17 Pro（25098PN5AC，HyperOS 3 / OS3.0.319.0.WBLCNXM），adb serial `94250f9e`
- 背屏：displayId=1，904×572，可用区 x≥296（相机带 296px 在左）
- 分支：`spec/0013-content-pages`
- APK：`:app:assembleDebug`，SHA-256 `EFC2C12BE59497B2C9614D46788AED218A5C4C0B10263B89CFBC08A793FFCCB5`
- 驱动脚本：[drive-acceptance.ps1](drive-acceptance.ps1)（一键安装/重绑监听、构造内容、逐路径注入、采集 logcat 与截图）
- 状态：**设备未连接，实机列全部 PENDING**。本轮已完成的非设备收口：#132/#133 代码、评审修复（水位数字常驻/滚动最新态/过渡手势门/锚词收敛）、内容页日志契约与 JVM 判例、
  `docs/specs/0013` 镜像、README/CONTEXT 回填、本判定表与驱动脚本。JVM/静态结果不能替代实机通过。

## 自动化证据（设备未插）

```powershell
$env:JAVA_HOME="C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1"; $env:ANDROID_HOME="C:\Users\13691\AppData\Local\RearCue-tools\android-sdk"
.\gradlew.bat :core:test :rear:test :notification:test :app:assembleDebug --console=plain
```

结果：BUILD SUCCESSFUL；core 207（`ContentPageLogContractTest` 4 例 + `AgentPulseTest` 日志锚 1 例）、rear debug 138 + release 138（`MirrorScrollPolicyTest` 新增 1 例）、notification 23，0 失败 0 跳过。

## 一键复跑

```powershell
$env:JAVA_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk"
.\gradlew.bat :app:assembleDebug --console=plain

# 默认会安装 APK 并重绑通知监听；已装好可加 -SkipInstall
powershell -ExecutionPolicy Bypass -File docs\poc-logs\20260928-spec0013-content-pages\drive-acceptance.ps1
powershell -ExecutionPolicy Bypass -File docs\poc-logs\20260928-spec0013-content-pages\drive-acceptance.ps1 -SkipInstall
```

脚本落盘：`sequence.logcat`、`acceptance-summary.txt`、各步骤 `*.png`。脚本只在 debug 构建使用既有
`DebugCommandReceiver`（`AGENT_STATE` / `SESSION_LOCK` / `POSTURE_GATE` / 通知调试动作），不发送 prompt、
不批准 agent、不触碰真实会话内容。

## 判定表

| # | 验收项（#134 AC / spec 0013） | 操作与判据 | JVM/静态 | 实机判定 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 1 | 两页都有内容时默认通知页 | 构造 Agent 长会话 + 一条 shell 通知，`EXIT_REAR` 后 `PROJECT_REAR`；断言 `content page reset notification` 与 `content page crossfade start show=notification` | GO（`ContentPageTest`） | PENDING | `10-default-*` |
| 2 | 双向切换 | 通知页空白点按 → Agent 页；Agent 页点正文/会话标识行 → 通知页；断言两条 `content page toggle` 与成对 crossfade | GO（内容页判决；`ContentPageLogContractTest`） | PENDING | `20-*` / `21-*` |
| 3 | 另一边空 no-op | Agent 断连后通知页点空白；通知清空后 Agent 页点空白；断言无 `content page toggle`、无目标页 crossfade | GO（`ContentPageTest`） | PENDING | `30-*` / `31-*` |
| 4 | 通知不抢页 | 停留在 Agent 页时新建 shell 通知；断言无 `crossfade start show=notification`、无 toggle，通知高亮仍触发 | GO（`ContentPageTest`） | PENDING | `40-*` |
| 5 | agent 不抢页 | 停留在通知页时注入 agent 新输出；断言无 `crossfade start show=agent`、无 toggle | GO（`ContentPageTest`） | PENDING | `50-*` |
| 6 | 当前页内容消失兜底 | 通知页清空通知 → fallback Agent；Agent 页断连 → fallback 通知；断言 `content page fallback <page>` + crossfade 到该页 | GO（`ContentPageTest`） | PENDING | `60-*` / `61-*` |
| 7 | 内容恢复不自动切回 | fallback 到 Agent 后恢复通知；fallback 到通知后恢复 agent；断言不自动出现反向 crossfade | GO（`ContentPageTest`） | PENDING | `62-*` / `63-*` |
| 8 | 退屏/重投重置 | 手动切到 Agent 页后 `EXIT_REAR` → `PROJECT_REAR`；断言 `content page reset notification` 与通知页 crossfade | GO（`ContentPageTest`） | PENDING | `70-*` |
| 9 | WFA 跳—锁—回 | 通知页注入 `waiting`：断言 `wfa enter notification` + crossfade agent；WFA 中点空白断言无 toggle；注入 `idle`：断言 `wfa exit notification` + crossfade notification | GO（`ContentPageTest`） | PENDING | `80-*` / `81-*` / `82-*` |
| 10 | 交叉淡入 150–200ms、不响不震 | `content page crossfade start/done show=… durationMs=…` 成对；肉眼/录屏检查无黑底闪烁、无声音与振动 | 契约 GO（`ContentPageLogContractTest`）；观感未验 | PENDING | `20-*` / `82-*` |
| 11 | 手势边界与历史位置 | Agent 页上下拖动不切页；正文点按切页；↓ 只恢复跟随；暂停后小幅下拖未触底仍回看；切走后回页保留历史滚动位置 | `MirrorScrollPolicyTest` 覆盖暂停后小幅下拖状态迁移 GO；其余无 Compose JVM 测试 | PENDING | `30-*` / `50-*` / `51-*` / `52-*` / `52b-*` / `54-*` |
| 12 | 既有锚词回归 | 通知图标点按/详情开合、通知呼吸、WFA 脉冲、Session Lock 清锁分别断言 `rear-tap received`、`detail open/close`、`highlight breath start/end`、`agent pulse start/end`、`session lock cleared <sessionId>` | `ContentPageLogContractTest` + 既有 core 判例 GO；清锁实机依赖真中继 | PENDING（清锁无真会话时 INCONCLUSIVE） | `90-*` |
| 13 | 切页时充电水面与水位数字常驻（Story 23 评审修复） | 充电/插电状态下在通知页与 Agent 页各截一图；断言切页成功，并人工核对两图右下角水面与 `%` 数字都在 | 纯 UI 结构无 Compose JVM seam；数字已移出 `AnimatedContent`，代码注释锁定 | PENDING | `22-water-number-notification.png` / `23-water-number-agent.png` |
| 14 | 交叉淡出期间离场通知页不接点按（评审修复） | 单次 adb 连发切页 + 离场通知图标点按；断言有切页 toggle、无 `detail open`、无反向 toggle | 无 Compose JVM 测试 | PENDING | `24-transition-gate.png` |

## 设备未插时已完成

- 新增 `core/.../ContentPageLogContract.kt`：冻结 `content page reset/toggle/fallback/wfa enter/wfa exit/crossfade start/crossfade done` 词形。
- 新增 `core/.../ContentPageLogContractTest.kt`：逐字面断言完整契约、crossfade 生成器与既有锚词常量。
- 新增 `docs/specs/0013-content-pages.md`：#128 spec 镜像 + 子票/PR/验收记录入口。
- 回填 `CONTEXT.md` 的 Dashboard / Content Page / Rear Tap / Agent Mirror / Waiting-for-Approval 词条（不带 spec 0014 的 Shade-visible 词条）。
- 回填 `README.md` 的模块状态、内容页说明与验收入口。
- 扩展本目录脚本覆盖默认页、双向切换、空边 no-op、不抢页、兜底/恢复、退屏重投、WFA、crossfade 与既有锚词回归。
- 评审修复：水位数字移出 `AnimatedContent` 常驻；Agent 滚动监听改用最新 follow 状态；过渡窗内两层统一吞点按并按当前页门控；Content Page 日志生成器直接吃 `ContentPage`；CONTEXT/AgentArbitrationTest 术语与 ADR 0007 来源注记同步。

## 插上设备后仍需执行（给验收 agent）

1. 确认 `adb devices` 出现 `94250f9e`，按本目录命令重新 `assembleDebug`，再跑脚本；首次建议不带 `-SkipInstall` 以重装并重绑通知监听。
2. 检查 `acceptance-summary.txt`：所有非清锁项应为 `PASS`；失败项按 `sequence.logcat` 与对应 `*.png` 归档。
3. 实机肉眼项：交叉淡入无黑底闪烁/无闪烁层叠、无声音/振动；背景层（呼吸光晕、充电水位、水位数字）不参与淡入；WFA 插队与恢复符合手感。
4. 充电项：跑 `2d` 前确认已插充电器/充电中；若脚本报未充电，插上充电器后用 `-SkipInstall` 复跑，人工比对 `22-*` 与 `23-*` 右下角数字均在。
5. 手势项：核对 `2e` 过渡窗内无 `detail open`/反向 toggle；核对 `52b-history-small-down.png` 仍有 ↓ 且未自动滚底。
6. 清锁项：脚本会尝试锁定不在册会话并等待真实 `AgentRoster`；若没有真 ZCode 会话，记录 `INCONCLUSIVE` 并引用 `SessionLockTest`，不要伪造 `session lock cleared` 现场。
7. 通过后回填本文件判定表的实机列与 APK SHA-256，更新 PR #140，再关闭 #134；parent #128 不在本票关闭。
