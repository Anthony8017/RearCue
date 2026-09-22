# 09-task-move.ps1 -- E14 (ticket #18): can the task-move transaction (moveRootTaskToDisplay via
# `service call activity_task <code> i32 <taskId> i32 <displayId>`, MRSS's second rear-display
# path) put an existing Dashboard task ON the rear display while the keyguard is locked?
#
# Run through the one-command entry point (`ex.ps1 -Task task-move` = authorize -> this ->
# collect), or alone:
#   .\tools\ex\09-task-move.ps1
#   .\tools\ex\09-task-move.ps1 -ObserveSeconds 10 -SampleSeconds 2
#   .\tools\ex\09-task-move.ps1 -TxnCode 51 -NoRearWake
#
# The phone has a SECURE keyguard (PIN/fingerprint) that adb cannot dismiss, and this scenario
# LOCKS it on purpose -- so every segment that needs the unlocked state runs FIRST (ticket #18
# constraint 3), and the run is effectively ONE SHOT:
#   (1) prep        an unlocked Dashboard task (notification -> E1 chain), read its root task id
#   (2) control     the SAME transaction unlocked must move the task (onto the rear display):
#                   that is what proves the transaction code and the argument order are right
#   (3) position    move the task OFF the rear display (onto display 0)
#   (4) lock        KEYCODE_POWER, device-clock stamped (08-wake-keepalive precedent)
#   (5) locked      the transaction again, with per-attempt sampling of task placement / rear
#                   owner / rear state, plus a same-state direction control and a rear-awake
#                   variant (phase B) so "display off" cannot be mistaken for "lock gate"
#
# Verdicts come from device facts only -- `dumpsys activity activities` (where the task really is),
# system-side log lines (ActivityStarterImpl / WindowManager / wm_finish_activity) and the app's
# own logcat. The `service call` reply and the command exit code are archived as evidence and
# NEVER judged on (ticket #18 constraint 1).
#
# Verdict words (the table lives in docs/poc-findings.md, ticket #18 section):
#   E14-PASS           the locked-state transaction really landed the task on the rear display
#   E14-REJECTED       the system refused it -- the refusal line is quoted verbatim
#   E14-TXN-BROKEN     the control failed while unlocked too (wrong code/usage: no verdict on the
#                      lock question is possible; the phone is NOT locked in this case)
#   E14-NO-TASK        no Dashboard task could be prepared before the lock (warned up front)
#   E14-TASK-GONE      the task was reaped before the locked transaction could move it
#   E14-NO-EFFECT      the transaction ran but the task never reached the rear display and no
#                      refusal line exists (nothing to attribute a cause to)
#   E14-RUN-INVALID    external pollution (fingerprint wake / a human on the phone) voids the
#                      round; the samples go to erratum.md
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $TxnCode = 51,
    [int] $ObserveSeconds = 6,
    [int] $SampleSeconds = 1,
    [int] $SettleSeconds = 6,
    [switch] $NoRearWake,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'task-move' -Serial $Serial | Out-Null }

$dashboardComponent = 'com.rearcue.poc/.rear.RearDashboardActivity'
# Every KEYCODE_POWER press of this run, device-clock stamped: the only way to tell "the script
# pressed it" from "a human pressed it" in the PowerGroup lines (08-wake-keepalive precedent).
$script:PowerPresses = New-Object System.Collections.Generic.List[object]

function Get-ExDeviceClock {
    ((Invoke-Adb -Arguments @('shell', "date '+%m-%d %H:%M:%S'") -AllowFailure) -join '').Trim()
}

function Invoke-ExPowerKey {
    param([Parameter(Mandatory, Position = 0)][string] $Purpose)
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, ('pc-e14-power-' + $Purpose)) -AllowFailure | Out-Null
    $clock = Get-ExDeviceClock
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
    $press = [pscustomobject]@{ Purpose = $Purpose; DeviceTime = $clock }
    $script:PowerPresses.Add($press)
    return $press
}

function Get-ExTaskSnapshot {
    <# One look at the device: task placement + rear owner + rear state + last app line. #>
    $activityDump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure) -join "`n"
    $displayDump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
    $rear = Get-RearDisplay -DumpsysDisplay $displayDump
    if ($script:TaskId) {
        $place = Get-ExTaskPlacement -DumpsysActivities $activityDump -TaskId $script:TaskId
    } else {
        $place = Get-ExTaskPlacement -DumpsysActivities $activityDump -Component $dashboardComponent
    }
    $owner = 'none'
    if ($rear) { $owner = Get-RearScreenOwner -DumpsysActivities $activityDump -DisplayId $rear.DisplayId }
    $lastLog = @(@(Get-ExLogcat) | Where-Object { $_ -match 'RearCue\s*: ' } | Select-Object -Last 1)
    return [pscustomobject]@{
        Rear        = $rear
        RearPair    = if ($rear) { '{0}/{1}' -f $rear.State, $rear.CommittedState } else { 'no-rear-display' }
        Owner       = $owner
        Place       = $place
        Last        = (Get-ExRearCueMessage ($lastLog -join ''))
    }
}

