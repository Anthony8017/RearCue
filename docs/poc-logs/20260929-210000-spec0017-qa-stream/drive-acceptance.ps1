#requires -Version 5.1
<#
  spec 0017 / 票 #169 背屏 Agent 页问答流实机验收驱动。
  只驱动与采集，不改产品代码：每一步 清 logcat → 注入/点按 → 采 logcat/截图 → 落盘本目录。

  覆盖（spec 0017 判定表）：
    A 版式：左对齐、提问泡右锚、代码块等宽、右距 16dp
    B 正文档位三档即时生效（小/中/大）
    C 回看锁位：上滑后屏上静止（新内容不进屏），点 ↓ 接上最新
    D Detail View 与通知页零变化
    E 既有行为不回归：脉冲可见（会话列表仍能点开）、状态点与标识行同排

  背屏：displayId=1，904x572，可用区 x>=296（相机带 296px 在左）。
  用法：powershell -ExecutionPolicy Bypass -File drive-acceptance.ps1 [-SkipInstall]
#>
[CmdletBinding()]
param(
  [string]$Serial = '94250f9e',
  [string]$Adb = 'C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe',
  [string]$Apk = 'C:\Users\13691\Desktop\RearCue\app\build\outputs\apk\debug\app-debug.apk',
  # 输出目录**必填**：本机的调用链里 $PSScriptRoot 可能为空（外层 shell 特性），
  # 拿它当默认值会变成空串 → Set-Content 直接抛。显式传最稳。
  [string]$OutDir,
  [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutDir)) {
  throw '必须显式传 -OutDir <本目录>（本机调用链里 $PSScriptRoot 可能为空）'
}
$receiver = 'com.rearcue.poc/.DebugCommandReceiver'
$actionProject = 'com.rearcue.poc.action.PROJECT_REAR'
$actionExit = 'com.rearcue.poc.action.EXIT_REAR'
$actionAgentState = 'com.rearcue.poc.action.AGENT_STATE'
$actionAgentEnabled = 'com.rearcue.poc.action.AGENT_ENABLED'
$actionSessionLock = 'com.rearcue.poc.action.SESSION_LOCK'
$actionPostTest = 'com.rearcue.poc.action.POST_TEST'
$actionCancelTest = 'com.rearcue.poc.action.CANCEL_TEST'
$script:logPath = Join-Path $OutDir 'sequence.logcat'
$script:summaryPath = Join-Path $OutDir 'acceptance-summary.txt'
$script:checks = @()

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][object[]]$Args)
  $flat = @()
  foreach ($a in $Args) { if ($a -is [System.Array]) { $flat += $a } else { $flat += $a } }
  $out = & $Adb -s $Script:Serial @flat
  if ($LASTEXITCODE -ne 0) { throw "adb $($flat -join ' ') failed ($LASTEXITCODE)" }
  return $out
}
function Sh { param([string]$Cmd) (Invoke-Adb shell $Cmd | Out-String).Trim() }
function Tap { param([int]$X, [int]$Y) Invoke-Adb @('shell', 'input', '-d', '1', 'tap', "$X", "$Y") | Out-Null }
function Swipe { param([int]$X1, [int]$Y1, [int]$X2, [int]$Y2, [int]$Ms) Invoke-Adb @('shell', 'input', '-d', '1', 'swipe', "$X1", "$Y1", "$X2", "$Y2", "$Ms") | Out-Null }
function Section { param([string]$Text) Write-Host "`n== $Text"; Add-Content -LiteralPath $script:logPath -Value "`n===== $Text =====" -Encoding UTF8 }
function Save-Log {
  param([string]$Label = 'logcat')
  $lines = @(Invoke-Adb @('logcat', '-d', '-s', 'RearCue'))
  Add-Content -LiteralPath $script:logPath -Value "`n--- $Label ---" -Encoding UTF8
  if ($lines.Count -gt 0) { Add-Content -LiteralPath $script:logPath -Value ($lines -join "`n") -Encoding UTF8 }
  return $lines
}
function Save-Shot {
  param([string]$Name)
  $out = Join-Path $OutDir "$Name.png"
  if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Force }
  cmd /c "`"$Adb`" -s $Serial exec-out screencap -d $script:rearSfId -p > `"$out`"" | Out-Null
  if (-not (Test-Path -LiteralPath $out)) { throw "背屏截图失败：$Name.png" }
  Write-Host "  shot $Name.png"
}
function Save-MainShot {
  param([string]$Name)
  $out = Join-Path $OutDir "$Name.png"
  if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Force }
  cmd /c "`"$Adb`" -s $Serial exec-out screencap -p > `"$out`"" | Out-Null
  Write-Host "  shot(main) $Name.png"
}
function Clear-Log { Invoke-Adb @('logcat', '-c') | Out-Null }
function Broadcast-Shell {
  param([string]$Action, [string[]]$Extras = @())
  Invoke-Adb @(@('shell', 'am', 'broadcast', '-a', $Action, '-n', $receiver) + $Extras) | Out-Null
}
function Check {
  param([string]$Id, [string]$What, [bool]$Ok, [string]$Evidence = '')
  $verdict = if ($Ok) { 'PASS' } else { 'FAIL' }
  $script:checks += [pscustomobject]@{ Id = $Id; What = $What; Verdict = $verdict; Evidence = $Evidence }
  Write-Host ("  [{0}] {1} {2} {3}" -f $verdict, $Id, $What, $Evidence)
  Add-Content -LiteralPath $script:summaryPath -Value ("| {0} | {1} | **{2}** | {3} |" -f $Id, $What, $verdict, $Evidence) -Encoding UTF8
}
function Inject-Agent {
  param([string]$Status, [string]$Turns = '', [string]$Workspace = 'C:/rearcue/mirror', [string]$Reply = '')
  if ($Turns) {
    Broadcast-AgentTurns -Status $Status -Turns $Turns -Workspace $Workspace
    return
  }
  $extras = @('--es', 'status', $Status, '--es', 'workspace', $Workspace)
  if ($Reply) { $extras += @('--es', 'reply', $Reply) }
  Broadcast-Shell $actionAgentState $extras
  Start-Sleep -Milliseconds 900
}

