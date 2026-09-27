# drive-probe-charging-row.ps1 -- probe the settings page dump for the charging switch row.
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'probe-charging-row' | Out-Null
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Start-ExApp -TimeoutSec 30 | Out-Null
Start-Sleep -Seconds 1
if (Open-ExSettingsPage) {
    for ($i = 0; $i -lt 3; $i++) {
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', '900', '2400', '900', '700', '500') -AllowFailure | Out-Null
        Start-Sleep -Seconds 1
    }
    $dump = Get-ExWindowDump
    $dump | Out-File -Encoding utf8 'C:\Users\13691\Desktop\RearCue\docs\poc-logs\probe-charging-dump.xml'
    $chg = Get-ExUiToggleForLabel -WindowDump $dump -Label ([regex]::Unescape('\u5145\u7535\u52A8\u753B'))
    Write-ExNote ('toggle found={0} checked={1} x={2} y={3}' -f $chg.Found, $chg.Checked, $chg.X, $chg.Y)
    $texts = [regex]::Matches($dump, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' }
    Write-ExNote ('texts: ' + ($texts -join ' | '))
    $checkables = [regex]::Matches($dump, 'checkable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    foreach ($m in $checkables) {
        Write-ExNote ('checkable at x={0} y={1}' -f (([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2), (([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2))
    }
} else {
    Write-ExNote 'settings page did not open'
}