function Invoke-ExTaskMove {
    <#
      One task-move transaction: `service call activity_task <code> i32 <taskId> i32 <display>`.
      The reply is recorded as evidence only; the caller judges from `dumpsys` placement.
    #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Label,
        [Parameter(Mandatory, Position = 1)][int] $TargetDisplay
    )
    $before = Get-ExTaskSnapshot
    $clock = Get-ExDeviceClock
    $raw = @(Invoke-Adb -Arguments @('shell', 'service', 'call', 'activity_task', [string]$TxnCode,
            'i32', [string]$script:TaskId, 'i32', [string]$TargetDisplay) -AllowFailure)
    $reply = ConvertTo-ExServiceCallResult -Line (($raw -join ' ').Trim())
    $record = [pscustomobject]@{
        Label          = $Label
        TargetDisplay  = $TargetDisplay
        Command        = ('service call activity_task {0} i32 {1} i32 {2}' -f $TxnCode, $script:TaskId, $TargetDisplay)
        DeviceTime     = $clock
        PlacementBefore = (Format-ExPlacementField $before.Place)
        RearBefore     = $before.RearPair
        Reply          = $reply.Raw
        ReplyStatus    = $reply.Status
    }
    Write-ExNote ('{0}: {1} @ {2} (task was {3})' -f $Label, $record.Command, $clock, $record.PlacementBefore)
    $script:MoveRecords.Add($record)
    return $record
}

function Format-ExPlacementField {
    param([Parameter(Position = 0)][AllowNull()][object] $Place)
    if (-not $Place -or -not $Place.Found) { return 'absent' }
    return ('t{0}@d{1}' -f $Place.TaskId, $Place.DisplayId)
}

function Add-ExWatchSample {
    <# One sampling point of the locked watch (the wire line Get-ExTaskMoveSampleFacts reads). #>
    param([Parameter(Mandatory, Position = 0)][int] $Elapsed)
    $snap = Get-ExTaskSnapshot
    $taskId = $null; $taskDisplay = $null
    if ($snap.Place -and $snap.Place.Found) { $taskId = $snap.Place.TaskId; $taskDisplay = $snap.Place.DisplayId }
    $line = Format-ExTaskMoveSampleLine -Elapsed $Elapsed -TaskId $taskId -TaskDisplay $taskDisplay `
        -Owner $snap.Owner -StatePair $snap.RearPair -Last $snap.Last
    $script:Samples.Add($line)
    Write-ExNote $line
    return $line
}

function Invoke-ExWatchSegment {
    <# Sample every -SampleSeconds for -Seconds, tagging the lines with the running elapsed time. #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Label,
        [Parameter(Mandatory, Position = 1)][int] $Seconds
    )
    $end = (Get-Date).AddSeconds($Seconds)
    do {
        Start-Sleep -Seconds $SampleSeconds
        $elapsed = [int]((Get-Date) - $script:WatchStart).TotalSeconds
        Add-ExWatchSample -Elapsed $elapsed | Out-Null
    } while ((Get-Date) -lt $end)
}

function Find-ExTaskMoveCode {
    <#
      Fallback for "the recorded code does not move anything": scan the candidate range for a
      transaction that really moves a task between displays. SAFETY deviation from the ticket's
      literal `i32 <taskId> i32 <displayId>` wording: the scan targets a DISPOSABLE task of our own
      package (the debug MainActivity task), so a destructive transaction in the range cannot eat
      the Dashboard task; the found code is then applied to the Dashboard task by the caller and
      judged like everything else -- from `dumpsys activity activities`, never from the reply.
      Reset between probes uses the known-good `cmd activity display move-stack`.
    #>
    param([Parameter(Mandatory, Position = 0)][int] $Radius = 25)

    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', $config.MainActivity) -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    $probe = Get-ExTaskPlacement -DumpsysActivities `
        ((Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure) -join "`n") `
        -Component $config.MainActivity
    if (-not $probe.Found) { return $null }
    $probeId = $probe.TaskId
    Write-ExNote ('code scan: probing against disposable task t{0} ({1})' -f $probeId, $config.MainActivity)

    $order = New-Object System.Collections.Generic.List[int]
    [void]$order.Add($TxnCode)
    foreach ($i in 1..$Radius) { [void]$order.Add($TxnCode + $i); [void]$order.Add($TxnCode - $i) }
    foreach ($candidate in $order) {
        if ($candidate -lt 1) { continue }
        Invoke-Adb -Arguments @('shell', 'cmd', 'activity', 'display', 'move-stack', [string]$probeId, '0') -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 700
        $raw = @(Invoke-Adb -Arguments @('shell', 'service', 'call', 'activity_task', [string]$candidate,
                'i32', [string]$probeId, 'i32', '1') -AllowFailure)
        Start-Sleep -Seconds 1
        $after = Get-ExTaskPlacement -DumpsysActivities `
            ((Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure) -join "`n") -TaskId $probeId
        $where = Format-ExPlacementField $after
        Write-ExNote ('code scan: N={0} -> {1} (reply {2})' -f $candidate, $where, (($raw -join ' ').Trim()))
        $script:MoveRecords.Add([pscustomobject]@{
                Label           = ('scan N={0}' -f $candidate)
                TargetDisplay   = 1
                Command         = ('service call activity_task {0} i32 {1} i32 1' -f $candidate, $probeId)
                DeviceTime      = (Get-ExDeviceClock)
                PlacementBefore = 'disposable'
                RearBefore      = ''
                Reply           = (($raw -join ' ').Trim())
                ReplyStatus     = ''
            })
        if ($after.Found -and $after.DisplayId -eq 1) {
            Invoke-Adb -Arguments @('shell', 'cmd', 'activity', 'display', 'move-stack', [string]$probeId, '0') -AllowFailure | Out-Null
            return $candidate
        }
    }
    return $null
}

