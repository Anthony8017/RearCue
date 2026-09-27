# drive-dump-settings3.ps1 -- open the settings page and archive three consecutive dumps.
$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'dump-settings3' | Out-Null
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', 'com.rearcue.poc/.ui.MainActivity') -AllowFailure | Out-Null
Start-Sleep -Seconds 2
$opened = Open-ExSettingsPage
Write-ExNote ('open result: ' + $opened)
for ($i = 1; $i -le 3; $i++) {
    Start-Sleep -Seconds 1
    $d = Get-ExWindowDump
    $d | Out-File -Encoding utf8 ('C:\Users\13691\Desktop\RearCue\docs\poc-logs\settings-dump-{0}.xml' -f $i)
    $texts = [regex]::Matches($d, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' }
    Write-ExNote ('dump {0} open={1} texts: {2}' -f $i, (Test-ExSettingsPageOpen), (($texts | Select-Object -First 8) -join ' | '))
}
