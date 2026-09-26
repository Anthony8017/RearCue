# 18-dnd-follow.ps1 -- ticket #52 (spec 0006): DND Follow end-to-end + cast source tags.
#
# The question: with DND on, is the notification-driven auto path fully silent (no new cast,
# the on-screen AUTO dashboard withdrawn with hand-back)? With DND off and the Icon Set
# non-empty, does it re-cast? Is a MANUAL cast (Debug Bypass) exempt -- cast despite DND and
# never withdrawn by the automatic logic (clearing the notifications must NOT exit it)?
#
# DND is driven through the public surface `cmd notification set_dnd priority|all` (no root,
# no new grant); the app hears it via NotificationListenerService.onInterruptionFilterChanged
# (the exact production seam, spec 0006: zero new permissions, no polling).
#
# Verdict words (one per leg):
#   DND-SUPPRESS-PASS   DND on first -> allowlist notification -> no LaunchDashboard effect
#                       and the rear owner never became 'dashboard'
#   DND-WITHDRAW-PASS   auto dashboard on the rear -> DND on -> ExitDashboard effect and the
#                       rear handed back (owner leaves 'dashboard')
#   DND-RECAST-PASS     DND off while the Icon Set is still non-empty -> auto re-cast, owner
#                       back to 'dashboard'
#   MANUAL-EXEMPT-PASS  under DND: Debug Bypass cast lands (manual), clearing the
#                       notifications does NOT exit it, EXIT_REAR (manual exit) does
#   Any leg failing -> the same word with -FAIL and the reason; DND-RUN-INVALID when the
#   preflight (listener / rear display / DND toggle) never came up.
#
# Run through the one-command entry point (`ex.ps1 -Task dnd-follow`), or alone:
#   .\tools\ex\18-dnd-follow.ps1
#   .\tools\ex\18-dnd-follow.ps1 -ObserveSeconds 12
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $ObserveSeconds = 8,
    [int] $SampleSeconds = 2,
    [switch] $NoRestore,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'dnd-follow' -Serial $Serial | Out-Null }

# ---- helpers ------------------------------------------------------------------------

function Set-ExDnd {
    # `priority` = DND on (interruption filter PRIORITY); `all` = filter ALL = DND off.
    param([Parameter(Mandatory)][ValidateSet('on', 'off')][string] $State)
    $word = if ($State -eq 'on') { 'priority' } else { 'all' }
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', $word) -AllowFailure | Out-Null
}

function Get-ExZenMode {
    # System-side proof the toggle really took (never judge by the app log alone).
    $dump = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'notification') -AllowFailure) -join "`n"
    if ($dump -match 'mZenMode=(\S+)') { return $Matches[1] }
    return 'unknown'
}

# line-count-anchored waits (Get-ExLogMatchCount / Wait-ExNewLog / Wait-ExRearNotDashboard)
# live in ExCommon since ticket #53 -- the anchors must be captured BEFORE each trigger.

function Get-ExDndSnapshot {
    $dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
    $rear = Get-RearDisplay -DumpsysDisplay $dump
    [pscustomobject]@{
        Zen    = (Get-ExZenMode)
        Rear   = (Format-ExStatePair $rear)
        RearId = if ($rear) { $rear.DisplayId } else { $null }
        Owner  = if ($rear) { Get-ExRearOwnerNow -DisplayId $rear.DisplayId } else { 'none' }
    }
}

$script:Steps = New-Object System.Collections.Generic.List[object]
function Add-ExStep {
    param([string] $Step, [string] $Zen, [string] $Owner, [bool] $Pass, [string] $Note)
    $script:Steps.Add([pscustomobject]@{ Step = $Step; Zen = $Zen; Owner = $Owner; Pass = $Pass; Note = $Note })
    Write-ExNote ('step {0}: zen={1} owner={2} pass={3} ({4})' -f $Step, $Zen, $Owner, $Pass, $Note)
}

# ---- 0. preflight: clean slate ------------------------------------------------------
# Phone UNLOCKED, main screen on, app process alive (the notification listener drives the
# chain), Shizuku UserService bound (the exit/hand-back leg runs shell commands).
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
if (Test-ExKeyguardLocked) { Write-ExNote 'WARNING: keyguard still locked; the run continues but unlock-leg facts may be polluted' }

Start-ExApp -TimeoutSec 30 | Out-Null
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1

