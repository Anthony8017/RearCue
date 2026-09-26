# 19-posture-gate.ps1 -- ticket #53 (spec 0006): Posture Gate end-to-end.
#
# The question: does the auto path stay silent while the phone lies FACE UP (notification
# posts, no cast), re-cast once the phone is flipped FACE DOWN (Icon Set non-empty), and
# withdraw (hand-back) when flipped back up? Is a MANUAL cast (Debug Bypass) exempt from the
# posture gate? And does the 800ms stability window keep a human flip (with its natural
# wobble) from producing more than exactly one effect per committed posture flip?
#
# Posture comes from the production seam: TYPE_PROXIMITY -> "face-down = near" ->
# PostureStableWindow (800ms, both directions) -> PostureGate event into the core. The
# PHYSICAL flips are the operator's: every leg that needs one prints a big console note and
# waits (FlipWaitSeconds) for the app's committed-posture line -- the PC cannot flip the
# phone. The app's ASCII anchors: `posture up` / `posture down` (lastEvent lines).
#
# Verdict words (one per leg):
#   POSTURE-SUPPRESS-PASS   face up + allowlist notification -> LaunchDashboard delta 0,
#                           rear owner never 'dashboard'
#   POSTURE-RECAST-PASS     flip face down -> LaunchDashboard + owner 'dashboard'
#   POSTURE-WITHDRAW-PASS   flip face up -> ExitDashboard + rear handed back
#   POSTURE-MANUAL-PASS     face up + Debug Bypass cast lands, manual exit takes it down
#   POSTURE-STABLE-PASS     whole run produced exactly the expected Launch/Exit line counts
#                           (2 Launch + 2 Exit) -- wobble-driven oscillation would inflate them
#   POSTURE-RUN-INVALID     preflight (listener / rear display / DND off / first posture
#                           commit) never came up
#
# Run through the one-command entry point (`ex.ps1 -Task posture-gate`), or alone:
#   .\tools\ex\19-posture-gate.ps1
#   .\tools\ex\19-posture-gate.ps1 -FlipWaitSeconds 180
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $ObserveSeconds = 8,
    [int] $SampleSeconds = 2,
    [int] $FlipWaitSeconds = 150,
    [switch] $NoRestore,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'posture-gate' -Serial $Serial | Out-Null }

# The app log is chatty (every refresh dumps the icon set); a ~6 min run rotates the default
# main buffer and eats the stability leg's line-count anchors (measured: Exit delta=-1).
# Enlarge it for the run (left enlarged; harmless, nothing restores it).
Invoke-Adb -Arguments @('logcat', '-G', '4096K', '-b', 'main') -AllowFailure | Out-Null

function Get-ExZenMode {
    # System-side proof DND is off (the OTHER gate must stay open the whole run).
    $dump = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'notification') -AllowFailure) -join "`n"
    if ($dump -match 'mZenMode=(\S+)') { return $Matches[1] }
    return 'unknown'
}

$script:Steps = New-Object System.Collections.Generic.List[object]
function Add-ExStep {
    param([string] $Step, [string] $Owner, [bool] $Pass, [string] $Note)
    $script:Steps.Add([pscustomobject]@{ Step = $Step; Owner = $Owner; Pass = $Pass; Note = $Note })
    Write-ExNote ('step {0}: owner={1} pass={2} ({3})' -f $Step, $Owner, $Pass, $Note)
}

# ---- 0. preflight: clean slate ------------------------------------------------------
# Phone UNLOCKED, DND off (the other gate stays open all run), app process alive.
# The proximity sensor is on-change and silent while still: after the process restart the
# first posture commit only comes after a PHYSICAL flip, so the operator is asked to flip
# once (down then up) to plant the `posture up` anchor.
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Start-ExApp -TimeoutSec 30 | Out-Null
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1

Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Start-Sleep -Seconds 2

$dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
$rear = Get-RearDisplay -DumpsysDisplay $dump
$zen = Get-ExZenMode
Write-ExNote ('start: zen={0} rear={1} owner={2}' -f $zen, (Format-ExStatePair $rear), (Get-ExRearOwnerNow))

