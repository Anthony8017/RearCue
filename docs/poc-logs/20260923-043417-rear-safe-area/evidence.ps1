# 票 #26 实机验收取证（E15）：背屏内容安全矩形 + 漂移极限位
# 一条命令 = 装 APK → Icon Set 两枚（本应用 + shell）→ Dashboard 上背屏（自动链路 → 手动旁路
# → E14 任务搬运事务三级兜底；手机当前 PIN 锁定，am start --display 必被 rearDisplay check
# locked 拒，任务搬运是锁屏稳态唯一可落位路径）→ 三个连续分钟各截背屏一张（漂移调度相邻
# 分钟都在极限位）→ 工具坑留证 → 清场 → 像素测量。
# 锁约定：Global\RearCueDevice 命名互斥锁（notes/env.md 修正写法：AbandonedMutexException
# 捕获后继续，finally 直接 ReleaseMutex，不用 WaitOne(0) 收尾）。
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe"
$wt = "C:\Users\13691\Desktop\RearCue-wt\03"
$dir = Join-Path $wt 'docs\poc-logs\20260923-043417-rear-safe-area'
$shots = Join-Path $dir 'screenshots'
$pkg = 'com.rearcue.poc'
$rearSfId = '4630946949513469332'   # SurfaceFlinger display id（screencap -d 的合法 id，见 tool-note）
$mainSfId = '4630946949513469331'
New-Item -ItemType Directory -Force -Path $shots | Out-Null
$sessionLog = Join-Path $dir 'session-log.txt'

function L([string]$msg) {
    $line = ('{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'), $msg)
    $line | Out-File $sessionLog -Encoding utf8 -Append
    Write-Host $line
}
function Broadcast([string]$action, [string]$extra = '') {
    & $adb shell am broadcast -n "$pkg/.DebugCommandReceiver" -a $action $extra | Out-Null
}
function Dump-Activities([string]$name) {
    & $adb shell dumpsys activity activities | Out-File (Join-Path $dir $name) -Encoding utf8
}
# Dashboard 是否已落在背屏（Display #1 块里有组件名；判定同 RearProjectionVerifier 口径）
function Test-OnRear([string]$name) {
    $path = Join-Path $dir $name
    if (-not (Test-Path $path)) { return $false }
    $text = Get-Content $path -Raw
    $rear = [regex]::Match($text, 'Display #1 \(activities from top to bottom\):[\s\S]*?(?=Display #\d+ \(activities|\z)')
    return ($rear.Success -and $rear.Value -match 'RearDashboardActivity')
}

