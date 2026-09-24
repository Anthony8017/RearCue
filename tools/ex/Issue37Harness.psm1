# Pure parsing and verdict seam for the issue #37 device harness. ASCII source for PS 5.1.
Set-StrictMode -Version 2.0
Import-Module (Join-Path $PSScriptRoot 'ExCommon.psm1')

function Get-I37AppUid {
    param([Parameter(Mandatory)][AllowEmptyString()][string] $DumpsysPackage)
    if ($DumpsysPackage -match '(?m)^\s*appId=(\d+)\s*$') { return $Matches[1] }
    if ($DumpsysPackage -match '(?m)^\s*userId=(\d+)\s*$') { return $Matches[1] }
    return $null
}

function Get-I37NotificationPermission {
    param([Parameter(Mandatory)][AllowEmptyString()][string] $DumpsysPackage)
    if ($DumpsysPackage -match 'android\.permission\.POST_NOTIFICATIONS: granted=(true|false)') { return ($Matches[1] -eq 'true') }
    return $null
}

function Get-I37ScreenPowerState {
    param([Parameter(Mandatory)][AllowEmptyString()][string] $DumpsysDisplay, [int] $DisplayId = 0)
    $inController = $false; $currentId = $null; $screenState = $null
    foreach ($line in ($DumpsysDisplay -split "`r?`n")) {
        if ($line -match '^\s*Display Power Controller:\s*$') {
            if ($inController -and $currentId -eq $DisplayId) { return $screenState }
            $inController = $true; $currentId = $null; $screenState = $null
            continue
        }
        if (-not $inController) { continue }
        if ($null -eq $currentId -and $line -match '^\s*mDisplayId=(\d+)\s*$') { $currentId = [int]$Matches[1] }
        if ($line -match '^\s*mScreenState=(ON|OFF|DOZE|DOZE_SUSPEND)\s*$') { $screenState = $Matches[1] }
    }
    if ($inController -and $currentId -eq $DisplayId) { return $screenState }
    return $null
}

function Get-I37MainCondition {
    param([string] $DisplayState, [string] $ScreenPower)
    if ($DisplayState -eq 'ON' -and $ScreenPower -eq 'ON') { return 'ON' }
    if ($DisplayState -in @('OFF', 'DOZE', 'DOZE_SUSPEND') -and $ScreenPower -eq 'OFF') { return 'OFF' }
    return 'unknown'
}

function Get-I37NotificationKeys {
    param([AllowEmptyCollection()][string[]] $Lines = @())
    $keys = New-Object System.Collections.Generic.List[object]
    foreach ($line in $Lines) {
        if ($line -match '^\s*(?<user>\d+)\|(?<pkg>[A-Za-z0-9_.]+)\|(?<id>-?\d+)\|(?<tag>[^|]*)\|(?<uid>\d+)\s*$') {
            $keys.Add([pscustomobject]@{ Package = $Matches['pkg']; Tag = $Matches['tag']; Id = $Matches['id'] })
        }
    }
    return ,$keys.ToArray()
}

function Get-I37ShellCleanupScope {
    param([AllowEmptyCollection()][object[]] $Keys = @(), [Parameter(Mandatory)][string] $Tag)
    $shell = @($Keys | Where-Object { $_.Package -eq 'com.android.shell' })
    if ($shell.Count -eq 0) { return 'absent' }
    if ($shell.Count -eq 1 -and $shell[0].Tag -ceq $Tag) { return 'target' }
    return 'unsafe'
}

function Get-I37CancelFeedback {
    param([AllowEmptyCollection()][string[]] $Lines = @(), [Parameter(Mandatory)][string] $Marker)
    $started = $false
    foreach ($line in $Lines) {
        if ($line -match ('RearCue\s*:\s*' + [regex]::Escape($Marker) + '\s*$')) { $started = $true; continue }
        if (-not $started) { continue }
        if ($line -match 'RearCue\s*:\s*debug cancel pkg=com\.android\.shell (?:->|cancelled=)\s*(-?\d+)') {
            $count = [int]$Matches[1]
            if ($count -lt 0) { return 'listener-unavailable' }
            if ($count -gt 0) { return 'cancelled' }
            return 'receiver-ran-no-cancel'
        }
    }
    if (-not $started) { return 'marker-missing' }
    return 'receiver-not-observed'
}

