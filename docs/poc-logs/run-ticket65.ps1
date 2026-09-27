# 票 #65 实机验收驱动脚本（Notification Highlight）：正向腿需手机倒扣（姿态门放行）。
# 用法: powershell -File docs/poc-logs/run-ticket65.ps1 -Phase <leg1|leg2|leg3|leg4|leg5|reset>
param(
  [Parameter(Mandatory=$true)][ValidateSet('leg1','leg2','leg3','leg4','leg5','reset','posture')] [string]$Phase,
  [string]$DeviceId = '94250f9e',
  [string]$OutDir = ''
)
$ErrorActionPreference = 'Stop'
$Adb = "$env:LOCALAPPDATA\RearCue-tools\android-sdk\platform-tools\adb.exe"
function Sh($c) { & $Adb -s $DeviceId shell $c }
function Snap($name) {
  Sh "screencap -d 4630946949513469332 -p /data/local/tmp/t65.png"
  & $Adb -s $DeviceId pull /data/local/tmp/t65.png "$OutDir\screenshots\$name.png" | Out-Null
  Write-Host "snap -> screenshots/$name.png"
}
function Log($name) { & $Adb -s $DeviceId logcat -d -s RearCue > "$OutDir\$name"; Write-Host "log -> $name" }

switch ($Phase) {
  'posture' { Sh "dumpsys sensors 2>/dev/null | grep -i -A2 prox | head -8"; & $Adb -s $DeviceId logcat -d -s RearCue | Select-Object -Last 5 }
  'reset' {
    Sh "cmd notification set_dnd off; am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.CANCEL_PACKAGE --es pkg com.android.shell" | Out-Null
    Sh "am broadcast -a com.rearcue.poc.action.CANCEL_TEST -n com.rearcue.poc/.DebugCommandReceiver" | Out-Null
    Start-Sleep -Seconds 2
    & $Adb -s $DeviceId logcat -c
    Write-Host 'reset done, logcat cleared'
  }
  'leg1' {
    # 首条 shell 通知：自动投送 + 呼吸一次。截图两连拍：1.2s（呼吸中）、5s（呼吸后高亮保持）。
    Sh "cmd notification post -t RearCue t65a 'ticket65 first message'"
    Start-Sleep -Milliseconds 1100
    Snap '01-breath-mid'
    Start-Sleep -Milliseconds 900
    Snap '02-breath-late'
    Start-Sleep -Seconds 3
    Snap '03-after-breath-highlight-kept'
    Log 'leg1-logcat.txt'
  }
  'leg2' {
    # 冷却内第二条（不同应用 com.rearcue.poc，调试旁路 POST_TEST）：不重复呼吸、高亮集 +1。
    Sh "am broadcast -a com.rearcue.poc.action.POST_TEST -n com.rearcue.poc/.DebugCommandReceiver" | Out-Null
    Start-Sleep -Seconds 2
    Snap '04-second-app-highlight-set-grows'
    Log 'leg2-logcat.txt'
  }
  'leg3' {
    # 清除全部：高亮熄灭 + auto 退出还原原生。
    Sh "am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.CANCEL_PACKAGE --es pkg com.android.shell"
    Sh "am broadcast -a com.rearcue.poc.action.CANCEL_TEST -n com.rearcue.poc/.DebugCommandReceiver" | Out-Null
    Start-Sleep -Seconds 2
    Snap '05-after-clear-native-restored'
    Log 'leg3-logcat.txt'
  }
  'leg4' {
    # DND 腿：重新来一条（重建在屏）→ DND 开 → 撤下清高亮；DND 中再来一条 → 无呼吸。
    Sh "cmd notification post -t RearCue t65b 'rebuild before dnd'"
    Start-Sleep -Seconds 3
    Sh "cmd notification set_dnd priority"
    Start-Sleep -Seconds 2
    Snap '06-dnd-withdrew-dashboard'
    Sh "cmd notification post -t RearCue t65c 'posted during dnd'"
    Start-Sleep -Seconds 2
    Snap '07-dnd-no-breath'
    Log 'leg4-logcat.txt'
  }
  'leg5' {
    # Updated 腿：DND 关、冷却过期后同 tag 改内容重发 → 同 key 更新触发呼吸（改写票 64 断言的新真相）。
    Sh "cmd notification set_dnd off; am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.CANCEL_PACKAGE --es pkg com.android.shell"
    Start-Sleep -Seconds 30
    Sh "cmd notification post -t RearCue t65b 'ticket65 UPDATED content'"
    Start-Sleep -Milliseconds 1200
    Snap '08-updated-triggers-breath'
    Log 'leg5-logcat.txt'
  }
}
