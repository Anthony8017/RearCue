# drive-scroll-dump.ps1 -- from the open settings page, run the 21-style scroll passes and
# archive a full dump after each, printing every text. ASCII-only source.
$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'scroll-dump' | Out-Null
for ($i = 0; $i -lt 4; $i++) {
    if ($i -gt 0) {
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '2300', '550', '700', '350') -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 900
    }
    $d = Get-ExWindowDump
    $d | Out-File -Encoding utf8 ('C:\Users\13691\Desktop\RearCue\docs\poc-logs\scroll-dump-{0}.xml' -f $i)
    $texts = [regex]::Matches($d, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' }
    $hasPrivacy = ($d -match 'Privacy Mode')
    Write-ExNote ('pass {0}: nodeCount={1} hasPrivacy={2} tail: {3}' -f $i, ([regex]::Matches($d, '<node')).Count, $hasPrivacy, (($texts | Select-Object -Last 6) -join ' | '))
}