$fallbackReady = $false
for ($i = 0; $i -lt 60; $i++) {
    $logNow = @(Get-ExLogcat)
    if (@($logNow | Where-Object { $_ -match 'granted=true userService=true' }).Count -gt 0) { $fallbackReady = $true; break }
    Start-Sleep -Seconds 1
}
Write-ExNote ('fallback-ready: {0} (app reports granted=true userService=true; informational only --' -f $fallbackReady)
Write-ExNote '  the hand-back legs prove shell access by succeeding)'

Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Set-ExDnd -State off
Start-Sleep -Seconds 2

$snap0 = Get-ExDndSnapshot
Write-ExNote ('start: zen={0} rear={1} owner={2}' -f $snap0.Zen, $snap0.Rear, $snap0.Owner)
# An on-screen dashboard at start is FINE (real allowlist notifications keep the Icon Set
# non-empty and cannot be cancelled from the PC): the suppress leg's first DND-on withdraws
# an AUTO one, and its gates (LaunchDashboard delta 0 + owner not dashboard) hold either way.
$runValid = ($snap0.Zen -eq 'ZEN_MODE_OFF') -and $snap0.RearId
if (-not $runValid) {
    Write-ExNote ('preflight incomplete: zen={0} rear={1} owner={2}' -f $snap0.Zen, $snap0.Rear, $snap0.Owner)
}
Write-ExNote ('protocol: DND on -> post (suppress) -> DND off (recast) -> DND on (withdraw) -> DND on + manual cast -> clear (must stay) -> manual exit' )

# ---- 1. DND on FIRST, then one allowlist notification (suppress leg) ----------------
$beforeDndOn = Get-ExLogMatchCount 'dnd on'
Set-ExDnd -State on
$hitDndOn = Wait-ExNewLog 'dnd on' -Before $beforeDndOn -TimeoutSec 10
$zenOn = Get-ExZenMode
Write-ExNote ('zen after set_dnd priority: {0}; app dnd-on line: {1}' -f $zenOn, (@($hitDndOn).Count -gt 0))

# line-count anchors (NOT index search: the logcat buffer survives app restarts, so stale
# LaunchDashboard lines from an earlier session are always in the buffer -- only the DELTA counts).
$castCountBefore = Get-ExLogMatchCount 'LaunchDashboard'
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex',
    'ticket52 dnd suppress') -AllowFailure | Out-Null
Invoke-ExDebugAction -Action 'POST_TEST'

$start = Get-Date
$deadline = $start.AddSeconds($ObserveSeconds)
do {
    Start-Sleep -Seconds $SampleSeconds
    Write-ExNote ('suppress watch: owner={0} (elapsed {1}s)' -f (Get-ExRearOwnerNow), [int]((Get-Date) - $start).TotalSeconds)
} while ((Get-Date) -lt $deadline)
$castCountAfter = Get-ExLogMatchCount 'LaunchDashboard'
$noCastAfterDnd = ($castCountAfter -eq $castCountBefore)
$dndOnSeen = (@($hitDndOn).Count -gt 0)
$suppressPass = $runValid -and $dndOnSeen -and $noCastAfterDnd -and ((Get-ExRearOwnerNow) -ne 'dashboard')
Add-ExStep -Step 'suppress' -Zen $zenOn -Owner (Get-ExRearOwnerNow) -Pass $suppressPass `
    -Note ('dnd-on line seen: {0}; LaunchDashboard lines {1}->{2} (delta must be 0); owner not dashboard' -f $dndOnSeen, $castCountBefore, $castCountAfter)

# ---- 2. DND off -> auto cast (recast prerequisite), then DND on -> withdraw ---------
$beforeDndOff2 = Get-ExLogMatchCount 'dnd off'
$beforeCast2 = Get-ExLogMatchCount 'LaunchDashboard'
Set-ExDnd -State off
$hitDndOff = Wait-ExNewLog 'dnd off' -Before $beforeDndOff2 -TimeoutSec 10
$zenOff = Get-ExZenMode
$hitLaunch1 = Wait-ExNewLog 'LaunchDashboard' -Before $beforeCast2 -TimeoutSec 15
$autoCastUp = (Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15)
Write-ExNote ('recast-prereq: dnd-off line={0} LaunchDashboard line={1} owner-dashboard={2}' -f
    (@($hitDndOff).Count -gt 0), (@($hitLaunch1).Count -gt 0), $autoCastUp)

$beforeExit2 = Get-ExLogMatchCount 'ExitDashboard'
Set-ExDnd -State on
$hitWithdraw = Wait-ExNewLog 'ExitDashboard' -Before $beforeExit2 -TimeoutSec 10
$zenOn2 = Get-ExZenMode
$handedBack = Wait-ExRearNotDashboard -TimeoutSec 15
$withdrawPass = $runValid -and $autoCastUp -and (@($hitWithdraw).Count -gt 0) -and $handedBack
Add-ExStep -Step 'withdraw' -Zen $zenOn2 -Owner (Get-ExRearOwnerNow) -Pass $withdrawPass `
    -Note ('auto cast was up: {0}; ExitDashboard after dnd-on: {1}; handed back: {2}' -f $autoCastUp, (@($hitWithdraw).Count -gt 0), $handedBack)

