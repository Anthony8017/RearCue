# 票 #25 补拍：空态/禁用态 + 触控目标 bounds + 图标态/可用态（主屏息屏导致首轮 03/04 未写盘）
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe"
$dir = "C:\Users\13691\Desktop\RearCue-wt\02\docs\poc-logs\20260923-030129-main-ui-refresh"
$shots = Join-Path $dir 'screenshots'
$pkg = 'com.rearcue.poc'

function Wake() {
    & $adb shell input keyevent KEYCODE_WAKEUP
    & $adb shell wm dismiss-keyguard
    Start-Sleep -Milliseconds 800
}
function Shot([string]$name) {
    Wake
    # 本机 HyperOS 的 screencap 不认 -d <id>（Display Id '0'/'1' is not valid），缺省 = 第一个 display = 主屏
    & $adb shell screencap -p /sdcard/rc-shot.png
    & $adb pull /sdcard/rc-shot.png (Join-Path $shots $name) | Out-Null
}
function Dump([string]$name) {
    & $adb shell uiautomator dump /sdcard/rc-ui.xml
    & $adb pull /sdcard/rc-ui.xml (Join-Path $dir $name) | Out-Null
}
function Broadcast([string]$action, [string]$extra = '') {
    & $adb shell am broadcast -n "$pkg/.DebugCommandReceiver" -a $action $extra
}

$m = [System.Threading.Mutex]::new($false, 'Global\RearCueDevice')
try {
    # notes/env.md 修正写法：AbandonedMutexException 捕获后继续（锁已授予本线程）
    try { $m.WaitOne() | Out-Null } catch [System.Threading.AbandonedMutexException] { }
    "supplement started: $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))" |
        Out-File (Join-Path $dir 'supplement.txt') -Encoding utf8

    # 主屏保持亮屏（首轮 03/04 未写盘的根因排查：息屏后 screencap 不出文件）
    $oldTimeout = (& $adb shell settings get system screen_off_timeout).Trim()
    "screen_off_timeout before: $oldTimeout" | Out-File (Join-Path $dir 'supplement.txt') -Encoding utf8 -Append
    & $adb shell settings put system screen_off_timeout 600000

    # 清掉测试产物通知（shell / 本应用）：Icon Set 应清空 → 自动下屏 →「退出」呈禁用态
    Broadcast com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    Broadcast com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.rearcue.poc'
    Start-Sleep -Seconds 3

    # 05：空态 + 未投送（禁用「退出」）
    Shot '05-main-empty-portrait.png'
    Dump 'ui-05-empty.xml'
    # 滚到底：调试动作区（触控目标 bounds）
    & $adb shell input swipe 600 2200 600 800 300
    Start-Sleep -Seconds 1
    Shot '06-main-actions-disabled-portrait.png'
    Dump 'ui-06-actions-disabled.xml'

    # 07/08：Icon Set 非空 + 已投送（「退出」可用）
    Broadcast com.rearcue.poc.action.POST_TEST
    Start-Sleep -Seconds 2
    & $adb shell input swipe 600 800 600 2200 300
    Start-Sleep -Seconds 1
    Shot '07-main-iconset-portrait.png'
    Broadcast com.rearcue.poc.action.PROJECT_REAR
    Start-Sleep -Seconds 3
    & $adb shell input swipe 600 2200 600 800 300
    Start-Sleep -Seconds 1
    Shot '08-main-actions-enabled-portrait.png'
    Dump 'ui-08-actions-enabled.xml'
    Broadcast com.rearcue.poc.action.EXIT_REAR
    Start-Sleep -Seconds 2
    Broadcast com.rearcue.poc.action.CANCEL_TEST

    & $adb logcat -d -s RearCue *>&1 | Out-File (Join-Path $dir 'logcat-supplement.txt') -Encoding utf8
    & $adb shell settings put system screen_off_timeout $oldTimeout
    "screen_off_timeout restored: $oldTimeout`r`nsupplement done: $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))" |
        Out-File (Join-Path $dir 'supplement.txt') -Encoding utf8 -Append
    & $adb shell rm -f /sdcard/rc-shot.png /sdcard/rc-ui.xml
}
finally {
    $m.ReleaseMutex()
    $m.Dispose()
}
