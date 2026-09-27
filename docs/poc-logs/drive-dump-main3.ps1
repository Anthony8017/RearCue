# drive-dump-main3.ps1 -- scroll MainActivity to the bottom and dump the action buttons.
$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'dumpmain-bottom' | Out-Null
Set-ExScreenAwake
for ($i = 0; $i -lt 4; $i++) {
    Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '2100', '550', '600', '400') -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 600
}
Start-Sleep -Seconds 1
$d = Get-ExWindowDump
$d | Out-File -Encoding utf8 'C:\Users\13691\Desktop\RearCue\docs\poc-logs\main-dump-bottom.xml'
$texts = [regex]::Matches($d, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' }
Write-ExNote ('texts: ' + ($texts -join ' | '))
$btn = Get-ExNodeCenter -WindowDump $d -Text ([regex]::Unescape('\u6295\u9001\u5230\u80CC\u5C4F'))
Write-ExNote ('project button: ' + $(if ($btn) { "x=$($btn.X) y=$($btn.Y)" } else { 'not found' }))