# ---- 3. DND off again, Icon Set still non-empty -> recast ---------------------------
$beforeDndOff3 = Get-ExLogMatchCount 'dnd off'
$beforeCast3 = Get-ExLogMatchCount 'LaunchDashboard'
Set-ExDnd -State off
$hitDndOff3 = Wait-ExNewLog 'dnd off' -Before $beforeDndOff3 -TimeoutSec 10
$zenOff2 = Get-ExZenMode
$hitLaunch2 = Wait-ExNewLog 'LaunchDashboard' -Before $beforeCast3 -TimeoutSec 15
$recastUp = (Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15)
$recastPass = $runValid -and (@($hitDndOff3).Count -gt 0) -and (@($hitLaunch2).Count -gt 0) -and $recastUp
Add-ExStep -Step 'recast' -Zen $zenOff2 -Owner (Get-ExRearOwnerNow) -Pass $recastPass `
    -Note ('LaunchDashboard after dnd-off: {0}; owner=dashboard: {1}' -f (@($hitLaunch2).Count -gt 0), $recastUp)

# ---- 4. manual exempt: DND stays ON this time ----------------------------------------
# (the recast above left an AUTO dashboard up; DND is still off here, so switch it on first)
$beforeExit4 = Get-ExLogMatchCount 'ExitDashboard'
Set-ExDnd -State on
$null = Wait-ExNewLog 'ExitDashboard' -Before $beforeExit4 -TimeoutSec 10
$null = Wait-ExRearNotDashboard -TimeoutSec 15   # the auto dashboard withdraws again
$zenOn3 = Get-ExZenMode

# manual cast through the Debug Bypass (the same seam the debug-page button uses).
$beforeManual = Get-ExLogMatchCount 'manual-cast'
Invoke-ExDebugAction -Action 'PROJECT_REAR'
$hitManual = Wait-ExNewLog 'manual-cast' -Before $beforeManual -TimeoutSec 10
$manualUp = (Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15)

# clearing every notification must NOT exit a manual cast.
$exitBefore = Get-ExLogMatchCount 'ExitDashboard'
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Start-Sleep -Seconds 6
$exitAfter = Get-ExLogMatchCount 'ExitDashboard'
$stillUpAfterClear = ((Get-ExRearOwnerNow) -eq 'dashboard')
$noAutoExitOfManual = ($exitAfter -eq $exitBefore) -and $stillUpAfterClear

# manual exit does exit it.
$beforeManualExit = Get-ExLogMatchCount 'manual-exit'
Invoke-ExDebugAction -Action 'EXIT_REAR'
$hitManualExit = Wait-ExNewLog 'manual-exit' -Before $beforeManualExit -TimeoutSec 10
$manualGone = (Wait-ExRearNotDashboard -TimeoutSec 15)
$manualPass = $runValid -and (@($hitManual).Count -gt 0) -and $manualUp -and $noAutoExitOfManual -and (@($hitManualExit).Count -gt 0) -and $manualGone
Add-ExStep -Step 'manual-exempt' -Zen $zenOn3 -Owner (Get-ExRearOwnerNow) -Pass $manualPass `
    -Note ('cast under DND: {0}; stays after clear: {1} (ExitDashboard {2}->{3}); manual exit line: {4}, gone: {5}' -f $manualUp, $stillUpAfterClear, $exitBefore, $exitAfter, (@($hitManualExit).Count -gt 0), $manualGone)

# ---- 5. evidence + summary -----------------------------------------------------------
$appLog = @(Get-ExLogcat)
Write-ExArtifact -Name 'dnd-follow-logcat.txt' -Lines $appLog | Out-Null