# ---- 0. preflight (everything unlocked-state comes first: the run locks the phone) -----------
$script:MoveRecords = New-Object System.Collections.Generic.List[object]
$script:Samples = New-Object System.Collections.Generic.List[string]
$script:TaskId = $null

$displayDump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
$rear = Get-RearDisplay -DumpsysDisplay $displayDump
if (-not $rear) {
    Write-ExNote 'ERROR: no rear display in `dumpsys display` (no non-default INTERNAL display with'
    Write-ExNote '       PRESENTATION + OWN_DISPLAY_GROUP): E14 cannot be judged on this device.'
    Write-ExArtifact -Name 'e14-task-move.txt' -Lines @('# e14-task-move (ticket #18)', 'e14 : E14-NO-TASK (no rear display found at runtime)') | Out-Null
    return @{ E14 = 'E14-NO-TASK (no rear display found at runtime)' }
}
$rearId = $rear.DisplayId
Write-ExNote ('rear display: id={0} state={1}/{2}' -f $rearId, $rear.State, $rear.CommittedState)

if (Test-ExKeyguardLocked) {
    # Prep and control both need the UNLOCKED state and the phone cannot be unlocked over adb
    # (secure lock, ticket #7/#16) -- and this scenario must not press KEYCODE_POWER for nothing:
    # one deliberate lock is all the run gets.
    Write-ExNote 'WARNING: the phone is keyguard-locked. The E14 control group (unlocked-state'
    Write-ExNote '         transaction) cannot run and no Dashboard task can be prepared: HyperOS'
    Write-ExNote '         denies third-party rear-display launches while locked. Unlock the phone'
    Write-ExNote '         and rerun -- a secure lock cannot be dismissed over adb. The phone is'
    Write-ExNote '         left as it is (NOT locked by this run).'
    Write-ExArtifact -Name 'e14-task-move.txt' -Lines @(
        '# e14-task-move (ticket #18)',
        'e14 : E14-NO-TASK (keyguard locked at start: no Dashboard task can be prepared and no control group can run; unlock the phone and rerun)'
    ) | Out-Null
    return @{ E14 = 'E14-NO-TASK (keyguard locked at start)' }
}

Write-ExNote ('protocol: prep -> control (unlocked) -> position off the rear -> KEYCODE_POWER lock -> {0}' -f
    'locked transaction with per-attempt sampling')
Write-ExNote ('transaction: service call activity_task {0} i32 <rootTaskId> i32 <displayId>; watch {1}s per attempt, sample every {2}s, settle {3}s' -f
    $TxnCode, $ObserveSeconds, $SampleSeconds, $SettleSeconds)

Clear-ExLogcat

# ---- 1. prep: one Dashboard task, prepared the proven E1 way (unlocked) ----------------------
Start-ExApp -TimeoutSec 30 | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_TEST' | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' } | Out-Null
Start-Sleep -Seconds 2
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex14',
    'ticket18 task move probe') -AllowFailure | Out-Null
$dashboardUp = Wait-ExRearOwner -Owner 'dashboard' -DisplayId $rearId -TimeoutSec 25

$place = Get-ExTaskPlacement -DumpsysActivities `
    ((Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure) -join "`n") `
    -Component $dashboardComponent
if ($place.Found) { $script:TaskId = $place.RootTaskId }
Write-ExNote ('prep: dashboard-up={0} task={1} placement={2}' -f $dashboardUp, $script:TaskId, (Format-ExPlacementField $place))

