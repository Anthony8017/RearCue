# ex.ps1 -- one-command entry point for the RearCue PC experiment script set (ticket #7).
#
#   .\tools\ex\ex.ps1                          # the whole chain: install -> authorize -> Shizuku
#                                              # -> notifications -> E1/E7/E3 lock -> collect
#   .\tools\ex\ex.ps1 -Task install -Build      # rebuild + install only
#   .\tools\ex\ex.ps1 -Task e8                  # E8: kill + restart the Shizuku server
#   .\tools\ex\ex.ps1 -Task overlay             # E9: overlay admission probe (ticket #10)
#   .\tools\ex\ex.ps1 -Task overlay-lock        # E10/E11 (ticket #11): lock once, watch both channels
#   .\tools\ex\ex.ps1 -Task wake-keepalive      # E12 (ticket #16): wake-key keep-alive vs control
#   .\tools\ex\ex.ps1 -Task task-move           # E14 (ticket #18): task-move transaction vs lock
#   .\tools\ex\ex.ps1 -Task lock-survive        # E13 (ticket #19): Dashboard survival under keep-alive
#   .\tools\ex\ex.ps1 -Task wake-cost           # ticket #21: keep-alive cost (heat + drain), idle vs keep
#   .\tools\ex\ex.ps1 -Task kill-recover        # ticket #21: keep-alive recovery after a process rebuild
#   .\tools\ex\ex.ps1 -Task lock-firstcast      # ticket #22: lock-screen first cast (task-move transaction)
#   .\tools\ex\ex.ps1 -Task freeze-probe         # ticket #29: GreezeManager freeze (reproduce,
#                                                # event timeline, countermeasure facts)
#   .\tools\ex\ex.ps1 -Task shuid-overlay       # SH-UID (ticket #17): shell-uid overlay vs rear door
#   .\tools\ex\ex.ps1 -Task autostart-probe      # ticket #27: MIUI autostart whitelist probe (detect + jump)
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
    [ValidateSet('all', 'install', 'authorize', 'shizuku', 'e8', 'drive', 'overlay', 'overlay-lock', 'wake-keepalive', 'task-move', 'shuid-overlay', 'autostart-probe', 'lock-survive', 'wake-cost', 'kill-recover', 'lock-firstcast', 'freeze-probe', 'screen-off-chain', 'collect', 'photos', 'selftest')]
    [string] $Task = 'all',
    [ValidateSet('e1', 'e7', 'e3-lock')][string[]] $Scenario,
    [int] $LockSeconds = 120,
    [int] $SampleSeconds = 5,
    [int] $WakeIntervalMs = 500,
    [int] $ObserveSeconds = 60,
    [int] $SettleSeconds = 6,
    [int] $HoldSeconds = 6,
    [string] $WindowPackage = 'com.rearcue.poc',
    [int] $TxnCode,
    [switch] $Build,
    [switch] $NoUnlock,
    [switch] $NoRearWake,
    [switch] $KeepKeyguard,
    [switch] $HoldDashboard,
    [switch] $OffOnly,
    # Ticket #21 regression: lock-survive treats with the APP's own Wake Keep-alive.
    [switch] $AppKeepAlive,
    # Ticket #29: how the freeze probe freezes the app (tobg / sleep / auto = tobg then sleep).
    [ValidateSet('auto', 'tobg', 'sleep')][string] $FreezeTrigger,
    # Ticket #24: wake-cost treats the keep leg with the E12 probe loop (shell uid) instead.
    [switch] $ShellKeepAlive,
    [int] $OverlayDisplayId,
    [string] $Serial,
    [string] $StartScript = '/data/local/tmp/start-shizuku.sh',
    # Issue #38: system-side candidate applied inside the screen-off-chain harness.
    [ValidateSet('none', 'keepalive', 'unfreeze')][string] $SystemAction = 'none'
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

