# 08-wake-keepalive.ps1 -- E12 (ticket #16): can periodic wake keys keep the rear display ON
# after the lock? One run = baseline leg (no keep-alive) + keep-alive leg, one session, one
# protocol, verdicts from device facts only.
#
# Run through the one-command entry point (`ex.ps1 -Task wake-keepalive` = this -> collect), or
# alone:
#   .\tools\ex\08-wake-keepalive.ps1
#   .\tools\ex\08-wake-keepalive.ps1 -WakeIntervalMs 100 -ObserveSeconds 120
#
# E12 is a pure display-power question, so the probe needs NO app and NO Shizuku process: the
# injection loop (tools/ex/device/wake-keepalive.sh) runs on the device as shell (uid 2000, the
# same identity a Shizuku UserService would have) and the PC only starts/stops it and samples.
# The injection interval is a command parameter (-WakeIntervalMs), never baked into the logic.
#
# Verdicts come from device facts, never from exit codes:
#   * `dumpsys display` per sampling point: rear AND main display state/committedState (the
#     price of a wake key that also lights the main screen is a fact, not a footnote)
#   * the device-side tick log of the loop (one line per injection with the `input` exit code)
#     plus `ps` snapshots -- the loop really running, not "the command did not complain"
#   * system-side PowerGroup lines: the lock really powering off display group 1, and the
#     injected KEYCODE_WAKEUP really reaching the power layer (reason + details, so a human's
#     fingerprint/power-button wake can never pass as our injection)
#
# Verdict words (the table lives in docs/poc-findings.md, ticket #16 section):
#   E12-PASS           rear stayed ON through the whole keep-alive watch
#   E12-LOST           rear left ON during the keep-alive watch -- the time is always reported
#                      and `e12-class` names which failure it is
#   E12-INJECT-FAILED  the injection loop did not provably run (no ticks / input errors)
#   E12-NO-BASELINE    the control leg never showed the rear leaving ON (nothing to compare)
#   e12-class: E12-IGNORED (left ON at the baseline time) | E12-DELAYED-ONLY (later, but left)
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
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'wake-keepalive' -Serial $Serial | Out-Null }

$loopScript = '/data/local/tmp/wake-keepalive.sh'
$tickFile = '/data/local/tmp/wake-keepalive.ticks'
$stopFile = '/data/local/tmp/wake-keepalive.stop'
$sleepArg = [math]::Round($WakeIntervalMs / 1000.0, 3).ToString([Globalization.CultureInfo]::InvariantCulture)
$expectedTicks = [int][math]::Ceiling($ObserveSeconds * 1000.0 / $WakeIntervalMs)
# Every KEYCODE_POWER press of this run, device-clock stamped (see Invoke-ExPowerKey): the only
# way to tell "the script pressed it" from "a human pressed it" in the PowerGroup lines.
$script:PowerPresses = New-Object System.Collections.Generic.List[object]

function Get-ExWakeSnapshot {
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

function Invoke-ExPowerKey {
    <#
      One KEYCODE_POWER press, timed on the DEVICE clock. The `pc-e12-power-*` logcat marker is
      only human-readable correlation: the main log buffer WRAPS under injection load (a 60s
      keep-alive window at 2 keys/s floods it past the run start, which once lost the baseline
      marker entirely). The authoritative stamp is `date` on the device, read right before the
      press and returned to the caller -- press attribution and T0 never depend on logcat.
    #>
    param([Parameter(Mandatory, Position = 0)][string] $Purpose)
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, ('pc-e12-power-' + $Purpose)) -AllowFailure | Out-Null
    $clock = ((Invoke-Adb -Arguments @('shell', "date '+%m-%d %H:%M:%S'") -AllowFailure) -join '').Trim()
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
    $press = [pscustomobject]@{ Purpose = $Purpose; DeviceTime = $clock }
    $script:PowerPresses.Add($press)
    return $press
}

