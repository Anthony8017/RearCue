# 15-lock-firstcast.ps1 -- ticket #22: lock-screen FIRST CAST through the task-move transaction.
#
# The question: phone locked and idle (no Active Notification, nothing on the rear) -> one
# allowlist notification arrives -> does the Dashboard land on the rear display? The route under
# test is the E14-verified task-move transaction (`service call activity_task 51`, move root
# task to the rear display); the `am start --display` path is REFUSED while locked
# (ActivityStarterImpl: rearDisplay check locked -> deny) and is deliberately not used.
#
# The second half of the AC: when the notifications clear, the app must exit and hand the rear
# back to the native interface (`firstcast-exit` line).
#
# Verdict words:
#   FIRSTCAST-PASS        the Dashboard reached the rear display while the phone stayed locked
#                         (the route -- app window vs task-move -- is reported separately)
#   FIRSTCAST-NO-TASK     no task with a Dashboard existed and the create did not make one
#   FIRSTCAST-REJECTED    no dashboard AND system-side refusal lines (E14 semantics: the words
#                         NO-EFFECT/REJECTED split on the PRESENCE of refusal lines)
#   FIRSTCAST-TXN-BROKEN  the transaction channel is broken (service reply text, never exit codes)
#   FIRSTCAST-NO-EFFECT   the transaction ran but the task did not land, and nothing refused
#   FIRSTCAST-NO-EVIDENCE not measured: no dashboard, no attempt line, no refusal line
#   FIRSTCAST-NO-LOCK     the lock never reached the rear display group (PASS unreachable)
#   FIRSTCAST-RUN-INVALID the lock dropped mid-run or external wake interference voided it
#
# Run through the one-command entry point (`ex.ps1 -Task lock-firstcast` = authorize -> this ->
# collect), or alone:
#   .\tools\ex\15-lock-firstcast.ps1
#   .\tools\ex\15-lock-firstcast.ps1 -ObserveSeconds 45
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $ObserveSeconds = 30,
    [int] $SampleSeconds = 2,
    [switch] $NoRestore,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'lock-firstcast' -Serial $Serial | Out-Null }

$dashboardComponent = 'com.rearcue.poc/com.rearcue.poc.rear.RearDashboardActivity'
$script:PowerPresses = New-Object System.Collections.Generic.List[object]

function Invoke-ExPowerKey {
    <# KEYCODE_POWER toggle with a device-clock stamp (pollution attribution anchor). #>
    param([Parameter(Mandatory, Position = 0)][string] $Purpose)
    $stamp = ((Invoke-Adb -Arguments @('shell', "date '+%m-%d %H:%M:%S'") -AllowFailure) -join '').Trim()
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
    $script:PowerPresses.Add([pscustomobject]@{ Purpose = $Purpose; DeviceTime = $stamp })
    return $stamp
}

function Get-ExFirstcastSnapshot {
    $dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
    $rear = Get-RearDisplay -DumpsysDisplay $dump
    $owner = 'none'
    if ($rear) { $owner = Get-ExRearOwnerNow -DisplayId $rear.DisplayId }
    $activityDump = ((Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure)) -join "`n"
    $place = $null
    if ($rear) { $place = Get-ExTaskPlacement -DumpsysActivities $activityDump -Component $dashboardComponent }
    return [pscustomobject]@{
        RearPair = (Format-ExStatePair $rear)
        RearId   = if ($rear) { $rear.DisplayId } else { $null }
        Owner    = $owner
        Task     = (Format-ExPlacementField $place)
    }
}