$overall = if (-not $runValid) {
    'DND-RUN-INVALID (preflight: listener/rear display/DND toggle not all up; see notes above)'
} elseif ($suppressPass -and $withdrawPass -and $recastPass -and $manualPass) {
    'DND-FOLLOW-PASS (all four legs: suppress / withdraw / recast / manual-exempt)'
} else {
    $failed = @(@('suppress', $suppressPass), @('withdraw', $withdrawPass), @('recast', $recastPass), @('manual-exempt', $manualPass)) |
        Where-Object { -not $_[1] } | ForEach-Object { $_[0] }
    ('DND-FOLLOW-FAIL (failed leg(s): {0})' -f ($failed -join ', '))
}

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# dnd-follow (ticket #52 / spec 0006: DND Follow end-to-end + cast source tags)')
$out.Add(('protocol            : DND on -> post (suppress) -> DND off (recast) -> DND on (withdraw) -> DND on + manual cast -> clear (must stay) -> manual exit'))
$out.Add(('dnd driving         : cmd notification set_dnd priority|all (public surface, no root)'))
$out.Add(('app seam            : NotificationListenerService.onInterruptionFilterChanged (zero new permissions, no polling)'))
$out.Add(('fallback-ready      : {0}' -f $fallbackReady))
$out.Add(('start               : zen={0} rear={1} owner={2}' -f $snap0.Zen, $snap0.Rear, $snap0.Owner))
foreach ($s in $script:Steps) {
    $out.Add(('{0,-20}: pass={1,-5} zen={2} owner={3} -- {4}' -f $s.Step, $s.Pass, $s.Zen, $s.Owner, $s.Note))
}
$out.Add(('overall             : {0}' -f $overall))
Write-ExArtifact -Name 'dnd-follow.txt' -Lines $out.ToArray() | Out-Null
foreach ($line in $out) { Write-ExNote $line }

# ---- 6. restore -----------------------------------------------------------------------
if (-not $NoRestore) {
    Write-ExNote 'restoring: DND off, notifications cleared'
    Set-ExDnd -State off
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
    Start-Sleep -Seconds 2
    Write-ExNote ('restore check: zen={0} owner={1}' -f (Get-ExZenMode), (Get-ExRearOwnerNow))
}

# ---- 7. scenario notes -----------------------------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (DND Follow, ticket #52 / spec 0006)')
$notes.Add('')
$notes.Add('- **Public surface only**: DND is toggled with `cmd notification set_dnd priority|all`;')
$notes.Add('  the app hears it through the NLS interruption-filter callback -- the exact production')
$notes.Add('  seam the spec mandates (no new permission, no polling).')
$notes.Add('- **Judged from device facts**: `mZenMode` from dumpsys notification (the toggle really')
$notes.Add('  took), rear owner from `dumpsys activity activities` (dashboard / native), and the app`s')
$notes.Add('  ASCII effect lines (`dnd on`, `dnd off`, `LaunchDashboard`, `ExitDashboard`,')
$notes.Add('  `manual-cast`, `manual-exit`) -- the same anchor vocabulary as the E-series.')
$notes.Add('- **Suppress leg ordering**: DND goes on FIRST, then the notification posts; the pass')
$notes.Add('  condition is "no LaunchDashboard line after the dnd-on anchor AND owner never')
$notes.Add('  dashboard" (the notification itself is visible to the listener -- DND does not block')
$notes.Add('  posting, only the cast decision is gated).')
$notes.Add('- **Withdraw = hand-back**: the exit runs the same task-move hand-back as the')
$notes.Add('  notification-clear exit (owner must leave `dashboard`).')
$notes.Add('- **Manual exempt**: Debug Bypass PROJECT_REAR under DND (manual source tag in core),')
$notes.Add('  then EVERY notification is cancelled -- the dashboard must stay (no auto exit);')
$notes.Add('  EXIT_REAR (manual exit) is the only thing that takes it down.')
$notes.Add('- **Source-tag proof on device**: the JVM cases (DashboardCoreTest, 77 green) pin the')
$notes.Add('  auto/manual matrix; this run pins the wired behavior end to end.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Overall = $overall
    Suppress = $suppressPass
    Withdraw = $withdrawPass
    Recast = $recastPass
    ManualExempt = $manualPass
}
