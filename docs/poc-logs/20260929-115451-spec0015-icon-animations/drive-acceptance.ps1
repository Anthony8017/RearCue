#requires -Version 5.1
<#
  spec 0015 / 票 #150 实机验收驱动：图标入场/退场/补位/档位重排 + 退屏宽限 + 既有锚回归。

  只驱动与采集，不改产品代码。每一步：清 logcat → 动作 → 采 logcat/截图/录屏 → 落盘到本目录。
  背屏：displayId=1，904x572，可用区 x>=296（相机带 296px 在左；相机带避让在架构上由
  rear-safe-geometry/placeNotificationBlock 保证，本脚本断言 block.left >= 296）。
  背屏截图/录屏都要 SurfaceFlinger 的 display id（`dumpsys display` 里 displayId=1 的 uniqueId 数字），
  逻辑 displayId=1 对 screencap 无效（"Display Id '1' is not valid"，spec0013 同坑）。

  两类腿，报告里逐场景标注：
    * 真实腿：`cmd notification post`（包名恒 com.android.shell）/ `POST_TEST`（com.rearcue.poc）——
      走 NLS → 可见性路由 → 仓库 → core 全链；
    * 注入腿：debug 动作 FIXTURE_NOTIF（--es mode post|remove --es pkgs a,b,c）——挂在
      AppContainer.debugInjectFixturePosted/Removed（可见性路由入口，等价 NLS 回调到 gate 的那一段），
      用来造本机造不出的 4~7 枚多应用档位。注意（票 #150 发现）：注入项不在 SystemUI 在册集合里，
      下一次成功的 Shade-visible 探测会把它隐藏（≤15s 的天然寿命），所以每个注入步骤都在
      15 秒内完成断言；脚本每次注入前先 remove 再 post（hiddenKeys 生效后同 key 需先移除才能复活）。

  用法：
    powershell -ExecutionPolicy Bypass -File drive-acceptance.ps1                 # 全链（假定已装好本分支 APK）
    powershell -ExecutionPolicy Bypass -File drive-acceptance.ps1 -Install        # 先装 APK（含 MIUI USB 弹窗轮询点按）
    powershell -ExecutionPolicy Bypass -File drive-acceptance.ps1 -Only 1,2,6     # 只跑指定步骤（开发/复跑用）
    powershell -ExecutionPolicy Bypass -File drive-acceptance.ps1 -GraceOnly      # 只跑「最后一条 + 退屏宽限」腿
                                                                                  #（需要 Icon Set 能清空：机主真实通知不得清理）
#>
[CmdletBinding()]
param(
  [string]$Serial = '94250f9e',
  [string]$Adb = 'C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe',
  [string]$Apk = 'C:\Users\13691\.codex\worktrees\rearcue-0015-150\RearCue\app\build\outputs\apk\debug\app-debug.apk',
  [string]$OutDir = '',
  [string]$Ffmpeg = 'ffmpeg',
  [string]$Ffprobe = 'ffprobe',
  [string[]]$Only = @(),
  [switch]$GraceOnly,
  [switch]$Install
)

$ErrorActionPreference = 'Stop'
if (-not $OutDir) { $OutDir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path } }
# `powershell -File` 传数组时只给一个字符串（`-Only 2,3` ⇒ @("2,3")），这里摊平逗号。
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ -ne '' })
$deviceTmp = '/data/local/tmp/r150'
$receiver = 'com.rearcue.poc/.DebugCommandReceiver'
$actionProject = 'com.rearcue.poc.action.PROJECT_REAR'
$actionExit = 'com.rearcue.poc.action.EXIT_REAR'
$actionState = 'com.rearcue.poc.action.STATE'
$actionPostTest = 'com.rearcue.poc.action.POST_TEST'
$actionCancelTest = 'com.rearcue.poc.action.CANCEL_TEST'
$actionCancelPackage = 'com.rearcue.poc.action.CANCEL_PACKAGE'
$actionCharging = 'com.rearcue.poc.action.CHARGING_ENABLED'
$actionPostureGate = 'com.rearcue.poc.action.POSTURE_GATE'
$actionSessionLock = 'com.rearcue.poc.action.SESSION_LOCK'
$actionFixture = 'com.rearcue.poc.action.FIXTURE_NOTIF'
$actionAgentState = 'com.rearcue.poc.action.AGENT_STATE'
$shellPkg = 'com.android.shell'
$testPkg = 'com.rearcue.poc'
# fixture 包名首字母互不相同（背屏解析不到图标时退化画包名首字母，字母不同才看得清哪一枚在动）。
$fixtures = @(
  'alpha.rearcue.fixture', 'bravo.rearcue.fixture', 'charlie.rearcue.fixture',
  'delta.rearcue.fixture', 'echo.rearcue.fixture', 'foxtrot.rearcue.fixture', 'golf.rearcue.fixture'
)
$breathProbePkg = 'bravo.rearcue.fixture'
$script:logPath = Join-Path $OutDir 'sequence.logcat'
$script:summaryPath = Join-Path $OutDir 'acceptance-summary.txt'
$script:shotsDir = Join-Path $OutDir 'shots'
$script:videoDir = Join-Path $OutDir 'video'
$script:framesDir = Join-Path $OutDir 'frames'
$script:checks = @()
$script:recordStarted = @{}
$script:apkHash = ''
$script:rearSfId = ''
$script:baseSet = @()
$script:currentSet = @()

foreach ($d in @($script:shotsDir, $script:videoDir, $script:framesDir)) {
  if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
}