function Test-I37CancelRetryAllowed {
    param([string] $Feedback, [string] $Scope, [int] $Attempts)
    return ($Attempts -eq 1 -and $Scope -eq 'target' -and
        $Feedback -in @('receiver-not-observed', 'listener-unavailable'))
}

function Test-I37CancelRestartAllowed {
    <#
      Last-resort cleanup gate: the bounded retry already ran (two scoped attempts)
      and the run-owned shell key is still there while the debug receiver never
      answered. That is the signature of a Greeze-frozen listener process that
      ignores `cmd activity unfreeze` and queued foreground broadcasts
      (20260924 run 7c98fd084b); only a process restart delivers the cancel.
    #>
    param([int] $Attempts, [string] $Scope, [string] $Feedback)
    return ($Attempts -ge 2 -and $Scope -eq 'target' -and
        $Feedback -in @('receiver-not-observed', 'listener-unavailable'))
}

function Get-I37OwnerRecoveryVerdict {
    param([string] $PreviousResult, [bool] $MovedOwnMainTask)
    if ($MovedOwnMainTask) {
        return [pscustomobject]@{ Result = 'INVALID'; Reason = 'RearCue MainActivity occupied the Rear Display after cleanup; harness moved its verified root task to Main Display' }
    }
    return [pscustomobject]@{ Result = $PreviousResult; Reason = $null }
}

function Get-I37RearMainTaskMovePlan {
    <# Fail closed: only an exact RearCue MainActivity root task above the Native Rear Screen may move. #>
    param([Parameter(Mandatory)][AllowEmptyString()][string] $DumpsysActivities, [int] $RearDisplayId = 1)
    $unsafe = { param($why, $top) [pscustomobject]@{ Safe = $false; TaskId = $null; TopActivity = $top; Reason = $why } }
    $section = Get-DisplayActivitySection -DumpsysActivities $DumpsysActivities -DisplayId $RearDisplayId
    if (-not $section.Count) { return (& $unsafe 'rear display activities missing' $null) }
    $tops = @($section | Where-Object { $_ -match 'topResumedActivity=\s*ActivityRecord\{' })
    if ($tops.Count -ne 1) { return (& $unsafe 'rear top activity is not unique and explicit' $null) }
    if ($tops[0] -notmatch 'topResumedActivity=\s*ActivityRecord\{[^}]*?\s([^\s}]+/[^\s}]+)\s+t(\d+)\}') {
        return (& $unsafe 'rear top activity cannot be parsed' $null)
    }
    $top = $Matches[1]; $taskId = [int]$Matches[2]
    if ($top -notin @('com.rearcue.poc/.ui.MainActivity', 'com.rearcue.poc/com.rearcue.poc.ui.MainActivity')) {
        return (& $unsafe 'rear top is not RearCue MainActivity' $top)
    }
    $firstTask = @($section | Where-Object { $_ -match '^\s*\* Task\{' } | Select-Object -First 1)
    if (-not $firstTask.Count -or $firstTask[0] -notmatch '^\s*\* Task\{[0-9a-f]+\s+#(\d+)') {
        return (& $unsafe 'rear top task header missing' $top)
    }
    if ([int]$Matches[1] -ne $taskId) { return (& $unsafe 'rear top record is not in the first task' $top) }
    $place = Get-ExTaskPlacement -DumpsysActivities $DumpsysActivities -TaskId $taskId
    if (-not $place.Found -or $place.DisplayId -ne $RearDisplayId -or $place.RootTaskId -ne $taskId -or
        $place.Affinity -ne 'com.rearcue.poc' -or $place.Header -notmatch 'type=standard' -or
        $place.Header -match 'rootTaskId=' -or
        $place.Component -ne $top) {
        return (& $unsafe 'RearCue MainActivity root task identity is not exact' $top)
    }
    $topIndex = [array]::IndexOf($section, $tops[0])
    $firstTaskIndex = -1; $nextTaskIndex = -1
    for ($i = 0; $i -lt $section.Count; $i++) {
        if ($section[$i] -match '^\s*\* Task\{') {
            if ($firstTaskIndex -ge 0) { $nextTaskIndex = $i; break }
            $firstTaskIndex = $i
        }
    }
    if ($topIndex -le $firstTaskIndex -or ($nextTaskIndex -ge 0 -and $topIndex -ge $nextTaskIndex)) {
        return (& $unsafe 'rear top record is not inside the first task' $top)
    }
    $nativeLines = @($section | Select-Object -Skip ($topIndex + 1) | Where-Object {
        $_ -match 'ActivityRecord\{[^}]*?\scom\.xiaomi\.subscreencenter/(?:\.SubScreenLauncher|com\.xiaomi\.subscreencenter\.SubScreenLauncher)\s+t(\d+)\}'
    })
    $nativeIds = @($nativeLines | ForEach-Object {
        if ($_ -match '\s+t(\d+)\}') { [int]$Matches[1] }
    } | Sort-Object -Unique)
    if ($nativeIds.Count -ne 1) {
        return (& $unsafe 'Native Rear Screen launcher not uniquely below MainActivity' $top)
    }
    $native = Get-ExTaskPlacement -DumpsysActivities $DumpsysActivities -TaskId $nativeIds[0]
    if (-not $native.Found -or $native.DisplayId -ne $RearDisplayId -or
        $native.Affinity -ne 'com.xiaomi.subscreencenter' -or
        $native.Header -notmatch 'type=home' -or
        $native.Component -notin @('com.xiaomi.subscreencenter/.SubScreenLauncher',
            'com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher')) {
        return (& $unsafe 'Native Rear Screen task identity is not exact' $top)
    }
    return [pscustomobject]@{ Safe = $true; TaskId = $taskId; TopActivity = $top; Reason = 'exact RearCue root above Native Rear Screen' }
}