if (-not $script:TaskId) {
    Write-ExNote 'WARNING: no Dashboard task in `dumpsys activity activities` after the E1 chain; the'
    Write-ExNote '         run stops BEFORE locking the phone (E14-NO-TASK).'
    Write-ExArtifact -Name 'e14-task-move.txt' -Lines @(
        '# e14-task-move (ticket #18)',
        ('prep : dashboard-up={0} task={1}' -f $dashboardUp, 'none'),
        'e14 : E14-NO-TASK (no Dashboard task in `dumpsys activity activities` before the lock; the phone is left unlocked)'
    ) | Out-Null
    return @{ E14 = 'E14-NO-TASK (no Dashboard task before the lock)' }
}

# ---- 2. control group: the SAME transaction must move the task while UNLOCKED -----------------
# The task sits on the rear display now, so "moving it onto the rear" is only observable as a
# round trip: off the rear (C0) and back onto the rear (C1). C1 is literally the command the
# locked phase runs -- if it works here and not there, the lock state is the only difference.
$control = New-Object System.Collections.Generic.List[object]
$c0 = Invoke-ExTaskMove -Label 'C0-unlocked-to-0' -TargetDisplay 0
Start-Sleep -Seconds 2
$afterC0 = Get-ExTaskSnapshot
$c0Ok = ($afterC0.Place.Found -and $afterC0.Place.DisplayId -eq 0)

if (-not $c0Ok) {
    Write-ExNote ('control C0 did not move the task (now {0}); scanning for the real transaction code' -f (Format-ExPlacementField $afterC0.Place))
    $found = Find-ExTaskMoveCode -Radius 25
    if ($found) {
        Write-ExNote ('code scan found N={0} (verified by `dumpsys` placement on the disposable task)' -f $found)
        $TxnCode = $found
        $c0 = Invoke-ExTaskMove -Label 'C0-unlocked-to-0' -TargetDisplay 0
        Start-Sleep -Seconds 2
        $afterC0 = Get-ExTaskSnapshot
        $c0Ok = ($afterC0.Place.Found -and $afterC0.Place.DisplayId -eq 0)
    }
}
$control.Add($c0)

$c1 = Invoke-ExTaskMove -Label 'C1-unlocked-to-rear' -TargetDisplay $rearId
Start-Sleep -Seconds 2
$afterC1 = Get-ExTaskSnapshot
$c1Ok = ($afterC1.Place.Found -and $afterC1.Place.DisplayId -eq $rearId)
$control.Add($c1)
Write-ExNote ('control: C0 moved off the rear={0}; C1 moved onto the rear={1} (now {2})' -f
    $c0Ok, $c1Ok, (Format-ExPlacementField $afterC1.Place))

if (-not ($c0Ok -and $c1Ok)) {
    # Wrong code / wrong usage: the locked-phase result could not be judged even if it moved
    # nothing. Defined failure word: E14-TXN-BROKEN. The phone is NOT locked in this case.
    $txnBroken = ('E14-TXN-BROKEN (the control group failed while UNLOCKED: move-to-0={0}, move-to-rear={1} at code {2};' -f $c0Ok, $c1Ok, $TxnCode)
    $txnBroken += ' the reply is never judged -- `dumpsys activity activities` showed no task move, so the code/usage is wrong and the lock question stays undecided)'
    Write-ExArtifact -Name 'e14-task-move.txt' -Lines (@(
            '# e14-task-move (ticket #18)',
            ('transaction : {0}' -f $c0.Command),
            ('control C0  : moved-off-rear={0} placement-after={1}' -f $c0Ok, (Format-ExPlacementField $afterC0.Place)),
            ('control C1  : moved-on-rear={0} placement-after={1}' -f $c1Ok, (Format-ExPlacementField $afterC1.Place)),
            ('e14         : {0}' -f $txnBroken),
            '',
            '# service call records (evidence only, never judged on)'
        ) + @($script:MoveRecords | ForEach-Object { ('  [{0}] {1} -> {2}' -f $_.DeviceTime, $_.Command, $_.Reply) })) | Out-Null
    Write-ExNote ('e14: {0}' -f $txnBroken)
    Write-ExNote 'the phone is left UNLOCKED (no lock was issued in this run)'
    return @{ E14 = $txnBroken }
}

