# 注册常驻自启（票 #116 / Q16=A）：由**计划任务**在登录时拉起桥；进程意外退出由
# start-bridge.cmd 的重试引擎 30 秒重来（最多 3 次，耗尽彻底停），运行日志落同目录
# bridge.log（排障用）。免管理员权限；注销用 disable-autostart.ps1。
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
# 计划任务跑 wscript.exe（GUI 程序，没有控制台）。它隐藏启动 start-bridge.cmd 并等待：
# 重试引擎仍在 cmd 里，任务保持 Running，登录时不再留一个关掉就断桥的窗口。
# 不用 powershell -Command：那层引号会被 Task Scheduler 吃掉（票 #171 实测）。
$hostScript = Join-Path $here "start-bridge-hidden.vbs"
$action = New-ScheduledTaskAction -Execute "wscript.exe" `
    -Argument ('//B //Nologo "' + $hostScript + '"') `
    -WorkingDirectory $here
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERNAME"
# 任务级失败重启**关掉**（票 #189）：Task Scheduler 拒绝 30 秒粒度的 RestartInterval——
# 注册时报「任务 XML 包含格式不正确或超出范围的值 (33,25):Interval:PT30S」，最小 PT1M
# （2026-09-30 实测）。重来节奏（3 次 × 30s，耗尽彻底停）归 start-bridge.cmd 的重试引擎，
# 与自启任务同一节奏（spec 0019）。
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force `
    -Description "RearCue PC bridge: constant collector + cloudflared quick tunnel (auto-pushes URL to phone via adb)" | Out-Null
# 迁移：旧版写过的 HKCU Run 同名项会被重复拉起（后到者抢不到端口），一并清掉。
Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name RearCueBridge -ErrorAction SilentlyContinue
Write-Host "[autostart] task registered: $TaskName (logon + launcher retry 3x30s) log=$log"
Write-Host "[autostart] start now: Start-ScheduledTask -TaskName $TaskName"
