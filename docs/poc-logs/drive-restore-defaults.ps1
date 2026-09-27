# drive-restore-defaults.ps1 -- normalize feed settings to documented defaults with the
# hardened drive path, so ex task 21's own normalization becomes a no-op. ASCII-only source.
$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'restore-defaults' | Out-Null
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Start-ExApp -TimeoutSec 30 | Out-Null
Start-Sleep -Seconds 2

# drive functions (same hardened lineage as drive-persist-reboot.ps1)
function Find-ExSettingsNode {
    param([string] $Text, [string] $ContentDesc, [int] $Passes = 3)
    if (-not (Test-ExSettingsPageOpen)) {
        Write-ExNote 'settings page not front; re-opening'
        if (-not (Open-ExSettingsPage)) { return $null }
    }
    for ($pass = 0; $pass -lt $Passes; $pass++) {
        $dump = Get-ExWindowDump
        $node = if ($ContentDesc) { Get-ExNodeCenter -WindowDump $dump -ContentDesc $ContentDesc }
                else { Get-ExNodeCenter -WindowDump $dump -Text $Text }
        if ($null -ne $node) { return $node }
        if ($pass -eq 0) {
            Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '2300', '550', '700', '350') -AllowFailure | Out-Null
        } elseif ($pass -eq 1) {
            Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '700', '550', '2300', '350') -AllowFailure | Out-Null
            Start-Sleep -Milliseconds 400
            Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '700', '550', '2300', '350') -AllowFailure | Out-Null
        }
        Start-Sleep -Milliseconds 900
    }
    return $null
}

function Set-ExFeedPrivacy2 {
    param([bool] $Enabled)
    $state = Get-ExAppStateNow
    if (($null -ne $state) -and ($state.FeedPrivacyMode -eq $Enabled)) { return $true }
    $node = Find-ExSettingsNode -Text 'Privacy Mode'
    if ($null -eq $node) { Write-ExNote 'privacy row not found'; return $false }
    $want = if ($Enabled) { 'feed-settings page privacy=true' } else { 'feed-settings page privacy=false' }
    $before = Get-ExLogMatchCount ([regex]::Escape($want))
    Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$node.X, [string]$node.Y) -AllowFailure | Out-Null
    $hit = Wait-ExNewLog -Pattern $want -Before $before -TimeoutSec 8
    $after = Get-ExAppStateNow
    return ((@($hit).Count -gt 0) -and ($null -ne $after) -and ($after.FeedPrivacyMode -eq $Enabled))
}

function Set-ExFeedAutoDismiss2 {
    param([long] $TargetMs)
    $want = ('feed-settings page autoDismiss={0}ms' -f $TargetMs)
    $before = Get-ExLogMatchCount ([regex]::Escape($want))
    $entry = Get-ExAppStateNow
    if (($null -ne $entry) -and ($entry.FeedAutoDismissMs -eq $TargetMs)) { return $true }
    $unitNode = Find-ExSettingsNode -Text ([regex]::Unescape('\u79D2'))
    if ($null -eq $unitNode) { Write-ExNote 'unit chip not found'; return $false }
    Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$unitNode.X, [string]$unitNode.Y) -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 700
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $fieldNode = Find-ExSettingsNode -ContentDesc 'Auto-dismiss value'
        if ($null -eq $fieldNode) { Write-ExNote 'value field not found'; return $false }
        Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$fieldNode.X, [string]$fieldNode.Y) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 700
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END') -AllowFailure | Out-Null
        for ($i = 0; $i -lt 8; $i++) {
            Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_DEL') -AllowFailure | Out-Null
        }
        Start-Sleep -Milliseconds 300
        Invoke-Adb -Arguments @('shell', 'input', 'text', ([string][long]($TargetMs / 1000))) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 900
        $hit = Wait-ExNewLog -Pattern ([regex]::Escape($want)) -Before $before -TimeoutSec 6
        $state = Get-ExAppStateNow
        if ((@($hit).Count -gt 0) -and ($null -ne $state) -and ($state.FeedAutoDismissMs -eq $TargetMs)) { return $true }
        Write-ExNote ('attempt {0} missed (state {1})' -f $attempt, $(if ($state) { $state.FeedAutoDismissMs } else { 'n/a' }))
    }
    return $false
}

if (Open-ExSettingsPage) {
    $p = Set-ExFeedPrivacy2 -Enabled $true
    $a = Set-ExFeedAutoDismiss2 -TargetMs 10000
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 800
} else {
    Write-ExNote 'settings page never came up'
    exit 1
}
$final = Get-ExAppStateNow
Write-ExNote ('restore done privacy-true={0} dismiss-10s={1}; STATE privacy={2} autoDismiss={3}ms charging={4}' -f
    $p, $a,
    $(if ($final) { $final.FeedPrivacyMode } else { 'n/a' }),
    $(if ($final) { $final.FeedAutoDismissMs } else { 'n/a' }),
    $(if ($final) { $final.ChargingEnabled } else { 'n/a' }))
if ($p -and $a -and $final -and ($final.FeedPrivacyMode -eq $true) -and ($final.FeedAutoDismissMs -eq 10000) -and ($final.ChargingEnabled -eq $true)) {
    Write-ExNote 'DEFAULTS-OK'; exit 0
}
Write-ExNote 'DEFAULTS-FAIL'; exit 1