# ---- 0. preflight: clean slate ---------------------------------------------------
# The app process must be ALIVE (the notification listener drives the chain); the real-world
# condition is "process up, no foreground activity", so start it and press HOME right away.
Start-ExApp -TimeoutSec 30 | Out-Null
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1
# The Shizuku UserService binds ASYNCHRONOUSLY after the process starts: trigger only once the
# app reports `... granted=true userService=true` (otherwise the fallback chain dies at
# "Shizuku unavailable" and the round measures nothing -- measured 20260923-011205).
$fallbackReady = $false
for ($i = 0; $i -lt 20; $i++) {
    $logNow = @(Get-ExLogcat)
    if (@($logNow | Where-Object { $_ -match 'granted=true userService=true' }).Count -gt 0) { $fallbackReady = $true; break }
    Start-Sleep -Seconds 1
}
Write-ExNote ('fallback-ready: {0} (app reports granted=true userService=true)' -f $fallbackReady)
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Start-Sleep -Seconds 2
$snap0 = Get-ExFirstcastSnapshot
Write-ExNote ('start: rear={0} owner={1} task={2} keyguard={3}' -f
    $snap0.RearPair, $snap0.Owner, $snap0.Task, (Test-ExKeyguardLocked))
if ($snap0.Owner -eq 'dashboard') {
    Write-ExNote 'WARNING: a Dashboard is still on the rear display -- this is NOT a first cast.'
    Write-ExNote '         Cancel notifications and let it exit first (the run continues anyway).'
}
Write-ExNote ('protocol: clean slate -> lock -> ONE allowlist notification (E1 drive method) -> {0}s watch -> clear notifications -> exit check' -f $ObserveSeconds)

# ---- 1. lock (two anchors: marker + device-clock stamp) --------------------------
Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-firstcast-lock-issued') -AllowFailure | Out-Null
$lockStamp = Invoke-ExPowerKey -Purpose 'lock'
Start-Sleep -Seconds 2
if ((Get-ExWakefulness) -eq 'Awake') {
    Write-ExNote 'still awake after KEYCODE_POWER: sending it once more'
    $lockStamp = Invoke-ExPowerKey -Purpose 'lock2'
    Start-Sleep -Seconds 2
}
$lockedNow = Test-ExKeyguardLocked
Write-ExNote ('wakefulness while locked: {0}; keyguard: {1}' -f (Get-ExWakefulness), $lockedNow)

# ---- 2. the trigger: one allowlist notification while locked ---------------------
# E1 drive method verbatim (04-drive.ps1): shell notification + the app's own debug POST_TEST.
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex',
    'ticket22 lock firstcast') -AllowFailure | Out-Null
Invoke-ExDebugAction -Action 'POST_TEST'

# ---- 3. watch --------------------------------------------------------------------
$start = Get-Date
$deadline = $start.AddSeconds($ObserveSeconds)
$samples = New-Object System.Collections.Generic.List[string]
do {
    Start-Sleep -Seconds $SampleSeconds
    $elapsed = [int]((Get-Date) - $start).TotalSeconds
    $snap = Get-ExFirstcastSnapshot
    $line = Format-ExWakeSampleLine -Elapsed $elapsed -RearPair $snap.RearPair -MainPair 'not-sampled' -Owner $snap.Owner
    $line = $line + (' task={0}' -f $snap.Task)
    $samples.Add($line)
    Write-ExNote $line
} while ((Get-Date) -lt $deadline)

# ---- 4. clear notifications -> the exit half of the AC ---------------------------
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Start-Sleep -Seconds 6
$snapExit = Get-ExFirstcastSnapshot
$nativeReturned = ($snapExit.Owner -ne 'dashboard')

# ---- 5. evidence + verdicts -------------------------------------------------------
$appLog = @(Get-ExLogcat)
$systemAll = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure)
$moveApp = Get-ExTaskMoveAppFacts -Logcat $appLog
$powerEvents = Get-ExPowerGroupEvents -Logcat $systemAll
$pollutionHits = Get-ExWakePollution -Logcat $systemAll
$externalPollution = Select-ExExternalPollution -Hits $pollutionHits -PowerPresses $script:PowerPresses.ToArray() -WakeEvents $powerEvents

# The lock really reaching the rear display group (E13's NO-LOCK gate semantics).
$lockOff = $powerEvents | Where-Object {
    ($_.Kind -eq 'power-off') -and ($_.GroupId -eq 1) -and ((Get-ExSecondStamp $_.Time) -ge $lockStamp)
} | Select-Object -First 1

