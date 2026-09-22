# 票 #26 补拍轮（E15）：Icon Set 两枚（本应用 + shell）的安全区留痕
# 首轮 POST_TEST 的本应用测试通知被系统静默丢弃（POST_NOTIFICATIONS 未授权），Icon Set 只有
# shell 一枚；本轮 pm grant 后重拍两枚形态。判定走 measure.ps1（同口径）。
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe"
$dir = "C:\Users\13691\Desktop\RearCue-wt\03\docs\poc-logs\20260923-043417-rear-safe-area"
$shotsDir = Join-Path $dir 'screenshots'
$pkg = 'com.rearcue.poc'
$rearSfId = '4630946949513469332'
$sessionLog = Join-Path $dir 'session-log.txt'

function L2([string]$msg) {
    $line = ('{0}  [2] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'), $msg)
    $line | Out-File $sessionLog -Encoding utf8 -Append
    Write-Host $line
}
function Broadcast2([string]$action, [string]$extra = '') {
    & $adb shell am broadcast -n "$pkg/.DebugCommandReceiver" -a $action $extra | Out-Null
}

$m = [System.Threading.Mutex]::new($false, 'Global\RearCueDevice')
try {
    try { $m.WaitOne() | Out-Null } catch [System.Threading.AbandonedMutexException] { }
    L2 'start'

    # 首轮缺口：POST_NOTIFICATIONS 未授权 → POST_TEST 被静默丢弃
    & $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS
    L2 'pm grant POST_NOTIFICATIONS'

    Broadcast2 com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    Broadcast2 com.rearcue.poc.action.CANCEL_PACKAGE "--es pkg $pkg"
    Start-Sleep -Seconds 3
    Broadcast2 com.rearcue.poc.action.POST_TEST
    & $adb shell cmd notification post -t 'RearCue E15' 'rearcue-e15' 'safe area probe 2' | Out-Null
    Start-Sleep -Seconds 8
    Broadcast2 com.rearcue.poc.action.STATE

    & $adb shell dumpsys activity activities | Out-File (Join-Path $dir 'activities-06-two-icons.txt') -Encoding utf8

    # 两个连续分钟各截背屏一张（两个漂移极限位）
    for ($i = 4; $i -le 5; $i++) {
        Start-Sleep -Seconds 61
        & $adb shell input -d 1 keyevent KEYCODE_WAKEUP | Out-Null
        Start-Sleep -Milliseconds 500
        $deviceClock = (& $adb shell date '+%H:%M:%S').Trim()
        $out = Join-Path $shotsDir ('0{0}-rear-drift-2icons.png' -f $i)
        cmd /c "$adb exec-out screencap -d $rearSfId -p > `"$out`""
        L2 ("shot {0} device-clock={1}" -f $i, $deviceClock)
    }

    Broadcast2 com.rearcue.poc.action.EXIT_REAR
    Start-Sleep -Seconds 3
    Broadcast2 com.rearcue.poc.action.CANCEL_TEST
    Broadcast2 com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    & $adb logcat -d -s RearCue | Out-File (Join-Path $dir 'logcat-supplement.txt') -Encoding utf8
    L2 'supplement done'
}
finally {
    $m.ReleaseMutex()
    $m.Dispose()
}

& (Join-Path $dir 'measure.ps1') -LogFile 'logcat-supplement.txt' `
    -Shots @('04-rear-drift-2icons.png', '05-rear-drift-2icons.png') -Out 'measure2.txt'