$m = [System.Threading.Mutex]::new($false, 'Global\RearCueDevice')
try {
    try { $m.WaitOne() | Out-Null } catch [System.Threading.AbandonedMutexException] { }
    L 'start'

    # 设备几何事实（运行时口径；换机型只换输入）
    & $adb shell dumpsys display | Select-String 'DisplayDeviceInfo' |
        Out-File (Join-Path $dir 'display-cutout.txt') -Encoding utf8
    & $adb shell dumpsys SurfaceFlinger --display-id |
        Out-File (Join-Path $dir 'screencap-tool-note.txt') -Encoding utf8

    # 装 APK（重装会重置 MIUIOP，装完立刻补锁屏显示授权）
    & $adb install -r "$wt\app\build\outputs\apk\debug\app-debug.apk" |
        Out-File (Join-Path $dir 'install.txt') -Encoding utf8
    & $adb shell appops set $pkg 10020 allow | Out-File (Join-Path $dir 'grant.txt') -Encoding utf8

    # 清场 + Icon Set 两枚（本应用测试通知 + shell 通知）
    Broadcast com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    Broadcast com.rearcue.poc.action.CANCEL_PACKAGE "--es pkg $pkg"
    Start-Sleep -Seconds 3
    Broadcast com.rearcue.poc.action.POST_TEST
    & $adb shell cmd notification post -t 'RearCue E15' 'rearcue-e15' 'safe area probe' | Out-Null
    Start-Sleep -Seconds 6

    # Dashboard 上背屏：①通知自动链路（票 #5）
    Dump-Activities 'activities-01-auto.txt'
    L ("route auto on-rear={0}" -f (Test-OnRear 'activities-01-auto.txt'))

    if (-not (Test-OnRear 'activities-01-auto.txt')) {
        # ②调试旁路「投送到背屏」
        Broadcast com.rearcue.poc.action.PROJECT_REAR
        Start-Sleep -Seconds 5
        Dump-Activities 'activities-02-project.txt'
        L ("route project-rear on-rear={0}" -f (Test-OnRear 'activities-02-project.txt'))
    }
    if (-not (Test-OnRear 'activities-01-auto.txt') -and -not (Test-OnRear 'activities-02-project.txt')) {
        # ③E14 任务搬运事务（锁屏稳态唯一可落位路径，票 #22 实测）
        & $adb shell am start -n "$pkg/$pkg.rear.RearDashboardActivity" |
            Out-File (Join-Path $dir 'am-start.txt') -Encoding utf8
        Start-Sleep -Seconds 5
        Dump-Activities 'activities-03-pre-move.txt'
        $tid = 0
        foreach ($line in (Get-Content (Join-Path $dir 'activities-03-pre-move.txt'))) {
            if ($line -match 'RearDashboardActivity t(\d+)') { $tid = [int]$Matches[1]; break }
        }
        L "task-move rootTaskId=$tid"
        & $adb shell input -d 1 keyevent KEYCODE_WAKEUP | Out-Null
        & $adb shell service call activity_task 51 i32 $tid i32 1 |
            Out-File (Join-Path $dir 'task-move.txt') -Encoding utf8
        Start-Sleep -Seconds 5
        Dump-Activities 'activities-04-post-move.txt'
        L ("route task-move on-rear={0}" -f (Test-OnRear 'activities-04-post-move.txt'))
    }
    Broadcast com.rearcue.poc.action.STATE

    # 三个连续分钟各截背屏一张：漂移调度相邻分钟都是极限位（±8px 对角/轴向轮驻）
    for ($i = 1; $i -le 3; $i++) {
        Start-Sleep -Seconds 61
        & $adb shell input -d 1 keyevent KEYCODE_WAKEUP | Out-Null
        Start-Sleep -Milliseconds 500
        $deviceClock = (& $adb shell date '+%H:%M:%S').Trim()
        $out = Join-Path $shots ('0{0}-rear-drift.png' -f $i)
        cmd /c "$adb exec-out screencap -d $rearSfId -p > `"$out`""
        L ("shot {0} device-clock={1}" -f $i, $deviceClock)
    }
    Dump-Activities 'activities-05-final.txt'

    # 工具坑留证：screencap -d 只认 SurfaceFlinger display id（逻辑 id '1' 报 not valid）
    "`r`n--- screencap -d 1（逻辑 id，非法）---" |
        Out-File (Join-Path $dir 'screencap-tool-note.txt') -Encoding utf8 -Append
    & $adb shell screencap -d 1 -p /sdcard/rc-e15-tool.png 2>&1 |
        Out-File (Join-Path $dir 'screencap-tool-note.txt') -Encoding utf8 -Append

    # 清场：退出 Dashboard（背屏交还 Native Rear Screen）+ 撤测试通知
    Broadcast com.rearcue.poc.action.EXIT_REAR
    Start-Sleep -Seconds 3
    Broadcast com.rearcue.poc.action.CANCEL_TEST
    Broadcast com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    & $adb logcat -d -s RearCue | Out-File (Join-Path $dir 'logcat-rearcue.txt') -Encoding utf8
    & $adb shell rm -f /sdcard/rc-e15-tool.png
    L 'device session done'
}
finally {
    $m.ReleaseMutex()
    $m.Dispose()
}

# ---- 像素测量（不需要设备）：三张背屏截图的内容 bbox vs 运行时内容安全矩形 ----
Add-Type -AssemblyName System.Drawing
$measure = Join-Path $dir 'measure.txt'
$lines = New-Object System.Collections.Generic.List[string]

# 运行时口径：应用日志的 rear-safe-geometry / rear-safe-place 行
$logcat = Get-Content (Join-Path $dir 'logcat-rearcue.txt')
foreach ($line in $logcat) {
    if ($line -match 'rear-safe-(geometry|place)') { $lines.Add(('log  {0}' -f $line.Trim())) }
}
$contentMatch = [regex]::Match(($logcat -join "`n"), 'content=PxRect\(left=(-?\d+), top=(-?\d+), right=(-?\d+), bottom=(-?\d+)\)')
if (-not $contentMatch.Success) {
    $lines.Add('verdict : E15-RUN-INVALID (logcat 里没有 contentRect，几何未采集)')
    $lines | Out-File $measure -Encoding utf8
    return
}
$cl = [int]$contentMatch.Groups[1].Value
$ct = [int]$contentMatch.Groups[2].Value
$cr = [int]$contentMatch.Groups[3].Value
$cb = [int]$contentMatch.Groups[4].Value
$lines.Add(('content-rect : [{0}, {1}, {2}, {3}]' -f $cl, $ct, $cr, $cb))

$boxes = @()
for ($i = 1; $i -le 3; $i++) {
    $path = Join-Path $shots ('0{0}-rear-drift.png' -f $i)
    $bmp = [System.Drawing.Bitmap]::FromFile($path)
    $bmpW = $bmp.Width
    $bmpH = $bmp.Height
    $data = $bmp.LockBits(
        [System.Drawing.Rectangle]::new(0, 0, $bmpW, $bmpH),
        [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $data.Stride
    $bytes = New-Object byte[] ($stride * $bmpH)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    $bmp.UnlockBits($data)
    $bmp.Dispose()
    $minX = [int]::MaxValue; $minY = [int]::MaxValue; $maxX = -1; $maxY = -1
    for ($y = 0; $y -lt $bmpH; $y++) {
        $row = $y * $stride
        for ($x = 0; $x -lt $bmpW * 4; $x += 4) {
            if (($bytes[$row + $x] -ne 0) -or ($bytes[$row + $x + 1] -ne 0) -or ($bytes[$row + $x + 2] -ne 0)) {
                $px = $x / 4
                if ($px -lt $minX) { $minX = $px }
                if ($px -gt $maxX) { $maxX = $px }
                if ($y -lt $minY) { $minY = $y }
                if ($y -gt $maxY) { $maxY = $y }
            }
        }
    }
    $boxes += , @($minX, $minY, $maxX, $maxY)
    $inside = ($minX -ge $cl) -and ($minY -ge $ct) -and ($maxX -le $cr) -and ($maxY -le $cb)
    $verdict = if ($maxX -lt 0) { 'E15-RUN-INVALID (全黑截图，背屏没亮/没上屏)' }
        elseif ($inside) { 'E15-SAFE-PASS' } else { 'E15-OUT-OF-SAFE' }
    $lines.Add(('shot {0} : bbox=[{1}, {2}, {3}, {4}] margins(l,t,r,b)=({5},{6},{7},{8}) -> {9}' -f
            $i, $minX, $minY, $maxX, $maxY,
            ($minX - $cl), ($minY - $ct), ($cr - $maxX), ($cb - $maxY), $verdict))
}
# 相邻分钟位移（漂移调度：相邻分钟都是极限位，位移应为幅度 8px 的轴向/对角 16px）
for ($i = 1; $i -lt $boxes.Count; $i++) {
    $dx = $boxes[$i][0] - $boxes[$i - 1][0]
    $dy = $boxes[$i][1] - $boxes[$i - 1][1]
    $lines.Add(('drift {0}->{1} : bbox 位移 dx={2} dy={3}' -f $i, ($i + 1), $dx, $dy))
}
$lines | Out-File $measure -Encoding utf8
Write-Host "measure written: $measure"