if ($Task -eq 'screen-off-chain') {
    $args37 = @{}
    if ($Serial) { $args37.Serial = $Serial }
    if ($OffOnly) { $args37.OffOnly = $true }
    if ($SystemAction -ne 'none') { $args37.SystemAction = $SystemAction }
    & (Join-Path $PSScriptRoot '17-screen-off-chain.ps1') @args37
    exit $LASTEXITCODE
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
if ($Task -in @('all', 'e8', 'drive', 'photos', 'overlay-lock', 'task-move', 'shuid-overlay', 'lock-survive') -and (Test-ExKeyguardLocked)) {
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
    # E9 (ticket #10): one command = install -> add overlay -> judge -> remove -> collect.
    # -OverlayDisplayId reruns the whole chain against another display (control group: 0 = main).
    'overlay' {
        $overlayArgs = @{ Serial = $Serial }
        if ($PSBoundParameters.ContainsKey('OverlayDisplayId')) { $overlayArgs.DisplayId = $OverlayDisplayId }
        & (Join-Path $PSScriptRoot '01-install.ps1') -Build:$Build -KeepKeyguard:$KeepKeyguard -Serial $Serial
        & (Join-Path $PSScriptRoot '06-overlay.ps1') @overlayArgs
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
    # E10/E11 + Activity comparison (ticket #11): one deliberate lock, both channels watched.
    # authorize is needed for the Activity leg (notification -> Icon Set -> Dashboard).
    'overlay-lock' {
        $overlayArgs = @{ Serial = $Serial; LockSeconds = $LockSeconds; SampleSeconds = $SampleSeconds }
        if ($PSBoundParameters.ContainsKey('OverlayDisplayId')) { $overlayArgs.DisplayId = $OverlayDisplayId }
        if ($NoUnlock) { $overlayArgs.NoUnlock = $true }
        & (Join-Path $PSScriptRoot '01-install.ps1') -Build:$Build -KeepKeyguard:$KeepKeyguard -Serial $Serial
        & (Join-Path $PSScriptRoot '02-authorize.ps1') -Serial $Serial
        & (Join-Path $PSScriptRoot '07-lock-compare.ps1') @overlayArgs
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
    # E12 (ticket #16): one command = baseline leg (no keep-alive) + keep-alive leg, one session.
    # No install step on purpose: E12 judges display power state only (no app, no Shizuku).
    'wake-keepalive' {
        # SampleSeconds stays 08's own default unless the caller overrides it (the drive
        # scenarios use a coarser 5s cadence; E12 wants the finer one).
        $wakeArgs = @{ Serial = $Serial; WakeIntervalMs = $WakeIntervalMs; ObserveSeconds = $ObserveSeconds }
        if ($PSBoundParameters.ContainsKey('SampleSeconds')) { $wakeArgs.SampleSeconds = $SampleSeconds }
        if ($NoRestore) { $wakeArgs.NoRestore = $true }
        & (Join-Path $PSScriptRoot '08-wake-keepalive.ps1') @wakeArgs
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
    # E14 (ticket #18): one command = prep Dashboard task -> unlocked control transaction ->
    # position off the rear -> KEYCODE_POWER lock -> locked task-move transactions + sampling.
    # Every segment that needs the unlocked state runs BEFORE the lock (the phone ends locked).
    'task-move' {
        $moveArgs = @{ Serial = $Serial }
        if ($PSBoundParameters.ContainsKey('ObserveSeconds')) { $moveArgs.ObserveSeconds = $ObserveSeconds }
        if ($PSBoundParameters.ContainsKey('SampleSeconds')) { $moveArgs.SampleSeconds = $SampleSeconds }
        if ($PSBoundParameters.ContainsKey('SettleSeconds')) { $moveArgs.SettleSeconds = $SettleSeconds }
        if ($PSBoundParameters.ContainsKey('TxnCode')) { $moveArgs.TxnCode = $TxnCode }
        if ($NoRearWake) { $moveArgs.NoRearWake = $true }
        & (Join-Path $PSScriptRoot '02-authorize.ps1') -Serial $Serial
        & (Join-Path $PSScriptRoot '09-task-move.ps1') @moveArgs
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
    # SH-UID (ticket #17): one command = build probe dex (javac + d8) -> control add on display 0
    # -> one-shot add/remove -> rear add -> judge from device facts -> archive. No install, no
    # APK, no KEYCODE_POWER (the phone must stay unlocked).
    'shuid-overlay' {
        $shuidArgs = @{ Serial = $Serial }
        if ($PSBoundParameters.ContainsKey('WindowPackage')) { $shuidArgs.WindowPackage = $WindowPackage }
        if ($PSBoundParameters.ContainsKey('HoldSeconds')) { $shuidArgs.HoldSeconds = $HoldSeconds }
        if ($PSBoundParameters.ContainsKey('SettleSeconds')) { $shuidArgs.SettleSeconds = $SettleSeconds }
        & (Join-Path $PSScriptRoot '10-shuid-overlay.ps1') @shuidArgs
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
    # Ticket #27: MIUI autostart whitelist probe -- detection surfaces, settings-page jump entry
    # and a switch toggle diff (the switch is always flipped back). No install step on purpose:
    # the probe reads system surfaces and one settings page; the app only needs to be installed
    # for the row label to exist.
    'autostart-probe' {
        & (Join-Path $PSScriptRoot '16-autostart-probe.ps1') -Serial $Serial
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
    # E13 (ticket #19): one command = Activity baseline -> the E12 keep-alive loop across one
    # KEYCODE_POWER lock -> survival watch with device-fact verdicts. authorize is needed for the
    # baseline (notification -> Icon Set -> Dashboard).
    'lock-survive' {
        $surviveArgs = @{ Serial = $Serial; WakeIntervalMs = $WakeIntervalMs; ObserveSeconds = $ObserveSeconds }
        if ($PSBoundParameters.ContainsKey('SampleSeconds')) { $surviveArgs.SampleSeconds = $SampleSeconds }
        if ($PSBoundParameters.ContainsKey('SettleSeconds')) { $surviveArgs.SettleSeconds = $SettleSeconds }
        if ($AppKeepAlive) { $surviveArgs.AppKeepAlive = $true }
        & (Join-Path $PSScriptRoot '02-authorize.ps1') -Serial $Serial
        $surviveResult = @(& (Join-Path $PSScriptRoot '11-lock-survive.ps1') @surviveArgs) |
            Where-Object { $_ -is [hashtable] } | Select-Object -Last 1
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
        # Shrink the grown log buffers only AFTER the collect: `logcat -G` truncates the ring
        # buffer, and shrinking first left the collect capture with 1 event and wrong chain facts
        # once (20260922-224734). 11-lock-survive hands the original sizes back for this step.
        if ($surviveResult -is [hashtable]) {
            foreach ($buffer in @(
                    @{ Name = 'main'; Kb = $surviveResult.MainBufferKb },
                    @{ Name = 'system'; Kb = $surviveResult.SystemBufferKb }
                )) {
                if ($null -ne $buffer.Kb) {
                    Invoke-Adb -Arguments @('shell', 'logcat', '-G', ('{0}K' -f $buffer.Kb), '-b', $buffer.Name) -AllowFailure | Out-Null
                }
            }
            Write-ExNote ('log buffers restored to main {0}Kb / system {1}Kb (after the collect)' -f $surviveResult.MainBufferKb, $surviveResult.SystemBufferKb)
        }
    }
    # Ticket #21: one command = two locked cost legs (idle vs keep-alive) + battery/heat facts.
    # -ShellKeepAlive (ticket #24): treat the keep leg with the E12 probe loop instead of the
    # app's own loop (the app is frozen by GreezeManager at >=5000ms cadence after the lock).
    # Kept on purpose (#30 review JUDGE#12): the ticket #24 shell-uid self-driven cost method --
    # the 5000ms cost-decision rounds ran through this switch (findings ticket #24).
    'wake-cost' {
        & (Join-Path $PSScriptRoot '02-authorize.ps1') -Serial $Serial
        $costArgs = @{ Serial = $Serial; WakeIntervalMs = $WakeIntervalMs }
        if ($PSBoundParameters.ContainsKey('SampleSeconds')) { $costArgs.SampleSeconds = [math]::Max(5, $SampleSeconds) }
        if ($PSBoundParameters.ContainsKey('HoldSeconds')) { $costArgs.DutySeconds = $HoldSeconds }
        if ($ShellKeepAlive) { $costArgs.ShellKeepAlive = $true }
        & (Join-Path $PSScriptRoot '12-wake-cost.ps1') @costArgs
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
    # Ticket #21: process-rebuild recovery -- kill the app, revive with one real notification.
    'kill-recover' {
        & (Join-Path $PSScriptRoot '02-authorize.ps1') -Serial $Serial
        $recoverArgs = @{ Serial = $Serial; WakeIntervalMs = $WakeIntervalMs }
        if ($PSBoundParameters.ContainsKey('SampleSeconds')) { $recoverArgs.SampleSeconds = [math]::Max(2, $SampleSeconds) }
        & (Join-Path $PSScriptRoot '14-kill-recover.ps1') @recoverArgs
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
    # Ticket #22: lock-screen first cast through the E14 task-move transaction.
    'lock-firstcast' {
        & (Join-Path $PSScriptRoot '02-authorize.ps1') -Serial $Serial
        $castArgs = @{ Serial = $Serial }
        if ($PSBoundParameters.ContainsKey('ObserveSeconds')) { $castArgs.ObserveSeconds = $ObserveSeconds }
        if ($PSBoundParameters.ContainsKey('SampleSeconds')) { $castArgs.SampleSeconds = [math]::Max(1, $SampleSeconds) }
        & (Join-Path $PSScriptRoot '15-lock-firstcast.ps1') @castArgs
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
    # Ticket #29: the GreezeManager freeze probe -- reproduce (tobg), frozen notification
    # timeline, thaw probes, visible-skip leg, countermeasure facts. No install on purpose:
    # the probe judges HyperOS behavior on whatever build is installed.
    'freeze-probe' {
        & (Join-Path $PSScriptRoot '02-authorize.ps1') -Serial $Serial
        $freezeArgs = @{ Serial = $Serial }
        if ($PSBoundParameters.ContainsKey('ObserveSeconds')) { $freezeArgs.ObserveSeconds = $ObserveSeconds }
        if ($PSBoundParameters.ContainsKey('SampleSeconds')) { $freezeArgs.SampleSeconds = [math]::Max(1, $SampleSeconds) }
        if ($PSBoundParameters.ContainsKey('HoldSeconds')) { $freezeArgs.OnRearSeconds = $HoldSeconds }
        if ($PSBoundParameters.ContainsKey('FreezeTrigger')) { $freezeArgs.FreezeTrigger = $FreezeTrigger }
        & (Join-Path $PSScriptRoot '16-freeze-probe.ps1') @freezeArgs
        & (Join-Path $PSScriptRoot '05-collect.ps1') -Serial $Serial
    }
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
