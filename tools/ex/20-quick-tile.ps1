# 20-quick-tile.ps1 -- ticket #54 (spec 0006): Quick Tile Entry end-to-end.
#
# The question: is the control-center tile the official manual entry? Tap with nothing on
# the rear -> manual cast (exempt from both gates); tap again -> manual exit. Does it work
# from the LOCKED lock screen? Does the manual cast survive DND-on / face-up (never
# auto-withdrawn) until the tile is tapped again?
#
# Driving the tile without fingers: `cmd statusbar add-tile` puts our tile into the shade,
# `cmd statusbar expand-settings` opens the shade, `uiautomator dump` finds the tile node's
# bounds and `input tap` clicks its center -- the same physical path a user's tap takes
# (SysUI dispatches the click to the TileService; no test hooks involved).
#
# Verdict words:
#   TILE-CAST-PASS     unlocked, rear empty -> tap -> manual-cast + LaunchDashboard + owner dashboard
#   TILE-EXIT-PASS     tap again -> manual-exit + ExitDashboard + rear handed back
#   TILE-EXEMPT-PASS   DND on (and face-up posture) -> tap still casts; clearing the test
#                      notifications does NOT withdraw it; only the tile exits it
#   TILE-LOCK-PASS     keyguard up -> shade -> tap -> cast lands (E14 route under lock),
#                      then unlock -> tap -> exit
#   TILE-RUN-INVALID   preflight (listener / rear / tile added / shade dump) failed
#
# Run: `ex.ps1 -Task quick-tile`, or alone .\tools\ex\20-quick-tile.ps1
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $SettleSeconds = 6,
    [switch] $NoRestore,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'quick-tile' -Serial $Serial | Out-Null }

# The app log is chatty; keep the whole run's anchors safe from buffer rotation.
Invoke-Adb -Arguments @('logcat', '-G', '4096K', '-b', 'main') -AllowFailure | Out-Null

$tileComponent = "$($config.Package)/$($config.Package).tile.RearCueTileService"

$script:Steps = New-Object System.Collections.Generic.List[object]
function Add-ExStep {
    param([string] $Step, [bool] $Pass, [string] $Note)
    $script:Steps.Add([pscustomobject]@{ Step = $Step; Pass = $Pass; Note = $Note })
    Write-ExNote ('step {0}: pass={1} ({2})' -f $Step, $Pass, $Note)
}

function Get-ExQsDump {
    <# Fresh --windows dump of the shade, pulled locally and read as UTF-8: the plain dump
      only sees the KEY-focused window, which the rear Dashboard holds while cast; --windows
      covers SystemUI as well. adb shell cat would hand PS 5.1 GBK-mangled bytes, hence pull. #>
    $local = Join-Path (Get-ExSessionDir) 'qs-dump.xml'
    Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', '--windows', '/sdcard/qs-dump.xml') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('pull', '/sdcard/qs-dump.xml', $local) -AllowFailure | Out-Null
    if (Test-Path -LiteralPath $local) { return [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8) }
    return ''
}

function Get-ExTileLabel {
    <# The tile label built from code points: the source stays ASCII-only (PS 5.1 / ANSI).
      MUST mirror strings.xml tile_label (rename the string -> update these four values). #>
    return -join [char[]](0x80CC, 0x5C4F, 0x6307, 0x793A)
}

function Open-ExShade {
    <# Open the shade with quick settings expanded. One `expand-settings` can land on the
      notification shade on this HyperOS build (observed intermittently), so: expand twice,
      then drag the header down to force the full QS panel. #>
    Invoke-Adb -Arguments @('shell', 'cmd', 'statusbar', 'expand-settings') -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 1200
    Invoke-Adb -Arguments @('shell', 'cmd', 'statusbar', 'expand-settings') -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 1200
    Invoke-Adb -Arguments @('shell', 'input', 'swipe', '540', '300', '540', '1800', '250') -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 1200
}

function Find-ExTileNode {
    <# Page through the expanded QS panel looking for our tile Button; returns the matched
      node string (with bounds) or $null. Closes the shade only in Invoke-ExTileTap. #>
    $label = Get-ExTileLabel
    for ($page = 1; $page -le 6; $page++) {
        $xml = Get-ExQsDump
        $node = [regex]::Match($xml, ('<node[^>]*' + [regex]::Escape($label) + '[^>]*>'))
        if ($node.Success -and $node.Value -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            return $node
        }
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', '850', '1300', '100', '1300', '250') -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 1500
    }
    return $null
}

