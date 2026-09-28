#requires -Version 5.1
<#
  票 #133（spec 0013）背屏实机验收驱动：交叉淡入淡出 + 手势边界 + 历史位置保持。
  只驱动与采集，不改产品代码。每一步 清 logcat → 注入 → 采 logcat/截图 → 落盘到本目录。
  背屏：displayId=1，904x572，可用区 x>=296（相机带 296px 在左）。
  前置：手机已连接、APK 已 install -r、通知监听已重绑（脚本默认会重装+重绑）。
  用法：
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
$deviceTmp = '/data/local/tmp/r133'
$script:logPath = Join-Path $OutDir 'sequence.logcat'

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  $out = & $Adb -s $Script:Serial @Args
  if ($LASTEXITCODE -ne 0) { throw "adb $($Args -join ' ') failed ($LASTEXITCODE)" }
  return $out
}
function Sh { param([string]$Cmd) (Invoke-Adb shell $Cmd | Out-String).Trim() }
function Tap { param([int]$X, [int]$Y) Invoke-Adb shell input -d 1 tap $X $Y | Out-Null }
function Swipe { param([int]$X1,[int]$Y1,[int]$X2,[int]$Y2,[int]$Ms) Invoke-Adb shell input -d 1 swipe $X1 $Y1 $X2 $Y2 $Ms | Out-Null }
function Save-Log {
  $lines = (Invoke-Adb logcat -d -s RearCue)
  Add-Content -LiteralPath $script:logPath -Value ($lines -join "`n") -Encoding UTF8
  return $lines
}
function Save-Shot {
  param([string]$Name)
  Invoke-Adb shell "screencap -p -d 1 $deviceTmp/$Name.png" | Out-Null
  Invoke-Adb pull "$deviceTmp/$Name.png" (Join-Path $OutDir "$Name.png") | Out-Null
  Write-Host "  shot $Name.png"
}
function Section { param([string]$Text) Write-Host "`n== $Text"; Add-Content -LiteralPath $script:logPath -Value "`n===== $Text =====" -Encoding UTF8 }
function Matches { param($Lines,[string]$Pattern) @($Lines | Where-Object { $_ -match $Pattern }) }

# ---------- 0. 前置 ----------
Set-Content -LiteralPath $script:logPath -Value "# 票 #133 背屏实机验收 logcat（按步骤追加；每步前已 logcat -c）" -Encoding UTF8
Section '0 设备/安装前置'
$devices = (Invoke-Adb devices) -join "`n"
if ($devices -notmatch [regex]::Escape($Serial)) { throw "设备 $Serial 不在 adb devices：`n$devices" }
Write-Host (($devices -split "`n" | Where-Object { $_ -match '\tdevice' }) -join "`n")
if (-not $SkipInstall) {
  if (-not (Test-Path -LiteralPath $Apk)) { throw "APK 不存在：$Apk（先跑 gradlew :app:assembleDebug）" }
  Invoke-Adb install -r $Apk | Out-Null
  Write-Host "installed $Apk"
  Invoke-Adb shell 'cmd notification disallow_listener com.rearcue.poc/.notify.RearNotificationListener' | Out-Null
  Invoke-Adb shell 'cmd notification allow_listener com.rearcue.poc/.notify.RearNotificationListener' | Out-Null
  Write-Host 'listener rebound'
}
Invoke-Adb shell "mkdir -p $deviceTmp" | Out-Null
Invoke-Adb shell 'am start -n com.rearcue.poc/.ui.MainActivity' | Out-Null
Start-Sleep -Seconds 2

