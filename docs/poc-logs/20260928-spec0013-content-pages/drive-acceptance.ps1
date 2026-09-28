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
  # 调用点用 `Invoke-Adb @('a','b')` 传数组字面量时，PowerShell 把它当成**单个**数组实参；
  # 若参数类型是 [string[]]，内层数组会被拼成 "a b" 一个参数发给 adb（adb 报 unknown command）。
  # 故取 object[] 并先摊平。
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
  # 工具坑：本机 HyperOS 的 screencap 不认逻辑 display id（`-d 1` → "Display Id '1' is not valid"），
  # 只认 SurfaceFlinger 的 display id（display uniqueId 里的数字），见 tools/ex/05-collect.ps1 的同类注记。
  param([string]$Name)
  $out = Join-Path $OutDir "$Name.png"
  if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Force }
  cmd /c "`"$Adb`" -s $Serial exec-out screencap -d $script:rearSfId -p > `"$out`"" | Out-Null
  if (-not (Test-Path -LiteralPath $out)) { throw "背屏截图失败：$Name.png（rearSfId=$script:rearSfId）" }
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

# ---------- 状态读取：验收脚本自己判断「现在在哪一页 / 有没有详情开着」，不靠固定步序的假设。 ----------
# 首轮实跑教训：把「上一步点了什么」当状态来推，会在两种真实情况下全盘错位——
# ①点按落在图标格间空白（坐标随图标枚数变化）变成切页/开详情；②系统常驻通知（USB 充电/调试）
# 让通知页永远不空。故本脚本改为每步先读状态、再动作、再断言。
function Get-CoreLog { return @(Invoke-Adb @('logcat', '-d', '-s', 'RearCue')) }

function Get-LastLine {
  param($Lines, [string]$Pattern)
  $m = @($Lines | Where-Object { $_ -match $Pattern })
  if ($m.Count -eq 0) { return $null }
  return [string]$m[$m.Count - 1]
}

# 当前内容页：取日志里最后一个页变化锚（core 的 toggle/fallback/reset/wfa enter|exit，或视图层 crossfade start）。
function Get-Page {
  param($Lines)
  $anchors = @($Lines | Where-Object {
      $_ -match 'content page (reset|toggle|fallback) (notification|agent)' -or
      $_ -match 'content page wfa (enter|exit) (notification|agent)' -or
      $_ -match 'content page crossfade start show=(notification|agent)'
    })
  if ($anchors.Count -eq 0) { return '?' }
  return [regex]::Match([string]$anchors[$anchors.Count - 1], '(notification|agent)').Groups[1].Value
}

