# 11-lock-survive.ps1 -- E13 (ticket #19): with the wake keep-alive running across the lock,
# does the Dashboard survive on the rear display? One run = Activity baseline up -> keep-alive
# loop on -> one deliberate lock -> watch, verdicts from device facts only.
#
# Run through the one-command entry point (`ex.ps1 -Task lock-survive` = authorize -> this ->
# collect), or alone:
#   .\tools\ex\11-lock-survive.ps1
#   .\tools\ex\11-lock-survive.ps1 -WakeIntervalMs 500 -ObserveSeconds 90
#
# The control for this probe is already archived: without keep-alive the Dashboard is reclaimed
# 1.3-1.4s after the lock (ticket #11, fixtures logcat-survive-cleared-*.txt), and E12 (ticket
# #16) proved the wake-key loop can hold the rear display ON through the lock. E13 asks the
# remaining question: display held + Dashboard up = does the Dashboard stay?
#
# Verdicts come from device facts, never from exit codes:
#   * the app's own logcat around the lock (Get-ExSurviveFacts): the lock marker is written
#     straight into the RearCue tag (`log -t RearCue pc-e13-lock-issued`), so the marker, the
#     racing re-projection and the `Dashboard detach` line share the device clock -- "reclaimed
#     at +Xs" carries no PC skew
#   * `dumpsys display` + `dumpsys activity activities` per sampling point (Format-ExWakeSampleLine
#     wire): rear state (keep-alive actually holding?) and rear owner (Dashboard actually up?)
#   * the device-side tick log + `ps` of the injection loop (the keep-alive really running) and
#     the system-side PowerGroup lines (the lock really reaching display group 1)
#
# Verdict words (the table lives in docs/poc-findings.md, ticket #19 section):
#   E13-PASS           the Dashboard was on the rear display through the whole watch
#   E13-CLEARED        the Dashboard was reclaimed -- the time is always reported and `e13-class`
#                      says whether the app's own re-projection put it back
#   E13-NO-BASELINE    the Dashboard never reached the rear display before the lock
#   E13-INJECT-FAILED  the keep-alive loop did not provably run (the premise of this probe)
#   E13-RUN-INVALID    external wake interference (fingerprint / a hand on the power button)
#                      voided the round -- the samples go to erratum.md, not into the verdict
#   e13-class: E13-RETURNED (cleared and back -- gap in ms) | E13-NO-RETURN (cleared and gone)
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $WakeIntervalMs = 500,
    [int] $ObserveSeconds = 60,
    [int] $SampleSeconds = 2,
    [int] $SettleSeconds = 5,
    [switch] $NoRestore,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'lock-survive' -Serial $Serial | Out-Null }

$loopScript = '/data/local/tmp/wake-keepalive.sh'
$tickFile = '/data/local/tmp/wake-keepalive.ticks'
$stopFile = '/data/local/tmp/wake-keepalive.stop'
$sleepArg = [math]::Round($WakeIntervalMs / 1000.0, 3).ToString([Globalization.CultureInfo]::InvariantCulture)
$expectedTicks = [int][math]::Ceiling($ObserveSeconds * 1000.0 / $WakeIntervalMs)
# Every input injection of this run, device-clock stamped (see Invoke-ExStampedInput): the only
# way to tell "the script injected it" from "a human touched the phone" in the PowerGroup lines.
$script:PowerPresses = New-Object System.Collections.Generic.List[object]

function Get-ExSurviveSnapshot {
    <# One look at the device: rear + main display state pair and who owns the rear display. #>
    $dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
    $blocks = Get-DisplayInfoBlocks -DumpsysDisplay $dump
    $main = $blocks | Where-Object { $_.DisplayId -eq 0 } | Select-Object -First 1
    $rear = Get-RearDisplay -DumpsysDisplay $dump
    $owner = 'none'
    if ($rear) { $owner = Get-ExRearOwnerNow -DisplayId $rear.DisplayId }
    return [pscustomobject]@{
        Rear     = $rear
        Main     = $main
        RearPair = (Format-ExStatePair $rear)
        MainPair = (Format-ExStatePair $main)
        Owner    = $owner
    }
}

