# 注销桥的常驻自启（票 #116）：删计划任务 + **停掉正在跑的那一份** + 清掉旧版留下的 HKCU Run 同名项。
#
# 为什么必须自己动手停（2026-09-30 实测）：计划任务的动作是 `cmd.exe /c start-bridge.cmd`，
# 而 start-bridge.cmd 又套了一层 `powershell -WindowStyle Hidden` 才起 node——
# `Stop-ScheduledTask` 只收掉任务直接持有的那个进程，**node / 托盘 / cloudflared 全变孤儿**：
# 端口仍被占、下一次 Start-ScheduledTask 直接撞 EADDRINUSE 静默失败，而体检还显示"在线"
# （活着的是那个没人管的孤儿）。所以这里按命令行精确找出桥的 node 进程，连同它的子进程一起收掉。
param(
    [string]$TaskName = "RearCueBridge"
)
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}
# 收掉孤儿进程树（只认命令行里带 tools\bridge\bridge.mjs 的 node，不碰别的 node）。
$orphans = @(Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
    Where-Object { $_.CommandLine -like "*bridge.mjs*" })
foreach ($p in $orphans) {
    taskkill /F /T /PID $p.ProcessId 2>$null | Out-Null
}
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v RearCueBridge /f 2>$null | Out-Null
Write-Host "[autostart] task removed: $TaskName (+ HKCU Run\RearCueBridge)"
Write-Host "[autostart] stopped bridge processes: $(if ($orphans.Count) { ($orphans.ProcessId -join ',') } else { 'none' })"