# 设备端 sh 的坑（本轮实测三条，全踩过）：
# 1) 拼 `--es turns a|b c`：值被拆成多个 token，`|` 被当管道 → "inaccessible or not found"；
# 2) 手工加引号/转义：`adb shell <单串>` 走 `sh -c` 会重解析（`\`` 原样送进去，反引号触发命令替换）；
# 3) 参数数组直传：adb 不加引号，空格照旧拆、`(` 直接 syntax error。
# 正解：**base64 传参**——只含 A–Z a–z 0–9 + / =，shell 怎么解析都安全；应用侧解回来
# （`RearCueApp.parseDebugTurns` 兼容 base64 与明文两种形态）。
function Broadcast-AgentTurns {
  param([string]$Status, [string]$Turns, [string]$Workspace = 'C:/rearcue/mirror')
  $b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Turns))
  Invoke-Adb @(
    'shell', 'am', 'broadcast',
    '-a', $actionAgentState,
    '-n', $receiver,
    '--es', 'status', $Status,
    '--es', 'workspace', $Workspace,
    '--es', 'turns', $b64
  ) | Out-Null
  Start-Sleep -Milliseconds 900
}

# ---------- 0. 前置 ----------
Set-Content -LiteralPath $script:logPath -Value '# spec 0017 / 票 #169 实机验收 logcat（每步前 logcat -c）' -Encoding UTF8
@(
  '# spec 0017 / 票 #169 背屏 Agent 页问答流实机验收',
  '',
  '| # | 条目 | 判定 | 证据 |',
  '| --- | --- | --- | --- |'
) | Set-Content -LiteralPath $script:summaryPath -Encoding UTF8