function Test-I37Preflight {
    param($State, [string[]] $Allowlist, [bool] $ListenerEnabled, [bool] $AppRunning, [switch] $OffOnly)
    if (-not $State.Locked) { return 'BLOCKED: device starts unlocked; secure keyguard cannot be restored unattended' }
    if ($State.Owner -ne 'native') { return ('BLOCKED: initial rear owner is {0}; need native empty baseline' -f $State.Owner) }
    if ($OffOnly -and ($State.Main -ne 'OFF' -or $State.MainPower -ne 'OFF')) { return 'BLOCKED: main screen is not physically OFF at OFF-only start' }
    $active = @($State.Keys | Where-Object { $_.Package -in $Allowlist })
    if ($active.Count) {
        $packages = @($active | ForEach-Object { $_.Package } | Sort-Object -Unique) -join ','
        return ('BLOCKED: {0} existing allowlist notifications ({1}); preserving them' -f $active.Count, $packages)
    }
    if ($State.Rear -notin @('ON', 'DOZE_SUSPEND') -and -not ($OffOnly -and $State.Rear -eq 'OFF')) {
        return ('BLOCKED: native rear starts {0}; this device cannot reliably restore that state after the locked-on control' -f $State.Rear)
    }
    if (-not $ListenerEnabled) { return 'BLOCKED: RearCue notification listener not enabled' }
    if (-not $AppRunning) { return 'BLOCKED: RearCue process not running; start it before the locked run' }
    return $null
}

function Test-I37RearRestored {
    param([string] $InitialRear, [string] $CurrentRear)
    if ($InitialRear -eq 'DOZE_SUSPEND') { return ($CurrentRear -in @('DOZE_SUSPEND', 'OFF')) }
    return ($InitialRear -eq $CurrentRear)
}

