# 07-lock-compare.ps1 -- E10/E11 (ticket #11): lock the main screen once and watch BOTH channels.
#
# Run through the one-command entry point (`ex.ps1 -Task overlay-lock` = install -> authorize ->
# this -> collect), or alone:
#   .\tools\ex\07-lock-compare.ps1
#   .\tools\ex\07-lock-compare.ps1 -LockSeconds 300
#
# The ticket was written assuming E9 had passed; on this device it did not (the rear-display
# overlay add is denied by WindowManager policy while UNLOCKED, ticket #10), so this scenario
# measures what is still measurable in one deliberate lock:
#   * E10  does the overlay window survive the lock -- expected here is not PASS but
#         BLOCKED-BY-E9 with the denial reason: a window that never reached the rear display
#         has no lock survival to lose. The classes exist so a future device that lets the
#         add through gets a real answer (PASS / CLEARED) from the same one command.
#   * E11  does the rear display stay ON under the window's FLAG_KEEP_SCREEN_ON.
#   * the Activity channel in the SAME run: how long until the system reclaims the Dashboard
#     (the control the ticket asks for; ticket #7 saw 1-5s).
#
# Verdicts come from device facts, never from exit codes: `dumpsys window windows` (is the probe
# window on the target display), `dumpsys display` (rear state), `dumpsys activity activities`
# (who owns the rear display), and the app's own logcat. Time-to-reclaim uses the DEVICE clock:
# the lock moment is marked into the RearCue tag (`log -t RearCue pc-lock-issued`), so the first
# `Dashboard detach` after the marker shares one clock with it -- no PC skew, and the 1s-grain
# dumpsys sampling only corroborates.
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $LockSeconds = 90,
    [int] $SampleSeconds = 2,
    [int] $WaitSeconds = 3,
    [int] $DisplayId,
    [switch] $NoUnlock,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'overlay-lock' -Serial $Serial | Out-Null }

function Get-ExLockSnapshot {
    <# One sampling point: rear state + rear owner + probe window, all from dumpsys. #>
    $displayDump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
    $rear = Get-RearDisplay -DumpsysDisplay $displayDump
    $windowDump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'window', 'windows') -AllowFailure) -join "`n"
    $ownerArgs = @(); if ($rear) { $ownerArgs = @{ DisplayId = $rear.DisplayId } }
    return [pscustomobject]@{
        Rear        = $rear
        Owner       = (Get-ExRearOwnerNow @ownerArgs)
        # state/committedState as one "ON/ON"-style pair (the wire field `rear=`); Rear.State is
        # the bare state -- keep the names honest.
        RearStatePair = if ($rear) { '{0}/{1}' -f $rear.State, $rear.CommittedState } else { 'no-rear-display' }
        Probe       = (Get-OverlayProbeWindow -DumpsysWindow $windowDump)
    }
}

# ---- 0. preflight ------------------------------------------------------------
$permission = Get-ExOverlayPermission
$granted = $permission.Granted
Write-ExNote ('appops SYSTEM_ALERT_WINDOW: {0}' -f $permission.Text)

$rearNow = Get-RearDisplay -DumpsysDisplay (((Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure)) -join "`n")
$rearId = if ($rearNow) { $rearNow.DisplayId } else { $null }
$hasDisplayId = $PSBoundParameters.ContainsKey('DisplayId')
$target = if ($hasDisplayId) { $DisplayId } else { $rearId }
Write-ExNote ('rear display: id={0} state={1}; overlay target display={2}' -f $rearId, $rearNow.State, $target)

# The Activity leg needs an unlocked phone: HyperOS denies third-party rear-display launches in
# the locked steady state, and a locked start would fake an ACT-NO-BASELINE result.
if (Test-ExKeyguardLocked) {
    Write-ExNote 'WARNING: the phone is keyguard-locked. The Activity baseline cannot come up while'
    Write-ExNote '         locked (ActivityStarterImpl: rearDisplay check locked -> deny). Unlock the'
    Write-ExNote '         phone and rerun for a comparable Activity leg; a secure lock cannot be'
    Write-ExNote '         dismissed over adb.'
}