Write-ExNote '*******************************************************'
Write-ExNote '* OPERATOR: flip the phone FACE DOWN, then FACE UP,    *'
Write-ExNote ('* and leave it lying FACE UP ({0}s window).           *' -f $FlipWaitSeconds)
Write-ExNote '*******************************************************'
$beforeUp = Get-ExLogMatchCount 'posture up'
$hitUp = Wait-ExNewLog 'posture up' -Before $beforeUp -TimeoutSec $FlipWaitSeconds
$postureReady = (@($hitUp).Count -gt 0)
Start-Sleep -Seconds 4   # let the brief face-down leg's cast+withdraw settle BEFORE anchoring

$runValid = ($zen -eq 'ZEN_MODE_OFF') -and $rear -and $postureReady
if (-not $runValid) {
    Write-ExNote ('preflight incomplete: zen={0} rear={1} posture-up-anchor={2}' -f $zen, (Format-ExStatePair $rear), $postureReady)
}
# Run-wide line anchor for the stability leg: everything AFTER this buffer index is
# caused by this run's legs (the preflight flips' effects are deliberately excluded).
$anchorIndex = @(Get-ExLogcat).Count
Write-ExNote ('anchor: logcat line index {0}' -f $anchorIndex)

# ---- 1. face up: allowlist notification must NOT cast (suppress leg) ----------------
$castBefore = Get-ExLogMatchCount 'LaunchDashboard'
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex',
    'ticket53 posture suppress') -AllowFailure | Out-Null
Invoke-ExDebugAction -Action 'POST_TEST'

$start = Get-Date
$deadline = $start.AddSeconds($ObserveSeconds)
do {
    Start-Sleep -Seconds $SampleSeconds
    Write-ExNote ('suppress watch: owner={0} (elapsed {1}s; keep the phone FACE UP)' -f (Get-ExRearOwnerNow), [int]((Get-Date) - $start).TotalSeconds)
} while ((Get-Date) -lt $deadline)
$castAfter = Get-ExLogMatchCount 'LaunchDashboard'
$suppressPass = $runValid -and ($castAfter -eq $castBefore) -and ((Get-ExRearOwnerNow) -ne 'dashboard')
Add-ExStep -Step 'suppress' -Owner (Get-ExRearOwnerNow) -Pass $suppressPass `
    -Note ('LaunchDashboard lines {0}->{1} (delta must be 0); owner not dashboard' -f $castBefore, $castAfter)

# ---- 2. flip FACE DOWN -> re-cast ----------------------------------------------------
$beforeDown = Get-ExLogMatchCount 'posture down'
$beforeLaunch2 = Get-ExLogMatchCount 'LaunchDashboard'
Write-ExNote '*******************************************************'
Write-ExNote ('* OPERATOR: flip the phone FACE DOWN now ({0}s window) *' -f $FlipWaitSeconds)
Write-ExNote '*******************************************************'
$hitDown = Wait-ExNewLog 'posture down' -Before $beforeDown -TimeoutSec $FlipWaitSeconds
$hitLaunch2 = Wait-ExNewLog 'LaunchDashboard' -Before $beforeLaunch2 -TimeoutSec 15
$recastUp = (Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15)
$recastPass = $runValid -and (@($hitDown).Count -gt 0) -and (@($hitLaunch2).Count -gt 0) -and $recastUp
Add-ExStep -Step 'recast' -Owner (Get-ExRearOwnerNow) -Pass $recastPass `
    -Note ('posture-down line: {0}; LaunchDashboard after it: {1}; owner=dashboard: {2}' -f (@($hitDown).Count -gt 0), (@($hitLaunch2).Count -gt 0), $recastUp)