function Get-I37Events {
    param([AllowEmptyCollection()][string[]] $Lines = @(), [string] $AppUid)
    $events = New-Object System.Collections.Generic.List[object]
    foreach ($line in $Lines) {
        if ($line -notmatch '^(?<time>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+[VDIWEF]\s+(?<tag>RearCue|GreezeManager|PowerGroup)\s*:\s*(?<message>.*)$') { continue }
        $time = $Matches['time']; $source = $Matches['tag']; $message = $Matches['message']
        $kind = $null; $pkg = $null; $iconSet = @(); $uid = $null
        if ($source -eq 'RearCue' -and $message -match '^pc-i37-[A-Za-z0-9-]+$') { $kind = 'marker' }
        elseif ($source -eq 'RearCue' -and $message -match '^posted (com\.android\.shell|com\.rearcue\.poc)\b') {
            $kind = 'callback'; $pkg = $Matches[1]
            if ($message -match 'iconSet \[[^\]]*\] -> \[([^\]]*)\]') { $iconSet = @($Matches[1] -split ',\s*' | Where-Object { $_ }) }
        }
        elseif ($source -eq 'RearCue' -and $message -match '^(update|project) iconSet=\[([^\]]*)\]') {
            $kind = 'render'; $iconSet = @($Matches[2] -split ',\s*' | Where-Object { $_ })
        }
        elseif ($source -eq 'GreezeManager' -and $message -match '\b(FZ|THAW) uid\s*=\s*(\d+)') {
            $uid = $Matches[2]
            if (-not $AppUid -or $uid -ne $AppUid) { continue }
            $kind = if ($Matches[1] -eq 'FZ') { 'freeze' } else { 'thaw' }
        }
        elseif ($source -eq 'PowerGroup' -and $message -match 'Waking up power group.*groupId=\s*0\b') { $kind = 'main-wake' }
        elseif ($source -eq 'PowerGroup' -and $message -match 'Powering off display group.*groupId=\s*0\b') { $kind = 'main-off' }
        if ($kind) { $events.Add([pscustomobject]@{ Time = $time; Kind = $kind; Package = $pkg; IconSet = $iconSet; Uid = $uid; Message = $message }) }
    }
    return ,$events.ToArray()
}