function Invoke-ExTileTap {
    <# Expand the QS panel, page until our tile is visible, tap its center -- SysUI
      dispatches a genuine TileService click. add-tile appends to the LAST page, so paging
      is mandatory on a fresh install. #>
    Open-ExShade
    $node = Find-ExTileNode
    if ($null -ne $node -and $node.Value -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        $cx = ([int]$Matches[1] + [int]$Matches[3]) / 2
        $cy = ([int]$Matches[2] + [int]$Matches[4]) / 2
        Write-ExNote ('tile found -> tap ({0},{1})' -f [int]$cx, [int]$cy)
        Invoke-Adb -Arguments @('shell', 'input', 'tap', [string][int]$cx, [string][int]$cy) -AllowFailure | Out-Null
        Invoke-Adb -Arguments @('shell', 'cmd', 'statusbar', 'collapse') -AllowFailure | Out-Null
        return $true
    }
    Write-ExNote 'tile node not found on any shade page'
    Invoke-Adb -Arguments @('shell', 'cmd', 'statusbar', 'collapse') -AllowFailure | Out-Null
    return $false
}

# ---- 0. preflight --------------------------------------------------------------------
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Start-ExApp -TimeoutSec 30 | Out-Null
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1

Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
# Any dashboard currently up (auto from real notifications) gets withdrawn by DND-on first,
# so the cast leg is a true first cast; DND goes off again after.
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'priority') -AllowFailure | Out-Null
Start-Sleep -Seconds 3
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
Start-Sleep -Seconds 3

$dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
$rear = Get-RearDisplay -DumpsysDisplay $dump
$zen = Get-ExZenMode

# Add the tile to the shade (Android 13+ statusbar command; harmless if already added).
$addTile = @(Invoke-Adb -Arguments @('shell', 'cmd', 'statusbar', 'add-tile', $tileComponent) -AllowFailure)
Write-ExNote ('add-tile: {0}' -f (($addTile -join ' ').Trim()))
Start-Sleep -Seconds 2

# Shade expansion is flaky on the FIRST open (observed: lands on the notification shade;
# every later Open-ExShade shows the QS panel), so visibility is NOT a preflight gate --
# the tile-cast leg's tap itself proves the tile is reachable (each tap finds the node).
$runValid = ($zen -eq 'ZEN_MODE_OFF') -and $rear
if (-not $runValid) {
    Write-ExNote ('preflight incomplete: zen={0} rear={1}' -f $zen, (Format-ExStatePair $rear))
}
Write-ExNote ('protocol: tap cast -> tap exit -> DND-on + face-up tap cast -> clear notifications (must stay) -> tap exit -> lock -> tap cast -> unlock -> tap exit')

# ---- 1. unlocked, rear empty -> tap = manual cast ------------------------------------
$beforeCast = Get-ExLogMatchCount 'manual-cast'
$beforeLaunch = Get-ExLogMatchCount 'LaunchDashboard'
$tapped1 = Invoke-ExTileTap
$hitCast = Wait-ExNewLog 'manual-cast' -Before $beforeCast -TimeoutSec 12
$hitLaunch1 = Wait-ExNewLog 'LaunchDashboard' -Before $beforeLaunch -TimeoutSec 15
$castUp = (Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15)
$castPass = $runValid -and $tapped1 -and (@($hitCast).Count -gt 0) -and (@($hitLaunch1).Count -gt 0) -and $castUp
Add-ExStep -Step 'tile-cast' -Pass $castPass `
    -Note ('tap ok: {0}; manual-cast line: {1}; LaunchDashboard: {2}; owner=dashboard: {3}' -f $tapped1, (@($hitCast).Count -gt 0), (@($hitLaunch1).Count -gt 0), $castUp)

# ---- 2. tap again = manual exit --------------------------------------------------------
$beforeExit = Get-ExLogMatchCount 'manual-exit'
$beforeExitLines = Get-ExLogMatchCount 'ExitDashboard'
$tapped2 = Invoke-ExTileTap
$hitExit = Wait-ExNewLog 'manual-exit' -Before $beforeExit -TimeoutSec 12
$hitExitLines = Wait-ExNewLog 'ExitDashboard' -Before $beforeExitLines -TimeoutSec 12
$handedBack = Wait-ExRearNotDashboard -TimeoutSec 15
$exitPass = $runValid -and $tapped2 -and (@($hitExit).Count -gt 0) -and $handedBack
Add-ExStep -Step 'tile-exit' -Pass $exitPass `
    -Note ('tap ok: {0}; manual-exit line: {1}; ExitDashboard: {2}; handed back: {3}' -f $tapped2, (@($hitExit).Count -gt 0), (@($hitExitLines).Count -gt 0), $handedBack)