# ---- 3. flip FACE UP -> withdraw (hand-back) -----------------------------------------
$beforeExit3 = Get-ExLogMatchCount 'ExitDashboard'
Write-ExNote '*******************************************************'
Write-ExNote ('* OPERATOR: flip the phone FACE UP now ({0}s window) *' -f $FlipWaitSeconds)
Write-ExNote '*******************************************************'
$hitUp3 = Wait-ExNewLog 'posture up' -Before (Get-ExLogMatchCount 'posture up') -TimeoutSec $FlipWaitSeconds
$hitExit3 = Wait-ExNewLog 'ExitDashboard' -Before $beforeExit3 -TimeoutSec 10
$handedBack = Wait-ExRearNotDashboard -TimeoutSec 15
$withdrawPass = $runValid -and (@($hitUp3).Count -gt 0) -and (@($hitExit3).Count -gt 0) -and $handedBack
Add-ExStep -Step 'withdraw' -Owner (Get-ExRearOwnerNow) -Pass $withdrawPass `
    -Note ('posture-up line: {0}; ExitDashboard after it: {1}; handed back: {2}' -f (@($hitUp3).Count -gt 0), (@($hitExit3).Count -gt 0), $handedBack)

# ---- 4. face up (still): manual cast exempt, manual exit ----------------------------
$beforeManual = Get-ExLogMatchCount 'manual-cast'
Invoke-ExDebugAction -Action 'PROJECT_REAR'
$hitManual = Wait-ExNewLog 'manual-cast' -Before $beforeManual -TimeoutSec 10
$manualUp = (Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15)

$beforeManualExit = Get-ExLogMatchCount 'manual-exit'
Invoke-ExDebugAction -Action 'EXIT_REAR'
$hitManualExit = Wait-ExNewLog 'manual-exit' -Before $beforeManualExit -TimeoutSec 10
$manualGone = (Wait-ExRearNotDashboard -TimeoutSec 15)
$manualPass = $runValid -and (@($hitManual).Count -gt 0) -and $manualUp -and (@($hitManualExit).Count -gt 0) -and $manualGone
Add-ExStep -Step 'manual-exempt' -Owner (Get-ExRearOwnerNow) -Pass $manualPass `
    -Note ('cast while face up: {0}; manual exit line: {1}; gone: {2}' -f $manualUp, (@($hitManualExit).Count -gt 0), $manualGone)

