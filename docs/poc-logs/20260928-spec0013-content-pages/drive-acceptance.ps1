#requires -Version 5.1
<#
  spec 0013 / 票 #134 背屏实机验收驱动：内容页全路径 + 既有日志锚回归。
  只驱动与采集，不改产品代码。每一步 清 logcat → 注入/点按 → 采 logcat/截图 → 落盘到本目录。
  背屏：displayId=1，904x572，可用区 x>=296（相机带 296px 在左）。
  前置：手机已连接、通知监听已授权；脚本默认会安装 + 重绑，已安装可用 -SkipInstall。
  用法：
    powershell -ExecutionPolicy Bypass -File drive-acceptance.ps1
    powershell -ExecutionPolicy Bypass -File drive-acceptance.ps1 -SkipInstall
#>
[CmdletBinding()]
param(
  [string]$Serial = '94250f9e',
  [string]$Adb = 'C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe',
  [string]$Apk = 'C:\Users\13691\.codex\worktrees\c01f\RearCue-0013\app\build\outputs\apk\debug\app-debug.apk',
  [string]$OutDir = $PSScriptRoot,
  [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$deviceTmp = '/data/local/tmp/r134'
$receiver = 'com.rearcue.poc/.DebugCommandReceiver'
$actionProject = 'com.rearcue.poc.action.PROJECT_REAR'
$actionExit = 'com.rearcue.poc.action.EXIT_REAR'
$actionAgentState = 'com.rearcue.poc.action.AGENT_STATE'
$actionSessionLock = 'com.rearcue.poc.action.SESSION_LOCK'
$actionPostureGate = 'com.rearcue.poc.action.POSTURE_GATE'
$actionCancelPackage = 'com.rearcue.poc.action.CANCEL_PACKAGE'
$actionCancelTest = 'com.rearcue.poc.action.CANCEL_TEST'
$actionAgentEnabled = 'com.rearcue.poc.action.AGENT_ENABLED'
$script:logPath = Join-Path $OutDir 'sequence.logcat'
$script:summaryPath = Join-Path $OutDir 'acceptance-summary.txt'
$script:checks = @()
$script:apkHash = ''

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  $out = & $Adb -s $Script:Serial @Args
  if ($LASTEXITCODE -ne 0) { throw "adb $($Args -join ' ') failed ($LASTEXITCODE)" }
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
  Invoke-Adb shell "screencap -p -d 1 $deviceTmp/$Name.png" | Out-Null
  Invoke-Adb pull "$deviceTmp/$Name.png" (Join-Path $OutDir "$Name.png") | Out-Null
  Write-Host "  shot $Name.png"
}
function Clear-Log { Invoke-Adb @('logcat', '-c') | Out-Null }
function MatchCount { param($Lines, [string]$Pattern) @($Lines | Where-Object { $_ -match $Pattern }).Count }
function CrossfadeDurationMs {
  param($Lines, [string]$Page)
  $pattern = 'content page crossfade done show=' + [regex]::Escape($Page) + ' durationMs=(\d+)'
  $line = @($Lines | Where-Object { $_ -match $pattern } | Select-Object -Last 1)
  if (-not $line) { return -1 }
  $m = [regex]::Match([string]$line, 'durationMs=(\d+)')
  if (-not $m.Success) { return -1 }
  return [int]$m.Groups[1].Value
}
function Check {
  param([string]$Id, [string]$Name, [bool]$Condition, [string]$Evidence = '')
  $status = if ($Condition) { 'PASS' } else { 'FAIL' }
  $script:checks += [pscustomobject]@{ Id = $Id; Name = $Name; Status = $status; Evidence = $Evidence }
  Write-Host ("  [{0}] {1} {2}" -f $status, $Id, $Name)
}
function Inconclusive {
  param([string]$Id, [string]$Name, [string]$Evidence = '')
  $script:checks += [pscustomobject]@{ Id = $Id; Name = $Name; Status = 'INCONCLUSIVE'; Evidence = $Evidence }
  Write-Host ("  [INCONCLUSIVE] {0} {1}" -f $Id, $Name)
}
function Broadcast-Shell {
  param([string]$Action, [string]$Extra = '')
  $cmd = "am broadcast -n $receiver -a $Action"
  if ($Extra) { $cmd += " $Extra" }
  Invoke-Adb shell $cmd | Out-Null
}
function Post-Notification { param([string]$Tag = 'r134notif') Invoke-Adb shell "cmd notification post -t RearCue $Tag 'RearCue spec0013 fixture'" | Out-Null }
function Cancel-ShellNotifications { Broadcast-Shell $actionCancelPackage '--es pkg com.android.shell' }
function Exit-Rear { Broadcast-Shell $actionExit }
function Project-Rear { Broadcast-Shell $actionProject }
function Inject-Agent-Status {
  param([string]$Status)
  Broadcast-Shell $actionAgentState "--ez connected true --es status $Status --es workspace RearCue-0013"
}
function Inject-Agent-Working {
  Invoke-Adb shell "reply=`$(cat $deviceTmp/reply.txt); am broadcast -n $receiver -a $actionAgentState --ez connected true --es status working --es workspace RearCue-0013 --es reply `"\`$reply`"" | Out-Null
}
function Inject-Agent-Disconnected {
  Broadcast-Shell $actionAgentState '--ez connected false'
}

# ---------- 0. 前置 ----------
Set-Content -LiteralPath $script:logPath -Value '# spec 0013 / 票 #134 实机验收 logcat（按步骤追加；每步前已 logcat -c）' -Encoding UTF8
Set-Content -LiteralPath $script:summaryPath -Value '# spec 0013 / 票 #134 acceptance summary' -Encoding UTF8
Section '0 设备/安装前置'
$devices = (Invoke-Adb devices) -join "`n"
if ($devices -notmatch [regex]::Escape($Serial)) { throw "设备 $Serial 不在 adb devices：`n$devices" }
Check '0' '设备在线' $true $Serial
if (-not $SkipInstall) {
  if (-not (Test-Path -LiteralPath $Apk)) { throw "APK 不存在：$Apk（先跑 gradlew :app:assembleDebug）" }
  $script:apkHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
  Invoke-Adb @('install', '-r', $Apk) | Out-Null
  Write-Host "installed $Apk"
  Invoke-Adb shell 'cmd notification disallow_listener com.rearcue.poc/.notify.RearNotificationListener' | Out-Null
  Invoke-Adb shell 'cmd notification allow_listener com.rearcue.poc/.notify.RearNotificationListener' | Out-Null
  Write-Host 'listener rebound'
}
Invoke-Adb shell "mkdir -p $deviceTmp" | Out-Null
Invoke-Adb shell 'am start -n com.rearcue.poc/.ui.MainActivity' | Out-Null
Start-Sleep -Seconds 2

# 基线复位：不依赖用户当前档位；验收结束再回出厂常用档。
Broadcast-Shell $actionSessionLock '--es sessionId auto'
Broadcast-Shell $actionPostureGate '--ez enabled false'
Broadcast-Shell $actionAgentEnabled '--ez enabled true'
Cancel-ShellNotifications
Broadcast-Shell $actionCancelTest
Start-Sleep -Milliseconds 500

$reply = (@(
  'S001 spec 0013 内容页验收样本。'
  'S002 这一段用于确认历史回看位置。'
  'S003 上下拖动应只滚动历史，不应切换内容页。'
  'S004 会话标识行与正文点按才切回通知页。'
  'S005 右下方向键只恢复实时跟随，不切页。'
  'S006 交叉淡入淡出约 180ms，不响不震。'
  'S007 背景层不参与页面淡入淡出。'
  'S008 最后一条详情收起后按 core 兜底规则切页。'
  'S009 本段文本足够长以产生可滚动的历史区间。'
  'S010 END OF FIXTURE.'
) -join "`n")
$replyFile = Join-Path $env:TEMP 'r134-reply.txt'
[System.IO.File]::WriteAllText($replyFile, $reply, [System.Text.UTF8Encoding]::new($false))
Invoke-Adb push $replyFile "$deviceTmp/reply.txt" | Out-Null

# 先只放 agent，再放通知，退屏重投后两页都有内容。
Inject-Agent-Working
Start-Sleep -Milliseconds 800
Post-Notification 'r134baseline'
Start-Sleep -Milliseconds 1000
Exit-Rear
Start-Sleep -Milliseconds 500
Clear-Log
Project-Rear
Start-Sleep -Seconds 2
$log = Save-Log '1 两页有内容默认通知页'
Save-Shot '10-default-notification'
Check '1' '两页有内容默认通知页' (
  (MatchCount $log 'content page reset notification') -gt 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -gt 0
) '10-default-notification.png'
Write-Host (Sh "dumpsys activity activities | grep -A4 'Display #1' | grep -E 'Task\{|topResumedActivity' | head -4")

# ---------- 2. 双向切换 ----------
Section '2 双向切换：通知页 -> Agent 页 -> 通知页'
Clear-Log
Tap 350 470
Start-Sleep -Milliseconds 600
$log = Save-Log '2a 通知页 -> Agent 页'
Save-Shot '20-agent-after-toggle'
Check '2a' '通知页 -> Agent 页 toggle + crossfade' (
  (MatchCount $log 'content page toggle agent') -gt 0 -and
  (MatchCount $log 'content page crossfade start show=agent') -gt 0 -and
  (MatchCount $log 'content page crossfade done show=agent') -gt 0 -and
  (CrossfadeDurationMs $log 'agent') -ge 150 -and
  (CrossfadeDurationMs $log 'agent') -le 400
) '20-agent-after-toggle.png'
Clear-Log
Tap 600 300
Start-Sleep -Milliseconds 600
$log = Save-Log '2b Agent 页正文 -> 通知页'
Save-Shot '21-notification-after-body-tap'
Check '2b' 'Agent 页 -> 通知页 toggle + crossfade' (
  (MatchCount $log 'content page toggle notification') -gt 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -gt 0 -and
  (CrossfadeDurationMs $log 'notification') -ge 150 -and
  (CrossfadeDurationMs $log 'notification') -le 400
) '21-notification-after-body-tap.png'

Inconclusive '2c' '交叉淡入观感：无黑底闪烁/无声/无振动/背景层不淡入（需人工）' '20-agent-after-toggle.png / 21-notification-after-body-tap.png'

# ---------- 2d. 充电水位数字跨切页常驻（Story 23 评审修复） ----------
Section '2d 充电时切页：水面与水位数字常驻'
$battery = Sh 'dumpsys battery'
if ($battery -match 'AC powered: true|USB powered: true|Wireless powered: true') {
  Save-Shot '22-water-number-notification'
  Clear-Log
  Tap 350 470
  Start-Sleep -Milliseconds 600
  $log = Save-Log '2d 充电时通知页 -> Agent 页'
  Save-Shot '23-water-number-agent'
  Check '2d-a' '充电时切页成功（供人工核对水位数字常驻）' (
    (MatchCount $log 'content page toggle agent') -gt 0
  ) '22-water-number-notification.png / 23-water-number-agent.png'
  Inconclusive '2d-b' '切页后水面与水位数字仍可见（人工比对两图右下角）' '22-water-number-notification.png / 23-water-number-agent.png'
  # 回通知页，保持后续 no-op 用例前置。
  Tap 600 300
  Start-Sleep -Milliseconds 600
} else {
  Inconclusive '2d-a' '充电时切页留证（当前未充电，请插充电器后复跑）' 'dumpsys battery'
}

# ---------- 2e. 交叉淡出期间离场通知页不接点按（评审修复） ----------
Section '2e 过渡窗内离场页不接点按'
Clear-Log
# 单次 adb shell 内先切页、约 80ms 后点离场通知图标，尽量压进 180ms 过渡窗。
Invoke-Adb shell 'input -d 1 tap 350 470; sleep 0.08; input -d 1 tap 600 286' | Out-Null
Start-Sleep -Milliseconds 900
$log = Save-Log '2e 过渡窗内点按离场通知图标'
Save-Shot '24-transition-gate'
Check '2e' '过渡窗内离场通知页不生效' (
  (MatchCount $log 'content page toggle agent') -gt 0 -and
  (MatchCount $log 'detail open') -eq 0 -and
  (MatchCount $log 'content page toggle notification') -eq 0
) '24-transition-gate.png'
# 回通知页，供后续 no-op 用例。
Tap 600 300
Start-Sleep -Milliseconds 600

# ---------- 3. 另一边为空 no-op ----------
Section '3 另一边为空：点按 no-op'
Inject-Agent-Disconnected
Start-Sleep -Milliseconds 600
Clear-Log
Tap 350 470
Start-Sleep -Milliseconds 500
$log = Save-Log '3a 通知页：Agent 边空 no-op'
Save-Shot '30-notification-agent-empty-noop'
Check '3a' 'Agent 边空时点按 no-op' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=agent') -eq 0
) '30-notification-agent-empty-noop.png'
Inject-Agent-Working
Start-Sleep -Milliseconds 600
Tap 350 470
Start-Sleep -Milliseconds 600
Cancel-ShellNotifications
Start-Sleep -Milliseconds 600
Clear-Log
Tap 350 470
Start-Sleep -Milliseconds 500
$log = Save-Log '3b Agent 页：通知边空 no-op'
Save-Shot '31-agent-notification-empty-noop'
Check '3b' '通知边空时点按 no-op' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -eq 0
) '31-agent-notification-empty-noop.png'
Post-Notification 'r134afterempty'
Start-Sleep -Milliseconds 800