function Invoke-ExStampedInput {
    <#
      One `input` injection of this run, timed on the DEVICE clock (08-wake-keepalive precedent).
      EVERY injection is stamped -- power presses AND wake pokes: a directed rear-display
      KEYCODE_WAKEUP can flip HyperOS into rear mode and log a `power_button` power-off of the
      MAIN group 3ms later (real round 20260922-222257-lock-survive), and an unstamped poke
      would then read as external interference. The `pc-e13-input-*` logcat marker is only
      human-readable correlation: the main log buffer wraps under injection load, so the
      authoritative stamp is `date` on the device, read right before the injection and returned
      to the caller.
    #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Purpose,
        [Parameter(Mandatory, Position = 1)][AllowEmptyCollection()][string[]] $InputArgs
    )
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, ('pc-e13-input-' + $Purpose)) -AllowFailure | Out-Null
    $clock = ((Invoke-Adb -Arguments @('shell', "date '+%m-%d %H:%M:%S'") -AllowFailure) -join '').Trim()
    Invoke-Adb -Arguments (@('shell', 'input') + $InputArgs) -AllowFailure | Out-Null
    $press = [pscustomobject]@{ Purpose = $Purpose; DeviceTime = $clock }
    $script:PowerPresses.Add($press)
    return $press
}

function Invoke-ExPowerKey {
    <# One KEYCODE_POWER press through the stamped injection path. #>
    param([Parameter(Mandatory, Position = 0)][string] $Purpose)
    Invoke-ExStampedInput -Purpose $Purpose -InputArgs @('keyevent', 'KEYCODE_POWER')
}

function Get-ExLogcatBufferKb {
    <#
      `logcat -g -b <buffer>` -> that ring buffer's size in Kb, or $null when unreadable. The
      real device prints `main: ring buffer is 2 MiB (1 MiB consumed, 12 MiB readable)` -- the
      space before the unit and the `i` of MiB/KiB are part of the wire format.
    #>
    param([Parameter(Mandatory, Position = 0)][string] $Buffer)
    $out = @(Invoke-Adb -Arguments @('shell', 'logcat', '-g', '-b', $Buffer) -AllowFailure)
    foreach ($line in $out) {
        if ($line -match 'ring buffer is (\d+)\s*(KiB|Kb|KB|MiB|Mb|MB)') {
            $size = [int]$Matches[1]
            if ($Matches[2] -match '^M') { $size = $size * 1024 }
            return $size
        }
    }
    return $null
}

# ---- 0. preflight ------------------------------------------------------------
$snap0 = Get-ExSurviveSnapshot
$keyguardAtStart = Test-ExKeyguardLocked
Write-ExNote ('rear display: {0} state={1}; main state={2}; wakefulness={3}' -f
    $(if ($snap0.Rear) { 'id=' + $snap0.Rear.DisplayId } else { 'NOT FOUND' }),
    $snap0.RearPair, $snap0.MainPair, (Get-ExWakefulness))
if (-not $snap0.Rear) {
    Write-ExNote 'WARNING: no rear display in `dumpsys display` (no non-default INTERNAL display with'
    Write-ExNote '         PRESENTATION + OWN_DISPLAY_GROUP). The run will report no-display pairs.'
}
if ($keyguardAtStart) {
    Write-ExNote 'WARNING: the phone is keyguard-locked and a secure lock cannot be dismissed over adb'
    Write-ExNote '         (ticket #7). The Activity baseline cannot come up while locked, so this run'
    Write-ExNote '         would report E13-NO-BASELINE. Unlock the phone first.'
}
Write-ExNote ('protocol: Activity baseline -> keep-alive loop across one KEYCODE_POWER lock -> {0}s watch, sample every {1}s, wake interval {2}ms' -f
    $ObserveSeconds, $SampleSeconds, $WakeIntervalMs)

# The injection flood wraps the log buffers (08-wake-keepalive lesson: a 60s window at 2 keys/s
# ate the start-of-run app lines). The app log around the baseline and the lock marker are the
# E13 measurement, so grow main + system first and put them back afterwards -- recorded either way.
$mainBufferKb = Get-ExLogcatBufferKb -Buffer 'main'
$systemBufferKb = Get-ExLogcatBufferKb -Buffer 'system'
if (($null -eq $mainBufferKb) -and ($null -eq $systemBufferKb)) {
    $bufferNote = 'log buffer sizes unreadable; left as-is'
} else {
    Invoke-Adb -Arguments @('shell', 'logcat', '-G', '32M', '-b', 'main,system') -AllowFailure | Out-Null
    $bufferNote = ('main {0}Kb / system {1}Kb grown to 32M for the run (restored at the end)' -f $mainBufferKb, $systemBufferKb)
}
Write-ExNote $bufferNote

Invoke-Adb -Arguments @('push', (Join-Path $PSScriptRoot 'device\wake-keepalive.sh'), $loopScript) -AllowFailure | Out-Null
Invoke-Adb -Arguments @('shell', 'rm', '-f', $tickFile, ($tickFile + '.err'), $stopFile) -AllowFailure | Out-Null

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
    'ticket19 lock survive') -AllowFailure | Out-Null
