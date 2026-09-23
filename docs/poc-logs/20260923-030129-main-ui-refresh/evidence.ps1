# 票 #25 实机视觉验收取证（Global\RearCueDevice 互斥锁内串行；证据归档本目录）
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe"
$dir = "C:\Users\13691\Desktop\RearCue-wt\02\docs\poc-logs\20260923-030129-main-ui-refresh"
$shots = Join-Path $dir 'screenshots'
New-Item -ItemType Directory -Force -Path $shots | Out-Null
$apk = "C:\Users\13691\Desktop\RearCue-wt\02\app\build\outputs\apk\debug\app-debug.apk"
$pkg = 'com.rearcue.poc'

function Shot([string]$name) {
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
    try { $m.WaitOne() | Out-Null } catch [System.Threading.AbandonedMutexException] { }
    $t0 = Get-Date
    "started  : $($t0.ToString('yyyy-MM-dd HH:mm:ss'))`r`ndevice   : 94250f9e`r`nsession  : $dir" | Out-File (Join-Path $dir 'session.md') -Encoding utf8

    & $adb install -r $apk *>&1 | Out-File (Join-Path $dir 'install.txt') -Encoding utf8
    & $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS *>&1 |
        Out-File (Join-Path $dir 'grant.txt') -Encoding utf8
    & $adb shell am force-stop $pkg
    & $adb logcat -c
    & $adb shell am start -n "$pkg/.ui.MainActivity" *>&1 | Out-File (Join-Path $dir 'am-start.txt') -Encoding utf8
    Start-Sleep -Seconds 4

    # 设备事实（挖孔/圆角/手势条/醒屏状态）
    & $adb shell "dumpsys power | grep -E 'mWakefulness=|Display Power'" *>&1 |
        Out-File (Join-Path $dir 'device-state.txt') -Encoding utf8
    & $adb shell "dumpsys window | grep -i -E 'roundedcorner|displaycutout|insetsstate' | head -40" *>&1 |
        Out-File (Join-Path $dir 'insets-window.txt') -Encoding utf8
    & $adb shell "dumpsys display | grep -i -B1 -A3 cutout | head -30" *>&1 |
        Out-File (Join-Path $dir 'display-cutout.txt') -Encoding utf8

    # 01：竖屏空态 + 禁用态（未投送 →「退出背屏 Dashboard」禁用）
    Shot '01-main-empty-portrait.png'
    Dump 'ui-01-empty.xml'

    # 02：横屏安全区（挖孔/圆角/手势条避让）
    & $adb shell settings put system accelerometer_rotation 0
    & $adb shell settings put system user_rotation 1
    Start-Sleep -Seconds 3
    Shot '02-main-empty-landscape.png'
    & $adb shell settings put system user_rotation 0
    & $adb shell settings put system accelerometer_rotation 1
    Start-Sleep -Seconds 3

    # 调试旁路语义复核（adb 命令逐条跑一遍，词面见 DebugCommandReceiver）
    Broadcast com.rearcue.poc.action.STATE
    Broadcast com.rearcue.poc.action.POST_TEST
    Start-Sleep -Seconds 3
    # 03：Icon Set 非空（测试通知一枚）+ 未投送禁用态
    Shot '03-main-iconset-portrait.png'
    Broadcast com.rearcue.poc.action.PROJECT_REAR
    Start-Sleep -Seconds 4
    # 04：已投送 →「退出背屏 Dashboard」可用
    Shot '04-main-projected-portrait.png'
    Dump 'ui-04-projected.xml'
    Broadcast com.rearcue.poc.action.EXIT_REAR
    Start-Sleep -Seconds 3
    Broadcast com.rearcue.poc.action.CANCEL_TEST
    Start-Sleep -Seconds 2
    Broadcast com.rearcue.poc.action.STATE

    & $adb logcat -d -s RearCue *>&1 | Out-File (Join-Path $dir 'logcat-rearcue.txt') -Encoding utf8
    & $adb shell rm /sdcard/rc-shot.png /sdcard/rc-ui.xml
    "done     : $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))" | Out-File (Join-Path $dir 'done.txt') -Encoding utf8
}
finally {
    $m.ReleaseMutex()
    $m.Dispose()
}
