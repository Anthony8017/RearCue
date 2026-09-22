# 票 #26 补拍轮 3（E15，终轮）：重装本分支构建 + 构建自证 + 逐拍落位门
# 前两轮翻车教训（erratum 7/8）：共用设备上并行实验会装走包/留冻结队列，背屏可能已被
# Native Rear Screen 收回。本轮：互斥锁内重装本分支 APK（杀进程清队列）→ 校验应用日志出现
# 本构建的词面 rear-safe-geometry（自证构建）→ 每拍前新鲜 dumpsys 校验 Dashboard 在背屏
# （丢了先补投一次，再丢即作废该拍）。
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe"
$wt = "C:\Users\13691\Desktop\RearCue-wt\03"
$dir = Join-Path $wt 'docs\poc-logs\20260923-043417-rear-safe-area'
$shotsDir = Join-Path $dir 'screenshots'
$pkg = 'com.rearcue.poc'
$rearSfId = '4630946949513469332'
$sessionLog = Join-Path $dir 'session-log.txt'

function L4([string]$msg) {
    $line = ('{0}  [4] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'), $msg)
    $line | Out-File $sessionLog -Encoding utf8 -Append
    Write-Host $line
}
function Broadcast4([string]$action, [string]$extra = '') {
    & $adb shell am broadcast -n "$pkg/.DebugCommandReceiver" -a $action $extra | Out-Null
}
function Test-OnRear4 {
    & $adb shell dumpsys activity activities | Out-File (Join-Path $dir 'activities-live.txt') -Encoding utf8
    $text = [IO.File]::ReadAllText((Join-Path $dir 'activities-live.txt'), [System.Text.UTF8Encoding]::new($false))
    $rear = [regex]::Match($text, 'Display #1 \(activities from top to bottom\):[\s\S]*?(?=Display #\d+ \(activities|\z)')
    return ($rear.Success -and $rear.Value -match 'RearDashboardActivity')
}

$m = [System.Threading.Mutex]::new($false, 'Global\RearCueDevice')
try {
    try { $m.WaitOne() | Out-Null } catch [System.Threading.AbandonedMutexException] { }
    L4 'start'

    # 互斥锁内重装本分支 APK：杀进程清冻结队列 + 保证跑的是本构建
    & $adb install -r "$wt\app\build\outputs\apk\debug\app-debug.apk" |
        Out-File (Join-Path $dir 'install-4.txt') -Encoding utf8
    & $adb shell appops set $pkg 10020 allow | Out-Null
    & $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS
    & $adb shell am start -n "$pkg/$pkg.ui.MainActivity" | Out-Null
    Start-Sleep -Seconds 4

    Broadcast4 com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    Broadcast4 com.rearcue.poc.action.CANCEL_PACKAGE "--es pkg $pkg"
    Start-Sleep -Seconds 3
    Broadcast4 com.rearcue.poc.action.POST_TEST
    & $adb shell cmd notification post -t 'RearCue E15' 'rearcue-e15' 'safe area probe 4' | Out-Null
    Start-Sleep -Seconds 8

    # 构建自证：本构建的词面 rear-safe-geometry 必须出现在应用日志里
    & $adb logcat -d -s RearCue | Out-File (Join-Path $dir 'logcat-supplement4.txt') -Encoding utf8
    $buildProof = Select-String -Path (Join-Path $dir 'logcat-supplement4.txt') -Pattern 'rear-safe-geometry' -Quiet
    L4 "build-proof rear-safe-geometry=$buildProof"
    if (-not $buildProof) { L4 'ABORT: 应用日志没有本构建词面，构建不对'; return }

    # 落位：自动链路 → 调试旁路 → E14 任务搬运事务
    if (-not (Test-OnRear4)) {
        Broadcast4 com.rearcue.poc.action.PROJECT_REAR
        Start-Sleep -Seconds 5
    }
    if (-not (Test-OnRear4)) {
        & $adb shell am start -n "$pkg/$pkg.rear.RearDashboardActivity" | Out-Null
        Start-Sleep -Seconds 5
        $tid = 0
        foreach ($row in [IO.File]::ReadAllLines((Join-Path $dir 'activities-live.txt'), [System.Text.UTF8Encoding]::new($false))) {
            if ($row -match 'RearDashboardActivity t(\d+)') { $tid = [int]$Matches[1]; break }
        }
        L4 "task-move rootTaskId=$tid"
        & $adb shell input -d 1 keyevent KEYCODE_WAKEUP | Out-Null
        & $adb shell service call activity_task 51 i32 $tid i32 1 | Out-File (Join-Path $dir 'task-move-4.txt') -Encoding utf8
        Start-Sleep -Seconds 5
    }
    if (-not (Test-OnRear4)) { L4 'ABORT: Dashboard 没上背屏'; return }
    L4 'on rear'

    # 两个连续分钟各截背屏一张（两个漂移极限位）；每拍前新鲜校验落位
    for ($i = 8; $i -le 9; $i++) {
        Start-Sleep -Seconds 61
        if (-not (Test-OnRear4)) {
            Broadcast4 com.rearcue.poc.action.PROJECT_REAR
            Start-Sleep -Seconds 5
            if (-not (Test-OnRear4)) { L4 ("shot {0} skipped: 落位丢失" -f $i); continue }
        }
        & $adb shell input -d 1 keyevent KEYCODE_WAKEUP | Out-Null
        Start-Sleep -Milliseconds 500
        $deviceClock = (& $adb shell date '+%H:%M:%S').Trim()
        $out = Join-Path $shotsDir ('0{0}-rear-drift-2icons.png' -f $i)
        cmd /c "$adb exec-out screencap -d $rearSfId -p > `"$out`""
        L4 ("shot {0} device-clock={1}" -f $i, $deviceClock)
    }

    Broadcast4 com.rearcue.poc.action.EXIT_REAR
    Start-Sleep -Seconds 3
    Broadcast4 com.rearcue.poc.action.CANCEL_TEST
    Broadcast4 com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    & $adb logcat -d -s RearCue | Out-File (Join-Path $dir 'logcat-supplement4.txt') -Encoding utf8
    L4 'supplement4 done'
}
finally {
    $m.ReleaseMutex()
    $m.Dispose()
}

if ((Test-Path (Join-Path $shotsDir '08-rear-drift-2icons.png')) -and (Test-Path (Join-Path $shotsDir '09-rear-drift-2icons.png'))) {
    & (Join-Path $dir 'measure.ps1') -LogFile 'logcat-supplement4.txt' `
        -Shots @('08-rear-drift-2icons.png', '09-rear-drift-2icons.png') -Out 'measure4.txt'
}