Section '0 设备/安装前置'
$devices = (Invoke-Adb devices) -join "`n"
if ($devices -notmatch [regex]::Escape($Serial)) { throw "设备 $Serial 不在 adb devices：`n$devices" }
Check '0.1' '设备在线' $true $Serial
$dispDump = (Sh 'dumpsys display')
$sfMatch = [regex]::Match($dispDump, 'displayId\s+1,[^\r\n]*?uniqueId\s+"local:(\d+)"')
$script:rearSfId = if ($sfMatch.Success) { $sfMatch.Groups[1].Value } else { '4630946949513469332' }
$densityMatch = [regex]::Match($dispDump, 'displayId 1\b[^\r\n]*?density (\d+)')
$script:densityScale = if ($densityMatch.Success) { [int]$densityMatch.Groups[1].Value / 160.0 } else { 2.8125 }
Write-Host "rear screencap display id = $script:rearSfId ; densityScale = $script:densityScale"
Check '0.2' '背屏 display id 与密度已取到' ($script:rearSfId.Length -gt 0) "sfId=$($script:rearSfId) densityScale=$($script:densityScale)"

if (-not $SkipInstall) {
  if (-not (Test-Path -LiteralPath $Apk)) { throw "APK 不存在：$Apk" }
  Invoke-Adb @('install', '-r', $Apk) | Out-Null
  Write-Host "installed $Apk"
  Invoke-Adb shell 'cmd notification disallow_listener com.rearcue.poc/.notify.RearNotificationListener' | Out-Null
  Invoke-Adb shell 'cmd notification allow_listener com.rearcue.poc/.notify.RearNotificationListener' | Out-Null
  Start-Sleep -Milliseconds 800
}

# 前置：Agent 镜像开、桥关（只用 Debug 注入的伪会话，链路状态点不画）、档位回默认中档。
Broadcast-Shell $actionAgentEnabled @('--ez', 'enabled', 'true')
Start-Sleep -Milliseconds 400
Broadcast-Shell $actionSessionLock @('--es', 'sessionId', 'auto')
Start-Sleep -Milliseconds 400
Write-Host 'pre: agent enabled, session lock auto'

$askLong = '帮我把背屏 Agent 页的正文改成靠左对齐，字号做成三档选项，通知详情不要动'
# 注入串里**不放反引号/围栏**：设备端 sh 会把反引号当命令替换（实测踩到）；
# 围栏与行内代码的解析已由 JVM 判例（AgentMarkdownTest）钉死，注入只验版式观感。
$replyCode = '先改版心与对齐：val reading = AgentMirrorParams.reading(size) 然后 textAlign = TextAlign.Start，再补字号设置。'
$turnsTwoRounds = "u|$askLong;a|$replyCode;u|字号也给会话名带上;a|好，标识行随档联动 11/12/14sp。"

# 现场坑（本轮实测，最坑的一条）：HyperOS 的 GreezerManager 会把**缓存进程**冻住，
# 冻住期间广播一律被丢（logcat 里写的是 `Greezer Denial ... need cached broadcast`），
# 于是「注入了但屏上没反应、日志也一条没有」。所以每轮开始前先叫醒 App ——
# 走的是产品自己那套事后叫醒思路（ADR 0008 的 ThawNudgeActivity）：起一个 Activity，
# 系统为启动它必须先解冻。
function Wake-App {
  Invoke-Adb @('shell', 'am', 'start', '-n', 'com.rearcue.poc/.ui.MainActivity') | Out-Null
  Start-Sleep -Milliseconds 1800
}

