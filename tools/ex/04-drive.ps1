# 04-drive.ps1 -- drive the notification -> rear screen chain from the PC (E1 / E7 / E3+E4+E6).
#
# Run through the one-command entry point (`ex.ps1`), or alone:
#   .\tools\ex\04-drive.ps1                          # E1 then E7 (default)
#   .\tools\ex\04-drive.ps1 -Scenario e1,e7,e3-lock  # full matrix; e3-lock locks the phone
#   .\tools\ex\04-drive.ps1 -Scenario e3-lock -LockSeconds 300 -PhotoPause
#
# Everything is driven through adb: the debug-only DebugCommandReceiver
# (`com.rearcue.poc.action.POST_TEST` / `CANCEL_TEST` / `CANCEL_PACKAGE`) posts and cancels the
# test notifications, and `cmd notification post` adds a com.android.shell notification so the
# Icon Set really grows to two icons. Judgement always comes from the device, never from
# "the command did not throw": `dumpsys activity activities` says who owns display #1.
#
# The three photo checkpoints of spec 0001 are recorded as an artifact here (and optionally
# paused for, with -PhotoPause); the phone's rear screen cannot be captured on this HyperOS build
# (`screencap -d 1` -> "Display Id '1' is not valid"), so a human photo is the only visual record.

[CmdletBinding()]
param(
    [ValidateSet('e1', 'e7', 'e3-lock')][string[]] $Scenario = @('e1', 'e7'),
    [int] $LockSeconds = 120,
    [int] $SampleSeconds = 5,
    [switch] $NoUnlock,
    [switch] $PhotoPause,
    [switch] $HoldDashboard,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'drive' -Serial $Serial | Out-Null }

$script:ExPhotoCheckpoints = New-Object System.Collections.Generic.List[object]
$script:ExPhotoPause = [bool]$PhotoPause
# Stable folder (not per-session): the operator drops the three shots there by filename, and
# findings points at one place.
$script:ExPhotoDir = Join-Path (Get-ExPath $config.LogDir) 'manual-photos'

$verdicts = [ordered]@{}

function Add-ExPhotoCheckpoint {
    param(
        [Parameter(Mandatory)][int] $Id,
        [Parameter(Mandatory)][string] $Title,
        [Parameter(Mandatory)][string] $Slug,
        [Parameter(Mandatory)][string] $What,
        [string] $Evidence
    )
    New-Item -ItemType Directory -Force -Path $script:ExPhotoDir | Out-Null
    $file = 'photo-0{0}-{1}.jpg' -f $Id, $Slug
    $target = Join-Path $script:ExPhotoDir $file
    # A scenario can run twice in one session (E7 re-runs E1 when nothing is on screen): the
    # checkpoint is the moment, not the run, so only the first arrival is recorded.
    $seen = $script:ExPhotoCheckpoints | Where-Object { $_.Id -eq $Id }
    if ($seen) {
        Write-ExNote ('photo checkpoint {0} already recorded at {1}' -f $Id, $seen[0].At)
        return
    }
    $script:ExPhotoCheckpoints.Add([pscustomobject]@{
            Id       = $Id
            Title    = $Title
            What     = $What
            Evidence = $Evidence
            At       = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
            Target   = $target
        })
    Write-ExNote ('photo checkpoint {0} [{1}] -> {2}' -f $Id, $Title, $file)
    if ($script:ExPhotoPause) {
        Write-Host ''
        Write-Host ('  >>> PHOTO CHECKPOINT {0}: {1}' -f $Id, $Title) -ForegroundColor Yellow
        Write-Host ('      rear screen should show: {0}' -f $What)
        Write-Host ('      save the photo as: {0}' -f $target)
        Read-Host '      press Enter once the photo is taken'
    }
}

function Reset-ExNotifications {
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
    Start-Sleep -Seconds 2
}

function Get-ExRearSnapshot {
    $displayDump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
    $rear = Get-RearDisplay -DumpsysDisplay $displayDump
    $owner = Get-ExRearOwnerNow
    $state = if ($rear) { '{0}/{1}' -f $rear.State, $rear.CommittedState } else { 'no-rear-display' }
    return [pscustomobject]@{ Rear = $rear; Owner = $owner; State = $state }
}

