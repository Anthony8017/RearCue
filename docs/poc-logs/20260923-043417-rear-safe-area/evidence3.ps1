# 票 #26 补拍轮 2（E15）：Icon Set 两枚形态 + 全链路落位校验
# 补拍轮（evidence2）翻车教训（erratum 7）：应用被别的实验留在 force-stopped 态时，显式
# 广播被系统静默丢弃、背屏出的是 Native Rear Screen 天气面——本轮先 am start 拉起应用、
# 校验落位（Display #1 块里有 RearDashboardActivity）才开拍，落位不上即中止并留 RUN-INVALID。
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe"
$dir = "C:\Users\13691\Desktop\RearCue-wt\03\docs\poc-logs\20260923-043417-rear-safe-area"
$shotsDir = Join-Path $dir 'screenshots'
$pkg = 'com.rearcue.poc'
$rearSfId = '4630946949513469332'
$sessionLog = Join-Path $dir 'session-log.txt'

function L3([string]$msg) {
    $line = ('{0}  [3] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'), $msg)
    $line | Out-File $sessionLog -Encoding utf8 -Append
    Write-Host $line
}
function Broadcast3([string]$action, [string]$extra = '') {
    & $adb shell am broadcast -n "$pkg/.DebugCommandReceiver" -a $action $extra | Out-Null
}
function Dump3([string]$name) {
    & $adb shell dumpsys activity activities | Out-File (Join-Path $dir $name) -Encoding utf8
}
function Test-OnRear3([string]$name) {
    $path = Join-Path $dir $name
    if (-not (Test-Path $path)) { return $false }
    $text = [IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false))
    $rear = [regex]::Match($text, 'Display #1 \(activities from top to bottom\):[\s\S]*?(?=Display #\d+ \(activities|\z)')
    return ($rear.Success -and $rear.Value -match 'RearDashboardActivity')
}

$m = [System.Threading.Mutex]::new($false, 'Global\RearCueDevice')
try {
    try { $m.WaitOne() | Out-Null } catch [System.Threading.AbandonedMutexException] { }
    L3 'start'

    # 先拉起应用（清 force-stopped 态），否则显式广播会被静默丢弃
    & $adb shell am start -n "$pkg/$pkg.ui.MainActivity" | Out-Null
    Start-Sleep -Seconds 4
    $appPid = (& $adb shell pidof $pkg).Trim()
    L3 "app pid=$appPid"
    if (-not $appPid) { L3 'ABORT: 应用拉不起来'; return }

    Broadcast3 com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    Broadcast3 com.rearcue.poc.action.CANCEL_PACKAGE "--es pkg $pkg"
    Start-Sleep -Seconds 3
    Broadcast3 com.rearcue.poc.action.POST_TEST
    & $adb shell cmd notification post -t 'RearCue E15' 'rearcue-e15' 'safe area probe 3' | Out-Null
    Start-Sleep -Seconds 8
    Dump3 'activities-07-two-icons.txt'

    # 三级兜底：自动链路 → 调试旁路 → E14 任务搬运事务（同 evidence.ps1）
    if (-not (Test-OnRear3 'activities-07-two-icons.txt')) {
        Broadcast3 com.rearcue.poc.action.PROJECT_REAR
        Start-Sleep -Seconds 5
        Dump3 'activities-08-project.txt'
        L3 ("route project-rear on-rear={0}" -f (Test-OnRear3 'activities-08-project.txt'))
    }
    if (-not (Test-OnRear3 'activities-07-two-icons.txt') -and -not (Test-OnRear3 'activities-08-project.txt')) {
        & $adb shell am start -n "$pkg/$pkg.rear.RearDashboardActivity" | Out-Null
        Start-Sleep -Seconds 5
        Dump3 'activities-09-pre-move.txt'
        $tid = 0
        foreach ($row in [IO.File]::ReadAllLines((Join-Path $dir 'activities-09-pre-move.txt'), [System.Text.UTF8Encoding]::new($false))) {
            if ($row -match 'RearDashboardActivity t(\d+)') { $tid = [int]$Matches[1]; break }
        }
        L3 "task-move rootTaskId=$tid"
        & $adb shell input -d 1 keyevent KEYCODE_WAKEUP | Out-Null
        & $adb shell service call activity_task 51 i32 $tid i32 1 | Out-File (Join-Path $dir 'task-move-3.txt') -Encoding utf8
        Start-Sleep -Seconds 5
        Dump3 'activities-10-post-move.txt'
        L3 ("route task-move on-rear={0}" -f (Test-OnRear3 'activities-10-post-move.txt'))
    }
    Broadcast3 com.rearcue.poc.action.STATE

    $finalOnRear = (Test-OnRear3 'activities-07-two-icons.txt') -or (Test-OnRear3 'activities-08-project.txt') -or (Test-OnRear3 'activities-10-post-move.txt')
    if (-not $finalOnRear) {
        L3 'ABORT: Dashboard 没上背屏，本轮作废（E15-RUN-INVALID）'
    } else {
        # 两个连续分钟各截背屏一张（两个漂移极限位）
        for ($i = 6; $i -le 7; $i++) {
            Start-Sleep -Seconds 61
            & $adb shell input -d 1 keyevent KEYCODE_WAKEUP | Out-Null
            Start-Sleep -Milliseconds 500
            $deviceClock = (& $adb shell date '+%H:%M:%S').Trim()
            $out = Join-Path $shotsDir ('0{0}-rear-drift-2icons.png' -f $i)
            cmd /c "$adb exec-out screencap -d $rearSfId -p > `"$out`""
            L3 ("shot {0} device-clock={1}" -f $i, $deviceClock)
        }
    }

    Broadcast3 com.rearcue.poc.action.EXIT_REAR
    Start-Sleep -Seconds 3
    Broadcast3 com.rearcue.poc.action.CANCEL_TEST
    Broadcast3 com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    & $adb logcat -d -s RearCue | Out-File (Join-Path $dir 'logcat-supplement3.txt') -Encoding utf8
    L3 'supplement3 done'
}
finally {
    $m.ReleaseMutex()
    $m.Dispose()
}

if (Test-Path (Join-Path $shotsDir '06-rear-drift-2icons.png')) {
    & (Join-Path $dir 'measure.ps1') -LogFile 'logcat-supplement3.txt' `
        -Shots @('06-rear-drift-2icons.png', '07-rear-drift-2icons.png') -Out 'measure3.txt'
}