# ---- 1. clean buffer, live process, live listener -----------------------------
Clear-ExLogcat
Start-ExApp -TimeoutSec 30 | Out-Null

# ---- 2. Activity baseline: one shell notification -> Dashboard on the rear ----
# Cancel leftovers first: re-posting an existing tag produces an update, not a Post event
# (ticket #3), and a stale shell notification would silently kill the baseline.
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Start-Sleep -Seconds 2
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex',
    'ticket11 lock compare') -AllowFailure | Out-Null
$dashboardUp = $false
if ($rearId -ne $null) { $dashboardUp = Wait-ExRearOwner -Owner 'dashboard' -DisplayId $rearId -TimeoutSec 20 }
if (-not $dashboardUp) {
    Write-ExNote 'WARNING: the Dashboard did not reach the rear display before the lock (see warnings above)'
}

# ---- 3. overlay add attempt (the E9 gate; on this device the overlay channel dies here) ----
$extra = if ($hasDisplayId) { @{ displayId = $DisplayId } } else { $null }
Invoke-ExDebugAction -Action 'OVERLAY_ADD' -Extra $extra
Start-Sleep -Seconds $WaitSeconds

$pre = Get-ExLockSnapshot
$lastLogPre = @(@(Get-ExLogcat) | Where-Object { $_ -match 'RearCue\s*: ' } | Select-Object -Last 1)
$windowPre = ($pre.Probe.Found -and ($null -ne $pre.Probe.DisplayId) -and ($pre.Probe.DisplayId -eq $target))
Write-ExNote ('pre-lock: owner={0} rear={1} probe-window={2}' -f $pre.Owner, $pre.RearStatePair,
    ($(if ($pre.Probe.Found) { 'present({0})' -f $pre.Probe.DisplayId } else { 'absent' })))

# ---- 4. lock once, with a device-clock marker ---------------------------------
# KEYCODE_POWER toggles, so a sleeping screen would be woken instead of locked. Wake first.
if ((Get-ExWakefulness) -ne 'Awake') {
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
    Start-Sleep -Seconds 3
}
Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-lock-issued') -AllowFailure | Out-Null
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
Start-Sleep -Seconds 2
if ((Get-ExWakefulness) -eq 'Awake') {
    Write-ExNote 'still awake after KEYCODE_POWER: sending it once more'
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
}
Write-ExNote ('wakefulness while locked: {0}' -f (Get-ExWakefulness))