function Invoke-Adb {
  # `Invoke-Adb @('a','b')` 的数组实参会被 PowerShell 压成一个字符串（spec0013 坑位），先摊平。
  param([Parameter(ValueFromRemainingArguments = $true)][object[]]$Args)
  $flat = @()
  foreach ($a in $Args) { if ($a -is [System.Array]) { $flat += $a } else { $flat += $a } }
  $out = & $Adb -s $Script:Serial @flat
  if ($LASTEXITCODE -ne 0) { throw "adb $($flat -join ' ') failed ($LASTEXITCODE)" }
  return $out
}
function Sh { param([string]$Cmd) (Invoke-Adb shell $Cmd | Out-String).Trim() }
function Clear-Log { Invoke-Adb @('logcat', '-c') | Out-Null }
function Get-Log { return @(Invoke-Adb @('logcat', '-d', '-s', 'RearCue')) }
function Has { param($Lines, [string]$Pattern) (@($Lines | Where-Object { $_ -match $Pattern }).Count -gt 0) }
function Count-Match { param($Lines, [string]$Pattern) @($Lines | Where-Object { $_ -match $Pattern }).Count }
function Last-Match { param($Lines, [string]$Pattern) $m = @($Lines | Where-Object { $_ -match $Pattern }); if ($m.Count -eq 0) { return $null }; return [string]$m[$m.Count - 1] }
function Install-MyApk {
  # 装本票的 debug APK（含 MIUI「USB安装提示」轮询点按）+ 重授权限 + 重绑监听。
  # 本机 2026-09-29 实测：同签名覆盖安装（`-r`）时 MIUI 通常不再弹框，但保留轮询兜底。
  param([int]$DialogTimeoutMs = 30000)
  if (-not (Test-Path -LiteralPath $Apk)) { throw "APK 不存在：$Apk" }
  $out = Join-Path $OutDir 'install.out'
  $err = Join-Path $OutDir 'install.err'
  $proc = Start-Process -FilePath $Adb -ArgumentList @('-s', $Serial, 'install', '-r', '-t', $Apk) -PassThru -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err
  $tapped = $false
  $deadline = (Get-Date).AddMilliseconds($DialogTimeoutMs)
  while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    if (-not $tapped) {
      # grep -c 在没有匹配时返回 1，会让 Invoke-Adb 抛错：|| true 保证退出码总是 0。
      $w = Sh 'dumpsys window windows | grep -c com.miui.securitycenter || true'
      if ([int]$w -gt 0) { Invoke-Adb shell 'input tap 351 2414' | Out-Null; $tapped = $true }
    }
  }
  $proc.WaitForExit(20000) | Out-Null
  $text = ((Get-Content -LiteralPath $out -Raw -ErrorAction SilentlyContinue) + (Get-Content -LiteralPath $err -Raw -ErrorAction SilentlyContinue))
  Note ("install: " + ($text -replace "`r?`n", ' | ') + " usbDialogTapped=$tapped")
  if ($text -notmatch 'Success') { throw "adb install 失败：$text" }
  Invoke-Adb shell "pm grant $testPkg android.permission.POST_NOTIFICATIONS" | Out-Null
  Invoke-Adb shell "appops set $testPkg 10020 allow" | Out-Null
  Invoke-Adb shell "appops set $testPkg 10008 allow" | Out-Null
  Invoke-Adb shell "appops set $testPkg 10021 allow" | Out-Null
  Invoke-Adb shell "cmd notification allow_listener $testPkg/.notify.RearNotificationListener" | Out-Null
  Start-Sleep -Seconds 2
  return $true
}
function Test-FixtureActionSupported {
  # 别的自动化可能在本机覆盖安装自己的 APK（票 #150 实录：跑一半被换了包，注入动作变成「未知调试动作」）。
  # 每个步骤开跑前嗅探一次：不支持就重装回本票的 APK。
  Clear-Log
  Broadcast-Action $actionFixture '--es mode remove --es pkgs probe.rearcue.fixture'
  Start-Sleep -Milliseconds 700
  $lines = Get-Log
  return (Has $lines 'debug fixture remove')
}
function Keep-Awake {
  # 主屏熄屏（Dozing）会让 MIUI GreezeManager 冻结本应用、并把背屏 Dashboard 撤下
  #（2026-09-29 实测：熄屏几分钟后 cgroup.freeze=1、Display #1 空、后续全部无日志）。
  # KEYCODE_WAKEUP 只唤醒屏幕、不点任何东西；脚本另有后台 job 定时补一发。
  Invoke-Adb shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
}
function Ensure-AppHealthy {
  # 每个步骤开跑前把「机主可见的前提」摆正：屏幕唤醒 → 本票 APK 还在 → 进程未冻结 → Dashboard 在屏。
  Keep-Awake
  if (-not (Test-FixtureActionSupported)) {
    Note '注入动作不可用（APK 被别的自动化覆盖安装？）→ 重装本票 APK'
    Install-MyApk | Out-Null
  }
  $pidText = (Sh 'pidof com.rearcue.poc').Trim()
  $frozen = ''
  if ($pidText) { $frozen = (Sh "cat /sys/fs/cgroup/apps/uid_*/pid_$pidText/cgroup.freeze 2>/dev/null").Trim() }
  if ((-not $pidText) -or ($frozen -match '1')) {
    Note "应用进程未活/被冻结（pid=$pidText freeze=$frozen）→ 唤醒并拉回前台"
    Invoke-Adb shell "am start -n $testPkg/.ThawNudgeActivity" | Out-Null
    Start-Sleep -Milliseconds 800
    Invoke-Adb shell "am start -n $testPkg/.ui.MainActivity" | Out-Null
    Start-Sleep -Seconds 3
  }
  $stateLines = Read-State
  if (-not (Has $stateLines 'projected=true')) {
    Note '背屏 Dashboard 不在屏 → 重新投送'
    Broadcast-Action $actionProject
    Start-Sleep -Seconds 3
  }
}
function Fixture-Arm {
  # 把 fixture 集装上屏，并**等到 icon enter 锚落地**才算 arm 成功：这一锚说明渲染层这一帧真在跑、
  # 退场账本（IconSetExitLedger）的基线已经把这批条目记进去——否则紧接着的 remove 可能什么锚都不打
  #（票 #150 跑第一轮时踩到：上一帧基线还没建立就 remove，icon exit 缺失）。
  param([string[]]$Pkgs, [string[]]$WaitFor = @(), [int]$TimeoutSec = 8)
  $waitPkgs = if ($WaitFor.Count -gt 0) { $WaitFor } else { $Pkgs }
  Fixture-Remove ($fixtures -join ',')
  Start-Sleep -Milliseconds 250
  Clear-Log
  Fixture-Post ($Pkgs -join ',')
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 350
    $lines = Get-Log
    $missing = @($waitPkgs | Where-Object { -not (Has $lines ('icon enter ' + [regex]::Escape($_))) })
    if ($missing.Count -eq 0) { return $lines }
  }
  return $null
}
function Wait-FreshProbeWindow {
  # 注入腿的寿命上限 = 下一次成功的 Shade-visible 探测（注入项不在 SystemUI 在册集合里）。
  # 周期性探测约 15s 一次：先清 logcat 等到刚落地一次 periodic，再动作，就有一段干净的 ~15s 窗。
  param([int]$TimeoutSec = 25)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    Clear-Log
    Broadcast-Action $actionState
    Start-Sleep -Milliseconds 600
    $lines = Get-Log
    if (Has $lines 'shade-visible probe reason=periodic') { return $true }
    Start-Sleep -Milliseconds 900
  }
  Note '没等到 periodic 探测（超时）——继续照跑，但注入项可能在步骤中途被隐藏'
  return $false
}
function Section {
  param([string]$Text)
  Write-Host "`n== $Text"
  Add-Content -LiteralPath $script:logPath -Value "`n===== $Text =====" -Encoding UTF8
  Ensure-AppHealthy
}
function Save-Log {
  param([string]$Label = 'logcat')
  $lines = Get-Log
  Add-Content -LiteralPath $script:logPath -Value "`n--- $Label ---" -Encoding UTF8
  if ($lines.Count -gt 0) { Add-Content -LiteralPath $script:logPath -Value ($lines -join "`n") -Encoding UTF8 }
  return $lines
}
function Save-Shot {
  # 背屏（或指定 display）截图。screencap 只认 SurfaceFlinger display id（spec0013 同坑）。
  param([string]$Name, [string]$DisplayId = '')
  $out = Join-Path $script:shotsDir "$Name.png"
  if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Force }
  $d = if ($DisplayId) { $DisplayId } else { $script:rearSfId }
  cmd /c "`"$Adb`" -s $Serial exec-out screencap -d $d -p > `"$out`"" | Out-Null
  if (-not (Test-Path -LiteralPath $out)) { throw "截图失败：$Name.png（display=$d）" }
  Write-Host "  shot $Name.png"
}
function Save-MainShot {
  param([string]$Name)
  $out = Join-Path $script:shotsDir "$Name.png"
  if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Force }
  cmd /c "`"$Adb`" -s $Serial exec-out screencap -p > `"$out`"" | Out-Null
  Write-Host "  shot $Name.png"
}
function Start-Record {
  # 设备侧后台录屏（screenrecord --display-id 认物理 display id；本机背屏 904x572@120Hz）。
  param([string]$Name, [int]$Seconds = 5, [switch]$MainDisplay)
  $disp = if ($MainDisplay) { '' } else { " --display-id $script:rearSfId" }
  Invoke-Adb shell "screenrecord$disp --bit-rate 6000000 --time-limit $Seconds $deviceTmp/$Name.mp4 >/dev/null 2>&1 &" | Out-Null
  $script:recordStarted[$Name] = Get-Date
  Write-Host "  rec $Name.mp4 (${Seconds}s$disp)"
}
function Stop-Record {
  # 等录屏自然结束 → pull → 抽帧 + 接触表 + 逐帧时间戳。
  #   contact-sheet.jpg：整段 4fps tile 5x3（看过程）
  #   fNNN.jpg：动作窗 [From, From+Window) 的 15fps 逐帧（看 0.25s 级动效；15fps ⇒ 约 67ms/帧）
  #   frames-timing.txt：原片逐帧时间戳（本机背屏录制约 120fps，用来说明抽帧的实际间隔口径）
  param([string]$Name, [int]$Seconds = 5, [double]$From = 0.4, [double]$Window = 1.8, [int]$ScaleWidth = 678)
  $started = $script:recordStarted[$Name]
  if ($started) {
    $elapsed = ((Get-Date) - $started).TotalSeconds
    if ($elapsed -lt ($Seconds + 1.2)) { Start-Sleep -Milliseconds ([int](($Seconds + 1.2 - $elapsed) * 1000)) }
  }
  $remote = "$deviceTmp/$Name.mp4"
  $local = Join-Path $script:videoDir "$Name.mp4"
  if (Test-Path -LiteralPath $local) { Remove-Item -LiteralPath $local -Force }
  Invoke-Adb @('pull', $remote, $local) | Out-Null
  $dir = Join-Path $script:framesDir $Name
  if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  & $Ffmpeg -y -loglevel error -i $local -vf 'fps=4,scale=452:-2,tile=5x3' -frames:v 1 (Join-Path $dir 'contact-sheet.jpg') | Out-Null
  & $Ffmpeg -y -loglevel error -ss $From -t $Window -i $local -vf "fps=15,scale=${ScaleWidth}:-2" -q:v 5 (Join-Path $dir 'f%03d.jpg') | Out-Null
  & $Ffprobe -v error -select_streams v -show_entries frame=pts_time -of csv=p=0 $local |
    Where-Object { $_ -match '^\d' } | ForEach-Object { [double]$_ } |
    ForEach-Object -Begin { $prev = $null; $i = 0 } -Process {
      $i++
      $delta = if ($null -eq $prev) { 0 } else { ($_ - $prev) * 1000 }
      "{0:D3} t={1:F3}s delta={2:F1}ms" -f $i, $_, $delta
      $prev = $_
    } | Set-Content -LiteralPath (Join-Path $dir 'frames-timing.txt') -Encoding UTF8
  Write-Host "  frames -> frames/$Name/（含 contact-sheet.jpg 与 frames-timing.txt）"
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
function Note { param([string]$Text) Write-Host "  $Text"; Add-Content -LiteralPath $script:logPath -Value "  # $Text" -Encoding UTF8 }
function Test-Step {
  param([string]$Id)
  if ($GraceOnly) { return ($Id -in @('0', '10', '11', '90')) }
  if ($Only.Count -gt 0) { return ($Id -in $Only -or $Id -eq '0' -or $Id -eq '90') }
  return $true
}
function Broadcast-Action { param([string]$Action, [string]$Extra = '') $cmd = "am broadcast -n $receiver -a $Action"; if ($Extra) { $cmd += " $Extra" }; Invoke-Adb shell $cmd | Out-Null }
function Post-ShellNotification {
  # 坑（票 #150 实测）：`cmd notification post` 的语法是 `[flags] <tag> <text>`，`-t` 是**标题**不是 tag。
  # tag 是 position 参数，且会进 notification key（`0|com.android.shell|2020|<tag>|2000`）——
  # tag 里一旦有空格，SystemUI NotifCollection dump 的 `[i] <key>` 行会被 App 的解析器按 `\S+` 截断，
  # 于是「真实通知」被误判成「不在下拉栏里」，发完约 300ms 就被可见性链自己删掉（图标闪一下就没）。
  # 故：tag 必须是**无空白**单词；正文放最后一个带引号的参数。
  param([string]$Tag, [string]$Body = 'RearCue spec0015 acceptance')
  Invoke-Adb shell "cmd notification post -t RearCue $Tag '$Body'" | Out-Null
}
function Cancel-ShellNotifications { Broadcast-Action $actionCancelPackage "--es pkg $shellPkg" }
function Fixture-Post {
  param([string]$Pkgs, [string]$Key = '0', [string]$Text = '')
  $extra = "--es mode post --es pkgs $Pkgs --es key $Key"
  if ($Text) { $extra += " --es text $Text" }
  Broadcast-Action $actionFixture $extra
}
function Fixture-Remove {
  param([string]$Pkgs, [string]$Key = '0')
  Broadcast-Action $actionFixture "--es mode remove --es pkgs $Pkgs --es key $Key"
}
function Fixture-Reset {
  # 先移除全部 fixture（清 hiddenKeys + 残留），再按需重投：注入项被探测隐藏过之后，同 key 必须
  # 先 onRemoved 再 onPosted 才会重新放行（票 #150 实测）。
  param([string[]]$Keep = @(), [string]$Text = '')
  Fixture-Remove ($fixtures -join ',')
  Start-Sleep -Milliseconds 300
  if ($Keep.Count -gt 0) { Fixture-Post ($Keep -join ',') ; Start-Sleep -Milliseconds 400 }
}
function Get-IconSetFromState {
  # 取 **第一个** `iconSet=[...]`：那是 AppState 顶层的 live 投影（顺序 = core 的时间倒序投影）。
  # 行尾 rear=RearBackendState(... iconSet=[...]) 是后端镜像，只在集合**变化**时随 UpdateIconSet 更新，
  # 同 App 新通知只挪位不改集合时它还是旧顺序（票 #150 实测：拿它判「挪到首位」会假 FAIL）。
  param($Lines)
  $line = Last-Match $Lines 'state AppState\('
  if (-not $line) { return @() }
  $m = [regex]::Match($line, 'iconSet=\[([^\[\]]*)\]')
  if (-not $m.Success) { return @() }
  $inner = $m.Groups[1].Value.Trim()
  if ($inner -eq '') { return @() }
  return @($inner -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
}
function Read-State {
  Clear-Log
  Broadcast-Action $actionState
  Start-Sleep -Milliseconds 900
  return Get-Log
}
function Line-Time {
  param([string]$Line)
  $m = [regex]::Match($Line, '^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d\d\d)')
  if (-not $m.Success) { return $null }
  return [datetime]::ParseExact("2026-$($m.Groups[1].Value) $($m.Groups[2].Value)", 'yyyy-MM-dd HH:mm:ss.fff', $null)
}
function Span-Ms {
  param($Lines, [string]$Pattern)
  $times = @($Lines | Where-Object { $_ -match $Pattern } | ForEach-Object { Line-Time ([string]$_) } | Where-Object { $_ })
  if ($times.Count -lt 2) { return 0 }
  $min = ($times | Measure-Object -Minimum).Minimum
  $max = ($times | Measure-Object -Maximum).Maximum
  return [int](($max - $min).TotalMilliseconds)
}
function Wait-Breath {
  # 呼吸带 30s 冷却（spec 0008）：连发同 key 内容更新探针，哪一发真呼吸了就用那一发。
  # 探针是 fixture（注入腿），只用来把呼吸打开，呼吸窗内的主证据另用真实通知腿。
  param([int]$MaxTries = 8)
  for ($i = 0; $i -lt $MaxTries; $i++) {
    Clear-Log
    Fixture-Post $breathProbePkg -Text "probe=$i"
    Start-Sleep -Milliseconds 1200
    $lines = Get-Log
    if (Has $lines 'highlight breath start') {
      Write-Host "  breath probe hit (try $i)"
      return $lines
    }
    Write-Host "  breath probe suppressed (cooldown) -> retry in 5s"
    Start-Sleep -Seconds 5
  }
  return @()
}
function Ensure-RearProjected {
  $lines = Read-State
  if (-not (Has $lines 'projected=true')) {
    Broadcast-Action $actionProject
    Start-Sleep -Seconds 2
  }
}
function Tap { param([int]$X, [int]$Y) Invoke-Adb @('shell', 'input', '-d', '1', 'tap', "$X", "$Y") | Out-Null }
function Get-IconCell {
  # 首格图标中心：从 rear-icon-place 锚现算（spec0013 同口径：overhang 取一半外扩）。
  # 仅在网格模式（>=2 条通知）且第一行满 3 列时指向左上那一枚；1 枚/2 枚时行居中，
  # 但本脚本只在计数 >= 2 时点（step 13 的 fixture 是新到的一枚 + 机主既有集合）。
  param([int]$Tries = 3)
  for ($i = 0; $i -lt $Tries; $i++) {
    $line = Last-Match (Get-Log) 'rear-icon-place size=\d+ overhang=\d+ block=PxRect\('
    if ($line) {
      $m = [regex]::Match($line, 'size=(\d+) overhang=(\d+) block=PxRect\(left=(-?\d+), top=(-?\d+), right=(-?\d+), bottom=(-?\d+)\)')
      if ($m.Success) {
        $size = [int]$m.Groups[1].Value
        $half = [int]([int]$m.Groups[2].Value / 2)
        $left = [int]$m.Groups[3].Value
        $top = [int]$m.Groups[4].Value
        return @(([int]($left + $half + $size / 2)), ([int]($top + $half + $size / 2)))
      }
    }
    Fixture-Post $fixtures[0]
    Start-Sleep -Milliseconds 900
  }
  return $null
}
# ============================ 执行 ============================

# 追加（不覆盖）：本机 2026-09-29 有其它票（#153/#154）在同一台真机上跑自己的验收，
# 会覆盖安装各自的 APK、中途把应用重启；跑一半被换包时本脚本靠 Ensure-AppHealthy 重新装回本票 APK，
# 失败/中断后的重跑也因此保留历次痕迹（README 里写明哪一轮是完整轮）。
Add-Content -LiteralPath $script:logPath -Value ("`n########## run " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + " (Only=" + ($Only -join ',') + ", GraceOnly=" + [bool]$GraceOnly + ") ##########") -Encoding UTF8
if (-not (Test-Path -LiteralPath $script:logPath)) { Set-Content -LiteralPath $script:logPath -Value '# spec 0015 / 票 #150 实机验收 logcat（按步骤追加；每步前已 logcat -c）' -Encoding UTF8 }
Add-Content -LiteralPath $script:summaryPath -Value ("`n# run " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + " (Only=" + ($Only -join ',') + ")") -Encoding UTF8

# 后台保活：主屏熄屏（Dozing）会冻结本应用、撤下背屏 Dashboard（见 Keep-Awake 注释）。
# 每 12 秒补一发 KEYCODE_WAKEUP（纯唤醒键，不点任何 UI），收尾时停掉。
$script:keepAwakeJob = Start-Job -ScriptBlock {
  param($AdbPath, $SerialNumber)
  while ($true) {
    & $AdbPath -s $SerialNumber shell 'input keyevent KEYCODE_WAKEUP' 2>$null | Out-Null
    Start-Sleep -Seconds 12
  }
} -ArgumentList $Adb, $Serial
# ---------- 0. 前置 ----------
if (Test-Step '0') {
  Section '0 前置：设备 / 背屏 display id / 授权 / 基线'
  $devices = (Invoke-Adb devices) -join "`n"
  if ($devices -notmatch [regex]::Escape($Serial)) { throw "设备 $Serial 不在 adb devices：`n$devices" }
  Check '0a' '设备在线' $true $Serial
  $dispDump = Sh 'dumpsys display'
  $sfMatch = [regex]::Match($dispDump, 'displayId\s+1,[^\r\n]*?uniqueId\s+"local:(\d+)"')
  $script:rearSfId = if ($sfMatch.Success) { $sfMatch.Groups[1].Value } else { '4630946949513469332' }
  Note "rear screencap/record display id = $script:rearSfId（逻辑 displayId=1 对 screencap 无效）"
  $listeners = (Sh 'settings get secure enabled_notification_listeners')
  Check '0b' '通知使用权已授权' ($listeners -match 'com.rearcue.poc') 'settings get secure enabled_notification_listeners'
  if ($Install) {
    if (-not (Test-Path -LiteralPath $Apk)) { throw "APK 不存在：$Apk" }
    $script:apkHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
    Note "install $Apk sha256=$script:apkHash"
    $proc = Start-Process -FilePath $Adb -ArgumentList @('-s', $Serial, 'install', '-r', '-t', $Apk) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $OutDir 'install.out') -RedirectStandardError (Join-Path $OutDir 'install.err')
    $tapped = $false
    for ($i = 0; $i -lt 40; $i++) {
      Start-Sleep -Milliseconds 400
      $win = (Sh 'dumpsys window windows | grep -c com.miui.securitycenter || true')
      if ([int]$win -gt 0) { Invoke-Adb shell 'input tap 351 2414' | Out-Null; $tapped = $true; break }
    }
    $proc.WaitForExit()
    $installOut = (Get-Content -LiteralPath (Join-Path $OutDir 'install.out') -Raw -ErrorAction SilentlyContinue) + (Get-Content -LiteralPath (Join-Path $OutDir 'install.err') -Raw -ErrorAction SilentlyContinue)
    Check '0c' 'APK 安装成功' ($installOut -match 'Success') "usbDialogTapped=$tapped / install.out"
    Invoke-Adb shell "pm grant $testPkg android.permission.POST_NOTIFICATIONS" | Out-Null
    Invoke-Adb shell "appops set $testPkg 10020 allow" | Out-Null
    Invoke-Adb shell "appops set $testPkg 10008 allow" | Out-Null
    Invoke-Adb shell "appops set $testPkg 10021 allow" | Out-Null
    Invoke-Adb shell "cmd notification allow_listener $testPkg/.notify.RearNotificationListener" | Out-Null
    Start-Sleep -Seconds 1
  } elseif (Test-Path -LiteralPath $Apk) {
    $script:apkHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
    Note "未安装（-Install 才装）；本地 APK sha256=$script:apkHash"
  }
  Invoke-Adb @('shell', 'mkdir', '-p', $deviceTmp) | Out-Null
  Start-Sleep -Milliseconds 300
  # 只清本脚本自己发的 shell / 测试通知；机主真实应用的通知一律不碰（票 #150 纪律）。
  Cancel-ShellNotifications
  Broadcast-Action $actionCancelTest
  Start-Sleep -Milliseconds 800
  Broadcast-Action $actionPostureGate '--ez enabled false'
  Broadcast-Action $actionSessionLock '--es sessionId auto'
  Invoke-Adb shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
  Ensure-RearProjected
  $stateLines = Read-State
  $script:baseSet = Get-IconSetFromState $stateLines
  $script:currentSet = $script:baseSet
  $realBase = @($script:baseSet | Where-Object { $_ -notlike '*.rearcue.fixture' })
  Note ("base iconSet ($($script:baseSet.Count)) = " + ($script:baseSet -join ', ') + "；其中非 fixture = $($realBase.Count)")
  Check '0d' '背屏 Dashboard 在屏（projected=true）' (Has $stateLines 'projected=true') 'state AppState(...) rear=RearBackendState(projected=true'
  $probeLine = Last-Match $stateLines 'shade-visible probe reason='
  Note ("可见性口径：" + $(if ($probeLine) { $probeLine } else { '(无 probe 行)' }))

}

# ---------- 1. 入场（图标＋角标）+ 3 秒呼吸高亮叠加（真实通知腿） ----------
  if (Test-Step '1') {
    Section '1 入场（图标＋角标）+ 3 秒呼吸叠加（真实通知腿）'
    $breath = Wait-Breath
    if ($breath.Count -eq 0) { Note '呼吸探针 8 次都没打到（冷却窗异常）—— 入场腿照跑，叠加改判 INCONCLUSIVE' }
    Start-Record '01-enter-real' 5
    Start-Sleep -Milliseconds 300
    Post-ShellNotification 'r150enter' 'RearCue spec0015 enter (real leg)'
    Start-Sleep -Seconds 4
    Stop-Record '01-enter-real' 5
    $log = Save-Log '1 入场（真实通知腿）'
    Save-Shot '10-enter-real'
    $enterLine = Last-Match $log ('icon enter ' + [regex]::Escape($shellPkg))
    $breathStart = Last-Match $log 'highlight breath start'
    $breathEnd = Last-Match $log 'highlight breath end'
    Check '1a' '入场触发 icon enter（真实通知腿）' ($null -ne $enterLine) '10-enter-real.png / frames/01-enter-real'
    if ($breathStart -and $breathEnd -and $enterLine) {
      $ts = Line-Time $breathStart; $te = Line-Time $enterLine; $tx = Line-Time $breathEnd
      $overlap = ($te -ge $ts) -and ($te -le $tx)
      Check '1b' '入场动效与 3 秒呼吸叠加（enter 落在 breath start..end 之间）' $overlap ("breath $($ts.ToString('HH:mm:ss.fff')) -> $($tx.ToString('HH:mm:ss.fff'))；enter $($te.ToString('HH:mm:ss.fff'))")
    } else {
      Inconclusive '1b' '入场动效与 3 秒呼吸叠加（本轮没有可用的呼吸窗）' 'sequence.logcat / 1 段'
    }
    $badgeShot = Join-Path $script:shotsDir '10-enter-real.png'
    Check '1c' '入场角标随图标一起出现（截图留证，观感需目检）' (Test-Path -LiteralPath $badgeShot) '10-enter-real.png'
  }

  # ---------- 2. 多枚同时入场（注入腿） ----------
if (Test-Step '2') {
  Section '2 多枚同时入场（注入腿）'
  Wait-FreshProbeWindow | Out-Null
  Fixture-Remove ($fixtures -join ',')
  Start-Sleep -Milliseconds 300
  Start-Record '02-multi-enter' 6
  Start-Sleep -Milliseconds 700
  Clear-Log
  Fixture-Post (($fixtures[0..2]) -join ',')
  Start-Sleep -Seconds 3
  Stop-Record '02-multi-enter' 6
  $log = Save-Log '2 多枚同时入场（注入腿）'
  Save-Shot '20-multi-enter'
  $ok = (Has $log ('icon enter ' + [regex]::Escape($fixtures[0]))) -and (Has $log ('icon enter ' + [regex]::Escape($fixtures[1]))) -and (Has $log ('icon enter ' + [regex]::Escape($fixtures[2])))
  $span = Span-Ms $log 'icon enter '
  Check '2' '三枚同时入场：3 条 icon enter 且跨度 <=300ms' ($ok -and ($span -le 300)) ("span=${span}ms / 20-multi-enter.png / frames/02-multi-enter")
}

# ---------- 3. 补位滑动（注入腿） ----------
if (Test-Step '3') {
  Section '3 补位滑动（注入腿）'
  # 容量前提（票 #150 实测 + IconSetMotion.presentedOrder 的容量规则）：退场条目占格**不占容量**，
  # 只有「本帧剩余条目 < 6」时它才留得下格位、才演得出来。所以先把总数补到 6 枚再撤一枚
  #（撤完剩 5 枚，退场那枚继续占格，其余 5 枚滑动补位）。
  $stateLines = Read-State
  $currentSet = Get-IconSetFromState $stateLines
  $nonFixture = @($currentSet | Where-Object { $_ -notlike '*.rearcue.fixture' }).Count
  $need = 6 - $nonFixture
  if ($need -ge 1) {
    $armPkgs = @($fixtures[0..($need - 1)])
    $armed = $null
    for ($try = 1; $try -le 3 -and -not $armed; $try++) {
      Wait-FreshProbeWindow | Out-Null
      $armed = Fixture-Arm -Pkgs $armPkgs
      if (-not $armed) { Note "Fixture-Arm 第 $try 次没等到全部 icon enter（$($armPkgs -join ',')）" }
    }
    if ($armed) {
      Start-Record '03-reflow' 6
      Start-Sleep -Milliseconds 700
      Clear-Log
      # 撤最新的一枚（在首格）：其余图标整体左移/重排，退场那枚占格收缩淡出。
      Fixture-Remove $armPkgs[0]
      Start-Sleep -Seconds 3
      Stop-Record '03-reflow' 6
      $log = Save-Log '3 补位滑动（注入腿）'
      Save-Shot '30-reflow-after'
      $exit = Has $log ('icon exit ' + [regex]::Escape($armPkgs[0]))
      $update = Has $log 'update iconSet'
      Check '3' "移除首格一枚（$($armPkgs[0])）：icon exit + 集合更新（补位滑动看帧序列）" ($exit -and $update) '30-reflow-after.png / frames/03-reflow（目测结论见 README 判定表）'
    } else {
      Check '3' '注入腿 fixture 上屏失败（3 次重试都拿不到 icon enter）' $false 'sequence.logcat'
    }
  } else {
    Inconclusive '3' '补位滑动：本机当前非 fixture 条目已 >= 6，构造不出「退场条目还能占格」的余量' "nonFixture=$nonFixture iconSet=[$($currentSet -join ',')]"
  }
}

# ---------- 4. 最新位挪动（真实通知腿 + 一枚注入项占位） ----------
if (Test-Step '4') {
  Section '4 最新位挪动（真实通知腿：新通知把该 App 挪到左上）'
  # 前置：com.android.shell 已在 Icon Set 里且**不在首位**。注入一枚 fixture 占住首位，
  # 再发一条真实 shell 通知（新 tag = 新 key）：core 的「重复通知把该 App 挪到最新」语义
  # 应当把它挪到首位，其余图标滑动让位（同 App 增条只挪位、不进退场）。
  $stateLines = Read-State
  $currentSet = Get-IconSetFromState $stateLines
  if ($shellPkg -notin $currentSet) {
    Post-ShellNotification 'r150movebase' 'RearCue spec0015 move baseline'
    Start-Sleep -Milliseconds 1200
    $stateLines = Read-State
    $currentSet = Get-IconSetFromState $stateLines
  }
  $nonFixture = @($currentSet | Where-Object { $_ -notlike '*.rearcue.fixture' }).Count
  $need = 6 - $nonFixture
  if (($shellPkg -in $currentSet) -and ($need -ge 1)) {
    $armed = $null
    for ($try = 1; $try -le 3 -and -not $armed; $try++) {
      Wait-FreshProbeWindow | Out-Null
      $armed = Fixture-Arm -Pkgs @($fixtures[0])
      if (-not $armed) { Note "Fixture-Arm 第 $try 次没等到 icon enter" }
    }
    if ($armed) {
      $before = Get-IconSetFromState (Read-State)
      Note ("before = " + ($before -join ', '))
      Start-Record '04-move-front' 6
      Clear-Log
      Post-ShellNotification 'r150move' 'RearCue spec0015 move to front'
      Start-Sleep -Seconds 3
      Stop-Record '04-move-front' 6
      $log = Save-Log '4 最新位挪动（真实通知腿）'
      Save-Shot '40-move-front'
      $after = Get-IconSetFromState (Read-State)
      Note ("after  = " + ($after -join ', '))
      $movedToFront = ($after.Count -gt 0) -and ($after[0] -eq $shellPkg) -and (($before.Count -eq 0) -or ($before[0] -ne $shellPkg))
      $noEnterExit = (-not (Has $log ('icon enter ' + [regex]::Escape($shellPkg)))) -and (-not (Has $log ('icon exit ' + [regex]::Escape($shellPkg))))
      Check '4a' '同 App 新通知把它挪到首位（AppState 投影顺序）' $movedToFront ("before[0]=$(if ($before.Count) { $before[0] } else { '-' }) after[0]=$(if ($after.Count) { $after[0] } else { '-' })")
      Check '4b' '挪位不触发进退场锚（只挪位不重演动效）+ 有连续帧可目检' ($noEnterExit -and (Test-Path -LiteralPath (Join-Path $script:framesDir '04-move-front\contact-sheet.jpg'))) 'frames/04-move-front/contact-sheet.jpg'
    } else {
      Check '4a' '注入腿 fixture 占位失败（3 次重试都拿不到 icon enter）' $false 'sequence.logcat'
    }
  } else {
    Inconclusive '4a' '最新位挪动：前置不满足' "shellInSet=$($shellPkg -in $currentSet) nonFixture=$nonFixture need=$need"
  }
}
# ---------- 5. 1 行 <-> 2 行换挡 ----------
if (Test-Step '5') {
  Section '5 1 行 <-> 2 行换挡'
  Fixture-Reset
  $stateLines = Read-State
  $script:currentSet = Get-IconSetFromState $stateLines
  $realCount = @($script:currentSet | Where-Object { $_ -notlike '*.rearcue.fixture' }).Count
  if ($realCount -le 3) {
    # 空基线才构造得出 3 -> 4 的行数换挡：先用 fixture 把总数补到 3，再 +1 枚。
    $need = 3 - $realCount
    $keepN = [math]::Max($need, 1)
    $armed = Fixture-Arm -Pkgs @($fixtures[0..($keepN - 1)])
    if ($armed) {
      Start-Record '05-rows' 6
      Start-Sleep -Milliseconds 700
      Clear-Log
      Fixture-Post $fixtures[$keepN]
      Start-Sleep -Seconds 3
      Stop-Record '05-rows' 6
      $log = Save-Log '5 1 行 -> 2 行换挡（注入腿）'
      Save-Shot '50-rows-after'
      Check '5' '3 枚 -> 4 枚：行数换挡（连续帧目检）' (Has $log 'icon enter') '50-rows-after.png / frames/05-rows'
    } else {
      Check '5' '注入腿 fixture 上屏失败' $false 'sequence.logcat'
    }
  } else {
    Inconclusive '5' '1 行 <-> 2 行换挡：本机当前构造不出 1 行态' ("iconSet 里非 fixture 的真实通知 App = $realCount 个（" + (($script:currentSet | Where-Object { $_ -notlike '*.rearcue.fixture' }) -join ', ') + "）；机主真实通知按纪律不清理，3 枚上限触不到。行数换挡的纯几何由 IconGridTest 判例覆盖，整组平移的表现另由 7（充电让位）/3（补位）两条腿间接覆盖。")
  }
}

# ---------- 6. 6 枚 <-> 7 枚边界 ----------
if (Test-Step '6') {
  Section '6 6 枚 <-> 7 枚边界（注入腿）'
  # 档位边界是「容量内(<=6) ↔ 出现 +N(>=7)」：先按当前非 fixture 枚数补到 6，再投第 7 枚。
  # 注入项的寿命只到下一次成功的可见性探测为止（本机 15s 周期 + 真实通知会触发 queued 探测），
  # 所以「6 枚态立住 → 投第 7 枚」这一段要短，且整段允许重试（最多 3 次）。
  $stateLines = Read-State
  $currentSet = Get-IconSetFromState $stateLines
  $nonFixture = @($currentSet | Where-Object { $_ -notlike '*.rearcue.fixture' }).Count
  $need = 6 - $nonFixture
  if ($need -ge 1 -and ($need + 1) -le $fixtures.Count) {
    $armPkgs = @($fixtures[0..($need - 1)])
    $seventh = $fixtures[$need]
    $enter7 = $false
    $chip7 = $false
    $sixChipNull = $false
    $shot6 = $false
    for ($attempt = 1; $attempt -le 3 -and -not ($enter7 -and $chip7); $attempt++) {
      Wait-FreshProbeWindow | Out-Null
      $armed = Fixture-Arm -Pkgs $armPkgs
      if (-not $armed) { Note "第 $attempt 次：arm 没等到 icon enter"; continue }
      $place6 = Last-Match $armed 'rear-icon-place'
      if (-not $shot6) { Save-Shot '60-tier-6'; $shot6 = $true }
      $set6Now = Get-IconSetFromState (Read-State)
      $missing = @($armPkgs | Where-Object { $_ -notin $set6Now })
      if (($missing.Count -gt 0) -or ($set6Now.Count -lt 6)) {
        Note "第 $attempt 次：6 枚态没立住（missing=[$($missing -join ',')] set=[$($set6Now -join ',')]）→ 重试"
        continue
      }
      $sixChipNull = $place6 -and ($place6 -match 'chip=null')
      Note ("第 $attempt 次 6 枚集合 = " + ($set6Now -join ', '))
      Start-Record '06-tier-67' 7
      Clear-Log
      Fixture-Post $seventh
      $deadline = (Get-Date).AddSeconds(5)
      while ((Get-Date) -lt $deadline -and -not ($enter7 -and $chip7)) {
        Start-Sleep -Milliseconds 300
        $l = Get-Log
        if (-not $enter7) { $enter7 = Has $l ('icon enter ' + [regex]::Escape($seventh)) }
        if (-not $chip7) { $chip7 = Has $l 'rear-icon-place .*chip=PxRect' }
      }
      Start-Sleep -Seconds 1
      Stop-Record '06-tier-67' 7
      $log = Save-Log ("6 6 枚 -> 7 枚边界（第 $attempt 次）")
      Save-Shot '60-tier-7'
      $place7 = Last-Match $log 'rear-icon-place'
      Note ("第 $attempt 次 7 枚 size/chip 锚 = " + $place7)
      if (-not ($enter7 -and $chip7)) { Note "第 $attempt 次没同时拿到 enter+chip → 重试" }
    }
    Check '6a' '6 枚（容量内）：无 +N（chip=null）' $sixChipNull '60-tier-6.png'
    Check '6b' "第 7 枚（$seventh）入场并出现 +N（chip=PxRect）" ($enter7 -and $chip7) '60-tier-7.png / frames/06-tier-67（整组缩放目检）'
  } else {
    Inconclusive '6a' '六格边界：当前非 fixture 枚数使补档不可行' "nonFixture=$nonFixture need=$need"
  }
}
# ---------- 7. 充电让位（让位电量数字） ----------
if (Test-Step '7') {
  Section '7 充电让位（让位电量数字；真实腿：CHARGING_ENABLED 开关 = 设置页同一入口）'
  Fixture-Reset
  $stateLines = Read-State
  $chargingOn = Has $stateLines 'chargingEnabled=true' -and (Has $stateLines 'castSource=CHARGING')
  if ($chargingOn) {
    $place0 = Last-Match (Get-Log) 'rear-icon-place'
    Start-Record '07-charging-slot' 7
    Start-Sleep -Milliseconds 800
    Clear-Log
    Broadcast-Action $actionCharging '--ez enabled false'
    Start-Sleep -Seconds 2
    $logOff = Get-Log
    $placeOff = Last-Match $logOff 'rear-icon-place'
    Save-Shot '70-charging-off'
    Broadcast-Action $actionCharging '--ez enabled true'
    Start-Sleep -Seconds 2
    Stop-Record '07-charging-slot' 7
    $log = Save-Log '7 充电让位（关→开）'
    Save-Shot '71-charging-on'
    $placeOn = Last-Match $log 'rear-icon-place'
    $slotOn = $place0 -and ($place0 -match 'numberSlot=PxRect')
    $slotOff = $placeOff -and ($placeOff -match 'numberSlot=null')
    $slotBack = $placeOn -and ($placeOn -match 'numberSlot=PxRect')
    Check '7a' '充电关：电量数字占位消失（numberSlot=null）' $slotOff '70-charging-off.png'
    Check '7b' '充电开：电量数字占位回来（numberSlot=PxRect）' ($slotOn -or $slotBack) '71-charging-on.png'
    Check '7c' '让位过程有连续帧可目检' (Test-Path -LiteralPath (Join-Path $script:framesDir '07-charging-slot\contact-sheet.jpg')) 'frames/07-charging-slot/'
  } else {
    Inconclusive '7' '充电让位：本机当前不是「插电 ∧ 充电开关开」的持屏态' ("state: chargingEnabled/castSource 见 sequence.logcat；需要插着 USB 且在设置里开着充电动画")
  }
}

# ---------- 8. 相机带避让（静态几何 + 截图） ----------
if (Test-Step '8') {
  Section '8 相机带避让（真实腿：安全几何 + 截图）'
  Fixture-Reset -Keep @($fixtures[0])
  Start-Sleep -Milliseconds 900
  $lines = Get-Log
  $place = Last-Match $lines 'rear-icon-place'
  $safe = Last-Match $lines 'rear-safe-geometry'
  Save-Shot '80-camera-band'
  $m = if ($place) { [regex]::Match($place, 'block=PxRect\(left=(-?\d+), top=(-?\d+), right=(-?\d+), bottom=(-?\d+)\)') } else { $null }
  if ($m -and $m.Success) {
    $left = [int]$m.Groups[1].Value; $right = [int]$m.Groups[3].Value
    Check '8' '图标组整组避开相机带（block.left >= 296 且不越右缘）' (($left -ge 296) -and ($right -le 904)) ("block.left=$left right=$right / 80-camera-band.png / rear-safe-geometry")
  } else {
    Inconclusive '8' '相机带避让：没拿到 rear-icon-place 锚' 'sequence.logcat'
  }
  Note ($safe)
}

# ---------- 9. 退场（真实通知腿） ----------
if (Test-Step '9') {
  Section '9 退场（真实通知腿：撤掉 shell 通知）'
  Fixture-Reset
  Start-Sleep -Milliseconds 500
  Start-Record '09-exit-real' 7
  Start-Sleep -Milliseconds 800
  Clear-Log
  Cancel-ShellNotifications
  Start-Sleep -Seconds 3
  Stop-Record '09-exit-real' 7
  $log = Save-Log '9 退场（真实通知腿）'
  Save-Shot '90-exit-real'
  $exit = Has $log ('icon exit ' + [regex]::Escape($shellPkg))
  Check '9' '撤通知触发 icon exit（真实通知腿）' $exit '90-exit-real.png / frames/09-exit-real'
}

# ---------- 10/11. 最后一个图标的退场 + 退屏宽限 ----------
if (Test-Step '10' -or (Test-Step '11')) {
  Section '10/11 最后一个图标的退场与交还时机 + 宽限窗内新通知取消退屏（真实通知腿）'
  Fixture-Reset
  $stateLines = Read-State
  $setNow = Get-IconSetFromState $stateLines
  $realNow = @($setNow | Where-Object { $_ -notlike '*.rearcue.fixture' })
  if ($realNow.Count -gt 0) {
    Inconclusive '10' '最后一个图标退场 + 交还时机：本机构造不出空集前置' ("Icon Set 里还有 $($realNow.Count) 个机主真实通知 App（" + ($realNow -join ', ') + "）；这些通知按票 #150 纪律不得清理，故「最后一条清空 → 退屏宽限」在本机不可构造。复跑路径：机主清掉这些通知后跑 `drive-acceptance.ps1 -GraceOnly`。")
    Inconclusive '11' '宽限窗内新通知取消退屏：同上（前置是空集）' '同 10'
  } else {
    # 空集 → 真实腿：发一条 → 自动上屏 → 撤掉 → 观察 icon exit / exit grace start|end。
    Broadcast-Action $actionCharging '--ez enabled false'
    Start-Sleep -Milliseconds 600
    Clear-Log
    Post-ShellNotification 'r150last' 'RearCue spec0015 last icon (real leg)'
    Start-Sleep -Seconds 2
    $castLog = Get-Log
    Start-Record '10-last-exit' 6
    Start-Sleep -Milliseconds 700
    Clear-Log
    Cancel-ShellNotifications
    Start-Sleep -Seconds 4
    Stop-Record '10-last-exit' 6
    $log = Save-Log '10 最后一个图标退场 + 宽限'
    Save-Shot '100-grace-end'
    $startLine = Last-Match $log 'exit grace start'
    $endLine = Last-Match $log 'exit grace end'
    $exitLine = Last-Match $log ('icon exit ' + [regex]::Escape($shellPkg))
    if ($startLine -and $endLine -and $exitLine) {
      $ts = Line-Time $startLine; $te = Line-Time $endLine; $tx = Line-Time $exitLine
      $graceMs = [int](($te - $ts).TotalMilliseconds)
      Check '10a' '最后一条清空：icon exit 先发生' ($tx -le $te) ("exit $($tx.ToString('HH:mm:ss.fff'))；grace start $($ts.ToString('HH:mm:ss.fff'))")
      Check '10b' '退屏宽限到期才交还（0 < grace <= 1000ms）' ($graceMs -gt 0 -and $graceMs -le 1000) "grace=${graceMs}ms"
    } else {
      Check '10' '最后一个图标退场 + 宽限（start/end/exit 三锚齐全）' $false 'sequence.logcat'
    }
    # 11：宽限窗内新通知取消退屏。
    Clear-Log
    Post-ShellNotification 'r150grace1' 'RearCue spec0015 grace cancel'
    Start-Sleep -Seconds 2
    Broadcast-Action $actionExit
    Start-Sleep -Milliseconds 150
    Cancel-ShellNotifications
    Start-Sleep -Milliseconds 100
    Post-ShellNotification 'r150grace2' 'RearCue spec0015 grace cancel (new)'
    Start-Sleep -Seconds 2
    $log11 = Save-Log '11 宽限窗内新通知'
    $cancelLine = Last-Match $log11 'exit grace cancel'
    $endAfter = @($log11 | Where-Object { $_ -match 'exit grace end' }).Count
    Check '11' '宽限窗内新通知 → exit grace cancel（不交还屏）' (($null -ne $cancelLine) -and ($endAfter -eq 0)) ("cancel 行：" + $(if ($cancelLine) { '有' } else { '无' }) + "；窗内 exit grace end 计数=$endAfter")
    Broadcast-Action $actionCharging '--ez enabled true'
  }
}

# ---------- 12. 溢出应用消失不演退场（注入腿） ----------
if (Test-Step '12') {
  Section '12 溢出应用（只在 +N 里）消失不演退场（注入腿）'
  $armed = $null
  for ($try = 1; $try -le 3 -and -not $armed; $try++) {
    Wait-FreshProbeWindow | Out-Null
    # 让 alpha 落到溢出位：同一次广播里先投 alpha（最旧），再投其余 6 枚（都比它新）——
    # 集合时间倒序后网格只装前 6 枚，alpha 恰好在 +N 里（网格内那 6 枚会打 icon enter，alpha 不会）。
    $armed = Fixture-Arm -Pkgs $fixtures -WaitFor @($fixtures[1..6])
    if (-not $armed) { Note "Fixture-Arm 第 $try 次没等到网格内 6 枚 icon enter" }
  }
  if ($armed) {
    $setNow = Get-IconSetFromState (Read-State)
    $alphaIndex = [array]::IndexOf($setNow, $fixtures[0])
    Note ("集合顺序（新→旧）：" + ($setNow -join ', ') + "；alpha 序号=$alphaIndex（>=6 即在 +N 里）")
    Start-Record '12-overflow-exit' 6
    Start-Sleep -Milliseconds 700
    Clear-Log
    Fixture-Remove $fixtures[0]
    Start-Sleep -Seconds 3
    Stop-Record '12-overflow-exit' 6
    $log = Save-Log '12 溢出应用消失（注入腿）'
    Save-Shot '120-overflow-after'
    $exitAlpha = Has $log ('icon exit ' + [regex]::Escape($fixtures[0]))
    if ($alphaIndex -ge 6) {
      Check '12' '溢出应用被清掉：无 icon exit 锚（只换 +N 数字）' (-not $exitAlpha) "alpha 序号=$alphaIndex；exit 锚=$exitAlpha；120-overflow-after.png"
    } else {
      Inconclusive '12' '溢出应用消失不演退场：本轮 alpha 没落到 +N 里' "alpha 序号=$alphaIndex（需要 >=6）"
    }
  } else {
    Check '12' '注入腿 fixture 上屏失败（3 次重试都拿不到网格内 icon enter）' $false 'sequence.logcat'
  }
}

# ---------- 13. Detail View 当口被清掉不演退场（注入腿） ----------
if (Test-Step '13') {
  Section '13 Detail View 当口被清掉不演退场（注入腿）'
  $armed = $null
  for ($try = 1; $try -le 3 -and -not $armed; $try++) {
    Wait-FreshProbeWindow | Out-Null
    $armed = Fixture-Arm -Pkgs @($fixtures[0])
    if (-not $armed) { Note "Fixture-Arm 第 $try 次没等到 alpha 的 icon enter" }
  }
  if ($armed) {
    $cell = Get-IconCell
    if (-not $cell) { throw '拿不到 rear-icon-place 锚，无法定位首格图标' }
    Note "首格图标中心 = $($cell[0]),$($cell[1])（alpha 是本帧最新一枚，应在首格）"
    Clear-Log
    Tap $cell[0] $cell[1]
    Start-Sleep -Milliseconds 1200
    $logTap = Get-Log
    $tapLine = Last-Match $logTap 'rear-tap received app='
    $detailLine = Last-Match $logTap ('detail open ' + [regex]::Escape($fixtures[0]))
    Start-Record '13-detail-exit' 6
    Start-Sleep -Milliseconds 700
    Clear-Log
    Fixture-Remove $fixtures[0]
    Start-Sleep -Seconds 3
    Stop-Record '13-detail-exit' 6
    $log = Save-Log '13 Detail 当口被清掉（注入腿）'
    Save-Shot '130-detail-silent'
    $exitAlpha = Has $log ('icon exit ' + [regex]::Escape($fixtures[0]))
    $probeInWindow = Has $log 'shade-visible probe reason='
    Check '13a' 'Detail 打开成功（rear-tap received + detail open）' (($null -ne $tapLine) -and ($null -ne $detailLine)) ("tap=" + $(if ($tapLine) { '有' } else { '无' }) + " detail=" + $(if ($detailLine) { '有' } else { '无' }))
    Check '13b' 'Detail 当口被清掉：无 icon exit 锚（静默让位）' (-not $exitAlpha) ("probeInWindow=$probeInWindow；130-detail-silent.png / frames/13-detail-exit")
    # 收尾：点一下卡片收起 Detail（避免影响后续步骤）。
    Start-Sleep -Milliseconds 300
    Tap $cell[0] $cell[1]
    Start-Sleep -Milliseconds 800
  } else {
    Check '13a' '注入腿 fixture 上屏失败（3 次重试都拿不到 icon enter）' $false 'sequence.logcat'
  }
}
# ---------- 14. 角标 1 -> 2 不动效（真实通知腿） ----------
if (Test-Step '14') {
  Section '14 角标 1 -> 2 不动效（真实通知腿）'
  # 前置：shell 通知已在第 9 步清掉。第一条 → 入场；第二条（另一个 tag = 另一个 key）→ 只加计数。
  Clear-Log
  Post-ShellNotification 'r150badge1' 'RearCue spec0015 badge 1'
  Start-Sleep -Milliseconds 1400
  $log1 = Get-Log
  $enter1 = Has $log1 ('icon enter ' + [regex]::Escape($shellPkg))
  Clear-Log
  Post-ShellNotification 'r150badge2' 'RearCue spec0015 badge 2'
  Start-Sleep -Milliseconds 1400
  $log2 = Save-Log '14 角标 1->2（第二条同 App 通知）'
  Save-Shot '140-badge-2'
  $enter2 = Has $log2 ('icon enter ' + [regex]::Escape($shellPkg))
  $exit2 = Has $log2 ('icon exit ' + [regex]::Escape($shellPkg))
  $state2 = Read-State
  $counts = Last-Match $state2 'iconSet=\['
  Check '14a' '第二条同 App 通知：不触发 icon enter/icon exit（集合成员没变）' ((-not $enter2) -and (-not $exit2)) "第一条 enter=$enter1；第二条 enter=$enter2 exit=$exit2"
  Check '14b' '角标计数由状态投影给出（截图留观感）' (Test-Path -LiteralPath (Join-Path $script:shotsDir '140-badge-2.png')) '140-badge-2.png'
}

# ---------- 15. 帧率与发热基线 ----------
if (Test-Step '15') {
  Section '15 帧率与发热基线（口径：dumpsys 采样 + 背屏录屏实测帧数）'
  $gfx = @(Sh "dumpsys gfxinfo $testPkg")
  $thermal = @(Sh 'dumpsys thermalservice')
  $batt = @(Sh 'dumpsys battery')
  $gfx | Set-Content -LiteralPath (Join-Path $OutDir 'gfxinfo.txt') -Encoding UTF8
  $thermal | Set-Content -LiteralPath (Join-Path $OutDir 'thermalservice.txt') -Encoding UTF8
  $batt | Set-Content -LiteralPath (Join-Path $OutDir 'battery.txt') -Encoding UTF8
  $totalFrames = Last-Match $gfx 'Total frames rendered: (\d+)'
  $janky = Last-Match $gfx 'Janky frames: (\d+)'
  $temp = Last-Match $batt 'temperature: (\d+)'
  $tempC = if ($temp) { [int]([regex]::Match($temp, 'temperature: (\d+)').Groups[1].Value) / 10 } else { $null }
  Note "gfxinfo: $totalFrames / $janky"
  Note "battery temperature = ${tempC}C（0.1°C 单位换算自 dumpsys battery）"
  # 背屏录屏实测：一次 5 秒空录，数帧数 → 背屏渲染帧率口径。
  Start-Record '15-fps-probe' 5
  Start-Sleep -Seconds 6
  Stop-Record '15-fps-probe' 5
  $probeMp4 = Join-Path $script:videoDir '15-fps-probe.mp4'
  $frames = & $Ffprobe -v error -select_streams v -show_entries stream=nb_frames -of csv=p=0 $probeMp4
  $dur = & $Ffprobe -v error -select_streams v -show_entries stream=duration -of csv=p=0 $probeMp4
  Note "背屏录屏实测：nb_frames=$frames duration=$dur（screenrecord 按背屏 120Hz 抓取）"
  Check '15' '帧率/发热基线采样落盘（gfxinfo + thermalservice + battery + 录屏帧数）' ((Test-Path -LiteralPath (Join-Path $OutDir 'gfxinfo.txt')) -and (Test-Path -LiteralPath (Join-Path $OutDir 'thermalservice.txt'))) 'gfxinfo.txt / thermalservice.txt / battery.txt / video/15-fps-probe.mp4'
}

# ---------- 16. 主屏总览同一套观感 ----------
if (Test-Step '16') {
  Section '16 主屏总览（IconSetCard）同一套观感（注入腿 + 主屏录屏）'
  Fixture-Reset
  Invoke-Adb shell "am start -n $testPkg/.ui.MainActivity" | Out-Null
  Start-Sleep -Seconds 2
  Save-MainShot '160-main-before'
  Start-Record '16-main-enter' 7 -MainDisplay
  Start-Sleep -Milliseconds 900
  Clear-Log
  Fixture-Post $fixtures[0]
  Start-Sleep -Seconds 3
  Stop-Record '16-main-enter' 7 -From 0.6 -Window 2.0 -ScaleWidth 610
  $log = Save-Log '16 主屏总览（注入腿）'
  Save-MainShot '161-main-after'
  $enter = Has $log ('icon enter ' + [regex]::Escape($fixtures[0]))
  Check '16a' '主屏同源集合：注入项进入 Icon Set（core 事件同一条链）' $enter '161-main-after.png'
  Check '16b' '主屏总览动效有连续帧可目检（与背屏同一套参数）' (Test-Path -LiteralPath (Join-Path $script:framesDir '16-main-enter\contact-sheet.jpg')) 'frames/16-main-enter/（主屏帧为整屏 1220x2656 缩放）'
  Invoke-Adb shell 'input keyevent KEYCODE_HOME' | Out-Null
}

# ---------- 17. 既有锚回归：Detail + Content Page（不碰机主真实通知） ----------
if (Test-Step '17') {
  Section '17 既有锚回归：Detail open/close + Content Page 一次切页往返'
  # Detail：用本应用自己的 POST_TEST 通知（允许撤销）点开再收起同一枚。
  # 先撤/重投一次，清掉可能残留的 Detail / 内容页状态，从默认通知页开始。
  Broadcast-Action $actionAgentState '--ez connected false'
  Broadcast-Action $actionExit
  Start-Sleep -Milliseconds 500
  Broadcast-Action $actionProject
  Start-Sleep -Milliseconds 1600
  $detailPkg = $testPkg
  Clear-Log
  Broadcast-Action $actionPostTest
  $detailDeadline = (Get-Date).AddSeconds(8)
  $detailEnter = @()
  while ((Get-Date) -lt $detailDeadline) {
    Start-Sleep -Milliseconds 500
    $detailEnter = Get-Log
    if (Has $detailEnter ('icon enter ' + [regex]::Escape($detailPkg))) { break }
  }
  $detailEntered = Has $detailEnter ('icon enter ' + [regex]::Escape($detailPkg))
  $cell = Get-IconCell
  $detailSet = Get-IconSetFromState (Read-State)
  if ($detailEntered -and $cell -and $detailSet.Count -gt 0 -and $detailSet[0] -eq $detailPkg) {
    $detailCellNote = "cell=$($cell[0]),$($cell[1])"
    Clear-Log
    Tap $cell[0] $cell[1]
    Start-Sleep -Milliseconds 1200
    $logDetailOpen = Get-Log
    $openLine = Last-Match $logDetailOpen ('detail open ' + [regex]::Escape($detailPkg))
    Clear-Log
    Tap $cell[0] $cell[1]
    Start-Sleep -Milliseconds 1200
    $logDetailClose = Save-Log '17a 既有锚：Detail 点开再收起'
    $closeLine = Last-Match $logDetailClose ('detail close ' + [regex]::Escape($detailPkg))
    Check '17a' '既有锚：detail open <pkg> → detail close <pkg>（点开再收起同一枚）' (($null -ne $openLine) -and ($null -ne $closeLine)) ("open=" + $(if ($openLine) { '有' } else { '无' }) + " close=" + $(if ($closeLine) { '有' } else { '无' }) + "；$detailCellNote")
  } else {
    Check '17a' '既有锚：detail open/close（POST_TEST 未上屏或不是首格）' $false ("enter=$detailEntered cell=$([bool]$cell) first=" + $(if ($detailSet.Count -gt 0) { $detailSet[0] } else { '(空)' }))
  }
  Broadcast-Action $actionCancelTest
  Start-Sleep -Milliseconds 800

  # Content Page：注入短 Agent 内容，通知页 <-> Agent 页各走一次。
  Broadcast-Action $actionAgentState "--ez connected true --es status working --es workspace RearCue-0015 --es reply R001spec0015existinganchors"
  Start-Sleep -Milliseconds 1000
  Clear-Log
  Tap 350 470
  Start-Sleep -Milliseconds 900
  $logToAgent = Save-Log '17b 既有锚：通知页 -> Agent 页'
  Save-Shot '170-content-agent'
  Check '17b' '既有锚：content page toggle agent + crossfade 往 Agent 页' (
    (Has $logToAgent 'content page toggle agent') -and
    (Has $logToAgent 'content page crossfade start show=agent') -and
    (Has $logToAgent 'content page crossfade done show=agent')
  ) '170-content-agent.png / sequence.logcat 17b 段'
  Clear-Log
  Tap 350 470
  Start-Sleep -Milliseconds 900
  $logToNotification = Save-Log '17c 既有锚：Agent 页 -> 通知页'
  Save-Shot '171-content-notification'
  Check '17c' '既有锚：content page toggle notification + crossfade 回通知页' (
    (Has $logToNotification 'content page toggle notification') -and
    (Has $logToNotification 'content page crossfade start show=notification') -and
    (Has $logToNotification 'content page crossfade done show=notification')
  ) '171-content-notification.png / sequence.logcat 17c 段'
  # 收口：断开伪 Agent 内容，恢复通知页；不影响机主真实通知。
  Broadcast-Action $actionAgentState '--ez connected false'
  Start-Sleep -Milliseconds 900
}

# ---------- 90. 收尾 ----------
if (Test-Step '90') {
  Section '90 收尾与状态复位'
  Fixture-Remove ($fixtures -join ',')
  Start-Sleep -Milliseconds 400
  Cancel-ShellNotifications
  Broadcast-Action $actionCancelTest
  Broadcast-Action $actionCharging '--ez enabled true'
  Broadcast-Action $actionPostureGate '--ez enabled false'
  Broadcast-Action $actionSessionLock '--es sessionId auto'
  Start-Sleep -Milliseconds 800
  $finalState = Read-State
  $finalSet = Get-IconSetFromState $finalState
  Note ("收尾 iconSet = " + ($finalSet -join ', '))
  Check '90' '收尾：fixture 与自发的 shell/test 通知已清（机主真实通知原样保留）' ($true) "收尾 iconSet=$($finalSet -join ',')"
  if ($script:keepAwakeJob) {
    Stop-Job $script:keepAwakeJob -ErrorAction SilentlyContinue
    Remove-Job $script:keepAwakeJob -Force -ErrorAction SilentlyContinue
  }
  Invoke-Adb shell "rm -rf $deviceTmp" | Out-Null
}

# ---------- 汇总 ----------
$failed = @($script:checks | Where-Object { $_.Status -eq 'FAIL' }).Count
$inconclusive = @($script:checks | Where-Object { $_.Status -eq 'INCONCLUSIVE' }).Count
Add-Content -LiteralPath $script:summaryPath -Value "device=$Serial apkSha256=$script:apkHash failed=$failed inconclusive=$inconclusive" -Encoding UTF8
Add-Content -LiteralPath $script:summaryPath -Value ($script:checks | Format-Table -AutoSize | Out-String) -Encoding UTF8
Write-Host "`nacceptance summary -> $script:summaryPath"
Write-Host ("failed={0} inconclusive={1}" -f $failed, $inconclusive)
if ($failed -gt 0) { exit 1 }