# ---- 3. position the task OFF the rear display (this is where it must be at lock time) --------
$pos = Invoke-ExTaskMove -Label 'P-position-off-rear' -TargetDisplay 0
Start-Sleep -Seconds 2
$beforeLock = Get-ExTaskSnapshot
if (-not ($beforeLock.Place.Found -and $beforeLock.Place.DisplayId -eq 0)) {
    # The app can re-project on its own (Icon Set self-heal); one retry keeps the start state clean.
    Write-ExNote ('task left the parked position ({0}); moving it again' -f (Format-ExPlacementField $beforeLock.Place))
    Invoke-ExTaskMove -Label 'P-position-off-rear-retry' -TargetDisplay 0 | Out-Null
    Start-Sleep -Seconds 2
    $beforeLock = Get-ExTaskSnapshot
}
Write-ExNote ('pre-lock placement={0} owner={1} rear={2}' -f (Format-ExPlacementField $beforeLock.Place), $beforeLock.Owner, $beforeLock.RearPair)

# ---- 4. lock once, device-clock stamped ------------------------------------------------------
if ((Get-ExWakefulness) -ne 'Awake') {
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
}
Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-e14-lock-issued') -AllowFailure | Out-Null
$lockPress = Invoke-ExPowerKey -Purpose 'lock'
$lockT0 = $lockPress.DeviceTime
Start-Sleep -Seconds 2
if ((Get-ExWakefulness) -eq 'Awake') {
    Write-ExNote 'still awake after KEYCODE_POWER: sending it once more'
    Invoke-ExPowerKey -Purpose 'lock2' | Out-Null
    Start-Sleep -Seconds 2
}
Write-ExNote ('wakefulness while locked: {0}' -f (Get-ExWakefulness))

# ---- 5. locked steady state: the transaction, with per-attempt sampling -----------------------
$script:WatchStart = Get-Date
$attemptFacts = New-Object System.Collections.Generic.List[object]

function Invoke-ExLockedAttempt {
    <# One locked-state action + its watch segment; records what was observable around it. #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Label,
        [string] $Action,            # 'move' | 'wake' | ''
        [int] $TargetDisplay = -1
    )
    $startElapsed = [int]((Get-Date) - $script:WatchStart).TotalSeconds
    $before = Get-ExTaskSnapshot
    if ($Action -eq 'move') {
        Invoke-ExTaskMove -Label $Label -TargetDisplay $TargetDisplay | Out-Null
    } elseif ($Action -eq 'wake') {
        $clock = Get-ExDeviceClock
        Invoke-Adb -Arguments @('shell', 'input', '-d', [string]$rearId, 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
        Write-ExNote ('{0}: input -d {1} keyevent KEYCODE_WAKEUP @ {2}' -f $Label, $rearId, $clock)
    }
    $lineStart = $script:Samples.Count
    Invoke-ExWatchSegment -Label $Label -Seconds $ObserveSeconds
    $attemptFacts.Add([pscustomobject]@{
            Label           = $Label
            Action          = $Action
            TargetDisplay   = $TargetDisplay
            StartElapsed    = $startElapsed
            PlacementBefore = (Format-ExPlacementField $before.Place)
            RearBefore      = $before.RearPair
            SampleLines     = @($script:Samples.GetRange($lineStart, $script:Samples.Count - $lineStart))
        })
}

# A0: what the lock itself did to the task, before any transaction touches it.
Invoke-ExLockedAttempt -Label 'A0-settle' -Action '' -TargetDisplay -1
# A1: THE question -- the transaction onto the rear display in the locked steady state.
Invoke-ExLockedAttempt -Label 'A1-locked-to-rear' -Action 'move' -TargetDisplay $rearId
# A2: same transaction, same locked state, non-rear target -- a within-locked-state direction check.
Invoke-ExLockedAttempt -Label 'A2-locked-to-0' -Action 'move' -TargetDisplay 0
# A3: repeat of the question (second shot inside the same lock).
Invoke-ExLockedAttempt -Label 'A3-locked-to-rear' -Action 'move' -TargetDisplay $rearId
if (-not $NoRearWake) {
    # Phase B: the same question with the rear display AWAKE, so "the target display was off"
    # cannot be mistaken for "the lock gate refused the move".
    Invoke-ExLockedAttempt -Label 'B0-wake-rear' -Action 'wake' -TargetDisplay -1
    Invoke-ExLockedAttempt -Label 'B1-locked-to-0' -Action 'move' -TargetDisplay 0
    Invoke-ExLockedAttempt -Label 'B2-locked-to-rear' -Action 'move' -TargetDisplay $rearId
}

# ---- 6. evidence -----------------------------------------------------------------------------
$appLog = @(Get-ExLogcat)
$systemAll = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure)
$moveEvents = Get-ExTaskMoveEvents -Logcat $systemAll
$pollutionHits = Get-ExWakePollution -Logcat $systemAll
$facts = Get-ExTaskMoveSampleFacts -SampleLines $script:Samples.ToArray() -RearDisplayId $rearId

