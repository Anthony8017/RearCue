# drive-dump-main2.ps1 -- dump MainActivity for the project button label. ASCII-only source.
$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'dumpmain' | Out-Null
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', 'com.rearcue.poc/.ui.MainActivity') -AllowFailure | Out-Null
Start-Sleep -Seconds 3
$d = Get-ExWindowDump
$d | Out-File -Encoding utf8 'C:\Users\13691\Desktop\RearCue\docs\poc-logs\main-dump.xml'
$texts = [regex]::Matches($d, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' }
Write-ExNote ('texts: ' + ($texts -join ' | '))
Write-ExNote ('focus: ' + (Get-ExCurrentFocus))
