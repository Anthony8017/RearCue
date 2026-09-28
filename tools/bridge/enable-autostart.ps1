# 注册开机自启（票 #116 / Q16=A）：HKCU Run 键——登录时自动拉起桥（无示例源，
# 适配器接真会话）。免管理员权限；注销用 disable-autostart.ps1。
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$bridge = Join-Path $here "bridge.mjs"
$cmd = "node `"$bridge`""
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v RearCueBridge /t REG_SZ /d $cmd /f | Out-Null
Write-Host "[autostart] 已注册 HKCU Run\RearCueBridge: $cmd"
Write-Host "[autostart] 立即启动: powershell -File `"$here\start.ps1`""
