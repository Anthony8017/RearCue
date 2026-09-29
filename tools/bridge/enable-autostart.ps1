# 注册常驻自启（票 #116 / Q16=A）：由**计划任务**在登录时拉起桥，进程意外退出自动重启，
# 运行日志落同目录 bridge.log（排障用）。免管理员权限；注销用 disable-autostart.ps1。
#
# 为什么不是 HKCU Run（原实现）：Run 键只在登录那一刻拉一次，进程此后死掉没人管、也没有
# 日志可查——2026-09-29 实测桥中途消失，手机侧只剩隧道 530，无从定位（改判留痕）。
#
# 本文件是 UTF-8 无 BOM + CRLF（仓内 .ps1 同例）：Windows PowerShell 5.1 按 ANSI 解码，
# 中文字符串的收尾引号前必须留 ASCII 字符，否则尾字节会吞掉引号、脚本直接语法错。
param(
    [string]$TaskName = "RearCueBridge"
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$bridge = Join-Path $here "bridge.mjs"
$log = Join-Path $here "bridge.log"
# 经 cmd 重定向落日志：直接跑 node 时 stdout 进的是隐藏控制台，掉了什么都看不到。
$arg = '/c node "' + $bridge + '" >> "' + $log + '" 2>&1'
$action = New-ScheduledTaskAction -Execute "cmd.exe" -Argument $arg -WorkingDirectory $here
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force `
    -Description "RearCue PC bridge: constant collector + cloudflared quick tunnel (auto-pushes URL to phone via adb)" | Out-Null
# 迁移：旧版写过的 HKCU Run 同名项会被重复拉起（后到者抢不到端口），一并清掉。
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v RearCueBridge /f 2>$null | Out-Null
Write-Host "[autostart] task registered: $TaskName (logon + restart 5x) log=$log"
Write-Host "[autostart] start now: Start-ScheduledTask -TaskName $TaskName"
