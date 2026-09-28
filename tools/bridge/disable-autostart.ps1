# 注销桥的开机自启（票 #116）。
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v RearCueBridge /f | Out-Null
Write-Host "[autostart] 已删除 HKCU Run\RearCueBridge"
