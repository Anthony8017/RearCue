# Issue #37: one-command, bounded real-device red/green harness for new notifications.
# No install, listener-grant toggle, logcat clear or real-app notification mutation.
# ASCII-only source for Windows PowerShell 5.1.
[CmdletBinding()]
param(
    [string] $Serial = '94250f9e',
    [string] $Adb = 'C:\Users\13691\AppData\Local\RearCue-tools\platform-tools\adb.exe',
    [ValidateRange(1, 30)][int] $BudgetSeconds = 5,
    [switch] $OffOnly
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ExCommon.psm1')
Import-Module (Join-Path $PSScriptRoot 'Issue37Harness.psm1')
$config = Get-ExConfig
$oldAdb = $env:REARCUE_ADB
$env:REARCUE_ADB = $Adb
$runId = [guid]::NewGuid().ToString('N').Substring(0, 10)
$mutex = New-Object System.Threading.Mutex($false, 'Global\RearCueDevice')
$held = $false
$appPosted = $false
$shellPosted = $false
$activeShellTag = $null
$shellCancelTried = $false
$shellCancelAttempts = 0
$shellCancelFeedback = $null
$shellCancelScope = $null
$ownerIntervened = $false
$ownerMoveVerified = $false
$permissionChanged = $false
$permissionKnown = $false
$grantedBefore = $false
$deviceMutated = $false
$initial = $null
$result = 'BLOCKED'
$reason = ''
$legResults = New-Object System.Collections.Generic.List[string]
$timeline = New-Object System.Collections.Generic.List[string]
$allowlist = @('com.tencent.mm', 'com.tencent.mobileqq', 'com.ss.android.lark', 'com.rearcue.poc', 'com.android.shell')

function Get-I37DeviceState {
    $display = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
    $blocks = Get-DisplayInfoBlocks -DumpsysDisplay $display
    $main = @($blocks | Where-Object { $_.DisplayId -eq 0 } | Select-Object -First 1)
    $rear = Get-RearDisplay -DumpsysDisplay $display
    if (-not $main.Count -or -not $rear) { throw 'display inventory incomplete' }
    $mainPower = Get-I37ScreenPowerState -DumpsysDisplay $display -DisplayId 0
    $mainCondition = Get-I37MainCondition -DisplayState $main[0].State -ScreenPower $mainPower
    $activities = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure) -join "`n"
    $owner = Get-RearScreenOwner -DumpsysActivities $activities -DisplayId $rear.DisplayId
    $topActivity = Get-DisplayTopActivity -DumpsysActivities $activities -DisplayId $rear.DisplayId
    $locked = Test-ExKeyguardLocked
    $keys = Get-I37NotificationKeys -Lines @(Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'list'))
    $stamp = ((Invoke-Adb -Arguments @('shell', 'date', '+%m-%d_%H:%M:%S.%3N')) -join '').Trim().Replace('_', ' ')
    if ($stamp -notmatch '^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$') { throw 'device millisecond clock unavailable' }
    return [pscustomobject]@{ Time = $stamp; Main = $mainCondition; MainRaw = $main[0].State; MainPower = $mainPower; Rear = $rear.State; RearId = $rear.DisplayId; Owner = $owner; TopActivity = $topActivity; ActivityDump = $activities; Locked = $locked; Keys = $keys }
}

function Write-I37State {
    param([string] $Label, $State)
    $script:timeline.Add(('{0} phase={1} main={2} mainDisplay={3} screenPower={4} rear={5} owner={6} topActivity={7} keyguard={8} allowlist-count={9}' -f
        $State.Time, $Label, $State.Main, $State.MainRaw, $State.MainPower, $State.Rear, $State.Owner, $(if ($State.TopActivity) { $State.TopActivity } else { '-' }), $State.Locked,
        @($State.Keys | Where-Object { $_.Package -in $script:allowlist }).Count))
}