# the E1 drive method verbatim (04-drive.ps1): shell notification + the app's own debug POST_TEST
# is what makes the projection fire without a manual touch
Invoke-ExDebugAction -Action 'POST_TEST'
$rearId = if ($snap0.Rear) { $snap0.Rear.DisplayId } else { $null }
$dashboardUp = $false
if ($null -ne $rearId) { $dashboardUp = Wait-ExRearOwner -Owner 'dashboard' -DisplayId $rearId -TimeoutSec 20 }
if (-not $dashboardUp) {
    Write-ExNote 'WARNING: the Dashboard did not reach the rear display before the lock (see warnings above)'
}

# ---- 3. reset to a defined start, then start the keep-alive loop ---------------
# KEYCODE_POWER toggles, so a sleeping screen would be woken instead of locked: wake first. A
# blocked proximity sensor makes MIUI veto a plain KEYCODE_WAKEUP (08-wake-keepalive note), so
# the fallback is a stamped KEYCODE_POWER toggle.
$pressedPower = $false
if ((Get-ExWakefulness) -ne 'Awake') {
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    if ((Get-ExWakefulness) -ne 'Awake') {
        Write-ExNote 'KEYCODE_WAKEUP did not keep the phone awake (proximity veto on this device);'
        Write-ExNote 'falling back to KEYCODE_POWER as a toggle (press is device-clock stamped)'
        Invoke-ExPowerKey -Purpose 'reset' | Out-Null
        $pressedPower = $true
        Start-Sleep -Seconds 2
    }
}
Invoke-ExKeyguardDismiss
Start-Sleep -Seconds $SettleSeconds
$preSnap = Get-ExSurviveSnapshot
if ($preSnap.RearPair -notlike 'ON/*') {
    Write-ExNote ('rear display not ON before the lock ({0}); sending one directed KEYCODE_WAKEUP' -f $preSnap.RearPair)
    Invoke-ExStampedInput -Purpose 'rear-poke' -InputArgs @('-d', '1', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    Start-Sleep -Seconds 2
    $preSnap = Get-ExSurviveSnapshot
}
Write-ExNote ('pre-lock: rear={0} main={1} owner={2}' -f $preSnap.RearPair, $preSnap.MainPair, $preSnap.Owner)

$runLine = 'nohup sh {0} 1 {1} {2} {3} >/dev/null 2>&1 &' -f $loopScript, $sleepArg, $tickFile, $stopFile
Invoke-Adb -Arguments @('shell', $runLine) -AllowFailure | Out-Null
# the loop starts injecting immediately; stamp its ramp too (a first poke can be the flip case)
$loopStamp = ((Invoke-Adb -Arguments @('shell', "date '+%m-%d %H:%M:%S'") -AllowFailure) -join '').Trim()
$script:PowerPresses.Add([pscustomobject]@{ Purpose = 'loop-start'; DeviceTime = $loopStamp })
Start-Sleep -Seconds 2
$psDuring = @(Invoke-Adb -Arguments @('shell', 'ps', '-A', '-o', 'PID,NAME,args') -AllowFailure |
    Where-Object { $_ -match 'wake-keepalive|PID' })

# ---- 4. lock once, with both clock anchors ------------------------------------
# The survive parser anchors on the `pc-e13-lock-issued` marker (same device clock as the app's
# log lines); the `date` stamp next to the press is the authoritative T0 for tick/pollution
# attribution, because the marker itself can wrap out of a flooded buffer.
Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-e13-lock-issued') -AllowFailure | Out-Null
$lockPress = Invoke-ExPowerKey -Purpose 'lock'
$lockT0 = $lockPress.DeviceTime
Start-Sleep -Seconds 2
if ((Get-ExWakefulness) -eq 'Awake') {
    Write-ExNote 'still awake after KEYCODE_POWER: sending it once more'
    Invoke-ExPowerKey -Purpose 'lock2' | Out-Null
    Start-Sleep -Seconds 2
}
Write-ExNote ('wakefulness while locked: {0}' -f (Get-ExWakefulness))

# ---- 5. sample loop -----------------------------------------------------------
$start = Get-Date
$deadline = $start.AddSeconds($ObserveSeconds)
$samples = New-Object System.Collections.Generic.List[string]
do {
    Start-Sleep -Seconds $SampleSeconds
    $elapsed = [int]((Get-Date) - $start).TotalSeconds
    $snap = Get-ExSurviveSnapshot
    $line = Format-ExWakeSampleLine -Elapsed $elapsed -RearPair $snap.RearPair -MainPair $snap.MainPair -Owner $snap.Owner
    $samples.Add($line)
    Write-ExNote $line
} while ((Get-Date) -lt $deadline)

# ---- 6. stop the loop, collect its own evidence -------------------------------
Invoke-Adb -Arguments @('shell', 'touch', $stopFile) -AllowFailure | Out-Null
Start-Sleep -Seconds 3
$psAfter = @(Invoke-Adb -Arguments @('shell', 'ps', '-A', '-o', 'PID,NAME,args') -AllowFailure |
    Where-Object { $_ -match 'wake-keepalive|PID' })
$tickLines = @(Invoke-Adb -Arguments @('shell', 'cat', $tickFile) -AllowFailure)
$tickErr = @(Invoke-Adb -Arguments @('shell', 'cat', ($tickFile + '.err')) -AllowFailure)

# ---- 7. evidence --------------------------------------------------------------
$appLog = @(Get-ExLogcat)
$systemAll = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure)
$survive = Get-ExSurviveFacts -Logcat $appLog -MarkerPattern 'pc-e13-lock-issued'
$sampleFacts = Get-ExWakeSampleFacts -SampleLines $samples.ToArray()
$tickFacts = Get-ExWakeTickFacts -TickLines $tickLines
$powerEvents = Get-ExPowerGroupEvents -Logcat $systemAll
$pollutionHits = Get-ExWakePollution -Logcat $systemAll
# Bare capture on purpose: the parser returns ONE array object (the `,$arr` seam convention) and
# an `@(...)` wrap would turn an empty selection into a phantom one-element hit.
$externalPollution = Select-ExExternalPollution -Hits $pollutionHits -PowerPresses $script:PowerPresses.ToArray() -WakeEvents $powerEvents

