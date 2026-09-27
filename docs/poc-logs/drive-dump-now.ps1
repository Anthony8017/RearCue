# drive-dump-now.ps1 -- dump whatever is on screen right now. ASCII-only source.
$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'dump-now' | Out-Null
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Start-Sleep -Seconds 1
Write-ExNote ('settings-open: ' + (Test-ExSettingsPageOpen))
Write-ExNote ('focus: ' + (Get-ExCurrentFocus))
$d = Get-ExWindowDump
$d | Out-File -Encoding utf8 'C:\Users\13691\Desktop\RearCue\docs\poc-logs\now-dump.xml'
$texts = [regex]::Matches($d, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' }
Write-ExNote ('texts: ' + ($texts -join ' | '))
$wak = Get-ExWakefulness
Write-ExNote ('wakefulness: ' + $wak)