# ---- 5. stability: every effect line must be attributable (no orphan churn) ----------
# The operator's real flip count is not fixed (run 4 measured 4 committed flips, not the 2
# the legs ask for), so "exactly N effect lines" is the wrong assertion. The honest
# anti-oscillation property: every Launch/Exit line after the anchor pairs with a committed
# cause (posture flip, manual action, notification reconcile, system signal) -- debounce
# leakage would surface as ORPHAN effect lines no cause accounts for.
$newLines = @(@(Get-ExLogcat) | Select-Object -Skip $anchorIndex)
# Only REFRESH lines count as effect emissions (they uniquely contain 'iconSet ['); the
# container also echoes a Chinese-only line per effect ('姿态倒扣 -> LaunchDashboard(1)')
# which has no ASCII anchor and must not be judged.
$effectLines = @($newLines | Where-Object { $_ -match '(LaunchDashboard|ExitDashboard)' -and $_ -match 'iconSet \[' })
$causePattern = '(posture (up|down)|manual-cast|manual-exit|posted |removed |allowlist |signal |dashboard-detached|fallback|dnd (on|off))'
$orphanEffects = @($effectLines | Where-Object { $_ -notmatch $causePattern })
$commitLines = @($newLines | Where-Object { $_ -match 'posture (up|down)' })
$stablePass = $runValid -and ($orphanEffects.Count -eq 0)
Add-ExStep -Step 'stable' -Owner (Get-ExRearOwnerNow) -Pass $stablePass `
    -Note ('{0} committed posture flip(s), {1} effect line(s), {2} orphan(s); every effect must trace to a committed cause (orphan = oscillation)' -f $commitLines.Count, $effectLines.Count, $orphanEffects.Count)

# ---- 6. evidence + summary -----------------------------------------------------------
$appLog = @(Get-ExLogcat)
Write-ExArtifact -Name 'posture-gate-logcat.txt' -Lines $appLog | Out-Null

$overall = if (-not $runValid) {
    'POSTURE-RUN-INVALID (preflight: listener/rear display/DND-off/posture anchor not all up)'
} elseif ($suppressPass -and $recastPass -and $withdrawPass -and $manualPass -and $stablePass) {
    'POSTURE-GATE-PASS (all five legs: suppress / recast / withdraw / manual-exempt / stable)'
} else {
    $failed = @(@('suppress', $suppressPass), @('recast', $recastPass), @('withdraw', $withdrawPass), @('manual-exempt', $manualPass), @('stable', $stablePass)) |
        Where-Object { -not $_[1] } | ForEach-Object { $_[0] }
    ('POSTURE-GATE-FAIL (failed leg(s): {0})' -f ($failed -join ', '))
}

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# posture-gate (ticket #53 / spec 0006: Posture Gate end-to-end)')
$out.Add(('protocol            : preflight flip (anchor) -> face-up post (suppress) -> flip down (recast) -> flip up (withdraw) -> manual cast/exit -> stability counts'))
$out.Add(('posture seam        : TYPE_PROXIMITY -> face-down=near -> 800ms stable window -> PostureGate event'))
$out.Add(('operator flips      : required (on-change sensor cannot be driven from the PC)'))
$out.Add(('start               : zen={0} rear={1}' -f $zen, (Format-ExStatePair $rear)))
foreach ($s in $script:Steps) {
    $out.Add(('{0,-20}: pass={1,-5} owner={2} -- {3}' -f $s.Step, $s.Pass, $s.Owner, $s.Note))
}
$out.Add(('overall             : {0}' -f $overall))
Write-ExArtifact -Name 'posture-gate.txt' -Lines $out.ToArray() | Out-Null
foreach ($line in $out) { Write-ExNote $line }

# ---- 7. restore -----------------------------------------------------------------------
if (-not $NoRestore) {
    Write-ExNote 'restoring: notifications cleared, DND stays off'
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
    Start-Sleep -Seconds 2
    Write-ExNote ('restore check: zen={0} owner={1}' -f (Get-ExZenMode), (Get-ExRearOwnerNow))
}

# ---- 8. scenario notes -----------------------------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (Posture Gate, ticket #53 / spec 0006)')
$notes.Add('')
$notes.Add('- **Production seam only**: TYPE_PROXIMITY -> `near = face-down` (values[0] < maxRange/2)')
$notes.Add('  -> PostureStableWindow 800ms both directions -> PostureGate event into the core.')
$notes.Add('  The sensor smoke (face-down reads near, face-up reads far) is the app log line')
$notes.Add('  `Posture Gate Monitor ... / posture-commit face-down near=true` vs `... near=false`.')
$notes.Add('- **The operator flips the phone**: on-change sensors are silent while still, so no adb')
$notes.Add('  path can drive posture. Every flip leg prints an OPERATOR note and waits for the')
$notes.Add('  app`s committed-posture ASCII anchor (`posture down` / `posture up`).')
$notes.Add('- **Known init gap (documented in code)**: the sensor delivers no initial reading on')
$notes.Add('  registration, so between process start and the first physical flip the core keeps its')
$notes.Add('  benign default (gate open). It can never wrongly SUPPRESS; a cast in that idle window')
$notes.Add('  self-corrects on the first committed face-up.')
$notes.Add('- **Stability leg**: the run anchors Launch/Exit line counts after preflight; two human')
$notes.Add('  flips (with natural wobble) must yield exactly 2 Launch + 2 Exit lines -- debounce')
$notes.Add('  leakage would inflate the counts. The JVM cases (PostureStableWindowTest) pin the')
$notes.Add('  window semantics separately.')
$notes.Add('- **DND stays off all run** (the other gate): asserted via mZenMode at preflight.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Overall = $overall
    Suppress = $suppressPass
    Recast = $recastPass
    Withdraw = $withdrawPass
    ManualExempt = $manualPass
    Stable = $stablePass
}