# The lock really reaching the rear display group: `Powering off display group ... (groupId= 1`.
$lockOff = $powerEvents | Where-Object {
    ($_.Kind -eq 'power-off') -and ($_.GroupId -eq 1) -and ((Get-ExSecondStamp $_.Time) -ge $lockT0)
} | Select-Object -First 1
$wakeKeyTraces = @($powerEvents | Where-Object {
    ($_.Kind -eq 'wake') -and ($_.Reason -eq 'WAKE_REASON_WAKE_KEY') -and ($_.GroupId -eq 1) -and
    ((Get-ExSecondStamp $_.Time) -ge $lockT0)
})
$ticksInWindow = @($tickFacts.Ticks | Where-Object { $_.Time -and $lockT0 -and ($_.Time -ge $lockT0) })

# The marker is stamped BEFORE the KEYCODE_POWER press (it must never be overtaken by a fast
# reclaim), so "reclaimed at +Xs from the marker" runs early by the press latency. The PowerGroup
# group-1 power-off is the lock actually landing -- measure the lag so the reclaimed time can also
# be read from the lock itself (the 1.3-1.4s control was measured from the same marker anchor,
# so marker-to-marker comparisons stay fair either way).
$lockLagSec = $null
if (($null -ne $lockOff) -and $survive.LockFound) {
    $lockOffTime = Get-ExLogcatTime -Line $lockOff.Raw
    $markerTime = Get-ExLogcatTime -Line $survive.LockRaw
    if (($null -ne $lockOffTime) -and ($null -ne $markerTime)) {
        $lockLagSec = [math]::Round((Get-ExSignedDeltaSeconds -From $markerTime -To $lockOffTime), 2)
    }
}

# ---- 8. verdicts --------------------------------------------------------------
$injectOk = ($tickFacts.Started -and ($tickFacts.ErrorCount -eq 0) -and
    ($ticksInWindow.Count -ge [math]::Max(1, [int][math]::Ceiling($expectedTicks * 0.5))))
$psSeen = (@($psDuring | Where-Object { $_ -match 'wake-keepalive\.sh' }).Count -gt 0)
$rearBehavior = Format-ExRearBehavior -Facts $sampleFacts -WatchLabel 'keep-alive watch'
$mainSideEffect = if ($sampleFacts.MainHeldOffThroughout) {
    'main display stayed dark (no side effect observed)'
} else {
    ('main display was lit during the watch (first ON at +{0}s) -- the wake key is NOT free of the main screen' -f $sampleFacts.MainFirstOnSec)
}