# ---------- 4. 通知不抢页 ----------
Section '4 通知不抢页 + 呼吸高亮保留'
Clear-Log
Post-Notification 'r134steal'
Start-Sleep -Milliseconds 800
$log = Save-Log '4 通知到达时停留 Agent 页'
Save-Shot '40-agent-notification-arrives'
Check '4' '通知到达不抢页且呼吸高亮保留' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -eq 0 -and
  (MatchCount $log 'highlight breath start') -gt 0
) '40-agent-notification-arrives.png'

# ---------- 5. agent 输出不抢页 ----------
Section '5 agent 新输出不抢页'
Tap 600 300
Start-Sleep -Milliseconds 600
Clear-Log
Inject-Agent-Working
Start-Sleep -Milliseconds 600
$log = Save-Log '5 agent 输出到达时停留通知页'
Save-Shot '50-notification-agent-output'
Check '5' 'agent 新输出不抢页' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=agent') -eq 0
) '50-notification-agent-output.png'

# ---------- 6. 当前页内容消失 -> 兜底 ----------
Section '6 内容消失兜底'
Clear-Log
Cancel-ShellNotifications
Start-Sleep -Milliseconds 800
$log = Save-Log '6a 通知消失 -> fallback Agent'
Save-Shot '60-fallback-agent'
Check '6a' '通知页内容消失兜底 Agent' (
  (MatchCount $log 'content page fallback agent') -gt 0 -and
  (MatchCount $log 'content page crossfade start show=agent') -gt 0
) '60-fallback-agent.png'