function Get-I37Verdict {
    param(
        [string] $StartTime,
        [bool] $InSystem,
        [string] $ReceiptTime,
        [AllowEmptyCollection()][object[]] $Events = @(),
        [AllowEmptyCollection()][object[]] $Samples = @(),
        [string[]] $ExpectedIcons,
        [string] $PostedPackage,
        [bool] $RequireOff,
        [int] $BudgetSeconds = 5
    )
    if (-not $StartTime -or -not $InSystem -or $Samples.Count -eq 0) {
        return [pscustomobject]@{ Word = 'INVALID'; Reason = 'system receipt or timed samples missing'; CallbackMs = $null; IconMs = $null; OwnerMs = $null }
    }
    $start = [datetime]::ParseExact($StartTime, 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
    $until = $start.AddSeconds($BudgetSeconds)
    if ($ReceiptTime) {
        $receipt = [datetime]::ParseExact($ReceiptTime, 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
        if ($receipt -gt $until) {
            return [pscustomobject]@{ Word = 'INVALID'; Reason = 'system key first observed after budget'; CallbackMs = $null; IconMs = $null; OwnerMs = $null }
        }
    }
    $inWindow = { param($time) if (-not $time) { return $false }; $t = [datetime]::ParseExact($time, 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture); return ($t -ge $start -and $t -le $until) }
    $safe = @($Samples | Where-Object { & $inWindow $_.Time })
    $lastTime = [datetime]::ParseExact($Samples[-1].Time, 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
    if ($safe.Count -eq 0 -or $lastTime -lt $until -or
        @($Samples | Where-Object { -not $_.Locked -or $_.Main -eq 'unknown' -or $_.MainPower -notin @('OFF', 'ON') }).Count -gt 0) {
        return [pscustomobject]@{ Word = 'INVALID'; Reason = 'keyguard or main display unobserved'; CallbackMs = $null; IconMs = $null; OwnerMs = $null }
    }
    if ($RequireOff -and (@($Samples | Where-Object { $_.Main -ne 'OFF' -or $_.MainPower -ne 'OFF' }).Count -gt 0 -or
            @($Events | Where-Object { $_.Kind -eq 'main-wake' -and (& $inWindow $_.Time) }).Count -gt 0)) {
        return [pscustomobject]@{ Word = 'INVALID'; Reason = 'main display woke during off window'; CallbackMs = $null; IconMs = $null; OwnerMs = $null }
    }
    if (-not $RequireOff -and @($Samples | Where-Object { $_.Main -ne 'ON' -or $_.MainPower -ne 'ON' }).Count -gt 0) {
        return [pscustomobject]@{ Word = 'INVALID'; Reason = 'locked-on control lost main display ON'; CallbackMs = $null; IconMs = $null; OwnerMs = $null }
    }
    $expected = @($ExpectedIcons | Sort-Object) -join ','
    $callbacks = @($Events | Where-Object { $_.Kind -eq 'callback' -and $_.Package -eq $PostedPackage -and (& $inWindow $_.Time) })
    $icon = @($callbacks | Where-Object { (@($_.IconSet | Sort-Object) -join ',') -eq $expected } | Select-Object -First 1)
    # Render is tied to this leg by window + icon-set equality only, NOT by wall-clock order
    # against the `posted` summary: project() runs inside the post() pipeline, so `project
    # iconSet=...` can land ~1ms before `posted ...` (device run 20260924-190143) and requiring
    # render >= callback mislabels a real GREEN as RED-RENDER. $icon.Count still requires the
    # expected-set callback to exist first for the RED-ICON distinction.
    $render = @($Events | Where-Object { $_.Kind -eq 'render' -and (& $inWindow $_.Time) -and
            (@($_.IconSet | Sort-Object) -join ',') -eq $expected -and
            $icon.Count -gt 0 } | Select-Object -First 1)
    $owner = @($safe | Where-Object { $_.Owner -eq 'dashboard' -and $render.Count -gt 0 -and $_.Time -ge $render[0].Time } | Select-Object -First 1)
    $callbackMs = $null; $iconMs = $null; $ownerMs = $null
    if ($callbacks.Count) { $callbackMs = [int]([datetime]::ParseExact($callbacks[0].Time, 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture) - $start).TotalMilliseconds }
    if ($icon.Count) { $iconMs = [int]([datetime]::ParseExact($icon[0].Time, 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture) - $start).TotalMilliseconds }
    if ($owner.Count) { $ownerMs = [int]([datetime]::ParseExact($owner[0].Time, 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture) - $start).TotalMilliseconds }
    $word = 'GREEN'; $reason = 'system, callback, icon set and dashboard within budget'
    if (-not $callbacks.Count) { $word = 'RED-NO-CALLBACK'; $reason = 'system accepted but listener callback missing within budget' }
    elseif (-not $icon.Count) { $word = 'RED-ICON'; $reason = 'callback arrived but expected icon set missing' }
    elseif (-not $render.Count) { $word = 'RED-RENDER'; $reason = 'icon set changed but rear update/project log missing' }
    elseif (-not $owner.Count -or $Samples[-1].Owner -ne 'dashboard') {
        $word = 'RED-OWNER'; $reason = 'render issued but dashboard not retained on rear through the window'
    }
    return [pscustomobject]@{ Word = $word; Reason = $reason; CallbackMs = $callbackMs; IconMs = $iconMs; OwnerMs = $ownerMs }
}

Export-ModuleMember -Function Get-I37AppUid, Get-I37NotificationPermission, Get-I37ScreenPowerState, Get-I37MainCondition, Get-I37NotificationKeys, Get-I37ShellCleanupScope, Get-I37CancelFeedback, Test-I37CancelRetryAllowed, Test-I37CancelRestartAllowed, Get-I37RearMainTaskMovePlan, Get-I37OwnerRecoveryVerdict, Test-I37Preflight, Test-I37RearRestored, Get-I37Events, Get-I37Verdict