# Primary measurement = the app's own device-clock chain (marker -> detach -> attach); the 2s
# sampling corroborates. When the marker line wrapped out of the buffer the sample grain is all
# that is left, and the verdict says so instead of pretending the same precision. An empty or
# unreadable watch claims nothing (Get-ExWakeSampleFacts rule).
# An owner loss only counts as a reclaim when the Dashboard was ever up (a NO-BASELINE round
# has owner=native from sample 1 and must not read as "reclaimed at +2s").
$ownerLost = (($sampleFacts.SampleCount -gt 0) -and (-not $sampleFacts.OwnerHeldThroughout))
$cleared = ($survive.ClearedAfterLock -eq $true) -or ($ownerLost -and $dashboardUp)
$clearedSecText = if ($null -ne $survive.ClearedSec) {
    if ($null -ne $lockLagSec) {
        ('{0}s (device clock: lock marker -> first Dashboard detach; the lock itself landed {1}s after the marker, so from the lock: {2}s)' -f
            $survive.ClearedSec, $lockLagSec, [math]::Round(($survive.ClearedSec - $lockLagSec), 1))
    } else {
        ('{0}s (device clock: lock marker -> first Dashboard detach)' -f $survive.ClearedSec)
    }
} elseif ($null -ne $sampleFacts.OwnerFirstLostSec) {
    ('+{0}s (sample grain: owner first lost in the samples, no detach line to time it)' -f $sampleFacts.OwnerFirstLostSec)
} else { 'not measured (no detach and no sample-point owner loss)' }
$clearedLine = if ($survive.ClearedAfterLock -eq $true) {
    ('cleared-after-lock    : True at {0}' -f $clearedSecText)
} elseif ($survive.ClearedAfterLock -eq $false) {
    'cleared-after-lock    : False (no Dashboard detach after the lock marker)'
} else {
    'cleared-after-lock    : not measured (the lock marker line was not in the buffer)'
}
$returnLine = if ($survive.ReturnedAfterClear -eq $true) {
    ('returned-after-clear  : True (back at +{0}s, gap {1}ms, display {2}, ends-attached {3})' -f
        $survive.ReturnSec, $survive.ReturnGapMs, $survive.ReturnDisplayId, $survive.EndsAttached)
} elseif ($survive.ClearedAfterLock -eq $true) {
    ('returned-after-clear  : False (no Dashboard attach after the detach; ends-attached {0})' -f $survive.EndsAttached)
} else {
    'returned-after-clear  : not measured (no detach to return from)'
}

if ($externalPollution.Count -gt 0) {
    $e13 = ('E13-RUN-INVALID ({0} external wake interference hit(s) during the run -- the round is void and its samples go to erratum.md)' -f $externalPollution.Count)
    $e13Class = 'n/a (the round is void)'
} elseif (-not $dashboardUp) {
    $e13 = 'E13-NO-BASELINE (the Dashboard never reached the rear display before the lock; see the warnings)'
    $e13Class = 'n/a (no Dashboard to survive)'
} elseif ($null -eq $lockOff) {
    # No PowerGroup group-1 power-off after T0 = the lock never reached the rear display group:
    # there is no lock for the Dashboard to survive, so nothing is judged (review fix: PASS must
    # never be reachable while the lock itself is unproven).
    $e13 = 'E13-NO-LOCK (no PowerGroup group-1 power-off after T0 -- the lock never reached the rear display group; there is no lock to survive)'
    $e13Class = 'n/a (no lock)'
} elseif (-not $injectOk) {
    $why = if (-not $tickFacts.Started) {
        'the loop never logged a start line'
    } elseif ($tickFacts.ErrorCount -gt 0) {
        ('input command failed {0} time(s)' -f $tickFacts.ErrorCount)
    } else {
        ('only {0} injection(s) inside the {1}s window (expected ~{2})' -f $ticksInWindow.Count, $ObserveSeconds, $expectedTicks)
    }
    $e13 = ('E13-INJECT-FAILED ({0}; tick log: {1} tick(s), first={2}, last={3}) -- without the keep-alive the probe premise is unproven' -f
        $why, $tickFacts.TickCount, $tickFacts.FirstTick, $tickFacts.LastTick)
    $e13Class = 'n/a (the keep-alive leg did not receive the treatment)'
} elseif ($cleared) {
    $e13 = ('E13-CLEARED (the system reclaimed the Dashboard at {0}; rear display facts of the same watch: {1})' -f $clearedSecText, $rearBehavior)
    if ($survive.ReturnedAfterClear -eq $true) {
        $e13Class = ('E13-RETURNED (back on the rear display {0}ms after the detach (return display {1}) -- the app`s own lock-window re-projection recreated it before the next sample point)' -f
            $survive.ReturnGapMs, $survive.ReturnDisplayId)
    } elseif ($survive.ClearedAfterLock -eq $true) {
        $e13Class = 'E13-NO-RETURN (no Dashboard attach after the detach -- the reclaim stuck)'
    } else {
        $e13Class = 'E13-NO-RETURN (the owner was lost in the samples with no detach line to time it -- the freezer may have silenced the app first)'
    }
} else {
    $precision = if ($sampleFacts.SampleCount -eq 0) {
        'the device-clock chain alone (no readable sample line)'
    } elseif ($survive.LockFound) {
        'device-clock chain + {0} samples (owner=dashboard in every one)' -f $sampleFacts.SampleCount
    } else {
        'the {0} samples only (owner=dashboard in every one; the lock marker line was not in the buffer)' -f $sampleFacts.SampleCount
    }
    $e13 = ('E13-PASS (the Dashboard held the rear display through the whole {0}s keep-alive watch: no Dashboard detach after the lock; evidence: {1}; rear display: {2})' -f
        $ObserveSeconds, $precision, $rearBehavior)
    $e13Class = 'n/a (no reclaim to classify)'
}