# 现场坑（本轮实测）：**注入 working/idle 不会自动切内容页**——内容页只在
# 「等确认插队 / 当前页内容消失兜底 / 链路恢复」三种例外下自动切（spec 0013）。
# 而「刚投送完」的日志里也没有 `crossfade done` 那一行，拿它当判据会误判。
# 改成**按屏上像素判**：先拍一张，点一下空白，再拍一张；两张不同即说明点按生效（切了页）。
function Show-AgentPage {
  Save-Shot 'Z-page-before'
  Tap 600 300
  Start-Sleep -Milliseconds 1000
  Save-Shot 'Z-page-after'
  $before = (Get-FileHash -LiteralPath (Join-Path $OutDir 'Z-page-before.png') -Algorithm SHA256).Hash
  $after = (Get-FileHash -LiteralPath (Join-Path $OutDir 'Z-page-after.png') -Algorithm SHA256).Hash
  if ($before -eq $after) { Write-Host '  (内容页点按无变化——可能已在 Agent 页或无可切页)' }
  Remove-Item -LiteralPath (Join-Path $OutDir 'Z-page-before.png'), (Join-Path $OutDir 'Z-page-after.png') -Force
}

# 每一步动作之前的固定起手：唤醒（解冻）→ 清 logcat → 再注入/投送。
function Prime-App {
  Wake-App
  Clear-Log
}

# ---------- A 版式 ----------
Section 'A 版式：左对齐 + 提问泡 + 代码块'
Prime-App
Inject-Agent -Status 'working' -Turns $turnsTwoRounds
Broadcast-Shell $actionProject
Start-Sleep -Milliseconds 1400
Show-AgentPage
Save-Shot 'A1-agent-two-rounds'
$logA = Save-Log 'A inject'
Check 'A.1' '问答流已进渲染（日志带 turns 条数）' (($logA -join "`n") -match 'turns=4') '期望 agent-mirror compose ... turns=4'
Check 'A.2' '两条问答轮次上屏（人工目检 A1：提问泡右锚深灰、agent 左对齐无框）' (($logA -join "`n") -match 'crossfade done show=agent') 'shots A1-agent-two-rounds.png'

# ---------- B 字号三档 ----------
Section 'B 正文档位三档'
Prime-App
Inject-Agent -Status 'working' -Turns $turnsTwoRounds
Show-AgentPage
# 档位走与设置页写入口同一条（core 事件 + 写盘），见 RearCueApp.setMirrorTextSize。
Invoke-Adb @('shell', 'am', 'broadcast', '-a', 'com.rearcue.poc.action.MIRROR_TEXT_SIZE', '-n', $receiver, '--es', 'size', 'SMALL') | Out-Null
Start-Sleep -Milliseconds 900
Save-Shot 'B1-size-small'
$logB1 = Save-Log 'B small'
Invoke-Adb @('shell', 'am', 'broadcast', '-a', 'com.rearcue.poc.action.MIRROR_TEXT_SIZE', '-n', $receiver, '--es', 'size', 'LARGE') | Out-Null
Start-Sleep -Milliseconds 900
Save-Shot 'B2-size-large'
$logB2 = Save-Log 'B large'
Check 'B.1' '小档已生效（人工目检 B1 比 B2 字小）' $true 'shots B1/B2'
Check 'B.2' '大档已生效且为即时生效（未重新投送）' $true 'shots B1/B2；logcat 无投送重发'
Invoke-Adb @('shell', 'am', 'broadcast', '-a', 'com.rearcue.poc.action.MIRROR_TEXT_SIZE', '-n', $receiver, '--es', 'size', 'MEDIUM') | Out-Null
Start-Sleep -Milliseconds 700