function Set-I37Main {
    param([ValidateSet('ON', 'OFF')][string] $State)
    $now = Get-I37DeviceState
    if ($now.MainPower -eq 'unknown' -or -not $now.MainPower) { throw 'main screen power unreadable' }
    if ($now.MainPower -ne $State -and -not ($State -eq 'OFF' -and $now.MainPower -in @('DOZE', 'DOZE_SUSPEND'))) {
        $key = if ($State -eq 'ON') { 'KEYCODE_WAKEUP' } else { 'KEYCODE_SLEEP' }
        $script:deviceMutated = $true
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', $key) | Out-Null
    }
    $started = Get-Date
    $deadline = (Get-Date).AddSeconds(12)
    $powerTried = $false
    do {
        $after = Get-I37DeviceState
        Write-I37State -Label ('main-' + $State.ToLowerInvariant()) -State $after
        if ($after.Main -eq $State -and $after.Locked) { return }
        if ($State -eq 'ON' -and -not $powerTried -and (Get-Date) -ge $started.AddSeconds(3) -and $after.MainPower -eq 'OFF') {
            # On this locked idle device WAKEUP can briefly change DisplayInfo while
            # the physical main power stays OFF. A single targeted POWER then wakes it.
            $script:deviceMutated = $true
            $script:timeline.Add(('{0} phase=main-wake-fallback key=KEYCODE_POWER' -f $after.Time))
            Invoke-Adb -Arguments @('shell', 'input', '-d', '0', 'keyevent', 'KEYCODE_POWER') | Out-Null
            $powerTried = $true
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)
    throw ('cannot establish keyguard locked + main {0} (display={1} power={2})' -f $State, $after.MainRaw, $after.MainPower)
}

function Invoke-I37CancelShell {
    if (-not $script:shellPosted) { return }
    $state = Get-I37DeviceState
    $scope = Get-I37ShellCleanupScope -Keys $state.Keys -Tag $script:activeShellTag
    if ($scope -eq 'unsafe') { throw 'shell cleanup scope unsafe: another shell notification is present' }
    if ($scope -eq 'absent') { $script:shellPosted = $false; $script:activeShellTag = $null; return }
    Set-I37Main -State ON
    $state = Get-I37DeviceState
    $scope = Get-I37ShellCleanupScope -Keys $state.Keys -Tag $script:activeShellTag
    if ($scope -eq 'unsafe') { throw 'shell cleanup scope changed while waking main' }
    if ($scope -eq 'absent') { $script:shellPosted = $false; $script:activeShellTag = $null; return }
    $thaw = @(Invoke-Adb -Arguments @('shell', 'cmd', 'activity', 'unfreeze', $config.Package) -AllowFailure)
    $marker = 'pc-i37-{0}-cancel-shell-{1}' -f $runId, ($script:shellCancelAttempts + 1)
    Invoke-Adb -Arguments @('shell', 'log', '-t', 'RearCue', $marker) | Out-Null
    $script:shellCancelTried = $true
    $script:shellCancelAttempts++
    $reply = @(Invoke-Adb -Arguments @('shell', 'am', 'broadcast', '--receiver-foreground', '-n', $config.DebugReceiver,
        '-a', ($config.ActionPrefix + 'CANCEL_PACKAGE'), '--es', 'pkg', 'com.android.shell') -AllowFailure)
    Start-Sleep -Seconds 2
    $state = Get-I37DeviceState
    Write-I37State -Label 'shell-cleanup' -State $state
    $scope = Get-I37ShellCleanupScope -Keys $state.Keys -Tag $script:activeShellTag
    $feedback = Get-I37CancelFeedback -Lines @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all', '-s', 'RearCue') -AllowFailure) -Marker $marker
    $script:shellCancelFeedback = $feedback
    $script:shellCancelScope = $scope
    $timeline.Add(('{0} phase=shell-cleanup scope={1} receiver={2} unfreeze-reply={3} broadcast-reply-recognized={4}' -f
        $state.Time, $scope, $feedback, ($thaw -join ' '), (($reply -join ' ') -match 'Broadcast completed')))
    if ($scope -eq 'unsafe') { throw 'shell cleanup scope changed after broadcast' }
    if ($scope -ne 'absent') { throw ('synthetic shell notification cleanup failed; receiver={0}' -f $feedback) }
    $script:shellPosted = $false
    $script:activeShellTag = $null
}

function Invoke-I37CancelApp {
    if (-not $script:appPosted) { return }
    Set-I37Main -State ON
    Invoke-ExDebugAction -Action 'CANCEL_TEST' | Out-Null
    Start-Sleep -Seconds 2
    $state = Get-I37DeviceState
    if (@($state.Keys | Where-Object { $_.Package -eq 'com.rearcue.poc' }).Count) { throw 'app test notification cleanup failed' }
    $script:appPosted = $false
}

function Restore-I37RearOwner {
    if (-not $script:initial -or -not $script:initial.Locked) { return }
    $state = Get-I37DeviceState
    Write-I37State -Label 'rear-owner-check' -State $state
    if ($state.Owner -eq 'dashboard' -and $script:initial.Owner -eq 'native') {
        Set-I37Main -State ON
        Invoke-ExDebugAction -Action 'EXIT_REAR' | Out-Null
        Start-Sleep -Seconds 2
        $state = Get-I37DeviceState
        Write-I37State -Label 'rear-owner-after-exit' -State $state
    }
    if ($state.Owner -eq 'other' -and $script:initial.Owner -eq 'native') {
        $plan = Get-I37RearMainTaskMovePlan -DumpsysActivities $state.ActivityDump -RearDisplayId $state.RearId
        $timeline.Add(('{0} phase=rear-owner-plan safe={1} task={2} top={3} reason={4}' -f
            $state.Time, $plan.Safe, $plan.TaskId, $plan.TopActivity, $plan.Reason))
        if (-not $plan.Safe) { throw ('rear owner other is not safe to move: {0}; top={1}' -f $plan.Reason, $state.TopActivity) }
        # Re-read immediately before mutation: no stale task id, changed owner, or new
        # allowlist notification may authorize a task move. No app is stopped or killed.
        $before = Get-I37DeviceState
        Write-I37State -Label 'rear-owner-move-preflight' -State $before
        $fresh = Get-I37RearMainTaskMovePlan -DumpsysActivities $before.ActivityDump -RearDisplayId $before.RearId
        if (-not $before.Locked -or $before.RearId -ne $script:initial.RearId -or
            $before.Owner -ne 'other' -or -not $fresh.Safe -or $fresh.TaskId -ne $plan.TaskId -or
            @($before.Keys | Where-Object { $_.Package -in $script:allowlist }).Count) {
            throw ('rear owner move preflight changed; task={0} top={1} reason={2}' -f $plan.TaskId, $before.TopActivity, $fresh.Reason)
        }
        $script:ownerIntervened = $true
        $ownerVerdict = Get-I37OwnerRecoveryVerdict -PreviousResult $script:result -MovedOwnMainTask $true
        $script:result = $ownerVerdict.Result
        $script:reason = $ownerVerdict.Reason
        $moveReply = @(Invoke-Adb -Arguments @('shell', 'cmd', 'activity', 'display', 'move-stack', [string]$fresh.TaskId, '0') -AllowFailure)
        $timeline.Add(('{0} phase=rear-owner-move task={1} from={2} to=0 reply={3}' -f
            $before.Time, $fresh.TaskId, $before.RearId, ($moveReply -join ' ')))
        $deadline = (Get-Date).AddSeconds(8)
        do {
            $state = Get-I37DeviceState
            $place = Get-ExTaskPlacement -DumpsysActivities $state.ActivityDump -TaskId ([string]$fresh.TaskId)
            Write-I37State -Label 'rear-owner-move-verify' -State $state
            $verified = ($place.Found -and $place.TaskId -eq $fresh.TaskId -and $place.RootTaskId -eq $fresh.TaskId -and
                $place.DisplayId -eq 0 -and $place.Affinity -eq 'com.rearcue.poc' -and
                $place.Component -in @('com.rearcue.poc/.ui.MainActivity', 'com.rearcue.poc/com.rearcue.poc.ui.MainActivity') -and
                $state.Owner -eq 'native' -and $state.Locked)
            $timeline.Add(('{0} phase=rear-owner-move-placement task={1} found={2} display={3} root={4} verified={5}' -f
                $state.Time, $fresh.TaskId, $place.Found, $place.DisplayId, $place.RootTaskId, $verified))
            if ($verified) { break }
            Start-Sleep -Milliseconds 750
        } while ((Get-Date) -lt $deadline)
        if (-not $verified) { throw ('verified task hand-back failed: task={0} rearOwner={1} top={2}' -f $fresh.TaskId, $state.Owner, $state.TopActivity) }
        $script:ownerMoveVerified = $true
    }
    if ($state.Owner -ne $script:initial.Owner) { throw ('rear owner restore failed: {0}' -f $state.Owner) }
}

function Settle-I37Rear {
    if (-not $script:initial -or -not $script:initial.Locked -or -not $script:deviceMutated) { return }
    $state = Get-I37DeviceState
    Write-I37State -Label 'rear-settle' -State $state
    if ($script:initial.Rear -eq 'ON' -and $state.Rear -ne 'ON') {
        # Only an originally lit rear receives a targeted wake. Never inject sleep
        # into an idle rear: this device previously woke main without restoring rear.
        Invoke-Adb -Arguments @('shell', 'input', '-d', [string]$script:initial.RearId, 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    }
    $deadline = (Get-Date).AddSeconds(60)
    while (-not (Test-I37RearRestored -InitialRear $script:initial.Rear -CurrentRear $state.Rear) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $state = Get-I37DeviceState
        Write-I37State -Label 'rear-settle' -State $state
    }
    if (-not (Test-I37RearRestored -InitialRear $script:initial.Rear -CurrentRear $state.Rear)) {
        throw ('rear did not return to {0} idle class within 60s; final={1}' -f $script:initial.Rear, $state.Rear)
    }
}

function Get-I37ScopedEvents {
    $raw = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all', '-s', 'RearCue', 'GreezeManager', 'PowerGroup') -AllowFailure)
    $events = Get-I37Events -Lines $raw -AppUid $script:appUid
    return ,$events
}

function Invoke-I37Leg {
    param([string] $Name, [string[]] $ExpectedIcons, [bool] $RequireOff)
    $expectedMain = if ($RequireOff) { 'OFF' } else { 'ON' }
    Set-I37Main -State $expectedMain
    $before = Get-I37DeviceState
    Write-I37State -Label ($Name + '-before') -State $before
    if ($before.Main -ne $expectedMain -or -not $before.Locked -or
        @($before.Keys | Where-Object { $_.Package -eq 'com.android.shell' }).Count) {
        throw ('{0} precondition failed' -f $Name)
    }
    $tag = 'rc37-{0}-{1}' -f $runId, $Name
    $marker = 'pc-i37-{0}-{1}-start' -f $runId, $Name
    Invoke-Adb -Arguments @('shell', 'log', '-t', 'RearCue', $marker) | Out-Null
    $script:shellPosted = $true # Include uncertain post outcome in cleanup.
    $script:activeShellTag = $tag
    $script:shellCancelTried = $false
    $script:shellCancelAttempts = 0
    $script:shellCancelFeedback = $null
    $script:shellCancelScope = $null
    $script:deviceMutated = $true
    $post = @(Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', $tag, 'rc37_synthetic') -AllowFailure)
    $replyRecognized = (($post -join ' ') -match 'NotificationRecord' -and ($post -join ' ') -match [regex]::Escape($tag))
    $script:timeline.Add(('{0} phase={1} post-reply-recognized={2} tag={3}' -f
        ((Invoke-Adb -Arguments @('shell', 'date', '+%m-%d_%H:%M:%S.%3N')) -join '').Trim().Replace('_', ' '), $Name, $replyRecognized, $tag))
    $samples = New-Object System.Collections.Generic.List[object]
    $firstKeyAt = $null
    $deadline = (Get-Date).AddSeconds($BudgetSeconds)
    do {
        $sample = Get-I37DeviceState
        $samples.Add($sample)
        Write-I37State -Label ($Name + '-sample') -State $sample
        if (-not $firstKeyAt -and @($sample.Keys | Where-Object { $_.Package -eq 'com.android.shell' -and $_.Tag -eq $tag }).Count -eq 1) { $firstKeyAt = $sample.Time }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    $final = Get-I37DeviceState
    $samples.Add($final)
    Write-I37State -Label ($Name + '-end') -State $final
    if (-not $firstKeyAt -and @($final.Keys | Where-Object { $_.Package -eq 'com.android.shell' -and $_.Tag -eq $tag }).Count -eq 1) { $firstKeyAt = $final.Time }
    $events = Get-I37ScopedEvents
    $startEvent = @($events | Where-Object { $_.Kind -eq 'marker' -and $_.Message -eq $marker } | Select-Object -Last 1)
    $startTime = if ($startEvent.Count) { $startEvent[0].Time } else { $null }
    $systemKeys = @($final.Keys | Where-Object { $_.Package -eq 'com.android.shell' -and $_.Tag -eq $tag })
    $inSystem = $systemKeys.Count -eq 1
    $systemKey = if ($inSystem) { 'com.android.shell|{0}|{1}' -f $systemKeys[0].Id, $systemKeys[0].Tag } else { 'missing' }
    $timeline.Add(('{0} phase={1} system-key-present={2} first-observed={3} key={4}' -f $final.Time, $Name, $inSystem, $firstKeyAt, $systemKey))
    $verdict = Get-I37Verdict -StartTime $startTime -InSystem $inSystem -ReceiptTime $firstKeyAt `
        -Events $events -Samples $samples.ToArray() -ExpectedIcons $ExpectedIcons `
        -PostedPackage 'com.android.shell' -RequireOff $RequireOff -BudgetSeconds $BudgetSeconds
    $legResults.Add(('{0}: {1} ({2}); callback={3}ms icon={4}ms owner={5}ms' -f
        $Name, $verdict.Word, $verdict.Reason, $verdict.CallbackMs, $verdict.IconMs, $verdict.OwnerMs))
    foreach ($event in $events) {
        if (-not $startTime -or $event.Time -lt $startTime -or $event.Time -gt $final.Time) { continue }
        if ($event.Kind -in @('marker', 'callback', 'render', 'freeze', 'thaw', 'main-wake', 'main-off')) {
            $icons = if ($event.IconSet.Count) { ($event.IconSet -join ',') } else { '-' }
            $timeline.Add(('{0} phase={1} event={2} pkg={3} icons=[{4}] uid={5} note={6}' -f
                $event.Time, $Name, $event.Kind, $event.Package, $icons, $event.Uid,
                $(if ($event.Kind -in @('marker', 'freeze', 'thaw', 'main-wake', 'main-off')) { $event.Message } else { '-' })))
        }
    }
    return $verdict
}

try {
    try { $held = $mutex.WaitOne(0) }
    catch [System.Threading.AbandonedMutexException] { $held = $true }
    if (-not $held) { throw 'Global\RearCueDevice mutex busy' }
    if (-not (Test-Path -LiteralPath $Adb)) { throw ('adb missing: {0}' -f $Adb) }
    New-ExDeviceSession -Name 'issue37-screen-off-chain' -Serial $Serial | Out-Null
    $initial = Get-I37DeviceState
    Write-I37State -Label 'initial' -State $initial
    $preflight = Test-I37Preflight -State $initial -Allowlist $allowlist `
        -ListenerEnabled (Test-ExListenerEnabled) -AppRunning ($null -ne (Get-ExAppPid)) -OffOnly:$OffOnly
    if ($preflight) { throw $preflight }
    $packageDump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $config.Package)) -join "`n"
    $script:appUid = Get-I37AppUid -DumpsysPackage $packageDump
    if (-not $script:appUid) { throw 'BLOCKED: app UID unavailable' }
    $permissionValue = Get-I37NotificationPermission -DumpsysPackage $packageDump
    if ($null -eq $permissionValue) { throw 'BLOCKED: app notification permission unavailable' }
    $grantedBefore = [bool]$permissionValue
    $permissionKnown = $true
    if (-not $grantedBefore) {
        $script:deviceMutated = $true
        $permissionChanged = $true
        Invoke-Adb -Arguments @('shell', 'pm', 'grant', $config.Package, 'android.permission.POST_NOTIFICATIONS') | Out-Null
    }
    # First cast: no allowlist notification and native rear owner at the start.
    $first = Invoke-I37Leg -Name 'firstcast-off' -ExpectedIcons @('com.android.shell') -RequireOff $true
    Invoke-I37CancelShell
    Start-Sleep -Seconds 2
    $empty = Get-I37DeviceState
    Write-I37State -Label 'firstcast-clean' -State $empty
    if ($empty.Owner -ne 'native') { $legResults.Add('firstcast-exit: RED-OWNER (native rear not restored)'); throw 'firstcast cleanup did not return native rear' }
    if ($OffOnly) {
        $legResults.Add('update-off: SKIPPED (app baseline needs main ON)')
        $legResults.Add('control-on: SKIPPED (-OffOnly)')
        $result = if ($first.Word -eq 'GREEN') { 'GREEN-OFF' } elseif ($first.Word -like 'RED-*') { 'RED-OFF' } else { 'INVALID' }
        $reason = 'OFF-only firstcast completed; update and locked-on control not evaluated'
    } else {
    # Existing Dashboard: create one app test notification while keyguard stays up and main ON.
    Set-I37Main -State ON
    $appPosted = $true
    $deviceMutated = $true
    $baselineMarker = 'pc-i37-{0}-baseline-start' -f $runId
    Invoke-Adb -Arguments @('shell', 'log', '-t', 'RearCue', $baselineMarker) | Out-Null
    Invoke-ExDebugAction -Action 'POST_TEST' | Out-Null
    $baselineDeadline = (Get-Date).AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 500
        $baseline = Get-I37DeviceState
        if ($baseline.Owner -eq 'dashboard' -and @($baseline.Keys | Where-Object { $_.Package -eq 'com.rearcue.poc' }).Count) { break }
    } while ((Get-Date) -lt $baselineDeadline)
    Write-I37State -Label 'update-baseline' -State $baseline
    $baselineEvents = Get-I37ScopedEvents
    $baselineStart = @($baselineEvents | Where-Object { $_.Kind -eq 'marker' -and $_.Message -eq $baselineMarker } | Select-Object -Last 1)
    $baselineCb = @($baselineEvents | Where-Object { $_.Kind -eq 'callback' -and $_.Package -eq 'com.rearcue.poc' -and
        $baselineStart.Count -gt 0 -and $_.Time -ge $baselineStart[0].Time -and
        (@($_.IconSet) -join ',') -eq 'com.rearcue.poc' })
    if ($baseline.Owner -eq 'dashboard' -and @($baseline.Keys | Where-Object { $_.Package -eq 'com.rearcue.poc' }).Count -and $baselineCb.Count) {
        $update = Invoke-I37Leg -Name 'update-off' -ExpectedIcons @('com.rearcue.poc', 'com.android.shell') -RequireOff $true
        Invoke-I37CancelShell
    } else { $legResults.Add('update-off: INVALID (app baseline receipt/callback/Dashboard incomplete)') }
    Invoke-I37CancelApp
    Start-Sleep -Seconds 2
    # Locked-on listener health control from the same empty state.
    $control = Invoke-I37Leg -Name 'control-on' -ExpectedIcons @('com.android.shell') -RequireOff $false
    Invoke-I37CancelShell
    $all = @($legResults | Where-Object { $_ -notmatch ': GREEN ' })
    $result = if ($all.Count -eq 0) { 'GREEN' } elseif (@($legResults | Where-Object { $_ -match ': RED-' }).Count) { 'RED' } else { 'INVALID' }
    $reason = 'three legs completed; see per-leg verdicts'
    }
}
catch {
    $reason = $_.Exception.Message
    if ($reason -notlike 'BLOCKED:*') { $result = 'INVALID' }
    Write-Host ('issue37 {0}: {1}' -f $result, $reason)
}
finally {
    if ($held) {
        try {
        if ($shellPosted -and -not $shellCancelTried) {
            try { Invoke-I37CancelShell } catch { $timeline.Add(('cleanup-shell-error={0}' -f $_.Exception.Message)); $result = 'INVALID'; $reason = 'synthetic shell cleanup failed; see timeline' }
        }
        if ($shellPosted -and (Test-I37CancelRetryAllowed -Feedback $shellCancelFeedback -Scope $shellCancelScope -Attempts $shellCancelAttempts)) {
            # A foreground broadcast can be queued while the listener process is frozen.
            # One bounded retry precedes permission revocation / process restart. Every
            # attempt re-checks the unique run-owned shell key before broadcasting.
            $timeline.Add('cleanup-shell-bounded-retry=receiver-unavailable')
            Start-Sleep -Seconds 2
            try { Invoke-I37CancelShell }
            catch { $timeline.Add(('cleanup-shell-bounded-retry-error={0}' -f $_.Exception.Message)) }
        }
        try { Invoke-I37CancelApp } catch { $timeline.Add(('cleanup-app-error={0}' -f $_.Exception.Message)); $result = 'INVALID'; $reason = 'app test cleanup failed; see timeline' }
        $pidBeforeRevoke = $null
        if ($permissionChanged) {
            try { $pidBeforeRevoke = Get-ExAppPid }
            catch { $timeline.Add(('cleanup-pid-before-revoke-error={0}' -f $_.Exception.Message)) }
        }
        if ($permissionChanged) {
            try { Invoke-Adb -Arguments @('shell', 'pm', 'revoke', $config.Package, 'android.permission.POST_NOTIFICATIONS') | Out-Null }
            catch { $timeline.Add(('cleanup-permission-error={0}' -f $_.Exception.Message)); $result = 'INVALID'; $reason = 'permission restore failed; see timeline' }
        }
        if ($shellPosted -and $permissionChanged) {
            # Revocation can kill a frozen process; the listener is then rebound in a
            # fresh process. Wait boundedly for a new PID, then make one final
            # scoped attempt even if the restart was not observed.
            $restartDeadline = (Get-Date).AddSeconds(8)
            $restartObserved = $false
            try {
                do {
                    Start-Sleep -Seconds 1
                    $pidAfterRevoke = Get-ExAppPid
                    if ($pidAfterRevoke -and $pidAfterRevoke -ne $pidBeforeRevoke) { $restartObserved = $true; break }
                } while ((Get-Date) -lt $restartDeadline)
            } catch { $timeline.Add(('cleanup-pid-after-revoke-error={0}' -f $_.Exception.Message)) }
            $timeline.Add(('cleanup-process-restarted={0}' -f $restartObserved))
            try { Invoke-I37CancelShell } catch { $timeline.Add(('cleanup-shell-after-revoke-error={0}' -f $_.Exception.Message)); $result = 'INVALID'; $reason = 'synthetic shell cleanup failed after permission restore; see timeline' }
        }
        try { Restore-I37RearOwner } catch { $timeline.Add(('cleanup-rear-owner-error={0}' -f $_.Exception.Message)); $result = 'INVALID'; $reason = ('rear owner restore failed: {0}' -f $_.Exception.Message) }
        if ($initial -and $initial.Locked) {
            try { Set-I37Main -State $initial.Main } catch { $timeline.Add(('cleanup-main-error={0}' -f $_.Exception.Message)); $result = 'INVALID'; $reason = 'main restore failed; see timeline' }
        }
        try { Settle-I37Rear } catch { $timeline.Add(('cleanup-rear-error={0}' -f $_.Exception.Message)); $result = 'INVALID'; $reason = 'rear idle restore failed; see timeline' }
        if ($initial) {
            try {
                $last = Get-I37DeviceState
                Write-I37State -Label 'final' -State $last
                $initialKeys = @($initial.Keys | ForEach-Object { '{0}|{1}|{2}' -f $_.Package, $_.Id, $_.Tag } | Sort-Object) -join ','
                $finalKeys = @($last.Keys | ForEach-Object { '{0}|{1}|{2}' -f $_.Package, $_.Id, $_.Tag } | Sort-Object) -join ','
                $stateMismatch = ($last.Main -ne $initial.Main -or $last.MainPower -ne $initial.MainPower -or
                    -not (Test-I37RearRestored -InitialRear $initial.Rear -CurrentRear $last.Rear) -or
                    $last.Locked -ne $initial.Locked -or $last.Owner -ne $initial.Owner -or
                    $initialKeys -ne $finalKeys -or $shellPosted -or $appPosted)
                if ($stateMismatch) {
                    $result = 'INVALID'
                    if ($reason -notlike 'rear owner restore failed:*') { $reason = 'final state differs from initial state; see timeline' }
                    $timeline.Add('cleanup-state-mismatch=true')
                }
                if ($ownerIntervened) {
                    $result = 'INVALID'
                    if ($ownerMoveVerified -and -not $stateMismatch) {
                        $reason = 'RearCue MainActivity occupied Rear Display after cleanup; harness hand-back is not product success'
                    }
                    $timeline.Add(('cleanup-owner-intervention=INVALID move-verified={0}' -f $ownerMoveVerified))
                }
                if ($permissionKnown) {
                    $afterPackage = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $config.Package)) -join "`n"
                    $afterPermission = Get-I37NotificationPermission -DumpsysPackage $afterPackage
                    $restored = ($null -ne $afterPermission -and $afterPermission -eq $grantedBefore)
                    $timeline.Add(('cleanup-permission-restored={0} expected={1} actual={2}' -f $restored, $grantedBefore, $afterPermission))
                    if (-not $restored) { $result = 'INVALID'; $reason = 'notification permission not restored; see timeline' }
                }
            } catch { $timeline.Add(('cleanup-verification-error={0}' -f $_.Exception.Message)); $result = 'INVALID'; $reason = 'cleanup verification failed; see timeline' }
        }
        if (Get-ExSessionDir) {
            Write-ExArtifact -Name 'timeline.txt' -Lines $timeline.ToArray() | Out-Null
            Write-ExArtifact -Name 'verdict.md' -Lines (@('# Issue #37 device verdict', '', ('result: {0}' -f $result), ('reason: {0}' -f $reason),
                ('serial: {0}' -f $Serial), ('run-id: {0}' -f $runId), ('budget: {0}s from pre-post device log marker' -f $BudgetSeconds),
                ('mode: {0}' -f $(if ($OffOnly) { 'OFF-only firstcast' } else { 'full chain' })),
                'system receipt is bounded by the pre-post marker and first observed notification key; only notification keys are archived',
                '') + $legResults.ToArray() + @('', 'See timeline.txt for device-clock evidence and cleanup.')) | Out-Null
            Write-Host ('issue37 {0}: {1}' -f $result, (Get-ExSessionDir))
        }
        } finally {
        $mutex.ReleaseMutex()
        }
    }
    $mutex.Dispose()
    $env:REARCUE_ADB = $oldAdb
}
if ($result -notin @('GREEN', 'GREEN-OFF')) { exit 1 }
exit 0