function Write-ExScenarioReport {
    param(
        [Parameter(Mandatory)][string] $Name,
        [Parameter(Mandatory)][hashtable] $ScenarioVerdicts,
        [string[]] $Note = @()
    )
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add(('# {0} -- {1}' -f $Name, (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')))
    foreach ($note in $Note) { $lines.Add('# ' + $note) }
    $lines.Add('')
    $lines.Add('# verdicts')
    foreach ($key in $ScenarioVerdicts.Keys) { $lines.Add(('  {0,-20} = {1}' -f $key, $ScenarioVerdicts[$key])) }
    $snapshot = Get-ExRearSnapshot
    $lines.Add('')
    $lines.Add('# rear display now')
    $lines.Add(('  owner = {0}' -f $snapshot.Owner))
    $lines.Add(('  state = {0}' -f $snapshot.State))
    if ($snapshot.Rear) {
        $lines.Add(('  size  = {0}x{1} density={2} flags={3}' -f $snapshot.Rear.Width, $snapshot.Rear.Height,
                $snapshot.Rear.Density, ($snapshot.Rear.Flags -join ',')))
    }
    $lines.Add('')
    $lines.Add('# logcat (RearCue)')
    foreach ($raw in (@(Get-ExLogcat) | Where-Object { $_ -match 'RearCue\s*: ' })) { $lines.Add('  ' + $raw) }
    $lines.Add('')
    $lines.Add('# dumpsys activity activities (display #1 block)')
    $activityDump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure) -join "`n"
    foreach ($raw in (Get-DisplayActivitySection -DumpsysActivities $activityDump -DisplayId 1)) { $lines.Add('  ' + $raw) }
    Write-ExArtifact -Name ('{0}.txt' -f $Name) -Lines $lines.ToArray() | Out-Null
    foreach ($key in $ScenarioVerdicts.Keys) { $verdicts[('{0}.{1}' -f $Name, $key)] = $ScenarioVerdicts[$key] }
}

function Invoke-ExScenarioE1 {
    Write-ExNote 'E1: two Allowlist notifications -> automatic projection onto the rear display'
    # HyperOS denies third-party rear-display launches while the keyguard is locked, so a locked
    # phone makes this scenario fail for a reason that is not the app's fault. Say so loudly.
    $unlocked = Unlock-ExScreen
    if (-not $unlocked) {
        Write-ExNote 'WARNING: the phone is still keyguard-locked (secure lock cannot be dismissed over adb)'
        Write-ExNote '         HyperOS will deny the rear-display launch; unlock the phone and rerun E1/E7'
    }
    Start-ExApp -TimeoutSec 30 | Out-Null
    Reset-ExNotifications

    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex',
        'ticket7 chain') -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    Invoke-ExDebugAction -Action 'POST_TEST'

    $onRear = Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 20
    $snapshot = Get-ExRearSnapshot
    $summary = Get-ExRearCueSummary

    $scenarioVerdicts = [ordered]@{
        'phone-unlocked'    = $unlocked
        'effect-launch'     = $summary.LaunchRequested
        'projection-sent'   = $summary.LaunchSent
        'projection-attach' = $summary.LaunchConfirmed
        'dashboard-on-rear' = $onRear
        'icon-set-two'      = ((@($summary.LastIconSet) -contains 'com.android.shell') -and
            (@($summary.LastIconSet) -contains 'com.rearcue.poc'))
    }
    Write-ExScenarioReport -Name 'e1-drive' -ScenarioVerdicts $scenarioVerdicts -Note @(
        'method: cmd notification post (com.android.shell) + debug POST_TEST, no manual touch',
        ('icon set now: {0}' -f ($summary.LastIconSet -join ',')),
        ('keyguard locked: {0}' -f (Test-ExKeyguardLocked))
    )

    if ($onRear) {
        Add-ExPhotoCheckpoint -Id 1 -Title 'Dashboard first on the rear screen' -Slug 'first-launch' `
            -What 'pure black + clock + the two app icons' `
            -Evidence ('owner=dashboard state={0} iconSet={1}' -f $snapshot.State, ($summary.LastIconSet -join ','))
    }
    return @{ OnRear = $onRear }
}

function Invoke-ExScenarioE7 {
    Write-ExNote 'E7: last notification removed -> Dashboard exits -> Native Rear Screen restored'
    if ((Get-ExRearOwnerNow) -ne 'dashboard') {
        Write-ExNote 'nothing on the rear screen: running E1 first so there is a Dashboard to take down'
        Invoke-ExScenarioE1 | Out-Null
    }

    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Start-Sleep -Seconds 3
    $midOwner = Get-ExRearOwnerNow

    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
    $native = Wait-ExRearOwner -Owner 'native' -TimeoutSec 20

    $summary = Get-ExRearCueSummary
    $scenarioVerdicts = [ordered]@{
        'update-not-relaunch' = ($summary.IconSetUpdates -ge 1)
        'stayed-while-notify' = ($midOwner -eq 'dashboard')
        'exit-effect'         = $summary.ExitRequested
        'dashboard-detached'  = $summary.Exited
        'native-restored'     = $native
    }
    Write-ExScenarioReport -Name 'e7-drive' -ScenarioVerdicts $scenarioVerdicts -Note @(
        'method: debug CANCEL_TEST then CANCEL_PACKAGE com.android.shell (no touch)',
        'the process keeps running: the listener must survive the down-screen (ticket #5)'
    )
    return @{ Native = $native }
}

function Invoke-ExScenarioE3Lock {
    Write-ExNote ('E3/E4/E6: lock the phone while the Dashboard is on the rear screen ({0}s watch)' -f $LockSeconds)
    if ((Get-ExRearOwnerNow) -ne 'dashboard') {
        Write-ExNote 'nothing on the rear screen: running E1 first (the Dashboard has to be up before locking)'
        Invoke-ExScenarioE1 | Out-Null
    }
    Start-Sleep -Seconds 2
    $before = Get-ExRearSnapshot
    Add-ExPhotoCheckpoint -Id 2 -Title 'Rear screen after locking the main screen' -Slug 'locked-30s' `
        -What 'the Dashboard still there (clock + icons) while the main screen is off' `
        -Evidence ('owner={0} state={1}' -f $before.Owner, $before.State)

    # The lock has to be deliberate: KEYCODE_POWER toggles, so a screen that already fell asleep
    # would be woken instead of locked. Wake it first, then lock and verify.
    if ((Get-ExWakefulness) -ne 'Awake') {
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
        Invoke-Adb -Arguments @('shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
        Start-Sleep -Seconds 3
    }
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    if ((Get-ExWakefulness) -eq 'Awake') {
        Write-ExNote 'still awake after KEYCODE_POWER: sending it once more'
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
        Start-Sleep -Seconds 2
    }
    Write-ExNote ('wakefulness while locked: {0}' -f (Get-ExWakefulness))

    # Checkpoint 3 is the swap to the native AOD: it happens in the first seconds after locking,
    # so it is recorded here rather than after the watch loop (the loop only reports what it saw).
    Add-ExPhotoCheckpoint -Id 3 -Title 'The moment the native AOD takes the rear screen back' -Slug 'aod-takeover' `
        -What 'the swap from Dashboard to the native rear clock/AOD (shoot right after locking)' `
        -Evidence ('locked; takeover signals counted from the logcat below')

    $start = Get-Date
    $deadline = $start.AddSeconds($LockSeconds)
    $samples = New-Object System.Collections.Generic.List[string]
    $dashboardHeld = $true
    $firstSleep = $null
    $sampleIndex = 0
    do {
        Start-Sleep -Seconds $SampleSeconds
        $sampleIndex++
        $elapsed = [int]((Get-Date) - $start).TotalSeconds
        $snapshot = Get-ExRearSnapshot
        $lastLog = @(Get-ExLogcat | Where-Object { $_ -match 'RearCue\s*: ' } | Select-Object -Last 1)
        $samples.Add(('[+{0,4}s] owner={1,-9} rear={2,-18} last={3}' -f $elapsed, $snapshot.Owner, $snapshot.State,
                (($lastLog -join '') -replace '^\S+ \S+ \d+ \d+ ', '')))
        if ($snapshot.Owner -ne 'dashboard') { $dashboardHeld = $false }
        if (-not $firstSleep -and $snapshot.Rear -and $snapshot.Rear.State -ne 'ON') { $firstSleep = $elapsed }
    } while ((Get-Date) -lt $deadline)

    $summary = Get-ExRearCueSummary
    $projectAttempts = @($summary.Kinds | Where-Object { $_ -eq 'project' }).Count
    $signals = @($summary.SignalActions | Select-Object -Unique)

    $scenarioVerdicts = [ordered]@{
        'dashboard-held'  = $dashboardHeld
        'samples'         = $sampleIndex
        'takeover-signal' = ($signals.Count -gt 0)
        'project-attempts' = $projectAttempts
        'first-non-on-s'  = $firstSleep
    }

    if (-not $NoUnlock) {
        Write-ExNote 'waking the main screen again and dismissing the keyguard'
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
        Invoke-Adb -Arguments @('shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
        Start-Sleep -Seconds 2
    }

    Write-ExScenarioReport -Name 'e3-lock' -ScenarioVerdicts $scenarioVerdicts -Note @(
        ('lock method: input keyevent KEYCODE_POWER; sampled every {0}s' -f $SampleSeconds),
        'photo checkpoint 2/3 are recorded above (rear screen cannot be captured by screencap on this build)'
    )
    $samples.Insert(0, ('# samples ({0})' -f $sampleIndex))
    Write-ExArtifact -Name 'e3-lock.txt' -Lines (@('') + $samples.ToArray()) -Append | Out-Null
    return @{ Held = $dashboardHeld }
}

function Write-ExPhotoCheckpoints {
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('# photo checkpoints (spec 0001: three manual shots)')
    $lines.Add('')
    $lines.Add('The HyperOS build on this device cannot capture the rear screen (`screencap -d 1` ->')
    $lines.Add('"Display Id ''1'' is not valid"), so visual proof is a human photo. Each checkpoint below')
    $lines.Add('records the moment the script reached it, what the rear screen showed, and where the photo')
    $lines.Add('belongs. Checkpoints not reached in this run stay listed with an empty "reached".')
    $lines.Add('')
    $lines.Add('| # | checkpoint | target file | reached |')
    $lines.Add('|---|---|---|---|')
    $planned = @(
        @{ Id = 1; Title = 'Dashboard first on the rear screen'; Slug = 'first-launch' },
        @{ Id = 2; Title = 'Rear screen after locking the main screen'; Slug = 'locked-30s' },
        @{ Id = 3; Title = 'The moment the native AOD takes the rear screen back'; Slug = 'aod-takeover' }
    )
    foreach ($plan in $planned) {
        $hit = $script:ExPhotoCheckpoints | Where-Object { $_.Id -eq $plan.Id } | Select-Object -First 1
        $reached = if ($hit) { $hit.At } else { '-' }
        $lines.Add(('| {0} | {1} | manual-photos/photo-0{0}-{2}.jpg | {3} |' -f $plan.Id, $plan.Title,
                $plan.Slug, $reached))
    }
    $lines.Add('')
    foreach ($checkpoint in $script:ExPhotoCheckpoints) {
        $lines.Add(('## {0}. {1}' -f $checkpoint.Id, $checkpoint.Title))
        $lines.Add(('- reached : {0}' -f $checkpoint.At))
        $lines.Add(('- shoot   : {0}' -f $checkpoint.What))
        $lines.Add(('- evidence: {0}' -f $checkpoint.Evidence))
        $lines.Add(('- save as : {0}' -f $checkpoint.Target))
        $lines.Add('')
    }
    Write-ExArtifact -Name 'photo-checkpoints.md' -Lines $lines.ToArray() | Out-Null
    Write-ExNote ('photo checkpoints recorded: {0}' -f $script:ExPhotoDir)
}

foreach ($name in $Scenario) {
    switch ($name) {
        'e1' { Invoke-ExScenarioE1 | Out-Null }
        'e7' { Invoke-ExScenarioE7 | Out-Null }
        'e3-lock' { Invoke-ExScenarioE3Lock | Out-Null }
    }
}

# Leave the phone in a clean state: no leftover test notifications, nothing on the rear screen.
# -HoldDashboard skips the cleanup on purpose: the Dashboard stays up so the operator can take
# photo checkpoint 1 before the next command takes it down again.
if (-not $HoldDashboard) { Reset-ExNotifications }
Write-ExPhotoCheckpoints
Write-ExNote ('04-drive done: ' + (($verdicts.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }) -join ' '))
return $verdicts