# 最新一次 iconSet 回显（时间倒序，第一个 = 最新 App）。空数组 = 通知页空。
# 注意词形：同一行常见 `iconSet [旧] -> [新] visible=N`，必须取**最后一个**方括号组才是当前集合。
function Get-IconSet {
  param($Lines)
  $line = Get-LastLine $Lines 'iconSet'
  if (-not $line) { return @() }
  $groups = [regex]::Matches([string]$line, '\[([^\[\]]*)\]')
  if ($groups.Count -eq 0) { return @() }
  $inner = $groups[$groups.Count - 1].Groups[1].Value.Trim()
  if ($inner -eq '') { return @() }
  return @($inner -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
}

# 是否还有没收起的 Detail View（最后一次 detail open 之后没有 detail close）。
function Test-DetailOpen {
  param($Lines)
  $open = Get-LastLine $Lines 'detail open '
  if (-not $open) { return $false }
  $close = Get-LastLine $Lines 'detail close '
  if (-not $close) { return $true }
  return ([string]$open -gt [string]$close)
}

# 通知页首格图标中心：从 rear-icon-place 锚现算（drift 是 block 的 overhang 外扩，取一半）。
function Get-FirstIconCenter {
  param($Lines)
  $line = Get-LastLine $Lines 'rear-icon-place size=\d+ overhang=\d+ block=PxRect\('
  if (-not $line) { return $null }
  $m = [regex]::Match([string]$line, 'size=(\d+) overhang=(\d+) block=PxRect\(left=(-?\d+), top=(-?\d+), right=(-?\d+), bottom=(-?\d+)\)')
  if (-not $m.Success) { return $null }
  $size = [int]$m.Groups[1].Value
  $half = [int]([int]$m.Groups[2].Value / 2)
  $left = [int]$m.Groups[3].Value
  $top = [int]$m.Groups[4].Value
  return @([int]($left + $half + $size / 2), [int]($top + $half + $size / 2))
}

# 取首格图标中心：锚只在通知页重绘时落盘，logcat 清过或页没重绘时先发一条 fixture 通知逼重绘。
function Get-IconCell {
  param([int]$Tries = 3)
  for ($i = 0; $i -lt $Tries; $i++) {
    $c = Get-FirstIconCenter (Get-CoreLog)
    if ($c) { return $c }
    Post-Notification "r134cell$i"
    Start-Sleep -Milliseconds 900
  }
  return $null
}

# 右下 ↓ 浮动按钮中心：40dp 圆钮、BottomEnd 对齐 rear-safe-geometry 的 layout 矩形。
# 旧脚本写死 (856,524)——那落在正文空白区，点下去只会切页（run 3–7 的真因）。
function Get-DownButtonCenter {
  param($Lines)
  $line = Get-LastLine $Lines 'rear-safe-geometry .*layout=PxRect\('
  if (-not $line) { return $null }
  $m = [regex]::Match([string]$line, 'layout=PxRect\(left=(-?\d+), top=(-?\d+), right=(-?\d+), bottom=(-?\d+)\)')
  if (-not $m.Success) { return $null }
  $radius = [int]([math]::Round(20 * $script:densityScale))
  $cx = [int]$m.Groups[3].Value
  $cy = [int]$m.Groups[4].Value
  # 注意：`@($cx - $radius, $cy - $radius)` 会被解析成 `$cx - ($radius, $cy) - $radius` ⇒ 必须逐个加括号。
  return @(($cx - $radius), ($cy - $radius))
}

# 把当前页收口到目标页：详情开着先收起（点按任意处 = 收起），否则点空白切页；切不动就重试。
function Ensure-Page {
  param([string]$Page, [int]$Tries = 6)
  for ($i = 0; $i -lt $Tries; $i++) {
    $lines = Get-CoreLog
    if ((Get-Page $lines) -eq $Page -and -not (Test-DetailOpen $lines)) { return $true }
    Tap 350 470
    Start-Sleep -Milliseconds 700
  }
  return ((Get-Page (Get-CoreLog)) -eq $Page)
}

# 呼吸高亮带 30s 冷却（spec 0008：呼吸中/冷却中再来通知不重复呼吸）。冷却窗不能靠读日志算
# ——logcat 在本脚本里被反复清空，早期呼吸记录早没了（首轮踩坑：等冷却的逻辑因此空转，
# 随后的断言必然假失败）。改为直接试：连发新 tag 的探针通知，哪一条真的呼吸了就用那一条断言。
function Invoke-BreathProbe {
  param([string]$TagPrefix = 'r134breath', [int]$MaxTries = 8)
  for ($i = 0; $i -lt $MaxTries; $i++) {
    Clear-Log
    Post-Notification "$TagPrefix$i"
    Start-Sleep -Milliseconds 900
    $lines = Save-Log "breath probe $i ($TagPrefix$i)"
    if ((MatchCount $lines 'highlight breath start') -gt 0) {
      Write-Host "  breath probe hit on $TagPrefix$i"
      return $lines
    }
    Write-Host "  breath probe $TagPrefix$i suppressed (cooldown) -> retry in 5s"
    Start-Sleep -Seconds 5
  }
  return @()
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
  # 关键坑（踩了两轮）：PowerShell 5.1 把参数交给原生 exe 时会**吃掉内层双引号**，
  # 所以设备侧 `--es reply "$reply"` 到这里就变成裸的 `$reply` 并被按空格切开（实测只进去 4 字节）；
  # 反过来，把含换行的整段文本交给 adb 又会因换行被当参数分隔（adb 报 unknown command）。
  # 结论：fixture 做成**不含空白字符的单行长文本**（中文句子之间只用全角标点），
  # 整段就是一个 argv，全程不需要任何引号，也就不经过那个会吃掉引号的边界。
  Invoke-Adb shell "am broadcast -n $receiver -a $actionAgentState --ez connected true --es status working --es workspace RearCue-0013 --es reply $script:replyText" | Out-Null
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
# 背屏（displayId 1）的 SurfaceFlinger display id = uniqueId 里的数字；截图与断言都依赖它。
$dispDump = (Sh 'dumpsys display')
$sfMatch = [regex]::Match($dispDump, 'displayId\s+1,[^\r\n]*?uniqueId\s+"local:(\d+)"')
$script:rearSfId = if ($sfMatch.Success) { $sfMatch.Groups[1].Value } else { '4630946949513469332' }
# 背屏密度（1dp = N px）：↓ 浮动按钮的半径换算要用它（本机 450dpi ⇒ 2.8125）。
$densityMatch = [regex]::Match($dispDump, 'displayId 1\b[^\r\n]*?density (\d+)')
$script:densityScale = if ($densityMatch.Success) { [int]$densityMatch.Groups[1].Value / 160.0 } else { 2.8125 }
Write-Host "rear screencap display id = $script:rearSfId ; densityScale = $script:densityScale"
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
# Agent 链路**关掉**（fixture 隔离）：真机上的 PC 桥/中继会在验收途中超时掉线（`agent ws failed`），
# 掉线会触发 agent-down → 内容页兜底、甚至让 WFA 提前收口，把 5 / 8-pre / 9d / 9e-c 搅成随机失败
# （run 3/4/5 实录）。Agent 页状态改由 debug 注入驱动（同一 core 事件入口，语义等价）；
# 实机链路本身由 spec 0010 的验收链覆盖，不在本 spec 的判据里。收尾再打开。
Broadcast-Shell $actionSessionLock '--es sessionId auto'
Broadcast-Shell $actionPostureGate '--ez enabled false'
Broadcast-Shell $actionAgentEnabled '--ez enabled false'
Cancel-ShellNotifications
Broadcast-Shell $actionCancelTest
Start-Sleep -Milliseconds 500

# fixture 原文：行数足够多才能在背屏可读区里产生「可滚动的历史区间」——
# 太短则根本滚不动、右下 ↓ 不出现，手势项（9e-c/9e-c1）会退化成「点空白切页」。
# 注意：**不能含空格/换行**（见 Inject-Agent-Working 的坑注），整段用一个 argv 传进去。
$script:replyText = 'S001spec0013内容页验收样本；S002这一段用于确认历史回看位置；S003上下拖动应只滚动历史不应切换内容页；S004会话标识行与正文点按才切回通知页；S005右下方向键只恢复实时跟随不切页；S006交叉淡入淡出约180ms且不响不震；S007背景层不参与页面淡入淡出；S008最后一条详情收起后按core兜底规则切页；S009本段文本足够长以产生可滚动的历史区间；S010回看位置在切页往返后应当保持；S011正文最大化且不显示状态词与动作行；S012Agent页与通知页平级互不抢页；S013WFA插队只在等待确认存续期间；S014锁定会话只决定显示谁不锁屏；S015空闲时背屏显示最近一段会话输出；S016断连才失去Agent页内容；S017通知呼吸约三秒且冷却三十秒；S018充电水位是背景层不参与切页；S019图标与角标语义本次不变；S020这是fixture最后一段用于确认滚动到底后回看位置仍在。'

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
# 词形注意：`content page reset <page>` 只在页**真的变了**时打（DashboardCore.resetContentPage 的 changed 分支），
# 首投本来就是默认页时没有这行。默认页判定以渲染锚（crossfade start show=notification）+ 无切页/兜底锚为准。
Check '1' '两页有内容默认通知页' (
  (MatchCount $log 'content page crossfade start show=notification') -gt 0 -and
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page fallback') -eq 0
) '10-default-notification.png（reset 行仅在页变化时出现）'
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
Tap 350 470
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
  Ensure-Page 'notification' | Out-Null
  Start-Sleep -Milliseconds 600
} else {
  Inconclusive '2d-a' '充电时切页留证（当前未充电，请插充电器后复跑）' 'dumpsys battery'
}

# ---------- 2e. 交叉淡出期间离场通知页不接点按（评审修复） ----------
Section '2e 过渡窗内离场页不接点按'
# 图标格坐标从 rear-icon-place 锚现算：固定坐标（曾用 600,286）在 1/2/3 枚图标间会落进格间空白，
# 那样点按变成「空白区切页」而不是「点图标」，本项就测不到。
$cell = Get-IconCell
if (-not $cell) { throw '拿不到 rear-icon-place 锚，无法定位首格图标' }
Write-Host "  first icon cell = $($cell[0]),$($cell[1])"
Clear-Log
# 单次 adb shell 内先切页、约 80ms 后点离场通知图标，尽量压进 180ms 过渡窗。
Invoke-Adb shell "input -d 1 tap 350 470; sleep 0.08; input -d 1 tap $($cell[0]) $($cell[1])" | Out-Null
Start-Sleep -Milliseconds 900
$log = Save-Log '2e 过渡窗内点按离场通知图标'
Save-Shot '24-transition-gate'
Check '2e' '过渡窗内离场通知页不生效' (
  (MatchCount $log 'content page toggle agent') -gt 0 -and
  (MatchCount $log 'detail open') -eq 0 -and
  (MatchCount $log 'content page toggle notification') -eq 0
) '24-transition-gate.png'
# 回通知页，供后续 no-op 用例。
Ensure-Page 'notification' | Out-Null
Start-Sleep -Milliseconds 600

# ---------- 3. 另一边为空 no-op ----------
Section '3 另一边为空：点按 no-op'
Ensure-Page 'notification' | Out-Null
Inject-Agent-Disconnected
Start-Sleep -Milliseconds 800
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
Start-Sleep -Milliseconds 800
Cancel-ShellNotifications
Start-Sleep -Milliseconds 1200
# 前提：通知页真的空了，才谈得上「另一边空 no-op」。本机挂着 USB 时系统常驻通知
# （android 包的 USB / USB 调试）不可撤——应用侧 cancel 被系统拒（首轮实录 cancelled=3 但仍在册），
# 故此时只能如实记 INCONCLUSIVE，不把「另一边非空时点按切页」当成失败。
$iconSet = Get-IconSet (Get-CoreLog)
Save-Shot '31-agent-notification-empty-noop'
if ($iconSet.Count -gt 0) {
  Inconclusive '3b' '通知边空时点按 no-op（通知页非空，构造不出单边空）' ("iconSet=[" + ($iconSet -join ', ') + "] 系统常驻通知不可撤")
} else {
  Ensure-Page 'agent' | Out-Null
  Clear-Log
  Tap 350 470
  Start-Sleep -Milliseconds 500
  $log = Save-Log '3b Agent 页：通知边空 no-op'
  Check '3b' '通知边空时点按 no-op' (
    (MatchCount $log 'content page toggle') -eq 0 -and
    (MatchCount $log 'content page crossfade start show=notification') -eq 0
  ) '31-agent-notification-empty-noop.png'
}
Post-Notification 'r134afterempty'
Start-Sleep -Milliseconds 800

# ---------- 4. 通知不抢页 ----------
Section '4 通知不抢页 + 呼吸高亮保留'
$onAgent = Ensure-Page 'agent'
if (-not $onAgent) { Write-Host '  warn: 未收口到 Agent 页，本项前置不成立' }
$log = Invoke-BreathProbe 'r134steal'
Save-Shot '40-agent-notification-arrives'
Check '4' '通知到达不抢页且呼吸高亮保留' (
  $onAgent -and
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=notification') -eq 0 -and
  (MatchCount $log 'highlight breath start') -gt 0
) '40-agent-notification-arrives.png'

# ---------- 5. agent 输出不抢页 ----------
Section '5 agent 新输出不抢页'
Ensure-Page 'notification' | Out-Null
# 收口用的点按要先落定再清 logcat：`logcat -c` 与还在路上的 tap 日志会赛跑，
# 上一步的点按日志落进本窗口就会把「不抢页」判成抢页（run 5 的假失败）。
Start-Sleep -Milliseconds 900
Clear-Log
Inject-Agent-Working
Start-Sleep -Milliseconds 800
$log = Save-Log '5 agent 输出到达时停留通知页'
Save-Shot '50-notification-agent-output'
Check '5' 'agent 新输出不抢页' (
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page crossfade start show=agent') -eq 0
) '50-notification-agent-output.png'

# ---------- 6. 当前页内容消失 -> 兜底 ----------
Section '6 内容消失兜底'
Cancel-ShellNotifications
Start-Sleep -Milliseconds 1200
# 6a 需要「通知页内容全部消失」这一前提：fixture 的 shell 通知撤掉后，若还有系统常驻通知
# （android 包的 USB / USB 调试，本机挂 USB 时必在），通知页不空、兜底本就不该发生 ⇒ 记 INCONCLUSIVE。
$notifEmpty = ((Get-IconSet (Get-CoreLog)).Count -eq 0)
Save-Shot '60-fallback-agent'
if ($notifEmpty) {
  Clear-Log
  $log = Save-Log '6a 通知消失 -> fallback Agent'
  Check '6a' '通知页内容消失兜底 Agent' (
    (MatchCount $log 'content page fallback agent') -gt 0 -and
    (MatchCount $log 'content page crossfade start show=agent') -gt 0
  ) '60-fallback-agent.png'
} else {
  Inconclusive '6a' '通知页内容消失兜底 Agent（通知页非空，构造不出「内容消失」）' ("iconSet=[" + ((Get-IconSet (Get-CoreLog)) -join ', ') + "]")
}

# ---------- 7. 恢复不自动切回 ----------
Section '7 内容恢复不自动切回'
if ($notifEmpty) {
  Clear-Log
  Post-Notification 'r134recover'
  Start-Sleep -Milliseconds 800
  $log = Save-Log '7a 通知恢复：仍停留 Agent 页'
  Save-Shot '62-agent-notification-recovered'
  Check '7a' '通知恢复不自动切回' (
    (MatchCount $log 'content page toggle') -eq 0 -and
    (MatchCount $log 'content page crossfade start show=notification') -eq 0
  ) '62-agent-notification-recovered.png'
} else {
  Save-Shot '62-agent-notification-recovered'
  Inconclusive '7a' '通知恢复不自动切回（前置的「兜底到 Agent」未发生）' '62-agent-notification-recovered.png'
}
Post-Notification 'r134recover'
Start-Sleep -Milliseconds 800
# 6b 的前提是「当前就在 Agent 页」：状态收口后再断 agent，否则测的是别的东西。
$onAgent = Ensure-Page 'agent'
if (-not $onAgent) { Write-Host '  warn: 未收口到 Agent 页，6b 前置不成立' }
Clear-Log
Inject-Agent-Disconnected
Start-Sleep -Milliseconds 1000
$log = Save-Log '6b Agent 消失 -> fallback 通知'
Save-Shot '61-fallback-notification'
Check '6b' 'Agent 页内容消失兜底通知页' (
  $onAgent -and
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
# 前置：退屏前先确立「手动切到了 Agent 页」，否则没有可测的前态（reset 行只在页变化时打）。
$preAgent = Ensure-Page 'agent'
$preLog = Save-Log '8 前置：退屏前手动切到 Agent 页'
Check '8-pre' '退屏前手动切到 Agent 页（重投重置的前态）' (
  $preAgent -and ((Get-Page (Get-CoreLog)) -eq 'agent')
) 'sequence.logcat / 8 前置'
Exit-Rear
Start-Sleep -Milliseconds 600
Clear-Log
Project-Rear
Start-Sleep -Milliseconds 1500
$log = Save-Log '8 EXIT_REAR -> PROJECT_REAR'
Save-Shot '70-recast-default-notification'
# 重置判定：重投后渲染/选中页 = notification，且窗口内无任何切页或兜底锚（有 reset 行更好，但没有不等于失败）。
Check '8' '退屏重投回默认通知页' (
  ((MatchCount $log 'content page reset notification') -gt 0 -or
    (MatchCount $log 'content page crossfade start show=notification') -gt 0) -and
  (MatchCount $log 'content page toggle') -eq 0 -and
  (MatchCount $log 'content page fallback') -eq 0
) '70-recast-default-notification.png'

# ---------- 9. WFA 跳 - 锁 - 回 ----------
Section '9 WFA 跳 - 锁 - 回 + agent pulse'
# `content page wfa enter <page>` 记的是**进入前的当前页**，故先收口到通知页，否则词形对不上。
Ensure-Page 'notification' | Out-Null
Start-Sleep -Milliseconds 900   # 同上：等收口点按的日志落定再清
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
Start-Sleep -Milliseconds 800
$log = Save-Log '9d WFA exit'
Save-Shot '83-wfa-return-notification'
Start-Sleep -Milliseconds 1500
$settle = Save-Log '9d settle'
# 判据：恢复锚（wfa exit <原页>）+ 收口后当前页 = 原页。不强求 crossfade 落在同一窗口——
# 真机上 PC 桥/中继会在 WFA 存续期间超时掉线，core 先把 WFA 收掉（crossfade 早于本窗口落盘），
# 随后的 idle 注入只是补一次 enter/exit，页本身仍是恢复后的原页（run 3/4 实录）。
Check '9d' 'WFA 解决回原通知页' (
  (MatchCount $log 'content page wfa exit notification') -gt 0 -and
  ((Get-Page (@($log) + @($settle))) -eq 'notification')
) '83-wfa-return-notification.png / sequence.logcat 9d settle'

# ---------- 9e. 手势边界与历史位置 ----------
Section '9e 手势边界与历史位置'
# 第 9 段的状态注入（Inject-Agent-Status）不带 reply，会把 debug 会话正文清空；
# 先补一次带正文的注入，否则 Agent 页只剩一行会话标识、没有可滚动区间、右下 ↓ 不出现，
# 9e-c 的 ↓ 点按就会落到空白区变成切页（run 3/4/5/6 连续踩同一个坑）。
Inject-Agent-Working
Start-Sleep -Milliseconds 900
# 前置页状态在清 logcat 之前取（清空后缓冲里没有页锚，Get-Page 只会回 '?'，首轮踩过这个坑）。
$onAgent = Ensure-Page 'agent'
# ↓ 浮动按钮中心的锚也在清 logcat 之前取（rear-safe-geometry 只在页面合成时落盘）。
$down = Get-DownButtonCenter (Get-CoreLog)
if (-not $down) { Write-Host '  warn: 拿不到 rear-safe-geometry 锚，9e-c 用本机实测兜底坐标'; $down = @(743, 411) }
Write-Host "  down button center = $($down[0]),$($down[1])"
if (-not $onAgent) { Write-Host '  warn: 未收口到 Agent 页，9e 前置不成立' }
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
Tap 350 470
Start-Sleep -Milliseconds 600
$log = Save-Log '9e-b Agent 页正文点按切回通知页'
Save-Shot '31-after-body-tap'
Check '9e-b' 'Agent 页正文点按切回通知页' (
  $onAgent -and
  (MatchCount $log 'rear-tap received area=content-page') -gt 0 -and
  (MatchCount $log 'content page toggle notification') -gt 0
) '31-after-body-tap.png'
Ensure-Page 'agent' | Out-Null
Start-Sleep -Milliseconds 400
Swipe 600 460 600 200 400
Start-Sleep -Milliseconds 500
Save-Shot '50-history-before'
Clear-Log
Tap $down[0] $down[1]
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
Ensure-Page 'notification' | Out-Null
Start-Sleep -Milliseconds 600
Save-Shot '53-notification-between'
Ensure-Page 'agent' | Out-Null
Start-Sleep -Milliseconds 600
Save-Shot '54-history-after-return'
Inconclusive '9e-d' '历史回看位置跨切换保持（需人工比对截图）' '50-history-before.png / 52-history-paused.png / 54-history-after-return.png'

# ---------- 10. 既有锚词回归 ----------
Section '10 既有锚词回归'
Ensure-Page 'notification' | Out-Null
$cell = Get-IconCell
if (-not $cell) { throw '拿不到 rear-icon-place 锚，无法定位首格图标' }
Write-Host "  first icon cell = $($cell[0]),$($cell[1])"
Clear-Log
Tap $cell[0] $cell[1]
Start-Sleep -Milliseconds 900
$log = Save-Log '10a rear-tap received + detail open'
Save-Shot '90-detail-open'
# 首格是哪个 App 由设备当时的通知排序决定（fixture 的 shell 与系统 android 并存），
# 故断言取同一 App 的两个锚词自洽：rear-tap received app=X + detail open X（+ 点开即销的 detail-toggle）。
$tapLine = Get-LastLine $log 'rear-tap received app='
$tappedPkg = if ($tapLine) { [regex]::Match([string]$tapLine, 'app=([\w\.]+)').Groups[1].Value } else { '' }
Check '10a' "rear-tap received / detail open（首格 app=$tappedPkg）" (
  $tappedPkg -ne '' -and
  (MatchCount $log ('detail open ' + [regex]::Escape($tappedPkg))) -gt 0 -and
  (MatchCount $log 'detail-toggle') -gt 0
) '90-detail-open.png'
Clear-Log
Tap $cell[0] $cell[1]
Start-Sleep -Milliseconds 900
$log = Save-Log '10b detail close'
Save-Shot '91-detail-close'
Check '10b' "detail close 不回归（app=$tappedPkg）" (
  $tappedPkg -ne '' -and (MatchCount $log ('detail close ' + [regex]::Escape($tappedPkg))) -gt 0
) '91-detail-close.png'
$breath = Invoke-BreathProbe 'r134highlight'
Start-Sleep -Seconds 3
$log = Save-Log '10c highlight breath'
Save-Shot '92-highlight-done'
Check '10c' 'highlight breath start/end 不回归' (
  (MatchCount $breath 'highlight breath start') -gt 0 -and
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