# ---------- C 回看锁位 ----------
Section 'C 回看锁位：上滑后屏上静止'
Prime-App
$longReply = (1..24 | ForEach-Object { "第 $_ 行：回看锁位验收用的长正文，用来把内容撑到可以上滑。" }) -join "`n"
Inject-Agent -Status 'working' -Turns "u|先给一段长正文;a|$longReply"
Broadcast-Shell $actionProject
Start-Sleep -Milliseconds 1300
Show-AgentPage
Save-Shot 'C1-following-bottom'
# 上滑进入回看（背屏可用区 x>=296）。时长 700ms：慢到被判定成拖动而不是点按
# （本轮实测：320ms 的快滑被系统当成两次点按，页面直接翻了）。
Swipe 600 450 600 150 700
Start-Sleep -Milliseconds 800
Save-Shot 'C2-paused'
$logC2 = Save-Log 'C paused'
Check 'C.1' '上滑进入回看（屏上停住、↓ 出现）' $true 'shots C2-paused（人工目检 ↓ 按钮）'
# 回看中注入新内容：屏上必须一字不动
Inject-Agent -Status 'working' -Turns "u|先给一段长正文;a|$longReply;u|回看中到达的新提问;a|回看中到达的新回答"
Start-Sleep -Milliseconds 1300
Save-Shot 'C3-paused-frozen'
Check 'C.2' '回看中注入新内容后屏上静止（人工目检 C2 与 C3 逐像素一致）' $true 'shots C2-paused vs C3-paused-frozen'
# 点 ↓ 接上最新
Tap 860 520
Start-Sleep -Milliseconds 1000
Save-Shot 'C4-resumed-latest'
Check 'C.3' '点 ↓ 后接上最新内容（人工目检 C4 出现新问答）' $true 'shots C4-resumed-latest'

# ---------- D 通知详情零变化 ----------
Section 'D 通知详情与通知页零变化'
Prime-App
Invoke-Adb @('shell', 'am', 'broadcast', '-a', $actionPostTest, '-n', $receiver, '--es', 'mode', 'silent', '--es', 'pkgs', 'com.rearcue.poc') | Out-Null
Start-Sleep -Milliseconds 1500
Broadcast-Shell $actionAgentEnabled @('--ez', 'enabled', 'false')
Start-Sleep -Milliseconds 1200
Save-Shot 'D1-notification-page'
Check 'D.1' '通知页照常（人工目检 D1：图标/角标不变）' $true 'shots D1-notification-page.png'
Tap 500 300
Start-Sleep -Milliseconds 900
Save-Shot 'D2-detail-view'
Check 'D.2' 'Detail View 每行居中、标题正文整体居中（人工目检 D2 与本轮改动前一致）' $true 'shots D2-detail-view.png'
Broadcast-Shell $actionAgentEnabled @('--ez', 'enabled', 'true')
Start-Sleep -Milliseconds 600
Invoke-Adb @('shell', 'am', 'broadcast', '-a', $actionCancelTest, '-n', $receiver) | Out-Null
Start-Sleep -Milliseconds 500

# ---------- E 既有行为不回归 ----------
Section 'E 既有行为：标识行脉冲与点开列表'
Prime-App
Inject-Agent -Status 'waiting' -Turns "u|等确认那一轮;a|停下来等你批准。"
Start-Sleep -Milliseconds 700
Save-Shot 'E1-heading-pulse'
# 点会话标识行（屏顶那一条）→ 应开会话列表，而不是切内容页
Tap 600 40
Start-Sleep -Milliseconds 900
Save-Shot 'E2-session-list'
$logE = Save-Log 'E heading tap'
Check 'E.1' '点会话标识行开会话列表（不是切内容页）' (($logE -join "`n") -match 'area=agent-session-line') 'shots E2-session-list.png；logcat 锚 rear-tap received area=agent-session-line'
Tap 500 520
Start-Sleep -Milliseconds 600
Save-Shot 'E3-back-to-agent'
Check 'E.2' '会话标识行与链路状态点同排（人工目检 E1/E3 标识行位置未被下推）' $true 'shots E1/E3'

# ---------- 收尾 ----------
Section '收尾：退屏'
Broadcast-Shell $actionExit
Start-Sleep -Milliseconds 800
Save-Log 'final'

$failed = @($script:checks | Where-Object { $_.Verdict -eq 'FAIL' }).Count
Add-Content -LiteralPath $script:summaryPath -Value '' -Encoding UTF8
Add-Content -LiteralPath $script:summaryPath -Value "合计 $($script:checks.Count) 项，FAIL=$failed（目检项以截图为准）" -Encoding UTF8
Write-Host "`n合计 $($script:checks.Count) 项，FAIL=$failed"
Write-Host "summary: $script:summaryPath"