# ---- 9. artifacts -------------------------------------------------------------
$sampleNotes = New-Object System.Collections.Generic.List[string]
$sampleNotes.Add('# e13 samples -- keep-alive on across the lock, then the survival watch')
$sampleNotes.Add(('# pre-lock state rear={0} main={1} owner={2}' -f $preSnap.RearPair, $preSnap.MainPair, $preSnap.Owner))
foreach ($line in $samples) { $sampleNotes.Add($line) }
Write-ExArtifact -Name 'e13-samples.txt' -Lines $sampleNotes.ToArray() | Out-Null

Write-ExArtifact -Name 'e13-wake-ticks.txt' -Lines $tickLines | Out-Null
Write-ExArtifact -Name 'e13-inject-errors.txt' -Lines $tickErr | Out-Null

$psLines = New-Object System.Collections.Generic.List[string]
$psLines.Add('# ps while the injection loop was supposed to run')
foreach ($line in $psDuring) { $psLines.Add($line) }
$psLines.Add('')
$psLines.Add('# ps after the stop file')
foreach ($line in $psAfter) { $psLines.Add($line) }
Write-ExArtifact -Name 'e13-inject-ps.txt' -Lines $psLines.ToArray() | Out-Null

$powerLines = New-Object System.Collections.Generic.List[string]
foreach ($entry in $powerEvents) { $powerLines.Add($entry.Raw) }
Write-ExArtifact -Name 'e13-power-group.txt' -Lines $powerLines.ToArray() | Out-Null

$systemWatch = @($systemAll |
    Where-Object { $_ -match 'PowerGroup|wm_finish_activity|remove-task|GreezeManager|ActivityStarterImpl|rearDisplay' } |
    Select-Object -Last 400)

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# e13-lock-survive (ticket #19)')
$out.Add(('rear display                : {0} state={1}' -f $(if ($snap0.Rear) { 'id=' + $snap0.Rear.DisplayId } else { 'NOT FOUND' }), $snap0.RearPair))
$out.Add(('protocol                    : Activity baseline -> keep-alive loop across one lock -> {0}s watch, sample every {1}s' -f $ObserveSeconds, $SampleSeconds))
$out.Add(('wake interval               : {0}ms (command parameter -WakeIntervalMs)' -f $WakeIntervalMs))
$out.Add(('injection loop              : adb shell nohup sh {0} (shell uid 2000), stopped via stop file {1}' -f $loopScript, $stopFile))
$out.Add(('pre-lock                    : rear={0} main={1} owner={2} dashboard-up={3}' -f $preSnap.RearPair, $preSnap.MainPair, $preSnap.Owner, $dashboardUp))
$out.Add(('lock T0 (device clock)      : {0} (KEYCODE_POWER press stamp; marker pc-e13-lock-issued at {1})' -f $lockT0, $survive.LockAt))
$out.Add(('keyguard at start           : {0} (a secure lock cannot be dismissed over adb -- see scenario-notes.md)' -f $keyguardAtStart))
$out.Add(('log buffer                  : {0}' -f $bufferNote))
$out.Add('')
$out.Add('# verdicts')
$out.Add(('inject-started        : {0} (pid={1} display={2} sleep={3}s)' -f $tickFacts.Started, $tickFacts.Pid, $tickFacts.DisplayId, $tickFacts.SleepSeconds))
$out.Add(('inject-running        : {0} (ticks-in-window={1}, expected~{2}, input-errors={3}, ps-seen={4}, wake-key-traces={5})' -f
        $injectOk, $ticksInWindow.Count, $expectedTicks, $tickFacts.ErrorCount, $psSeen, $wakeKeyTraces.Count))