# ---------- 1. 构造内容：Agent 长会话 + 一条通知 ----------
Section '1 注入基线内容（Agent 长回复 + shell 通知）'
$reply = (@(
  'S001 票 #133 实机验收样本。'
  'S002 这一段用于确认历史回看位置。'
  'S003 上下拖动应只滚动历史，不应切换内容页。'
  'S004 会话标识行与正文点按才切回通知页。'
  'S005 右下方向键只恢复实时跟随，不切页。'
  'S006 交叉淡入淡出约 180ms，不响不震。'
  'S007 充电水位是背景层，不参与页面淡入淡出。'
  'S008 最后一条详情收起后按 core 兜底规则切页。'
  'S009 本段文本足够长以产生可滚动的历史区间。'
  'S010 END OF FIXTURE.'
) -join "`n")
Invoke-Adb shell "cmd notification post -t RearCue repro133fixture 'RearCue 133 fixture'" | Out-Null
Invoke-Adb shell 'am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.AGENT_STATE --ez connected true' | Out-Null
$replyFile = Join-Path $env:TEMP 'r133-reply.txt'
[System.IO.File]::WriteAllText($replyFile, $reply, [System.Text.UTF8Encoding]::new($false))
Invoke-Adb push $replyFile "$deviceTmp/reply.txt" | Out-Null
Invoke-Adb shell "reply=`$(cat $deviceTmp/reply.txt); am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.AGENT_STATE --es status working --es workspace RearCue-0013 --es reply `"\`$reply`"" | Out-Null
Start-Sleep -Seconds 3
Save-Log | Out-Null
Save-Shot '10-baseline'
Write-Host (Sh "dumpsys activity activities | grep -A4 'Display #1' | grep -E 'Task\{|topResumedActivity' | head -4")

# ---------- 2. 通知页空白点按 → Agent 页（交叉淡入） ----------
Section '2 通知页空白点按 → Agent 页（交叉淡入）'
Invoke-Adb logcat -c | Out-Null
Tap 350 470
Start-Sleep -Milliseconds 60
Save-Shot '20-crossfade-mid'
Start-Sleep -Milliseconds 400
Save-Shot '21-agent-after'
$log = Save-Log
$log | Where-Object { $_ -match 'rear-tap received|content page' }

# ---------- 3. Agent 页：拖动不切页、点正文才切页 ----------
Section '3 Agent 页：拖动不切页、点正文才切页'
Invoke-Adb logcat -c | Out-Null
Swipe 600 460 600 200 400
Start-Sleep -Milliseconds 500
$dragLog = Save-Log
$dragToggles = Matches $dragLog 'rear-tap received|content page toggle'
Write-Host ("  drag toggles={0}" -f $dragToggles.Count)
Save-Shot '30-agent-scrolled'
Tap 600 300
Start-Sleep -Milliseconds 500
$tapLog = Save-Log
$tapToggles = Matches $tapLog 'content page toggle'
Write-Host ("  body-tap toggles={0}" -f $tapToggles.Count)
Save-Shot '31-after-body-tap'
$tapLog | Where-Object { $_ -match 'rear-tap received|content page|agent-mirror' }

# ---------- 4. ↓ 只恢复跟随 ----------
Section '4 Agent 页 ↓ 按钮：只恢复跟随'
Tap 350 470
Start-Sleep -Milliseconds 500
Swipe 600 460 600 200 400
Start-Sleep -Milliseconds 500
Invoke-Adb logcat -c | Out-Null
Save-Shot '40-before-down'
Tap 856 524
Start-Sleep -Milliseconds 600
$downLog = Save-Log
Write-Host ("  ↓ toggles={0}" -f (Matches $downLog 'content page toggle').Count)
Save-Shot '41-after-down'
$downLog | Where-Object { $_ -match 'content page|agent-mirror' }

# ---------- 5. 历史回看位置跨切换保持 ----------
Section '5 历史回看位置跨切换保持'
Swipe 600 460 600 200 400
Start-Sleep -Milliseconds 600
Save-Shot '50-history-before'
Tap 350 470
Start-Sleep -Milliseconds 600
Save-Shot '51-notification'
Tap 350 470
Start-Sleep -Milliseconds 600
Save-Shot '52-history-after'
Save-Log | Out-Null

# ---------- 6. 通知图标 / Detail 路由不回归 ----------
Section '6 通知页图标与详情卡路由'
Invoke-Adb logcat -c | Out-Null
Save-Shot '60-notification-page'
Tap 600 286
Start-Sleep -Milliseconds 700
Save-Shot '61-detail-open'
$detailOpenLog = Save-Log
$detailOpenLog | Where-Object { $_ -match 'detail open|rear-tap received|content page' }
Tap 600 286
Start-Sleep -Milliseconds 900
Save-Shot '62-detail-closed'
$detailCloseLog = Save-Log
$detailCloseLog | Where-Object { $_ -match 'detail close|rear-tap received|content page' }

# ---------- 7. 收尾 ----------
Section '7 收尾'
Invoke-Adb shell "rm -rf $deviceTmp" | Out-Null
Save-Log | Out-Null
Write-Host "evidence -> $OutDir"