function Initialize-ExWakeSegment {
    <#
      Reset one leg to a defined start: phone awake, rear display ON, keyguard best-effort
      dismissed. A secure keyguard cannot be dismissed over adb (ticket #7) and on this device a
      blocked proximity sensor makes MIUI veto a KEYCODE_WAKEUP ("Going to sleep due to
      KEYCODE_WAKEUP ... proximity sensor too close"), so a KEYCODE_POWER toggle is the fallback
      wake lever -- every press is marked, so a human's press is still separable from ours.
    #>
    param([Parameter(Mandatory, Position = 0)][string] $Label)

    $pressedPower = $false
    if ((Get-ExWakefulness) -ne 'Awake') {
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
        Start-Sleep -Seconds 2
        if ((Get-ExWakefulness) -ne 'Awake') {
            Write-ExNote ('{0}: KEYCODE_WAKEUP did not keep the phone awake (proximity veto on this device); falling back to KEYCODE_POWER as a toggle' -f $Label)
            Invoke-ExPowerKey -Purpose ($Label + '-reset')
            $pressedPower = $true
            Start-Sleep -Seconds 2
        }
    }
    Invoke-ExKeyguardDismiss | Out-Null
    Start-Sleep -Seconds $SettleSeconds

    $snap = Get-ExWakeSnapshot
    $wakePoked = $false
    if ($snap.RearPair -notlike 'ON/*') {
        # The leg only means something if the rear display is lit at T0; a single directed wake
        # key brings it back (device fact: `Waking up power group ... (groupId=1 ...)`).
        Write-ExNote ('{0}: rear display not ON at reset ({1}); sending one directed KEYCODE_WAKEUP' -f $Label, $snap.RearPair)
        Invoke-Adb -Arguments @('shell', 'input', '-d', '1', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
        $wakePoked = $true
        Start-Sleep -Seconds 2
        $snap = Get-ExWakeSnapshot
    }
    return [pscustomobject]@{
        Snapshot     = $snap
        PressedPower = $pressedPower
        WakePoked    = $wakePoked
    }
}

function Invoke-ExWakeSegment {
    <#
      One leg of the experiment: reset -> (start the injection loop) -> lock with KEYCODE_POWER
      -> sample rear + main state every -SampleSeconds for -ObserveSeconds -> (stop the loop).
      Returns every raw fact; the verdict is computed later, from these.
    #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Label,
        [switch] $KeepAlive
    )

    Write-ExNote ('---- segment {0} ({1}) ----' -f $Label, $(if ($KeepAlive) { 'wake keep-alive ON' } else { 'no keep-alive (control)' }))
    $reset = Initialize-ExWakeSegment -Label $Label
    Write-ExNote ('{0}: pre state rear={1} main={2} owner={3}' -f $Label, $reset.Snapshot.RearPair, $reset.Snapshot.MainPair, $reset.Snapshot.Owner)

    $psDuring = @()
    if ($KeepAlive) {
        Invoke-Adb -Arguments @('shell', 'rm', '-f', $tickFile, ($tickFile + '.err'), $stopFile) -AllowFailure | Out-Null
        $runLine = 'nohup sh {0} 1 {1} {2} {3} >/dev/null 2>&1 &' -f $loopScript, $sleepArg, $tickFile, $stopFile
        Invoke-Adb -Arguments @('shell', $runLine) -AllowFailure | Out-Null
        Start-Sleep -Seconds 2
        $psDuring = @(Invoke-Adb -Arguments @('shell', 'ps', '-A', '-o', 'PID,NAME,args') -AllowFailure |
            Where-Object { $_ -match 'wake-keepalive|PID' })
    }

    # KEYCODE_POWER toggles, so a sleeping screen would be woken instead of locked: wake first
    # (Initialize-Ex*) and press anyway, then verify -- the second press is the 07-lock-compare
    # fallback, device-clock stamped like every press so it cannot be mistaken for a human one.
    $lockPress = Invoke-ExPowerKey -Purpose ($Label + '-lock')
    $lockT0 = $lockPress.DeviceTime
    Start-Sleep -Seconds 2
    if ((Get-ExWakefulness) -eq 'Awake') {
        Write-ExNote ('{0}: still awake after KEYCODE_POWER: sending it once more' -f $Label)
        Invoke-ExPowerKey -Purpose ($Label + '-lock2') | Out-Null
        Start-Sleep -Seconds 2
    }
    Write-ExNote ('{0}: wakefulness while locked: {1}' -f $Label, (Get-ExWakefulness))

    $start = Get-Date
    $deadline = $start.AddSeconds($ObserveSeconds)
    $lines = New-Object System.Collections.Generic.List[string]
    do {
        Start-Sleep -Seconds $SampleSeconds
        $elapsed = [int]((Get-Date) - $start).TotalSeconds
        $snap = Get-ExWakeSnapshot
        $line = Format-ExWakeSampleLine -Elapsed $elapsed -RearPair $snap.RearPair -MainPair $snap.MainPair -Owner $snap.Owner
        $lines.Add($line)
        Write-ExNote ('{0} {1}' -f $Label, $line)
    } while ((Get-Date) -lt $deadline)

    $psAfter = @()
    $tickLines = @()
    $tickErr = @()
    if ($KeepAlive) {
        Invoke-Adb -Arguments @('shell', 'touch', $stopFile) -AllowFailure | Out-Null
        Start-Sleep -Seconds 3
        $psAfter = @(Invoke-Adb -Arguments @('shell', 'ps', '-A', '-o', 'PID,NAME,args') -AllowFailure |
            Where-Object { $_ -match 'wake-keepalive|PID' })
        $tickLines = @(Invoke-Adb -Arguments @('shell', 'cat', $tickFile) -AllowFailure)
        $tickErr = @(Invoke-Adb -Arguments @('shell', 'cat', ($tickFile + '.err')) -AllowFailure)
    }

    return [pscustomobject]@{
        Label       = $Label
        KeepAlive   = [bool]$KeepAlive
        Reset       = $reset
        LockT0      = $lockT0
        SampleLines = $lines.ToArray()
        PsDuring    = $psDuring
        PsAfter     = $psAfter
        TickLines   = $tickLines
        TickErrors  = $tickErr
    }
}

# ---- 0. preflight ------------------------------------------------------------
$snap0 = Get-ExWakeSnapshot
$keyguardAtStart = Test-ExKeyguardLocked
Write-ExNote ('rear display: {0} state={1}; main state={2}; wakefulness={3}' -f
    $(if ($snap0.Rear) { 'id=' + $snap0.Rear.DisplayId } else { 'NOT FOUND' }),
    $snap0.RearPair, $snap0.MainPair, (Get-ExWakefulness))
if (-not $snap0.Rear) {
    Write-ExNote 'WARNING: no rear display in `dumpsys display` (no non-default INTERNAL display with'
    Write-ExNote '         PRESENTATION + OWN_DISPLAY_GROUP). The legs will report no-display pairs.'
}
if ($keyguardAtStart) {
    Write-ExNote 'WARNING: the phone is keyguard-locked and a secure lock cannot be dismissed over adb'
    Write-ExNote '         (ticket #7). Both legs still run -- they lock the phone anyway and they are'
    Write-ExNote '         symmetric -- but "unlock back to steady state between the legs" degrades to'
    Write-ExNote '         "wake + settle"; the deviation is recorded in summary.md.'
}
Write-ExNote ('protocol: baseline leg first (no keep-alive), then keep-alive leg; {0}s per leg, sample every {1}s, wake interval {2}ms' -f
    $ObserveSeconds, $SampleSeconds, $WakeIntervalMs)

Invoke-Adb -Arguments @('push', (Join-Path $PSScriptRoot 'device\wake-keepalive.sh'), $loopScript) -AllowFailure | Out-Null
Invoke-Adb -Arguments @('shell', 'rm', '-f', $tickFile, ($tickFile + '.err'), $stopFile) -AllowFailure | Out-Null
Clear-ExLogcat

# ---- 1-2. the two legs -------------------------------------------------------
$baseline = Invoke-ExWakeSegment -Label 'baseline'
$keepalive = Invoke-ExWakeSegment -Label 'keepalive' -KeepAlive

# ---- 3. evidence -------------------------------------------------------------
$appLog = @(Get-ExLogcat)
$systemAll = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure)
$powerEvents = Get-ExPowerGroupEvents -Logcat $systemAll
$pollutionHits = Get-ExWakePollution -Logcat $systemAll
$baselineFacts = Get-ExWakeSampleFacts -SampleLines $baseline.SampleLines
$keepFacts = Get-ExWakeSampleFacts -SampleLines $keepalive.SampleLines
$tickFacts = Get-ExWakeTickFacts -TickLines $keepalive.TickLines

# T0 of each leg = the device-clock stamp of its lock press (logcat markers only corroborate:
# the main buffer wraps under injection load).
$blT0 = $baseline.LockT0
$kaT0 = $keepalive.LockT0

# The lock really reaching the rear display group: `Powering off display group ... (groupId= 1`.
$blLockOff = $powerEvents | Where-Object {
    ($_.Kind -eq 'power-off') -and ($_.GroupId -eq 1) -and ((Get-ExSecondStamp $_.Time) -ge $blT0)
} | Select-Object -First 1
$kaLockOff = $powerEvents | Where-Object {
    ($_.Kind -eq 'power-off') -and ($_.GroupId -eq 1) -and ((Get-ExSecondStamp $_.Time) -ge $kaT0)
} | Select-Object -First 1

# The injected wake key really reaching the power layer, aimed at the rear group only.
$wakeKeyTraces = @($powerEvents | Where-Object {
    ($_.Kind -eq 'wake') -and ($_.Reason -eq 'WAKE_REASON_WAKE_KEY') -and ($_.GroupId -eq 1) -and
    ((Get-ExSecondStamp $_.Time) -ge $kaT0)
})

# Injections inside the keep-alive window (tick log shares the device clock, 1s grain).
$ticksInWindow = @($tickFacts.Ticks | Where-Object { $_.Time -and $kaT0 -and ($_.Time -ge $kaT0) })

# Which power-button transitions were our own device-clock-stamped presses (+-3s), which are hands
# (Select-ExExternalPollution, shared with the E13 probe).
# Bare capture on purpose: the parser returns ONE array object (the `,$arr` seam convention) and
# an `@(...)` wrap would turn an empty selection into a phantom one-element hit.
$externalPollution = Select-ExExternalPollution -Hits $pollutionHits -PowerPresses $script:PowerPresses.ToArray() -WakeEvents $powerEvents

# ---- 4. verdicts -------------------------------------------------------------
$tolerance = 2 * $SampleSeconds
$injectOk = ($tickFacts.Started -and ($tickFacts.ErrorCount -eq 0) -and
    ($ticksInWindow.Count -ge [math]::Max(1, [int][math]::Ceiling($expectedTicks * 0.5))))
$psSeen = (@($keepalive.PsDuring | Where-Object { $_ -match 'wake-keepalive\.sh' }).Count -gt 0)
# Baseline validity rests on the sampled device facts ONLY (the logcat PowerGroup line wraps
# under injection load and must never gate a verdict -- review finding on 162618).
$baselineValid = (($baselineFacts.SampleCount -gt 0) -and ($null -ne $baselineFacts.RearFirstNonOnSec))

$baselineBehavior = Format-ExRearBehavior -Facts $baselineFacts -WatchLabel 'control watch'
$keepBehavior = Format-ExRearBehavior -Facts $keepFacts -WatchLabel 'keep-alive watch'
$mainSideEffect = if ($keepFacts.MainHeldOffThroughout) {
    'main display stayed dark (no side effect observed)'
} else {
    ('main display was lit during the keep-alive watch (first ON at +{0}s) -- the wake key is NOT free of the main screen' -f $keepFacts.MainFirstOnSec)
}

if (-not $injectOk) {
    $why = if (-not $tickFacts.Started) {
        'the loop never logged a start line'
    } elseif ($tickFacts.ErrorCount -gt 0) {
        ('input command failed {0} time(s)' -f $tickFacts.ErrorCount)
    } else {
        ('only {0} injection(s) inside the {1}s window (expected ~{2})' -f $ticksInWindow.Count, $ObserveSeconds, $expectedTicks)
    }
    $e12 = ('E12-INJECT-FAILED ({0}; tick log: {1} tick(s), first={2}, last={3})' -f $why, $tickFacts.TickCount, $tickFacts.FirstTick, $tickFacts.LastTick)
    $e12Class = 'n/a (the keep-alive leg did not receive the treatment)'
} elseif (-not $baselineValid) {
    $e12 = ('E12-NO-BASELINE (control samples do not show the rear leaving ON, so "it would have stayed on anyway" cannot be excluded; control: {0}; lock power-off line (corroboration only): {1})' -f
        $baselineBehavior, ($null -ne $blLockOff))
    $e12Class = 'n/a (no control to compare against)'
} elseif ($keepFacts.RearHeldOnThroughout) {
    $e12 = ('E12-PASS (rear display stayed ON for the whole {0}s keep-alive watch at {1}ms injection interval; control leg left ON at +{2}s)' -f
        $ObserveSeconds, $WakeIntervalMs, $baselineFacts.RearFirstNonOnSec)
    $e12Class = 'n/a (no loss to classify)'
} else {
    $e12 = ('E12-LOST (rear display left ON at +{0}s of the keep-alive watch; control leg left ON at +{1}s; {2})' -f
        $keepFacts.RearFirstNonOnSec, $baselineFacts.RearFirstNonOnSec, $keepBehavior)
    if ($null -eq $keepFacts.RearFirstNonOnSec) {
        $e12Class = 'n/a (no readable keep-alive samples to classify)'
    } elseif ([math]::Abs($keepFacts.RearFirstNonOnSec - $baselineFacts.RearFirstNonOnSec) -le $tolerance) {
        # Facts only (when it left ON vs the control); "the key was ignored" is a mechanism
        # claim and belongs in findings with log support -- never in the verdict text.
        $e12Class = ('E12-IGNORED (left ON at +{0}s vs control +{1}s: unchanged within the {2}s sampling tolerance)' -f
            $keepFacts.RearFirstNonOnSec, $baselineFacts.RearFirstNonOnSec, $tolerance)
    } elseif ($keepFacts.RearFirstNonOnSec -gt $baselineFacts.RearFirstNonOnSec) {
        $e12Class = ('E12-DELAYED-ONLY (left ON at +{0}s vs control +{1}s: postponed {2}s, still left ON inside the watch)' -f
            $keepFacts.RearFirstNonOnSec, $baselineFacts.RearFirstNonOnSec,
            ($keepFacts.RearFirstNonOnSec - $baselineFacts.RearFirstNonOnSec))
    } else {
        $e12Class = ('E12-IGNORED (left ON at +{0}s, at or before the control leg`s +{1}s)' -f
            $keepFacts.RearFirstNonOnSec, $baselineFacts.RearFirstNonOnSec)
    }
}

# ---- 5. artifacts ------------------------------------------------------------
$baselineNotes = New-Object System.Collections.Generic.List[string]
$baselineNotes.Add('# e12 samples -- baseline leg (no keep-alive): KEYCODE_POWER lock, then sampling')
$baselineNotes.Add(('# pre state rear={0} main={1} owner={2}' -f $baseline.Reset.Snapshot.RearPair, $baseline.Reset.Snapshot.MainPair, $baseline.Reset.Snapshot.Owner))
foreach ($line in $baseline.SampleLines) { $baselineNotes.Add($line) }
Write-ExArtifact -Name 'e12-samples-baseline.txt' -Lines $baselineNotes.ToArray() | Out-Null

$keepNotes = New-Object System.Collections.Generic.List[string]
$keepNotes.Add(('# e12 samples -- keep-alive leg: injection loop (KEYCODE_WAKEUP every ' + $sleepArg + 's on display 1) across the lock'))
$keepNotes.Add(('# pre state rear={0} main={1} owner={2}' -f $keepalive.Reset.Snapshot.RearPair, $keepalive.Reset.Snapshot.MainPair, $keepalive.Reset.Snapshot.Owner))
foreach ($line in $keepalive.SampleLines) { $keepNotes.Add($line) }
Write-ExArtifact -Name 'e12-samples-keepalive.txt' -Lines $keepNotes.ToArray() | Out-Null

Write-ExArtifact -Name 'e12-wake-ticks.txt' -Lines $keepalive.TickLines | Out-Null
Write-ExArtifact -Name 'e12-inject-errors.txt' -Lines $keepalive.TickErrors | Out-Null

$psLines = New-Object System.Collections.Generic.List[string]
$psLines.Add('# ps while the injection loop was supposed to run')
foreach ($line in $keepalive.PsDuring) { $psLines.Add($line) }
$psLines.Add('')
$psLines.Add('# ps after the stop file')
foreach ($line in $keepalive.PsAfter) { $psLines.Add($line) }
Write-ExArtifact -Name 'e12-inject-ps.txt' -Lines $psLines.ToArray() | Out-Null

$powerLines = New-Object System.Collections.Generic.List[string]
foreach ($entry in $powerEvents) { $powerLines.Add($entry.Raw) }
Write-ExArtifact -Name 'e12-power-group.txt' -Lines $powerLines.ToArray() | Out-Null

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# e12-wake-keepalive (ticket #16)')
$out.Add(('rear display                : {0} state={1}' -f $(if ($snap0.Rear) { 'id=' + $snap0.Rear.DisplayId } else { 'NOT FOUND' }), $snap0.RearPair))
$out.Add(('protocol                    : baseline leg first (control), then keep-alive leg; {0}s per leg, sample every {1}s' -f $ObserveSeconds, $SampleSeconds))
$out.Add(('wake interval               : {0}ms (command parameter -WakeIntervalMs)' -f $WakeIntervalMs))
$out.Add(('injection loop              : adb shell nohup sh {0} (shell uid 2000), stopped via stop file {1}' -f $loopScript, $stopFile))
$out.Add(('baseline pre-lock           : rear={0} main={1} owner={2}' -f $baseline.Reset.Snapshot.RearPair, $baseline.Reset.Snapshot.MainPair, $baseline.Reset.Snapshot.Owner))
$out.Add(('keep-alive pre-lock         : rear={0} main={1} owner={2}' -f $keepalive.Reset.Snapshot.RearPair, $keepalive.Reset.Snapshot.MainPair, $keepalive.Reset.Snapshot.Owner))
$out.Add(('leg T0 (device clock)       : baseline={0} keep-alive={1} (KEYCODE_POWER press stamps)' -f $blT0, $kaT0))
$out.Add(('keyguard at start           : {0} (a secure lock cannot be dismissed over adb -- see scenario-notes.md)' -f $keyguardAtStart))
$out.Add('')
$out.Add('# verdicts')
$out.Add(('inject-started        : {0} (pid={1} display={2} sleep={3}s)' -f $tickFacts.Started, $tickFacts.Pid, $tickFacts.DisplayId, $tickFacts.SleepSeconds))
$out.Add(('inject-running        : {0} (ticks-in-window={1}, expected~{2}, input-errors={3}, ps-seen={4}, wake-key-traces={5})' -f
        $injectOk, $ticksInWindow.Count, $expectedTicks, $tickFacts.ErrorCount, $psSeen, $wakeKeyTraces.Count))
$out.Add(('baseline-lock-poweroff: {0} ({1})' -f ($null -ne $blLockOff), $(if ($blLockOff) { $blLockOff.Raw.Trim() } else { 'no PowerGroup group-1 power-off after the baseline lock marker' })))
$out.Add(('keepalive-lock-poweroff: {0} ({1})' -f ($null -ne $kaLockOff), $(if ($kaLockOff) { $kaLockOff.Raw.Trim() } else { 'no PowerGroup group-1 power-off after the keep-alive lock marker' })))
$out.Add(('baseline              : {0}' -f $baselineBehavior))
$out.Add(('baseline-left-on-s    : {0}' -f $baselineFacts.RearFirstNonOnSec))
$out.Add(('baseline-valid        : {0}' -f $baselineValid))
$out.Add(('keepalive-left-on-s   : {0}' -f $keepFacts.RearFirstNonOnSec))
$out.Add(('rear-behavior         : {0}' -f $keepBehavior))
$out.Add(('main-side-effect      : {0}' -f $mainSideEffect))
$out.Add(('pollution             : {0}' -f $(if ($externalPollution.Count -eq 0) { 'none detected' } else { '{0} external hit(s) -- see erratum.md' -f $externalPollution.Count })))
$out.Add(('e12                   : {0}' -f $e12))
$out.Add(('e12-class             : {0}' -f $e12Class))
$out.Add('')
$out.Add('# samples (baseline leg, no keep-alive)')
foreach ($line in $baseline.SampleLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# samples (keep-alive leg)')
foreach ($line in $keepalive.SampleLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# injection tick log (device side, one line per KEYCODE_WAKEUP)')
foreach ($line in $keepalive.TickLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# power-key presses of this run (device clock, for power-button attribution)')
foreach ($press in $script:PowerPresses) {
    $out.Add(('  {0}  {1}' -f $press.DeviceTime, $press.Purpose))
}
$out.Add('')
$out.Add('# power-group events (logcat -b all)')
foreach ($entry in $powerEvents) { $out.Add('  ' + $entry.Raw.Trim()) }
$out.Add('')
$out.Add('# app log (RearCue tag)')
foreach ($line in $appLog) { $out.Add('  ' + $line) }
Write-ExArtifact -Name 'e12-wake-keepalive.txt' -Lines $out.ToArray() | Out-Null

foreach ($line in ($out | Where-Object { $_ -match '^(inject-|baseline|keepalive-|rear-behavior|main-side-effect|pollution|e12)' })) {
    Write-ExNote $line
}

# ---- 6. erratum (external pollution never belongs in the verdict) -------------
if ($externalPollution.Count -gt 0) {
    $erratum = New-Object System.Collections.Generic.List[string]
    $erratum.Add('# erratum -- external pollution (ticket #16)')
    $erratum.Add('')
    $erratum.Add('The watch window(s) below were disturbed from outside the script (fingerprint wake /')
    $erratum.Add('power button pressed by hand / phone touched). Those samples are NOT lock-screen facts;')
    $erratum.Add('raw output is left untouched and this note marks it. findings must use a clean round.')
    $erratum.Add('')
    foreach ($hit in $externalPollution) {
        $erratum.Add(('- [{0}] {1}' -f $hit.Class, $hit.Raw.Trim()))
    }
    Write-ExArtifact -Name 'erratum.md' -Lines $erratum.ToArray() | Out-Null
    Write-ExNote ('pollution: {0} external hit(s) recorded in erratum.md' -f $externalPollution.Count)
}

# ---- 7. scenario notes (summary.md embeds these) ------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (E12 wake keep-alive, ticket #16)')
$notes.Add('')
$notes.Add('- **How the injection loop starts/stops (the recorded trade-off)**: a device-side sh loop')
$notes.Add('  (`/data/local/tmp/wake-keepalive.sh`, pushed from `tools/ex/device/`) started with')
$notes.Add('  `adb shell "nohup sh ... >/dev/null 2>&1 &"` and stopped by creating a stop file the loop')
$notes.Add('  checks each iteration. Chosen over an app-side Shizuku UserService because E12 is a pure')
$notes.Add('  display-power question -- no app, no binder, no UserService lifecycle is needed, and the')
$notes.Add('  loop runs as shell uid 2000, the identity a Shizuku UserService would have anyway. The PC')
$notes.Add('  only samples; every injection happens on the device and leaves its own tick line with the')
$notes.Add('  `input` exit code, plus a `ps` snapshot while it runs.')
$notes.Add(('- **Injection interval**: `{0}ms`, a command parameter (`-WakeIntervalMs`), converted to a `sleep` argument of {1}s.' -f $WakeIntervalMs, $sleepArg))
$notes.Add(('- **Legs**: baseline (control, no keep-alive) first, then keep-alive, {0}s each, sample every {1}s; the lock is KEYCODE_POWER (07-lock-compare precedent) and the loop runs continuously ACROSS the lock (MRSS-style: keep-alive means the wake keys are already flowing when the display group powers off).' -f $ObserveSeconds, $SampleSeconds))
$notes.Add('- **Deviation -- inter-leg reset**: the ticket asks for "unlock back to steady state" between')
$notes.Add('  the legs. This device has a SECURE keyguard (PIN/biometric) that adb cannot dismiss')
$notes.Add('  (`wm dismiss-keyguard` no-ops; ticket #7 recorded the same), so the reset degrades to')
$notes.Add('  "wake + dismiss attempt + settle + require rear=ON at T0", applied IDENTICALLY to both legs.')
$notes.Add('  Both legs therefore start from the same state (keyguard up, rear ON) and remain a valid')
$notes.Add('  same-round control comparison; the absolute baseline may differ from a run made from the')
$notes.Add('  unlocked steady state.')
$notes.Add('- **Environment fact found while building**: with the proximity sensor covered (phone lying')
$notes.Add('  face down), MIUI vetoes a plain `KEYCODE_WAKEUP` (`BaseMiuiPhoneWindowManager: Going to')
$notes.Add('  sleep due to KEYCODE_WAKEUP/KEYCODE_DPAD_CENTER: proximity sensor too close`). The reset')
$notes.Add('  falls back to a marked `KEYCODE_POWER` toggle wake, and every press is stamped with the')
$notes.Add('  DEVICE clock straight from `date` so a human press stays separable from ours.')
$notes.Add('- **Why T0 is not a logcat marker**: the main log buffer wraps under injection load (2 keys/s')
$notes.Add('  for 60s flood it past the run start -- one round lost its baseline marker that way and')
$notes.Add('  misjudged the control leg). The authoritative stamps are the `date` reads next to each')
$notes.Add('  KEYCODE_POWER press and the tick log; `pc-e12-power-*` logcat lines remain as corroboration.')
$notes.Add('- **No install step**: E12 judges display power state only, so `-Task wake-keepalive` does not')
$notes.Add('  run `01-install` (no MIUI USB install dialog risk, no app dependency); `05-collect` simply')
$notes.Add('  finds no app state and records that.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

# ---- 8. restore ---------------------------------------------------------------
if (-not $NoRestore) {
    Write-ExNote 'restoring: waking the main screen and dismissing the keyguard (best effort)'
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    Invoke-ExKeyguardDismiss | Out-Null
    Start-Sleep -Seconds 1
    if (Test-ExKeyguardLocked) { Write-ExNote 'still locked: a secure lock needs a human to unlock' }
}
return @{ E12 = $e12; Class = $e12Class; InjectOk = $injectOk; BaselineValid = $baselineValid }