# ---- 5. sample loop -----------------------------------------------------------
$start = Get-Date
$deadline = $start.AddSeconds($LockSeconds)
$samples = New-Object System.Collections.Generic.List[string]
do {
    Start-Sleep -Seconds $SampleSeconds
    $elapsed = [int]((Get-Date) - $start).TotalSeconds
    $snap = Get-ExLockSnapshot
    $lastLog = @(@(Get-ExLogcat) | Where-Object { $_ -match 'RearCue\s*: ' } | Select-Object -Last 1)
    $windowId = if ($snap.Probe.Found) { $snap.Probe.DisplayId } else { $null }
    $line = Format-ExLockSampleLine -Elapsed $elapsed -WindowDisplayId $windowId `
        -Owner $snap.Owner -StatePair $snap.RearStatePair -Last (Get-ExRearCueMessage ($lastLog -join ''))
    $samples.Add($line)
    Write-ExNote $line
} while ((Get-Date) -lt $deadline)

Write-ExArtifact -Name 'e10-samples.txt' -Lines (@(
        ('# samples ({0} points, {1}s target interval; dumpsys display/activities/window per point)' -f $samples.Count, $SampleSeconds),
        ('# window=present(<displayId>) = the overlay probe window is in `dumpsys window windows` on that display'),
        ('# owner = who holds the rear display (dashboard | native | other | none); rear = state/committedState')
    ) + $samples.ToArray()) | Out-Null

# ---- 6. wake + best-effort unlock, then clean up both subjects -----------------
if (-not $NoUnlock) {
    Write-ExNote 'waking the main screen again and dismissing the keyguard'
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    if (Test-ExKeyguardLocked) { Write-ExNote 'still locked: a secure lock needs a human to unlock' }
}
Invoke-ExDebugAction -Action 'OVERLAY_REMOVE'
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Start-Sleep -Seconds 2

# ---- 7. final evidence + verdicts ----------------------------------------------
$appLogEnd = @(Get-ExLogcat)
$systemAll = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure)
$events = Get-OverlayWindowEvents -Logcat ($appLogEnd + $systemAll)
$facts = Get-ExLockSampleFacts -SampleLines $samples.ToArray()
$detachDelay = Get-ExDelaySeconds -Logcat $appLogEnd -Marker 'pc-lock-issued' -Pattern 'Dashboard detach'
$windowEverOnTarget = (@($facts.Samples | Where-Object { $_.WindowPresent -and ($_.WindowDisplayId -eq $target) }).Count -gt 0)
$overlayReachedTarget = ($windowPre -or $windowEverOnTarget)

$rearBehavior = if ($facts.RearHeldOnThroughout) {
    'stayed ON through the whole watch'
} elseif ($null -ne $facts.RearFirstNonOnSec) {
    # Facts only, no mechanism guesses: say when it left ON, whether it came back, how it ended.
    $endState = ($facts.Samples[-1].RearState + '/' + $facts.Samples[-1].RearCommitted)
    $backOn = (@($facts.Samples | Where-Object { ($_.Elapsed -gt $facts.RearFirstNonOnSec) -and ($_.RearState -eq 'ON') }).Count -gt 0)
    if ($backOn) {
        ('first non-ON at +{0}s, ON again later, ending {1}' -f $facts.RearFirstNonOnSec, $endState)
    } else {
        ('first non-ON at +{0}s and never ON again, ending {1}' -f $facts.RearFirstNonOnSec, $endState)
    }
} else {
    'no readable rear state'
}

if (-not $granted) {
    $e10 = 'E10-BLOCKED-PERMISSION (SYSTEM_ALERT_WINDOW not granted: rerun 01-install)'
    $e11 = 'E11-BLOCKED-PERMISSION (SYSTEM_ALERT_WINDOW not granted: rerun 01-install)'
} elseif (-not $overlayReachedTarget) {
    $why = if ($events.RearPolicyDeny) {
        'WindowManager denied the add for a non-system app on the rear display (see the deny line below)'
    } elseif ($events.AddFailed) {
        ('the app-side add failed: {0}' -f $events.FailureReason)
    } else {
        'no probe window on the target display before the lock'
    }
    $e10 = ('E10-BLOCKED-BY-E9 (the overlay window never reached display {0} while UNLOCKED: {1}; there is no lock survival to measure -- the overlay channel dies one stage before the lock)' -f $target, $why)
    $e11 = ('E11-BLOCKED-BY-E9 (no overlay window on display {0}, so FLAG_KEEP_SCREEN_ON had nothing to hold; rear display facts without the window: {1})' -f $target, $rearBehavior)
} else {
    if ($facts.WindowSurvivedLock) {
        $e10 = ('E10-PASS (the overlay window was still on display {0} in every sample of the {1}s lock watch)' -f $target, $LockSeconds)
    } else {
        $e10 = ('E10-CLEARED (the overlay window left display {0} at +{1}s of the lock watch; last seen +{2}s)' -f $target, $facts.WindowFirstAbsentSec, $facts.WindowLastSeenSec)
    }
    if ($facts.RearHeldOnThroughout) {
        $e11 = ('E11-PASS (the rear display stayed ON for the whole {0}s watch while the window was on it)' -f $LockSeconds)
    } else {
        $e11 = ('E11-LOST (rear state left ON at +{0}s; window last seen +{1}s)' -f $facts.RearFirstNonOnSec, $facts.WindowLastSeenSec)
    }
}

if (-not $dashboardUp) {
    $compare = 'ACT-NO-BASELINE (the Dashboard never reached the rear display before the lock; see the warnings)'
} elseif ($facts.OwnerHeldThroughout) {
    $compare = ('ACT-SURVIVED (owner=dashboard in all {0} samples of the {1}s watch -- ticket #6 behaviour, not ticket #7)' -f $facts.SampleCount, $LockSeconds)
} elseif ($null -ne $detachDelay) {
    $compare = ('ACT-REMOVED-IN {0}s (device clock: lock marker -> first Dashboard detach; samples lost the owner at +{1}s)' -f $detachDelay, $facts.OwnerFirstLostSec)
} else {
    $compare = ('ACT-REMOVED (samples lost the owner at +{0}s; no detach line after the marker -- the freezer may have silenced the app first)' -f $facts.OwnerFirstLostSec)
}

$overlaySystem = @($systemAll |
    Where-Object { $_ -match 'WindowManager|system_window|APPLICATION_OVERLAY|RearCueOverlayProbe|SYSTEM_ALERT_WINDOW|Permission Denial|PowerGroup|wm_finish_activity|remove-task|GreezeManager' } |
    Select-Object -Last 400)

# Built as a flat list on purpose: a pipeline inside an array literal stays a NESTED array
# (`@('a', ($x | ForEach-Object ...) )`), and Write-ExArtifact's [string[]] cast would collapse
# the whole section into one space-joined line -- the bug that flattened the log sections of the
# e9/e10 artifacts before this fix.
$out = New-Object System.Collections.Generic.List[string]
$out.Add('# e10-e11-lock (ticket #11)')
$out.Add(('appops SYSTEM_ALERT_WINDOW : {0} granted={1}' -f $permission.Text, $granted))
$out.Add(('rear display                : id={0} state={1}' -f $rearId, $rearNow.State))
$out.Add(('overlay target display      : {0}' -f $target))
$out.Add(('activity baseline pre-lock  : owner={0} rear={1} dashboard-up={2}' -f $pre.Owner, $pre.RearStatePair, $dashboardUp))
$out.Add(('lock watch                  : {0}s, sample target every {1}s' -f $LockSeconds, $SampleSeconds))
$out.Add('')
$out.Add('# verdicts')
$out.Add(('granted              : {0}' -f $granted))
$out.Add(('overlay-add-accepted : {0} reason={1}' -f $events.Added, $events.FailureReason))
$out.Add(('rear-policy-deny     : {0}' -f $events.RearPolicyDeny))
$out.Add(('window-pre-lock      : {0}' -f ($(if ($pre.Probe.Found) { 'present({0})' -f $pre.Probe.DisplayId } else { 'absent' }))))
$out.Add(('window-ever-on-target: {0}' -f $windowEverOnTarget))
$out.Add(('e10                  : {0}' -f $e10))
$out.Add(('e11                  : {0}' -f $e11))
$out.Add(('activity-detach-s    : {0}' -f $detachDelay))
$out.Add(('activity-owner-lost-s: {0}' -f $facts.OwnerFirstLostSec))
$out.Add(('rear-first-non-on-s  : {0}' -f $facts.RearFirstNonOnSec))
$out.Add(('rear-behavior        : {0}' -f $rearBehavior))
$out.Add(('compare              : {0}' -f $compare))
$out.Add('')
$out.Add('# samples')
foreach ($sampleLine in $samples) { $out.Add('  ' + $sampleLine) }
$out.Add('')
$out.Add('# pre-lock snapshot')
$out.Add(('  owner={0} rear={1} probe-window found={2} displayId={3}' -f $pre.Owner, $pre.RearStatePair, $pre.Probe.Found, $pre.Probe.DisplayId))
$out.Add(('  last app log: {0}' -f (Get-ExRearCueMessage ($lastLogPre -join ''))))
$out.Add('')
$out.Add('# system-side lines (logcat -b all, filtered)')
foreach ($systemLine in $overlaySystem) { $out.Add('  ' + $systemLine) }
$out.Add('')
$out.Add('# app log (RearCue tag)')
foreach ($appLine in $appLogEnd) { $out.Add('  ' + $appLine) }
Write-ExArtifact -Name 'e10-e11-lock.txt' -Lines $out.ToArray() | Out-Null

foreach ($line in ($out | Where-Object { $_ -match '^(granted|overlay-|rear-policy|window-|e10|e11|activity-|rear-first|compare) ' })) {
    Write-ExNote $line
}
return @{ E10 = $e10; E11 = $e11; Compare = $compare }