# ---------- 7. 恢复不自动切回 ----------
Section '7 内容恢复不自动切回'
Clear-Log
Post-Notification 'r134recover'
Start-Sleep -Milliseconds 800
$log = Save-Log '7a 通知恢复：仍停留 Agent 页'
Save-Shot '62-agent-notification-recovered'
Check '7a' '通知恢复不自动切回' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -eq 0
) '62-agent-notification-recovered.png'
Clear-Log
Inject-Agent-Disconnected
Start-Sleep -Milliseconds 800
$log = Save-Log '6b Agent 消失 -> fallback 通知'
Save-Shot '61-fallback-notification'
Check '6b' 'Agent 页内容消失兜底通知页' (
  (MatchCount $log 'content page fallback notification') -gt 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -gt 0
) '61-fallback-notification.png'
Clear-Log
Inject-Agent-Working
Start-Sleep -Milliseconds 800
$log = Save-Log '7b Agent 恢复：仍停留通知页'
Save-Shot '63-notification-agent-recovered'
Check '7b' 'Agent 恢复不自动切回' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=agent') -eq 0
) '63-notification-agent-recovered.png'

# ---------- 8. 退屏/重投重置 ----------
Section '8 退屏 / 重投重置默认页'
Tap 350 470
Start-Sleep -Milliseconds 600
Exit-Rear
Start-Sleep -Milliseconds 500
Clear-Log
Project-Rear
Start-Sleep -Milliseconds 800
$log = Save-Log '8 EXIT_REAR -> PROJECT_REAR'
Save-Shot '70-recast-default-notification'
Check '8' '退屏重投回默认通知页' (
  (MatchCount $log 'content page reset notification') -gt 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -gt 0
) '70-recast-default-notification.png'

