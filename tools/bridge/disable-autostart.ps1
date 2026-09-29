# 注销桥的常驻自启（票 #116）：删计划任务，并清掉旧版留下的 HKCU Run 同名项。
# 已在运行的那一个实例不受影响——要停它：Stop-ScheduledTask -TaskName RearCueBridge。
#
# 本文件是 UTF-8 无 BOM + CRLF（仓内 .ps1 同例）：Windows PowerShell 5.1 按 ANSI 解码，
# 中文字符串的收尾引号前必须留 ASCII 字符，否则尾字节会吞掉引号、脚本直接语法错。
param(
    [string]$TaskName = "RearCueBridge"
)
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v RearCueBridge /f 2>$null | Out-Null
Write-Host "[autostart] task removed: $TaskName (+ HKCU Run\RearCueBridge)"