$ownerUp = @($samples | Where-Object { $_ -match 'owner=dashboard' })
$firstUp = $ownerUp | Select-Object -First 1
$refusalLines = @($systemAll | Where-Object {
        ($_ -match 'rearDisplay check locked') -or ($_ -match 'Background activity launch blocked') -or
        ($_ -match 'BAL_BLOCK') -or ($_ -match 'ActivityStart.*deny')
    })
# The chain dying at "Shizuku unavailable" is NOT a system rejection -- count it apart (ASCII
# pattern bridging the Chinese app text: the line reads `project ... Shizuku ...`).
$shizukuDown = @($appLog | Where-Object { $_ -match 'project .*Shizuku' }).Count

$dashboardUp = ($ownerUp.Count -gt 0)
$route = if ($moveApp.LastWord -eq 'OK') {
    'task-move transaction (E14 route)'
} elseif ($dashboardUp) {
    'app window won the lock window (task-move not needed)'
} else {
    'none'
}

$firstcast = if ((-not $lockedNow) -or ($null -eq $lockOff)) {
    'FIRSTCAST-NO-LOCK (the lock never reached the rear display group -- PASS is unreachable)'
} elseif ($externalPollution.Count -gt 0) {
    ('FIRSTCAST-RUN-INVALID ({0} external wake interference hit(s) -- see erratum.md)' -f $externalPollution.Count)
} elseif ($dashboardUp) {
    ('FIRSTCAST-PASS (the Dashboard reached the rear display while locked; first owner=dashboard at {0}; route: {1})' -f
        $(if ($firstUp) { ($firstUp -split '\]')[0].TrimStart('[').Trim() } else { '?' }), $route)
} elseif ($moveApp.LastWord -eq 'NO-TASK') {
    'FIRSTCAST-NO-TASK (no task with a Dashboard existed and the default-display create did not make one)'
} elseif ($refusalLines.Count -gt 0) {
    # E14 word semantics: REJECTED = the system refused it (refusal lines are the evidence);
    # NO-EFFECT is only the word when NOTHING refused and the task still did not land.
    ('FIRSTCAST-REJECTED (no dashboard and {0} system refusal line(s); app made {1} attempt(s), last word {2})' -f $refusalLines.Count, $moveApp.Attempts, $moveApp.LastWord)
} elseif ($moveApp.LastWord -eq 'TXN-BROKEN') {
    ('FIRSTCAST-TXN-BROKEN (the transaction channel is broken per the service reply; txn-raw lines={0})' -f $moveApp.TxnRaws)
} elseif ($moveApp.LastWord -eq 'NO-EFFECT') {
    ('FIRSTCAST-NO-EFFECT (the transaction ran taskId={0} but the task did not land on the rear, and no refusal line appeared)' -f $moveApp.TaskId)
} elseif ($shizukuDown -gt 0) {
    ('FIRSTCAST-NO-EVIDENCE (the fallback chain died at Shizuku unavailable: {0} line(s) -- the UserService was not bound yet or Shizuku is down; NOT a system rejection)' -f $shizukuDown)
} else {
    'FIRSTCAST-NO-EVIDENCE (not measured: no dashboard, no app attempt line and no refusal line -- the fallback chain never ran or the run is incomplete)'
}

# ---- 6. output + artifacts --------------------------------------------------------
$out = New-Object System.Collections.Generic.List[string]
$out.Add('# firstcast (ticket #22: lock-screen first cast through the task-move transaction)')
$out.Add(('lock-stamp            : {0} (device clock; PowerGroup group-1 power-off after it: {1})' -f $lockStamp, ($null -ne $lockOff)))
$out.Add(('keyguard-at-trigger   : {0}' -f $lockedNow))
$out.Add(('task-move app facts   : attempts={0} last-word={1} task-id={2} on-display={3} creates={4} txn-raws={5}' -f
        $moveApp.Attempts, $moveApp.LastWord, $moveApp.TaskId, $moveApp.OnDisplay, $moveApp.Creates, $moveApp.TxnRaws))