# ---------- 9. WFA 跳 - 锁 - 回 ----------
Section '9 WFA 跳 - 锁 - 回 + agent pulse'
Clear-Log
Inject-Agent-Status 'waiting'
Start-Sleep -Milliseconds 600
$log = Save-Log '9a WFA enter'
Save-Shot '80-wfa-agent'
Check '9a' 'WFA 插队到 Agent 页' (
  (MatchCount $log 'content page wfa enter notification') -gt 0 -and
  (MatchCount $log 'content page crossfade start show=agent') -gt 0 -and
  (MatchCount $log 'agent pulse start') -gt 0
) '80-wfa-agent.png'
Clear-Log
Tap 350 470
Start-Sleep -Milliseconds 500
$log = Save-Log '9b WFA 存续期手动切换被忽略'
Save-Shot '81-wfa-locked'
Check '9b' 'WFA 存续期不可手动切走' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -eq 0
) '81-wfa-locked.png'
Start-Sleep -Seconds 3
$pulse = Save-Log '9c agent pulse end'
Save-Shot '82-wfa-pulse-end'
Check '9c' 'agent pulse start/end 不回归' (
  (MatchCount $pulse 'agent pulse end') -gt 0
) '80-wfa-agent.png / 82-wfa-pulse-end.png'
Clear-Log
Inject-Agent-Status 'idle'
Start-Sleep -Milliseconds 600
$log = Save-Log '9d WFA exit'
Save-Shot '83-wfa-return-notification'
Check '9d' 'WFA 解决回原通知页' (
  (MatchCount $log 'content page wfa exit notification') -gt 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -gt 0
) '83-wfa-return-notification.png'