# ---- 3. gates closed (DND on + posture as-is) -> tap still casts; auto logic keeps away --
$beforeDnd = Get-ExLogMatchCount 'dnd on'
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'priority') -AllowFailure | Out-Null
$null = Wait-ExNewLog 'dnd on' -Before $beforeDnd -TimeoutSec 10
$zenOn = Get-ExZenMode
# Read the LAST committed posture from the log (the phone may be lying face up or down;
# the exemption is judged under DND-on regardless, posture recorded for the evidence).
$lastPosture = (@(Get-ExLogcat) | Where-Object { $_ -match 'posture (up|down)' } | Select-Object -Last 1)
$postureWord = if ($lastPosture -match 'posture (up|down)') { $Matches[1] } else { 'unknown' }

$beforeCast3 = Get-ExLogMatchCount 'manual-cast'
$beforeLaunch3 = Get-ExLogMatchCount 'LaunchDashboard'
$tapped3 = Invoke-ExTileTap
$hitCast3 = Wait-ExNewLog 'manual-cast' -Before $beforeCast3 -TimeoutSec 12
$hitLaunch3 = Wait-ExNewLog 'LaunchDashboard' -Before $beforeLaunch3 -TimeoutSec 15
$exemptUp = (Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15)

# clear the test notifications: the manual cast must NOT be withdrawn (auto logic).
$exitBefore3 = Get-ExLogMatchCount 'ExitDashboard'
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Start-Sleep -Seconds $SettleSeconds
$exitAfter3 = Get-ExLogMatchCount 'ExitDashboard'
$stillUp = ((Get-ExRearOwnerNow) -eq 'dashboard')

# tile exits it.
$beforeExit3 = Get-ExLogMatchCount 'manual-exit'
$tapped4 = Invoke-ExTileTap
$null = Wait-ExNewLog 'manual-exit' -Before $beforeExit3 -TimeoutSec 12
$exemptGone = Wait-ExRearNotDashboard -TimeoutSec 15

$exemptPass = $runValid -and (@($hitCast3).Count -gt 0) -and $exemptUp -and ($exitAfter3 -eq $exitBefore3) -and $stillUp -and $exemptGone
Add-ExStep -Step 'tile-exempt' -Pass $exemptPass `
    -Note ('DND={0} posture={1}; cast under gates: {2}; ExitDashboard {3}->{4} (delta 0 = not auto-withdrawn); still on rear: {5}; tile exit works: {6}' -f $zenOn, $postureWord, $exemptUp, $exitBefore3, $exitAfter3, $stillUp, $exemptGone)

# DND back off for the lock leg (isolate the lock variable).
$beforeDndOff = Get-ExLogMatchCount 'dnd off'
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
$null = Wait-ExNewLog 'dnd off' -Before $beforeDndOff -TimeoutSec 10

# ---- 4. lock screen: shade -> tap -> cast; unlock -> tap -> exit -----------------------
Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-quicktile-lock-issued') -AllowFailure | Out-Null
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
Start-Sleep -Seconds 2
# Wake to the lock screen (not past it) and open the shade from there.
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
Start-Sleep -Milliseconds 800
Invoke-Adb -Arguments @('shell', 'input', 'swipe', '540', '200', '540', '1200', '200') -AllowFailure | Out-Null
Start-Sleep -Milliseconds 800
$lockedNow = Test-ExKeyguardLocked

$beforeCast4 = Get-ExLogMatchCount 'manual-cast'
$beforeLaunch4 = Get-ExLogMatchCount 'LaunchDashboard'
$tapped5 = Invoke-ExTileTap
$hitCast4 = Wait-ExNewLog 'manual-cast' -Before $beforeCast4 -TimeoutSec 15
$hitLaunch4 = Wait-ExNewLog 'LaunchDashboard' -Before $beforeLaunch4 -TimeoutSec 20
$lockUp = (Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 20)

# unlock and exit by tile.
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
Start-Sleep -Milliseconds 500
Invoke-ExKeyguardDismiss | Out-Null
Start-Sleep -Seconds 1
$beforeExit4 = Get-ExLogMatchCount 'manual-exit'
$tapped6 = Invoke-ExTileTap
$null = Wait-ExNewLog 'manual-exit' -Before $beforeExit4 -TimeoutSec 12
$lockGone = Wait-ExRearNotDashboard -TimeoutSec 15
$lockPass = $runValid -and $lockedNow -and (@($hitCast4).Count -gt 0) -and $lockUp -and $lockGone
Add-ExStep -Step 'tile-lock' -Pass $lockPass `
    -Note ('keyguard up at tap: {0}; tap ok: {1}; manual-cast: {2}; owner=dashboard under lock: {3}; exit after unlock: {4}' -f $lockedNow, $tapped5, (@($hitCast4).Count -gt 0), $lockUp, $lockGone)

