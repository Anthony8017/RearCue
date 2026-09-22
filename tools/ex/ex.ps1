# ex.ps1 -- one-command entry point for the RearCue PC experiment script set (ticket #7).
#
#   .\tools\ex\ex.ps1                          # the whole chain: install -> authorize -> Shizuku
#                                              # -> notifications -> E1/E7/E3 lock -> collect
#   .\tools\ex\ex.ps1 -Task install -Build      # rebuild + install only
#   .\tools\ex\ex.ps1 -Task e8                  # E8: kill + restart the Shizuku server
#   .\tools\ex\ex.ps1 -Task photos              # E1 + lock with the three photo checkpoints paused
#   .\tools\ex\ex.ps1 -Task selftest            # Pester tests of the parsing seam (no device)
#
# Every run creates docs/poc-logs/<stamp>-<task>/ holding the logcat, dumpsys, screenshots,
# per-step verdicts and summary.md. Nothing is judged by "the command exited 0": the verdicts come
# from parsing `dumpsys activity activities` (who owns display #1) and the app's own logcat lines.
#
# Steps are ordinary scripts and can be run alone (see tools/ex/README.md).
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [ValidateSet('all', 'install', 'authorize', 'shizuku', 'e8', 'drive', 'collect', 'photos', 'selftest')]
    [string] $Task = 'all',
    [ValidateSet('e1', 'e7', 'e3-lock')][string[]] $Scenario,
    [int] $LockSeconds = 120,
    [int] $SampleSeconds = 5,
    [switch] $Build,
    [switch] $NoUnlock,
    [switch] $KeepKeyguard,
    [switch] $HoldDashboard,
    [string] $Serial,
    [string] $StartScript = '/data/local/tmp/start-shizuku.sh'
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

if ($Task -eq 'selftest') {
    $tests = Join-Path $PSScriptRoot 'tests'
    $result = Invoke-Pester -Path $tests -PassThru -Quiet
    Write-Host ('selftest: passed={0} failed={1}' -f $result.PassedCount, $result.FailedCount)
    foreach ($failed in ($result.TestResult | Where-Object { -not $_.Passed })) {
        Write-Host ('  FAIL {0} / {1}: {2}' -f $failed.Describe, $failed.Name, $failed.FailureMessage)
    }
    if ($result.FailedCount -gt 0) { exit 1 }
    exit 0
}

if (-not $Scenario) {
    $Scenario = switch ($Task) {
        'photos' { @('e1', 'e3-lock') }
        'drive' { @('e1', 'e7', 'e3-lock') }
        default { @('e1', 'e7', 'e3-lock') }
    }
}

New-ExDeviceSession -Name $Task -Serial $Serial | Out-Null
Clear-ExLogcat
if ($Task -in @('all', 'e8', 'drive', 'photos') -and (Test-ExKeyguardLocked)) {
    Write-ExNote 'WARNING: the phone is keyguard-locked. HyperOS denies third-party rear-display launches'
    Write-ExNote '         while locked (ActivityStarterImpl: rearDisplay check locked -> deny), so E1/E7/E3'
    Write-ExNote '         will report failures that are not the app`s fault. Unlock the phone first --'
    Write-ExNote '         a secure lock (PIN/pattern) cannot be dismissed over adb.'
}

switch ($Task) {
    'install' { & (Join-Path $PSScriptRoot '01-install.ps1') -Build:$Build -KeepKeyguard:$KeepKeyguard -Serial $Serial }
    'authorize' { & (Join-Path $PSScriptRoot '02-authorize.ps1') -Serial $Serial }
    'shizuku' { & (Join-Path $PSScriptRoot '03-shizuku.ps1') -StartScript $StartScript -Serial $Serial }
    'e8' { & (Join-Path $PSScriptRoot '03-shizuku.ps1') -Restart -StartScript $StartScript -Serial $Serial }
    'drive' {
        & (Join-Path $PSScriptRoot '04-drive.ps1') -Scenario $Scenario -LockSeconds $LockSeconds `
            -SampleSeconds $SampleSeconds -NoUnlock:$NoUnlock -HoldDashboard:$HoldDashboard -Serial $Serial
    }
    'collect' { & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial }
    'photos' {
        & (Join-Path $PSScriptRoot '04-drive.ps1') -Scenario $Scenario -LockSeconds $LockSeconds `
            -SampleSeconds $SampleSeconds -NoUnlock:$NoUnlock -PhotoPause -HoldDashboard:$HoldDashboard -Serial $Serial
    }
    'all' {
        & (Join-Path $PSScriptRoot '01-install.ps1') -Build:$Build -KeepKeyguard:$KeepKeyguard -Serial $Serial
        & (Join-Path $PSScriptRoot '02-authorize.ps1') -Serial $Serial
        & (Join-Path $PSScriptRoot '03-shizuku.ps1') -StartScript $StartScript -Serial $Serial
        & (Join-Path $PSScriptRoot '04-drive.ps1') -Scenario $Scenario -LockSeconds $LockSeconds `
            -SampleSeconds $SampleSeconds -NoUnlock:$NoUnlock -HoldDashboard:$HoldDashboard -Serial $Serial
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
}

Write-ExNote ('task {0} finished; artifacts in {1}' -f $Task, (Get-ExSessionDir))