# ---------- 9e. 手势边界与历史位置 ----------
Section '9e 手势边界与历史位置'
Tap 350 470
Start-Sleep -Milliseconds 600
Clear-Log
Swipe 600 460 600 200 400
Start-Sleep -Milliseconds 500
$log = Save-Log '9e-a Agent 页拖动不切页'
Save-Shot '30-agent-scrolled'
Check '9e-a' 'Agent 页上下拖动不切页' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -eq 0
) '30-agent-scrolled.png'
Clear-Log
Tap 600 300
Start-Sleep -Milliseconds 600
$log = Save-Log '9e-b Agent 页正文点按切回通知页'
Save-Shot '31-after-body-tap'
Check '9e-b' 'Agent 页正文点按切回通知页' (
  (MatchCount $log 'rear-tap received area=content-page') -gt 0 -and
  (MatchCount $log 'content page toggle notification') -gt 0
) '31-after-body-tap.png'
Tap 350 470
Start-Sleep -Milliseconds 600
Swipe 600 460 600 200 400
Start-Sleep -Milliseconds 500
Save-Shot '50-history-before'
Clear-Log
Tap 856 524
Start-Sleep -Milliseconds 600
$log = Save-Log '9e-c Agent 页 ↓ 只恢复跟随'
Save-Shot '51-after-down'
Check '9e-c' 'Agent 页 ↓ 不切页' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -eq 0
) '50-history-before.png / 51-after-down.png'
Swipe 600 460 600 200 400
Start-Sleep -Milliseconds 500
Save-Shot '52-history-paused'
Clear-Log
# 评审修复回归：暂停后小幅下拖、不触底，旧实现会因闭包捕获 FOLLOWING 误回跟随。
Swipe 600 200 600 260 250
Start-Sleep -Milliseconds 500
$log = Save-Log '9e-c1 暂停后小幅下拖未触底仍回看'
Save-Shot '52b-history-small-down'
Check '9e-c1' '暂停后小幅下拖未触底仍保持回看（↓ 仍在/未自动滚底，人工核对截图）' (
  (MatchCount $log 'content page toggle') -eq 0
) '52-history-paused.png / 52b-history-small-down.png'
Tap 350 470
Start-Sleep -Milliseconds 600
Save-Shot '53-notification-between'
Tap 350 470
Start-Sleep -Milliseconds 600
Save-Shot '54-history-after-return'
Inconclusive '9e-d' '历史回看位置跨切换保持（需人工比对截图）' '50-history-before.png / 52-history-paused.png / 54-history-after-return.png'