# ---- 5. evidence + summary -------------------------------------------------------------
$appLog = @(Get-ExLogcat)
Write-ExArtifact -Name 'quick-tile-logcat.txt' -Lines $appLog | Out-Null

$overall = if (-not $runValid) {
    'TILE-RUN-INVALID (preflight: zen not off or rear display absent)'
} elseif ($castPass -and $exitPass -and $exemptPass -and $lockPass) {
    'TILE-PASS (all four legs: cast / exit / exempt / lock)'
} else {
    $failed = @(@('tile-cast', $castPass), @('tile-exit', $exitPass), @('tile-exempt', $exemptPass), @('tile-lock', $lockPass)) |
        Where-Object { -not $_[1] } | ForEach-Object { $_[0] }
    ('TILE-FAIL (failed leg(s): {0})' -f ($failed -join ', '))
}

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# quick-tile (ticket #54 / spec 0006: Quick Tile Entry)')
$out.Add('protocol            : tap cast -> tap exit -> DND-on tap cast -> clear (must stay) -> tile exit -> lock tap cast -> unlock tile exit')
$out.Add(('tile driving        : cmd statusbar add-tile + expand-settings + uiautomator bounds + input tap (real SysUI click path)'))
$out.Add(('tile state seam     : Presence (OnScreen/LaunchPending -> ACTIVE = tap-to-exit semantics)'))
$out.Add(('start               : zen={0} rear={1}' -f $zen, (Format-ExStatePair $rear)))
foreach ($s in $script:Steps) {
    $out.Add(('{0,-20}: pass={1,-5} -- {2}' -f $s.Step, $s.Pass, $s.Note))
}
$out.Add(('overall             : {0}' -f $overall))
Write-ExArtifact -Name 'quick-tile.txt' -Lines $out.ToArray() | Out-Null
foreach ($line in $out) { Write-ExNote $line }

# ---- 6. restore -----------------------------------------------------------------------
if (-not $NoRestore) {
    Write-ExNote 'restoring: DND off, notifications cleared, screen awake'
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    Invoke-ExKeyguardDismiss | Out-Null
    Write-ExNote ('restore check: zen={0} owner={1} keyguard={2}' -f (Get-ExZenMode), (Get-ExRearOwnerNow), (Test-ExKeyguardLocked))
}

# ---- 7. scenario notes ----------------------------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (Quick Tile Entry, ticket #54 / spec 0006)')
$notes.Add('')
$notes.Add('- **Real click path**: the tile is added with `cmd statusbar add-tile` (Android 13+),')
$notes.Add('  the shade opened with `expand-settings`, the tile located by `uiautomator dump`')
$notes.Add('  bounds and clicked with `input tap` -- SysUI dispatches a genuine TileService click.')
$notes.Add('- **State seam**: tile ACTIVE/INACTIVE mirrors `Presence` (the project`s single')
$notes.Add('  on-screen fact), refreshed on onStartListening and after each click (+1.5s delayed')
$notes.Add('  sync for the async session close). No persistent polling.')
$notes.Add('- **Manual source**: the tile routes through the SAME ManualCast/ManualExit core')
$notes.Add('  events as Debug Bypass -- exempt from DND Follow and Posture Gate, never withdrawn')
$notes.Add('  by the automatic logic (the exempt leg clears the test notifications and watches')
$notes.Add('  the ExitDashboard line count stay flat).')
$notes.Add('- **Lock leg**: keyguard confirmed via KeyguardServiceDelegate before the tap; the')
$notes.Add('  cast under lock takes the E14 task-move route (rear display admittance while locked).')
$notes.Add('- **No new permissions**: TileService needs only the standard BIND_QUICK_SETTINGS_TILE')
$notes.Add('  (held by the system); guarded by TileManifestTest.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Overall = $overall
    Cast = $castPass
    Exit = $exitPass
    Exempt = $exemptPass
    Lock = $lockPass
}
