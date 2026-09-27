# drive-shizuku-grant.ps1 -- re-grant the Shizuku runtime permission after the reinstall
# (signature-change uninstall cleared it; poc-findings "20260922-233947" records the same trap).
# Route: MainActivity "project to rear" ActionButton (the only requestPermission() caller) ->
# the Shizuku manager dialog -> tap the allow button -> judge by the app's `granted=true` line.
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'shizuku-grant' | Out-Null
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null

$projectLabel = [regex]::Unescape('\u6295\u9001\u5230\u80CC\u5C4F')   # action_project_to_rear
$allowLabel = [regex]::Unescape('\u5141\u8BB8')                       # "Allow"
$allowAlways = [regex]::Unescape('\u59CB\u7EC8\u5141\u8BB8')          # "Always allow"

$grantedLine = 'granted=true'
$before = Get-ExLogMatchCount $grantedLine
Start-ExApp -TimeoutSec 30 | Out-Null
Start-Sleep -Seconds 2

# fire the request: expand the collapsed developer-options section first (the ActionButton
# lives inside it), then tap "project to rear" -- the only requestPermission() caller
$devLabel = [regex]::Unescape('\u5F00\u53D1\u8005\u9009\u9879')       # section_dev_options
$dev = Get-ExNodeCenter -WindowDump (Get-ExWindowDump) -Text $devLabel
if ($null -eq $dev) {
    Write-ExNote 'developer-options header not found on MainActivity'
    exit 1
}
Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$dev.X, [string]$dev.Y) -AllowFailure | Out-Null
Write-ExNote ('expanded developer options (tap {0},{1})' -f $dev.X, $dev.Y)
Start-Sleep -Seconds 1

$btn = $null
for ($i = 0; $i -lt 6 -and -not $btn; $i++) {
    $dump = Get-ExWindowDump
    $btn = Get-ExNodeCenter -WindowDump $dump -Text $projectLabel
    if ($null -eq $btn) {
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '2100', '550', '900', '350') -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 800
    }
}
if ($null -eq $btn) {
    Write-ExNote 'project button not found on MainActivity'
    exit 1
}
Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$btn.X, [string]$btn.Y) -AllowFailure | Out-Null
Write-ExNote ('tapped project button at {0},{1}' -f $btn.X, $btn.Y)
Start-Sleep -Seconds 2

$ok = $false
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    $hit = Wait-ExNewLog -Pattern $grantedLine -Before $before -TimeoutSec 3
    if (@($hit).Count -gt 0) { $ok = $true; break }

    $dump = Get-ExWindowDump
    $focus = Get-ExCurrentFocus
    if ($focus -and $focus -match 'shizuku') {
        $tapped = $false
        foreach ($label in @($allowAlways, $allowLabel, 'Allow', 'ALLOW')) {
            $node = Get-ExNodeCenter -WindowDump $dump -Text $label
            if ($null -ne $node) {
                Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$node.X, [string]$node.Y) -AllowFailure | Out-Null
                Write-ExNote ('tapped Shizuku dialog button "{0}" at {1},{2}' -f $label, $node.X, $node.Y)
                $tapped = $true
                Start-Sleep -Seconds 2
                break
            }
        }
        if (-not $tapped) {
            Write-ExArtifact -Name 'shizuku-dialog.xml' -Lines @($dump) | Out-Null
            Write-ExNote 'shizuku window focused but no known allow label; dump archived'
            Start-Sleep -Seconds 3
        }
    } else {
        Start-Sleep -Seconds 2
    }
}

Write-ExNote ('granted line new: {0}' -f $ok)
if ($ok) { Write-ExNote 'SHIZUKU-GRANT-OK'; exit 0 }
Write-ExNote 'SHIZUKU-GRANT-FAIL (needs the human to tap the Shizuku dialog)'
exit 1