# External pollution voids the round: fingerprint wakes / power buttons the script did not press.
$externalPollution = New-Object System.Collections.Generic.List[object]
foreach ($hit in $pollutionHits) {
    $ours = $false
    $hitTime = Get-ExLogcatTime -Line $hit.Raw
    if (($hit.Class -eq 'power-button') -and $hitTime) {
        foreach ($press in $script:PowerPresses) {
            $pressTime = [datetime]::ParseExact($press.DeviceTime, 'MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
            if ([math]::Abs(($hitTime - $pressTime).TotalSeconds) -le 3) { $ours = $true; break }
        }
    }
    if (-not $ours) { $externalPollution.Add($hit) }
}

# ---- 7. verdicts (device facts only) ---------------------------------------------------------
$lockedAttempts = @($attemptFacts | Where-Object { $_.Action -eq 'move' -and $_.TargetDisplay -eq $rearId })
$landings = New-Object System.Collections.Generic.List[object]
foreach ($attempt in $lockedAttempts) {
    $attemptFactsParsed = Get-ExTaskMoveSampleFacts -SampleLines $attempt.SampleLines -RearDisplayId $rearId
    $wasOnRearBefore = ($attempt.PlacementBefore -eq ('t{0}@d{1}' -f $script:TaskId, $rearId))
    if ($attemptFactsParsed.TaskEverOnRear -and -not $wasOnRearBefore) {
        $landings.Add([pscustomobject]@{
                Label        = $attempt.Label
                FirstOnRearSec = $attemptFactsParsed.TaskFirstOnRearSec
                RearPair     = $attemptFactsParsed.RearEndStatePair
                AlreadyThere = $false
            })
    } elseif ($attemptFactsParsed.TaskEverOnRear) {
        $landings.Add([pscustomobject]@{ Label = $attempt.Label; FirstOnRearSec = $attemptFactsParsed.TaskFirstOnRearSec; RearPair = $attemptFactsParsed.RearEndStatePair; AlreadyThere = $true })
    }
}
$attributable = @($landings | Where-Object { -not $_.AlreadyThere })
$a0 = $attemptFacts | Where-Object { $_.Label -eq 'A0-settle' } | Select-Object -First 1
$taskGoneAtLock = ($a0.PlacementBefore -notmatch '^t')

if ($externalPollution.Count -gt 0) {
    $e14 = ('E14-RUN-INVALID (external pollution during the watch: {0} fingerprint/power-button hit(s) the script did not make -- the samples of this round are void and archived in erratum.md)' -f $externalPollution.Count)
} elseif ($attributable.Count -gt 0) {
    $first = $attributable[0]
    $e14 = ('E14-PASS (locked steady state: the task-move transaction landed the Dashboard root task t{0} on display {1} -- first seen at +{2}s of the {3} watch, rear state {4}; before that attempt the task was {5})' -f
        $script:TaskId, $rearId, $first.FirstOnRearSec, $first.Label, $first.RearPair,
        (($lockedAttempts | Where-Object { $_.Label -eq $first.Label } | Select-Object -First 1).PlacementBefore))
} elseif ($taskGoneAtLock) {
    $e14 = ('E14-TASK-GONE (the Dashboard task t{0} was gone from `dumpsys activity activities` at the first locked sample, before any transaction ran; reap lines below say whether the system finished it)' -f $script:TaskId)
} elseif ($moveEvents.DenyLines.Count -gt 0) {
    $e14 = ('E14-REJECTED (the system refused the move while locked; refusal lines verbatim: {0})' -f (($moveEvents.DenyLines | ForEach-Object { $_.Trim() }) -join ' || '))
} else {
    $e14 = ('E14-NO-EFFECT (locked-state transactions at code {0} never put t{1} on display {2} and the system logged no refusal line: samples below show where the task stayed)' -f $TxnCode, $script:TaskId, $rearId)
}

$phaseB = if ($NoRearWake) {
    'not run (-NoRearWake)'
} else {
    $bLanding = @($landings | Where-Object { $_.Label -like 'B2*' })
    if ($bLanding.Count -gt 0 -and -not $bLanding[0].AlreadyThere) {
        ('rear-awake variant landed too (B2 at +{0}s)' -f $bLanding[0].FirstOnRearSec)
    } elseif ($bLanding.Count -gt 0) {
        'rear-awake variant: task already on the rear before B2 (not attributable)'
    } else {
        'rear-awake variant did not land the task on the rear'
    }
}

# ---- 8. artifacts ----------------------------------------------------------------------------
$sampleNotes = New-Object System.Collections.Generic.List[string]
$sampleNotes.Add('# e14 samples -- locked watch (task placement + rear owner + rear state per point)')
$sampleNotes.Add(('# rear display id={0} (identified at runtime from display flags); task=t<id>@d<display> or absent' -f $rearId))
$sampleNotes.Add(('# attempts inside the watch: A0 settle | A1/A3 to-rear (the question) | A2 to-0 (direction check) | B0 rear wake | B1/B2 rear-awake variant'))
foreach ($line in $script:Samples) { $sampleNotes.Add($line) }
Write-ExArtifact -Name 'e14-samples.txt' -Lines $sampleNotes.ToArray() | Out-Null

$callLines = New-Object System.Collections.Generic.List[string]
$callLines.Add('# every `service call` of this run -- evidence only, NEVER judged on (the verdict reads dumpsys/logcat)')
foreach ($record in $script:MoveRecords) {
    $callLines.Add(('[{0}] {1} (task before: {2}, rear: {3})' -f $record.DeviceTime, $record.Command, $record.PlacementBefore, $record.RearBefore))
    $callLines.Add(('    reply: {0}' -f $record.Reply))
}
Write-ExArtifact -Name 'e14-service-calls.txt' -Lines $callLines.ToArray() | Out-Null

$systemLines = @($systemAll |
    Where-Object { $_ -match 'ActivityStarterImpl|rear display|wm_finish_activity|PowerGroup|SecurityException|Permission Denial|moveRootTask|move-stack|GreezeManager|Not allow' } |
    Select-Object -Last 400)

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# e14-task-move (ticket #18)')
$out.Add(('rear display                : id={0} state={1}' -f $rearId, ($rear.State + '/' + $rear.CommittedState)))
$out.Add(('dashboard root task         : t{0} (prepared through the E1 chain while unlocked)' -f $script:TaskId))
$out.Add(('transaction                 : service call activity_task {0} i32 <rootTaskId> i32 <displayId>' -f $TxnCode))
$out.Add(('watch                       : {0}s per attempt, sample every {1}s, keyguard settle {2}s' -f $ObserveSeconds, $SampleSeconds, $SettleSeconds))
$out.Add(('lock T0 (device clock)      : {0} (KEYCODE_POWER press stamp)' -f $lockT0))
$out.Add('')
$out.Add('# verdicts')
$out.Add(('control-off-rear      : {0} (C0 moved the task to display 0 while unlocked)' -f $c0Ok))
$out.Add(('control-on-rear       : {0} (C1 = the very command of the locked phase, unlocked)' -f $c1Ok))
$out.Add(('pre-lock-placement    : {0}' -f (Format-ExPlacementField $beforeLock.Place)))
$out.Add(('locked-deny-lines     : {0}' -f $moveEvents.DenyLines.Count))
$out.Add(('task-on-rear-samples  : first=+{0}s last=+{1}s' -f $facts.TaskFirstOnRearSec, $facts.TaskLastOnRearSec))
$out.Add(('task-end-placement    : {0}' -f $facts.TaskEndPlacement))
$out.Add(('rear-end-state        : {0}' -f $facts.RearEndStatePair))
$out.Add(('phase-b               : {0}' -f $phaseB))
$out.Add(('pollution             : {0}' -f $(if ($externalPollution.Count -eq 0) { 'none detected' } else { '{0} external hit(s) -- see erratum.md' -f $externalPollution.Count })))
$out.Add(('e14                   : {0}' -f $e14))
$out.Add('')
$out.Add('# attempts (device clock + placement facts around each transaction)')
foreach ($attempt in $attemptFacts) {
    $parsed = Get-ExTaskMoveSampleFacts -SampleLines $attempt.SampleLines -RearDisplayId $rearId
    $firstSeen = if ($null -eq $parsed.TaskFirstOnRearSec) { 'never-in-segment' } else { ('first+{0}s' -f $parsed.TaskFirstOnRearSec) }
    $out.Add(('  {0,-22} action={1,-5} target={2,-3} before={3,-12} rear-before={4,-18} on-rear={5}' -f
            $attempt.Label, $attempt.Action, $attempt.TargetDisplay, $attempt.PlacementBefore, $attempt.RearBefore, $firstSeen))
}
$out.Add('')
$out.Add('# samples (locked watch)')
foreach ($line in $script:Samples) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# service call records (evidence only)')
foreach ($record in $script:MoveRecords) {
    $out.Add(('  [{0}] {1} (before {2}) -> {3}' -f $record.DeviceTime, $record.Command, $record.PlacementBefore, $record.Reply))
}
$out.Add('')
$out.Add('# system-side lines (logcat -b all, filtered)')
foreach ($line in $systemLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# power-key presses of this run (device clock, for power-button attribution)')
foreach ($press in $script:PowerPresses) { $out.Add(('  {0}  {1}' -f $press.DeviceTime, $press.Purpose)) }
$out.Add('')
$out.Add('# app log (RearCue tag)')
foreach ($line in $appLog) { $out.Add('  ' + $line) }
Write-ExArtifact -Name 'e14-task-move.txt' -Lines $out.ToArray() | Out-Null

foreach ($line in ($out | Where-Object { $_ -match '^(control-|pre-lock|locked-deny|task-on-rear|task-end|rear-end|phase-b|pollution|e14) ' })) {
    Write-ExNote $line
}

# ---- 9. erratum (external pollution never belongs in the verdict) ----------------------------
if ($externalPollution.Count -gt 0) {
    $erratum = New-Object System.Collections.Generic.List[string]
    $erratum.Add('# erratum -- external pollution (ticket #18)')
    $erratum.Add('')
    $erratum.Add('The watch window(s) below were disturbed from outside the script (fingerprint wake /')
    $erratum.Add('power button pressed by hand / the phone was touched). Those samples are NOT')
    $erratum.Add('lock-screen facts; raw output is left untouched and this note marks it. The round is')
    $erratum.Add('void (E14-RUN-INVALID) and a clean rerun is needed -- which needs the owner to unlock')
    $erratum.Add('the phone first (this scenario locks it and cannot unlock it).')
    $erratum.Add('')
    foreach ($hit in $externalPollution) { $erratum.Add(('- [{0}] {1}' -f $hit.Class, $hit.Raw.Trim())) }
    Write-ExArtifact -Name 'erratum.md' -Lines $erratum.ToArray() | Out-Null
    Write-ExNote ('pollution: {0} external hit(s) recorded in erratum.md' -f $externalPollution.Count)
}

# ---- 10. scenario notes (summary.md embeds these) --------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (E14 task-move probe, ticket #18)')
$notes.Add('')
$notes.Add('- **Why the order is prep -> control -> position -> lock -> locked transaction**: this')
$notes.Add('  device has a SECURE keyguard (PIN/fingerprint) that adb cannot dismiss (`wm')
$notes.Add('  dismiss-keyguard` no-ops; tickets #7/#16), and KEYCODE_POWER leaves the phone locked')
$notes.Add('  until the owner unlocks it. The run is therefore ONE SHOT: every segment that needs the')
$notes.Add('  unlocked state runs before the lock, and a failed prep/control stops the run BEFORE')
$notes.Add('  KEYCODE_POWER is ever pressed (the phone is left usable).')
$notes.Add('- **How the transaction number is verified ("how do you know the code is right")**: the')
$notes.Add('  control group runs the EXACT command of the locked phase (`service call activity_task')
$notes.Add(('  {0} i32 <rootTaskId> i32 {1}`) while unlocked and the fact checked is the task stack:`dumpsys activity activities` must show the Dashboard root task on display {1} again. The round trip' -f $TxnCode, $rearId))
$notes.Add('  (off the rear and back) also proves the argument order `<taskId> <displayId>`. The')
$notes.Add('  `service call` reply is archived but never judged on. Fixture-collection round')
$notes.Add('  `docs/poc-logs/20260922-174125-task-move-collect/` records how code 51 was found (MRSS`s')
$notes.Add('  code 50 is a silent no-op on this Android 16 build; raw-14-code-scan.txt).')
$notes.Add(('- **Observation window and sampling interval are parameters** (`-ObserveSeconds` {0}s per' -f $ObserveSeconds))
$notes.Add(('  attempt, `-SampleSeconds` {0}s, `-SettleSeconds` {1}s); `-TxnCode` overrides the transaction' -f $SampleSeconds, $SettleSeconds))
$notes.Add('  number and `-NoRearWake` drops the rear-awake variant.')
$notes.Add('- **Phase B (rear-awake variant)**: after the plain attempts, the rear display is woken')
$notes.Add('  with one display-directed `input -d <rear> keyevent KEYCODE_WAKEUP` (E12 mechanism) and')
$notes.Add('  the same transaction is repeated. It separates "the target display was powered off" from')
$notes.Add('  "the lock gate refused the move"; both variants are reported separately.')
$notes.Add('- **Code-scan safety deviation (documented)**: when the given `-TxnCode` does not move')
$notes.Add('  anything, the scan probes `service call activity_task N i32 <taskId> i32 <displayId>`')
$notes.Add('  candidates against a DISPOSABLE task of our own package (debug MainActivity task), not the')
$notes.Add('  Dashboard task, so a destructive transaction in the range cannot eat it. The candidate')
$notes.Add('  code is judged the same way as everything else: `dumpsys` placement, not the reply.')
$notes.Add('- **A task move destroy+recreates the moved activity** (display configuration change: the')
$notes.Add('  app logs `Dashboard detach` then `RearDashboardActivity onCreate display=N`). The task id')
$notes.Add('  stays stable across the move, so placement is read by task id, never by instance count.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

Write-ExNote 'the phone is left LOCKED (expected: a secure lock needs the owner to unlock it)'
return @{ E14 = $e14; PhaseB = $phaseB; ControlOk = ($c0Ok -and $c1Ok) }