# ---------- 10. 既有锚词回归 ----------
Section '10 既有锚词回归'
Clear-Log
Tap 600 286
Start-Sleep -Milliseconds 800
$log = Save-Log '10a rear-tap received + detail open'
Save-Shot '90-detail-open'
Check '10a' 'rear-tap received / detail open' (
  (MatchCount $log 'rear-tap received app=com.android.shell') -gt 0 -and
  (MatchCount $log 'detail open com.android.shell') -gt 0
) '90-detail-open.png'
Clear-Log
Tap 600 286
Start-Sleep -Milliseconds 900
$log = Save-Log '10b detail close'
Save-Shot '91-detail-close'
Check '10b' 'detail close 不回归' (
  (MatchCount $log 'detail close com.android.shell') -gt 0
) '91-detail-close.png'
Clear-Log
Post-Notification 'r134highlight'
Start-Sleep -Seconds 4
$log = Save-Log '10c highlight breath'
Save-Shot '92-highlight-done'
Check '10c' 'highlight breath start/end 不回归' (
  (MatchCount $log 'highlight breath start') -gt 0 -and
  (MatchCount $log 'highlight breath end') -gt 0
) '92-highlight-done.png'
Clear-Log
Broadcast-Shell $actionSessionLock '--es sessionId r134-not-in-roster'
Start-Sleep -Seconds 12
$log = Save-Log '10d session lock cleared（需真实 AgentRoster）'
Save-Shot '93-session-lock-clear'
if ((MatchCount $log 'session lock cleared r134-not-in-roster') -gt 0) {
  Check '10d' 'session lock cleared 词形' $true 'sequence.logcat / 93-session-lock-clear.png'
} else {
  Inconclusive '10d' 'session lock cleared 词形（无非在册会话/无真实 AgentRoster）' 'sequence.logcat / 93-session-lock-clear.png'
}
Broadcast-Shell $actionSessionLock '--es sessionId auto'

# ---------- 11. 收尾 ----------
Section '11 收尾与状态复位'
Broadcast-Shell $actionPostureGate '--ez enabled false'
Broadcast-Shell $actionAgentEnabled '--ez enabled true'
Broadcast-Shell $actionSessionLock '--es sessionId auto'
Cancel-ShellNotifications
Broadcast-Shell $actionCancelTest
Exit-Rear
Invoke-Adb shell "rm -rf $deviceTmp" | Out-Null
Save-Log '11 收尾' | Out-Null

$failed = @($script:checks | Where-Object { $_.Status -eq 'FAIL' }).Count
$inconclusive = @($script:checks | Where-Object { $_.Status -eq 'INCONCLUSIVE' }).Count
Add-Content -LiteralPath $script:summaryPath -Value "device=$Serial apkSha256=$script:apkHash failed=$failed inconclusive=$inconclusive" -Encoding UTF8
Add-Content -LiteralPath $script:summaryPath -Value ($script:checks | Format-Table -AutoSize | Out-String) -Encoding UTF8
Write-Host "`nacceptance summary -> $script:summaryPath"
Write-Host ("failed={0} inconclusive={1}" -f $failed, $inconclusive)
if ($failed -gt 0) { exit 1 }