$out.Add(('system refusals       : {0} (rearDisplay check locked / BAL / deny lines in the buffer)' -f $refusalLines.Count))
$out.Add(('shizuku-down          : {0} (chain died at "Shizuku unavailable" line(s); not a system rejection)' -f $shizukuDown))
$out.Add(('fallback-ready        : {0}' -f $fallbackReady))
$out.Add(('owner=dashboard       : {0}/{1} samples; first at {2}' -f $ownerUp.Count, $samples.Count, $(if ($firstUp) { ($firstUp -split '\]')[0].TrimStart('[').Trim() } else { 'never' })))
$out.Add(('route                 : {0}' -f $route))
$out.Add(('firstcast-exit        : native-returned {0} (owner after clearing notifications: {1})' -f $nativeReturned, $snapExit.Owner))
$out.Add(('pollution             : {0}' -f $(if ($externalPollution.Count -gt 0) { ('{0} external hit(s) -- see erratum.md' -f $externalPollution.Count) } else { 'none detected' })))
$out.Add(('firstcast             : {0}' -f $firstcast))
Write-ExArtifact -Name 'firstcast.txt' -Lines $out.ToArray() | Out-Null
Write-ExArtifact -Name 'firstcast-samples.txt' -Lines (@('# wire: ' + 'mm-dd HH:mm:ss elapsed=N rear=<pair> main=not-sampled owner=<owner> task=t<id>@d<display>|absent') + $samples.ToArray()) | Out-Null
if ($externalPollution.Count -gt 0) {
    Write-ExArtifact -Name 'erratum.md' -Lines (@('# erratum -- external pollution (ticket #22 firstcast)') +
        $externalPollution) | Out-Null
}
foreach ($line in $out) { Write-ExNote $line }

# ---- 7. restore -------------------------------------------------------------------
if (-not $NoRestore) {
    Write-ExNote 'restoring: emptying the Icon Set, waking the main screen'
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    Invoke-ExKeyguardDismiss | Out-Null
}

# ---- 8. scenario notes -------------------------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (lock-screen first cast, ticket #22)')
$notes.Add('')
$notes.Add('- **Clean slate first**: both test notifications are cancelled and any Dashboard is')
$notes.Add('  confirmed gone BEFORE the lock -- this is a FIRST cast, not a re-projection.')
$notes.Add('- **The route is the E14 transaction**: `service call activity_task 51 i32 <taskId>')
$notes.Add('  i32 1` (moveRootTaskToDisplay; code 51 is the one E14 verified -- MRSS`s 50 is a')
$notes.Add('  silent no-op on this build). The `am start --display` path is refused while locked')
$notes.Add('  (`rearDisplay check locked -> deny`) and is deliberately skipped by the app.')
$notes.Add('- **The trigger is the real-world one**: one allowlist notification while locked (the')
$notes.Add('  E1 drive method: shell notification + debug POST_TEST -- both are allowlist apps).')
$notes.Add('- **Judged from device facts only**: owner=dashboard per 2s sample + the app`s ASCII')
$notes.Add('  `task-move word=...` markers (Get-ExTaskMoveAppFacts) + system refusal lines; the')
$notes.Add('  `service call` return is archived (`task-move txn-raw`) but never judged (E14 rule).')
$notes.Add('- **The exit half**: clearing the notifications must hand the rear back to the native')
$notes.Add('  interface (`firstcast-exit : native-returned`); the app also moves a task-move-moved')
$notes.Add('  root task back to display 0 (`task-move hand-back` marker).')
$notes.Add('- **Icon rendering**: the device facts prove the Dashboard (with the Icon Set content')
$notes.Add('  logged by `ACTION_STATE`) is on the rear; the pixels themselves are the app`s Compose')
$notes.Add('  UI -- for human-grade proof take a photo at the peak per the photo-checkpoints practice.')
$notes.Add('- **Deviation**: the main display state is not sampled (the question is the rear chain);')
$notes.Add('  the wire keeps the slot as `main=not-sampled`. The app process is alive but has NO')
$notes.Add('  foreground activity -- the real "phone idle" condition (BAL applies).')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Firstcast     = $firstcast
    Route         = $route
    NativeReturned = $nativeReturned
}