$out.Add(('lock-poweroff         : {0} ({1})' -f ($null -ne $lockOff), $(if ($lockOff) { $lockOff.Raw.Trim() } else { 'no PowerGroup group-1 power-off after the lock stamp' })))
$out.Add(('lock-press-lag        : {0} (seconds from the lock marker to the PowerGroup power-off; the marker is stamped before the press)' -f $(if ($null -ne $lockLagSec) { $lockLagSec } else { 'not measured' })))
$out.Add(('lock-marker           : {0} (at {1})' -f $survive.LockFound, $survive.LockAt))
$out.Add($clearedLine)
$out.Add($returnLine)
$out.Add(('detach/attach counts  : {0} / {1} after the lock marker' -f $survive.DetachCountAfterLock, $survive.AttachCountAfterLock))
$out.Add(('reproject-after-lock  : {0} (first at +{1}s, {2} effect line(s))' -f $survive.ReprojectAfterLock, $survive.ReprojectSec, $survive.ReprojectCount))
$out.Add(('owner-lost-s          : {0} (sample grain; owner at end of watch: {1})' -f $sampleFacts.OwnerFirstLostSec, $sampleFacts.OwnerEnd))
$out.Add(('rear-behavior         : {0}' -f $rearBehavior))
$out.Add(('main-side-effect      : {0}' -f $mainSideEffect))
$out.Add(('pollution             : {0}' -f $(if ($externalPollution.Count -eq 0) { 'none detected' } else { '{0} external hit(s) -- see erratum.md' -f $externalPollution.Count })))
$out.Add(('e13                   : {0}' -f $e13))
$out.Add(('e13-class             : {0}' -f $e13Class))
$out.Add('')
$out.Add('# survive event trail (app logcat, after the lock marker)')
foreach ($record in $survive.Events) {
    $out.Add(('  [{0,6}s] {1,-11} display={2} instances={3} {4}' -f $record.Sec, $record.Kind, $record.DisplayId, $record.Instances, ($record.Raw.Trim())))
}
$out.Add('')
$out.Add('# samples (keep-alive on, one line per point: rear + main state pair, rear owner)')
foreach ($line in $samples) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# injection tick log (device side, one line per KEYCODE_WAKEUP)')
foreach ($line in $tickLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# power-key presses of this run (device clock, for power-button attribution)')
foreach ($press in $script:PowerPresses) {
    $out.Add(('  {0}  {1}' -f $press.DeviceTime, $press.Purpose))
}
$out.Add('')
$out.Add('# power-group events (logcat -b all)')
foreach ($entry in $powerEvents) { $out.Add('  ' + $entry.Raw.Trim()) }
$out.Add('')
$out.Add('# system-side lines (logcat -b all, filtered)')
foreach ($line in $systemWatch) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# app log (RearCue tag)')
foreach ($line in $appLog) { $out.Add('  ' + $line) }
Write-ExArtifact -Name 'e13-lock-survive.txt' -Lines $out.ToArray() | Out-Null

foreach ($line in ($out | Where-Object { $_ -match '^(inject-|lock-|cleared-|returned-|detach/|reproject-|owner-|rear-behavior|main-side-effect|pollution|e13)' })) {
    Write-ExNote $line
}
# ---- 10. erratum (external pollution never belongs in the verdict) ------------
if ($externalPollution.Count -gt 0) {
    $erratum = New-Object System.Collections.Generic.List[string]
    $erratum.Add('# erratum -- external pollution (ticket #19)')
    $erratum.Add('')
    $erratum.Add('The run below was disturbed from outside the script (fingerprint wake / power button')
    $erratum.Add('pressed by hand / phone touched). Those samples are NOT lock-screen facts; raw output')
    $erratum.Add('is left untouched and this note marks it. findings must use a clean round.')
    $erratum.Add('')
    foreach ($hit in $externalPollution) {
        $erratum.Add(('- [{0}] {1}' -f $hit.Class, $(if ($hit.Raw) { $hit.Raw.Trim() } else { '<no raw line captured>' })))
    }
    Write-ExArtifact -Name 'erratum.md' -Lines $erratum.ToArray() | Out-Null
    Write-ExNote ('pollution: {0} external hit(s) recorded in erratum.md' -f $externalPollution.Count)
}

# ---- 11. scenario notes (summary.md embeds these) -----------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (E13 lock survival, ticket #19)')
$notes.Add('')
$notes.Add('- **Protocol**: Activity baseline up (one shell notification -> Dashboard on the rear) ->')
$notes.Add('  the E12 injection loop started BEFORE the lock and kept running across it (MRSS-style:')
$notes.Add('  keep-alive means the wake keys are already flowing when the display group powers off) ->')
$notes.Add(('  one KEYCODE_POWER lock -> {0}s survival watch, sample every {1}s -> stop file.' -f $ObserveSeconds, $SampleSeconds))
$notes.Add('- **Precondition**: the debug APK is already installed (`-Task install` / `-Task drive` do')
$notes.Add('  that; this task deliberately does not reinstall), the listener grant is re-asserted by')
$notes.Add('  the 02-authorize step, and the phone starts unlocked (a swipe-up dismisses this keyguard,')
$notes.Add('  `wm dismiss-keyguard` is a silent no-op -- ticket #7).')
$notes.Add(('- **Injection interval**: `{0}ms`, a command parameter (`-WakeIntervalMs`), converted to a `sleep` argument of {1}s.' -f $WakeIntervalMs, $sleepArg))
$notes.Add('- **Two clock anchors on the lock**: `pc-e13-lock-issued` into the RearCue tag is the')
$notes.Add('  survive-parser anchor (same device clock as the app`s `Dashboard detach` line, so')
$notes.Add('  "reclaimed at +Xs" carries no PC skew); the `date` stamp next to every injected input (lock')
$notes.Add('  presses and wake pokes alike) is the authoritative T0 for tick/pollution attribution,')
$notes.Add('  because the main log buffer wraps under injection load (08-wake-keepalive lesson).')
$notes.Add('- **Log buffer**: the run grows the main + system log buffers to 32M first ({0}) so the lock' -f $bufferNote)
$notes.Add('  marker and the baseline window survive the injection flood. The shrink back happens in')
$notes.Add('  `ex.ps1` AFTER the 05-collect step: `logcat -G` truncates the ring buffer, and shrinking')
$notes.Add('  first once left the collect capture with 1 event and wrong chain facts (20260922-224734).')
$notes.Add('- **Keep-alive is the E12 loop verbatim** (`/data/local/tmp/wake-keepalive.sh`, shell uid')
$notes.Add('  2000): E13 is about the Dashboard`s survival GIVEN the keep-alive, so the treatment is')
$notes.Add('  deliberately the one already validated by E12 -- no new mechanism is mixed into this probe.')
$notes.Add('  The deviation ticket #16 recorded still applies: the loop runs in an adb shell (same uid as')
$notes.Add('  a Shizuku UserService), not through the binder path.')
$notes.Add('- **The control leg is archived, not repeated**: without keep-alive the Dashboard is reclaimed')
$notes.Add('  1.3-1.4s after the lock (ticket #11 runs, pinned as the logcat-survive-cleared-* fixtures).')
$notes.Add('  This run spends its whole lock on the keep-alive question instead of re-proving the control.')
$notes.Add('- **Deviation -- no secure unlock needed on this build**: `wm dismiss-keyguard` is a no-op on')
$notes.Add('  this keyguard (ticket #7), but a swipe up dismisses it (verified 2026-09-22); the run still')
$notes.Add('  starts from an unlocked phone and its restore is wake + dismiss attempt + swipe, best effort.')
$notes.Add('- **Pollution policy**: fingerprint wakes and power-button transitions that are not one of')
$notes.Add('  this run`s own device-clock-stamped injections void the round (E13-RUN-INVALID + erratum.md),')
$notes.Add('  per the ticket #11/#16 lesson. Two signatures are absolved as our own: a transition within')
$notes.Add('  +-3s of a stamped injection, and a `power_button` power-off within 200ms after a')
$notes.Add('  WAKE_REASON_WAKE_KEY event (the wake key`s own mode flip -- real round')
$notes.Add('  20260922-222257-lock-survive had one 3ms behind a rear-display wake, fixture')
$notes.Add('  logcat-e13-wake-flip.txt).')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

# ---- 12. restore --------------------------------------------------------------
if (-not $NoRestore) {
    Write-ExNote 'restoring: stopping any leftover loop, emptying the Icon Set, waking the main screen'
    Invoke-Adb -Arguments @('shell', 'touch', $stopFile) -AllowFailure | Out-Null
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    Invoke-ExKeyguardDismiss
    Start-Sleep -Seconds 1
    # The buffer SHRINK lives in ex.ps1 after the 05-collect step (`logcat -G` truncates the ring
    # buffer: shrinking here once left the collect capture with 1 event and wrong chain facts,
    # 20260922-224734). Sizes travel on the return object for that step to restore.
    Write-ExNote ('log buffers stay grown (main {0}Kb / system {1}Kb were the originals) until after the collect step' -f $mainBufferKb, $systemBufferKb)
    if (Test-ExKeyguardLocked) { Write-ExNote 'still locked: a secure lock needs a human to unlock' }
}
return @{
    E13           = $e13
    Class         = $e13Class
    InjectOk      = $injectOk
    Cleared       = $cleared
    MainBufferKb  = $mainBufferKb
    SystemBufferKb = $systemBufferKb
}
