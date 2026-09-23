# erratum -- 判定行被「命令文本里的锚词形」污染（锚已收紧 + 回归 fixture）

本轮**事实链全部有效**（28/28 采样 `rear=ON/ON owner=dashboard`、`rear stayed ON through the
whole keep-alive watch`、`cleared-after-lock : False`、`pollution : none detected`、设备侧循环
心跳 `ticks=10`/`ticks=20` 跨过冻结期照打），但两条判定行被解析层误计，**不能照字面读**：

- `e13 : E13-INJECT-FAILED (... heartbeats=2 max-ticks=10 fails=1 ...)` —— `fails=1` 与多出的
  1 条心跳是**幽灵**：`ShizukuShell` 会把整条命令原文打进日志（`sh [<command>] exit=...`），
  而保活循环的**命令文本里就含有全部锚词形**（`wake-keep-alive ok ticks=$i`、`... fail ...`、
  `... stop ticks=...`）。按收紧后的锚（` : wake-keep-alive `，见 ExCommon.psm1 注释）重读同轮
  产物：`start=True heartbeats=2 max-ticks=20 fails=0`（ticks=10 @04:44:08、ticks=20 @04:44:58，
  两条都是设备侧循环自报）——注入证据成立，判定本应是注入有效的形态。
- `keep-alive-stopped : False (stop-line=True heartbeats-after=1 rear-wake-after=1)` —— 同因：
  `stop-line` 也是命令文本里的幽灵，导致残留窗起点前移。真实停止锚在捕获窗后（循环最迟
  一个间隔后自打 `wake-keep-alive stop ticks=`），该行不构成残留证据。

处置：`Get-ExAppKeepAliveFacts` 与 `11-lock-survive.ps1` 残留判定的匹配全部收紧到
` : wake-keep-alive `（tag 分隔符锚），并把本轮的 `sh [...]` 原文行**逐字**做回归 fixture
（Pester 146/146）。本目录产物原样保留、不回改。
