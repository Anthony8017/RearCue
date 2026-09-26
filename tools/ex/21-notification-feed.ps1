# 21-notification-feed.ps1 -- tickets #55 + #56 (spec 0007): Notification Feed banner
# end-to-end (content banner + Privacy Mode + Auto-dismiss + settings page).
#
# The question: does a real allowlist notification put a CONTENT banner on the rear Dashboard
# (privacy default = app name + fixed line), does a SECOND key refresh it and RESTART the
# auto-dismiss timer, does expiry fall back to the pure Icon Set with the icon kept, does the
# Privacy Mode switch act on the LIVE banner immediately, does a 5s tier take effect at once,
# do both settings survive a process restart, does clearing the shown notification drop the
# banner at once, and does the banner follow the DND gate (withdraw with the Dashboard, come
# back with the re-cast)?
#
# Driving without fingers:
#   * notifications   `cmd notification post -t <title> <tag> <text>` (a DIFFERENT tag is a
#                      different key -> a real Post event; same key would only be an update)
#   * settings page   the activity is NOT exported (`am start` denied), so: MainActivity ->
#                      tap the gear (content-desc) -> uiautomator dump -> tap the Privacy row
#                      / the auto-dismiss stepper; every change is judged from the app's own
#                      `feed-settings page ...` log line + the STATE debug echo, never from
#                      the tap exit code
#   * rear visuals     `screencap -d <SurfaceFlinger id>` (the logical id `1` is rejected on
#                      this build; the SF id is the number in `dumpsys display` uniqueId)
#
# Verdict words (one per leg):
#   FEED-SHOW-PASS        post -> ShowFeedBanner + LaunchDashboard + owner=dashboard,
#                         privacy default reads true (app name + fixed line on screen)
#   FEED-REFRESH-PASS     second key -> ShowFeedBanner again, Hide lands 8..14s AFTER post2
#                         and >=12s after post1 (the 10s window restarted, not continued)
#   FEED-EXPIRY-PASS      after expiry the Dashboard STAYS (icon retained), owner=dashboard,
#                         ExitDashboard delta 0 -> pure Icon Set fallback
#   FEED-PRIVACY-PASS     privacy off in settings -> `privacy=false` + ShowFeedBanner
#                         re-issued while the banner is on screen (title+text immediate)
#   FEED-5S-PASS          auto-dismiss stepped to 5000ms -> next banner hides 4..8s after post
#   FEED-PERSIST-PASS     force-stop + restart -> first-read line carries privacy=false
#                         autoDismiss=5000ms (both settings survived)
#   FEED-CLEAR-PASS       clear the shown notification -> HideFeedBanner within 3s of the
#                         marker + Dashboard handed back (icons empty -> exit conjunction)
#   FEED-GATE-PASS        DND on -> ExitDashboard + HideFeedBanner + handed back; DND off ->
#                         LaunchDashboard + ShowFeedBanner (banner comes back with the recast)
#   FEED-RUN-INVALID      preflight (listener / rear / channel / default settings / empty
#                         Icon Set) never came up
#
# Run: `ex.ps1 -Task notification-feed`, or alone .\tools\ex\21-notification-feed.ps1
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $SettleSeconds = 3,
    [switch] $NoRestore,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'notification-feed' -Serial $Serial | Out-Null }

# The app log is chatty (every refresh dumps the icon set); a ~5 min run would rotate the
# default main buffer away from the line-count anchors. Enlarge for the run.
Invoke-Adb -Arguments @('logcat', '-G', '4096K', '-b', 'main') -AllowFailure | Out-Null

$shellPkg = 'com.android.shell'
$privacyLabel = 'Privacy Mode'                       # strings.xml: ASCII in every locale here
$stepperMinus = [regex]::Unescape('\u2212')   # the stepper's minus button (U+2212)
$stepperPlus = '+'
$defaultPrivacy = $true
$defaultDismissMs = 10000L

$script:Steps = New-Object System.Collections.Generic.List[object]
function Add-ExStep {
    param([string] $Step, [bool] $Pass, [string] $Note)
    $script:Steps.Add([pscustomobject]@{ Step = $Step; Pass = $Pass; Skipped = $false; Note = $Note })
    Write-ExNote ('step {0}: pass={1} ({2})' -f $Step, $Pass, $Note)
}

function Skip-ExStep {
    <# A leg this ENVIRONMENT cannot judge (never counted as a pass, never silently dropped):
      it lands in the verdict as SKIPPED with the physical reason spelled out. #>
    param([string] $Step, [string] $Reason)
    $script:Steps.Add([pscustomobject]@{ Step = $Step; Pass = $null; Skipped = $true; Note = ('SKIPPED: ' + $Reason) })
    Write-ExNote ('step {0}: SKIPPED ({1})' -f $Step, $Reason)
}

function Set-ExFeedMarker {
    <# One line into the app's own tag, so marker and app lines share the device clock. #>
    param([Parameter(Mandatory)][string] $Name)
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, $Name) -AllowFailure | Out-Null
}

function Get-ExStamp {
    <# Device-clock stamp (Get-ExLogcatTime) of the LAST logcat line matching $Pattern, #>
    <# or $null when the marker never landed (not-measured, never 0). #>
    param([Parameter(Mandatory)][string] $Pattern)
    $hits = @(Get-ExLogcat | Where-Object { $_ -match $Pattern })
    if ($hits.Count -eq 0) { return $null }
    return Get-ExLogcatTime -Line $hits[-1]
}

function Get-ExDeltaSeconds {
    <# Seconds between two Get-ExStamp results; $null when either end is missing. #>
    param($From, $To)
    if (($null -eq $From) -or ($null -eq $To)) { return $null }
    return [math]::Round((Get-ExSignedDeltaSeconds -From $From -To $To), 1)
}

function Get-ExElapsedSince {
    <# Seconds since a marker: stamps a fresh clock line, same device clock (no PC skew). #>
    param([Parameter(Mandatory)][string] $Pattern)
    $from = Get-ExStamp -Pattern $Pattern
    if ($null -eq $from) { return $null }
    Set-ExFeedMarker -Name 'pc-feed-clock'
    $to = Get-ExStamp -Pattern ('pc-feed-clock' + '$')
    return (Get-ExDeltaSeconds -From $from -To $to)
}

function Send-ExFeedPost {
    <# A shell (allowlisted) notification under a UNIQUE tag -> a real Post event with its own
      key. The marker shares the device clock with the app's reaction lines.

      The whole device command goes as ONE argv element after `shell`: adb joins MULTIPLE
      args with spaces and drops the quoting, so `-t 'RearCue feed' <tag> <text>` reached the
      device as `... -t RearCue feed <tag> <text>` -> title='RearCue', tag='feed',
      text='<our tag>' -- EVERY post then hit the SAME key (0|com.android.shell|2020|feed|2000)
      and later posts became silent UPDATES instead of new Posts (run 20260926-210259: only
      the first post ever logged `posted`). One string reaches `sh -c` verbatim, quotes intact. #>
    param([Parameter(Mandatory)][string] $Tag, [Parameter(Mandatory)][string] $Text)
    Set-ExFeedMarker -Name ('pc-feed-post-' + $Tag)
    $command = "cmd notification post -t 'RearCue feed' '$Tag' '$Text'"
    Invoke-Adb -Arguments @('shell', $command) -AllowFailure | Out-Null
}

function Find-ExSettingsNode {
    <# Dump the settings page and find a TEXT node, retrying: the first dump right after an
      activity transition can land before the page is laid out, and a one-shot lookup then
      reports "not found" for a node that is really there (run 20260926-203147 lost the 5min
      tier leg that way). #>
    param([Parameter(Mandatory)][string] $Text, [int] $Retries = 4)
    for ($i = 0; $i -lt $Retries; $i++) {
        $dump = Get-ExWindowDump
        $node = Get-ExNodeCenter -WindowDump $dump -Text $Text
        if ($null -ne $node) { return $node }
        Start-Sleep -Milliseconds 900
    }
    return $null
}

function Start-ExChargingHold {
    <# Hold the Dashboard up through the CHARGING source when the posture gate is closed
      (gate-exempt by design, ticket #57). The battery override makes the SYSTEM emit the
      POWER_* broadcasts (`am broadcast` for them is a protected-broadcast denial); a fresh
      process starts with core plugged=false, so the pair unplug -> `set ac 1` is what
      produces the CONNECTED transition the runtime receiver listens for. #>
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'unplug') -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    $before = Get-ExLogMatchCount 'power-connected'
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'set', 'ac', '1') -AllowFailure | Out-Null
    $hit = Wait-ExNewLog -Pattern 'power-connected' -Before $before -TimeoutSec 12
    $up = Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15
    return ((@($hit).Count -gt 0) -and $up)
}

function Stop-ExChargingHold {
    <# Release the charging hold (POWER_DISCONNECTED); the exit conjunction then decides. #>
    $before = Get-ExLogMatchCount 'power-disconnected'
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'unplug') -AllowFailure | Out-Null
    $hit = Wait-ExNewLog -Pattern 'power-disconnected' -Before $before -TimeoutSec 12
    return (@($hit).Count -gt 0)
}

function Set-ExFeedPrivacy {
    <# Tap the Privacy Mode row on the settings page and judge from the app's own line + echo.
      The count baseline MUST use the SAME pattern as the wait: a baseline counted over all
      `privacy=` lines but a wait counted over only `privacy=<v>` lines never grows past it
      (run 20260926-210259: a correct toggle reported False because an older line of the OTHER
      polarity sat in the baseline). #>
    param([bool] $Enabled)
    $state = Get-ExAppStateNow
    if (($null -ne $state) -and ($state.FeedPrivacyMode -eq $Enabled)) { return $true }
    $node = Find-ExSettingsNode -Text $privacyLabel
    if ($null -eq $node) {
        Write-ExNote 'privacy row label not found on the settings page'
        return $false
    }
    $want = if ($Enabled) { 'feed-settings page privacy=true' } else { 'feed-settings page privacy=false' }
    $before = Get-ExLogMatchCount ([regex]::Escape($want))
    Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$node.X, [string]$node.Y) -AllowFailure | Out-Null
    $hit = Wait-ExNewLog -Pattern $want -Before $before -TimeoutSec 8
    $after = Get-ExAppStateNow
    return ((@($hit).Count -gt 0) -and ($null -ne $after) -and ($after.FeedPrivacyMode -eq $Enabled))
}

function Set-ExFeedAutoDismiss {
    <# Walk the auto-dismiss ladder with the -/+ steppers until STATE reads $TargetMs; judged
      from STATE (the single source of truth) plus the `page autoDismiss=<n>ms` log line.
      Two run-20260926-210259 traps closed here: (1) the baseline was counted over ALL
      `autoDismiss=` lines while the wait counted only the TARGET line, so a correct walk
      reported False (the target line count never passed the bigger baseline); (2) an entry
      that already sits at the target must return True immediately -- there will be no new
      line to wait for. #>
    param([long] $TargetMs)
    $want = ('feed-settings page autoDismiss={0}ms' -f $TargetMs)
    $wantPattern = [regex]::Escape($want)
    $before = Get-ExLogMatchCount $wantPattern
    $entry = Get-ExAppStateNow
    if (($null -ne $entry) -and ($entry.FeedAutoDismissMs -eq $TargetMs)) {
        Write-ExNote ('auto-dismiss already at {0}ms (no tap needed)' -f $TargetMs)
        return $true
    }
    for ($i = 0; $i -lt 10; $i++) {
        $state = Get-ExAppStateNow
        if ($null -eq $state) { Write-ExNote 'STATE echo lost while stepping auto-dismiss'; return $false }
        if ($state.FeedAutoDismissMs -eq $TargetMs) { break }
        $step = if ($state.FeedAutoDismissMs -gt $TargetMs) { $stepperMinus } else { $stepperPlus }
        $node = Find-ExSettingsNode -Text $step
        if ($null -eq $node) { Write-ExNote ('stepper node "{0}" not found' -f $step); return $false }
        Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$node.X, [string]$node.Y) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 700
    }
    $state = Get-ExAppStateNow
    if (($null -eq $state) -or ($state.FeedAutoDismissMs -ne $TargetMs)) {
        Write-ExNote ('auto-dismiss did not reach {0}ms (now {1})' -f $TargetMs, $(if ($state) { $state.FeedAutoDismissMs } else { 'n/a' }))
        return $false
    }
    $hit = Wait-ExNewLog -Pattern $wantPattern -Before $before -TimeoutSec 8
    return (@($hit).Count -gt 0)
}

# ---- 0. preflight --------------------------------------------------------------------
# Clear any battery override a previous charging run left behind BEFORE the app starts (the
# real USB charger stays attached; reset only drops the override -- doing it after the start
# would hand the fresh process a POWER_CONNECTED broadcast it should not see here).
Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'reset') -AllowFailure | Out-Null
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Start-ExApp -TimeoutSec 30 | Out-Null
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1

Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = $shellPkg }
Start-Sleep -Seconds $SettleSeconds

$dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
$rear = Get-RearDisplay -DumpsysDisplay $dump
# NOTE the plumbing: Get-DisplayInfoBlocks RETURNS its array as one pipeline object, so the
# filter must run on an ASSIGNED array (piping $blocks unrolls it). Filtering the call
# directly keeps the array as one element, member-enumerates UniqueId into BOTH ids and made
# Save-ExDisplayShot reject the value (run 20260926-203147).
$blocks = Get-DisplayInfoBlocks -DumpsysDisplay $dump
$mainBlock = $blocks | Where-Object { $_.IsDefault } | Select-Object -First 1
$sfRear = if ($rear -and $rear.UniqueId) { ($rear.UniqueId -replace '^local:', '') } else { $null }
$sfMain = if ($mainBlock -and $mainBlock.UniqueId) { ($mainBlock.UniqueId -replace '^local:', '') } else { $null }
$zen = Get-ExZenMode
$state0 = Get-ExAppStateNow

# Settings normalization: the two settings must START at their documented defaults, otherwise
# a leftover value from an earlier (or crashed) run would masquerade as a "default" verdict.
$normalized = $false
$stateNote = if ($state0) {
    ('privacy={0} autoDismiss={1}ms' -f $state0.FeedPrivacyMode, $state0.FeedAutoDismissMs)
} else { 'no STATE echo' }
if ($state0 -and (($state0.FeedPrivacyMode -ne $defaultPrivacy) -or ($state0.FeedAutoDismissMs -ne $defaultDismissMs))) {
    if (Open-ExSettingsPage) {
        $null = Set-ExFeedPrivacy -Enabled $defaultPrivacy
        $null = Set-ExFeedAutoDismiss -TargetMs $defaultDismissMs
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 800
        $normalized = $true
        $state0 = Get-ExAppStateNow
    }
    Write-ExNote ('settings normalized to defaults at preflight (was: {0})' -f $stateNote)
}
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1
if ($null -eq $state0) { $state0 = Get-ExAppStateNow }

# ---- posture gate mode ---------------------------------------------------------------
# The auto first-cast needs BOTH gates open (DND off AND face-down). The proximity sensor is
# on-change with no adb path: when the phone physically lies FACE UP (postureFaceDown=false,
# committed within seconds of every process start), the auto path stays silent no matter what
# is posted. Evidence from run 20260926-203147: the same post cast NOTHING with the gate
# closed, and cast immediately after a restart (default-open) until the next posture commit
# closed it again -- hardware side: proximity reads 5.00 (far), gravity z=-9.80 (screen up).
# In that mode the banner legs run on a Dashboard held by the CHARGING source (gate-exempt,
# ticket #57) and the two DND gate legs are SKIPPED: they need source=AUTO, which only an
# open gate can produce.
$gateOpen = ($null -ne $state0) -and ($state0.PostureFaceDown -eq $true)
$mode = if ($gateOpen) { 'auto' } else { 'charging-held' }
$chargingHoldOk = $true
if (-not $gateOpen) {
    Write-ExNote 'posture gate CLOSED (postureFaceDown=false -- phone physically face-up; the proximity sensor is on-change with no adb path)'
    Write-ExNote '-> banner legs run on a CHARGING-held Dashboard (gate-exempt, ticket #57); dnd-withdraw / dnd-recast will be SKIPPED'
    $chargingHoldOk = Start-ExChargingHold
    $state0 = Get-ExAppStateNow
    Write-ExNote ('charging hold up: {0} (castSource={1})' -f $chargingHoldOk, $(if ($state0) { $state0.CastSource } else { 'n/a' }))
}

$runValid = ($zen -eq 'ZEN_MODE_OFF') -and $rear -and $sfRear -and ($null -ne $state0) -and
    $state0.Found -and $state0.ListenerConnected -and $state0.ChannelReady -and
    ($state0.IconSet.Count -eq 0) -and ($state0.FeedPrivacyMode -eq $defaultPrivacy) -and
    ($state0.FeedAutoDismissMs -eq $defaultDismissMs) -and $chargingHoldOk
if (-not $runValid) {
    Write-ExNote ('preflight incomplete: zen={0} rear={1} state={2} hold={3}' -f $zen, (Format-ExStatePair $rear),
        $(if ($state0) { 'ok' } else { 'no-echo' }), $chargingHoldOk)
}
$notes = New-Object System.Collections.Generic.List[string]
Write-ExNote ('start: zen={0} rear={1} owner={2} posture={3} mode={4} state={5}' -f $zen, (Format-ExStatePair $rear),
    (Get-ExRearOwnerNow), $(if ($state0) { $state0.PostureFaceDown } else { 'n/a' }), $mode, $stateNote)
Write-ExNote ('protocol: post1 -> post2(>=6s) -> expiry -> privacy off (5min tier) -> post -> 5s tier -> ' +
    'restart -> clear -> restore defaults -> DND on/off')

# ---- 1. banner shows, privacy default ------------------------------------------------
$beforeShow1 = Get-ExLogMatchCount 'ShowFeedBanner'
$beforeLaunch1 = Get-ExLogMatchCount 'LaunchDashboard'
Send-ExFeedPost -Tag 'rearcue-feed-a' -Text 'ticket55 first notification body'
$hitShow1 = Wait-ExNewLog -Pattern 'ShowFeedBanner' -Before $beforeShow1 -TimeoutSec 12
# In charging-held mode no Launch can come from the post (the screen is already up) -- waiting
# for one would burn its full timeout and push post2 past the 10s window (run 20260926-210259).
$hitLaunch1 = if ($mode -eq 'auto') {
    Wait-ExNewLog -Pattern 'LaunchDashboard' -Before $beforeLaunch1 -TimeoutSec 12
} else {
    @()
}
$up1 = Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15
$state1 = Get-ExAppStateNow
$null = Save-ExDisplayShot -Name 'feed-01-banner-privacy-on.png' -SfDisplayId $sfRear
$bannerUp = (@($hitShow1) | Where-Object { $_ -match 'ShowFeedBanner\(' + [regex]::Escape($shellPkg) }).Count -gt 0
$privacyDefault = ($null -ne $state1) -and ($state1.FeedPrivacyMode -eq $defaultPrivacy)
# The cast fact differs by mode: auto mode must PRODUCE the cast from the post; charging-held
# mode already has the Dashboard up, so the post must only add the banner (no new Launch).
$castOk1 = if ($mode -eq 'auto') {
    (@($hitLaunch1).Count -gt 0)
} else {
    ($null -ne $state1) -and ($state1.CastSource -eq 'CHARGING') -and (@($hitLaunch1).Count -eq 0)
}
$castNote1 = if ($mode -eq 'auto') {
    ('LaunchDashboard from the post: {0}' -f (@($hitLaunch1).Count -gt 0))
} else {
    ('charging-held: castSource={0}, LaunchDashboard delta={1} (must be 0: screen already up)' -f $(if ($state1) { $state1.CastSource } else { 'n/a' }), (@($hitLaunch1).Count))
}
$showPass = $runValid -and $bannerUp -and $castOk1 -and $up1 -and $privacyDefault
Add-ExStep -Step 'show' -Pass $showPass `
    -Note ('ShowFeedBanner(com.android.shell): {0}; {1}; owner=dashboard: {2}; privacy default: {3}' -f $bannerUp, $castNote1, $up1, $privacyDefault)

# ---- 2. second key refreshes and RESTARTS the timer ----------------------------------
# post2 must land >=6s after post1 so a WORKING re-timer (hide ~ post2+10 = post1+16) and a
# BROKEN one (hide ~ post1+10) separate cleanly: pass requires hide-post2 in [8,14] and
# hide-post1 >= 12.
$stamp1 = Get-ExStamp -Pattern ('pc-feed-post-rearcue-feed-a' + '$')
$elapsed1 = Get-ExElapsedSince -Pattern ('pc-feed-post-rearcue-feed-a' + '$')
if (($null -ne $elapsed1) -and ($elapsed1 -lt 6)) {
    Start-Sleep -Seconds ([int][math]::Ceiling(6 - $elapsed1))
}
$beforeShow2 = Get-ExLogMatchCount 'ShowFeedBanner'
$beforeHide2 = Get-ExLogMatchCount 'HideFeedBanner'
Send-ExFeedPost -Tag 'rearcue-feed-b' -Text 'ticket55 second notification refresh'
$hitShow2 = Wait-ExNewLog -Pattern 'ShowFeedBanner' -Before $beforeShow2 -TimeoutSec 10
$hitHide2 = Wait-ExNewLog -Pattern 'HideFeedBanner' -Before $beforeHide2 -TimeoutSec 22
$stamp2 = Get-ExStamp -Pattern ('pc-feed-post-rearcue-feed-b' + '$')
$stampHide = Get-ExStamp -Pattern 'HideFeedBanner'
$deltaHidePost2 = Get-ExDeltaSeconds -From $stamp2 -To $stampHide
$deltaHidePost1 = Get-ExDeltaSeconds -From $stamp1 -To $stampHide
$refreshPass = $runValid -and (@($hitShow2).Count -gt 0) -and (@($hitHide2).Count -gt 0) -and
    ($null -ne $deltaHidePost2) -and ($deltaHidePost2 -ge 8) -and ($deltaHidePost2 -le 14) -and
    ($null -ne $deltaHidePost1) -and ($deltaHidePost1 -ge 12)
Add-ExStep -Step 'refresh-restart' -Pass $refreshPass `
    -Note ('ShowFeedBanner after post2: {0}; hide-post2={1}s (want 8..14, timer restarted); hide-post1={2}s (want >=12)' -f (@($hitShow2).Count -gt 0), $deltaHidePost2, $deltaHidePost1)

# ---- 3. expiry falls back to the pure Icon Set (icon retained) -----------------------
$beforeExit3 = Get-ExLogMatchCount 'ExitDashboard'
Start-Sleep -Seconds $SettleSeconds
$owner3 = Get-ExRearOwnerNow
$state3 = Get-ExAppStateNow
$afterExit3 = Get-ExLogMatchCount 'ExitDashboard'
$iconKept = ($null -ne $state3) -and ($state3.IconSet -contains $shellPkg)
$null = Save-ExDisplayShot -Name 'feed-03-after-expiry-iconset.png' -SfDisplayId $sfRear
$expiryPass = $runValid -and ($owner3 -eq 'dashboard') -and ($afterExit3 -eq $beforeExit3) -and $iconKept
Add-ExStep -Step 'expiry-iconset' -Pass $expiryPass `
    -Note ('owner after expiry: {0}; ExitDashboard {1}->{2} (delta 0 = banner expiry alone does not withdraw); iconSet keeps {3}: {4}' -f $owner3, $beforeExit3, $afterExit3, $shellPkg, $iconKept)

# ---- 4. Privacy Mode off acts on the LIVE banner (5min tier buys the navigation time) -
$settingsOpen = Open-ExSettingsPage
$dismissRaised = $settingsOpen -and (Set-ExFeedAutoDismiss -TargetMs 300000)
Send-ExFeedPost -Tag 'rearcue-feed-c' -Text 'ticket55 privacy switch target body'
$beforeShow4 = Get-ExLogMatchCount 'ShowFeedBanner'
$hitShow4 = Wait-ExNewLog -Pattern 'ShowFeedBanner' -Before $beforeShow4 -TimeoutSec 12
$beforePriv = Get-ExLogMatchCount 'feed-settings page privacy='
# capture the ShowFeedBanner count at the SAME moment: the toggle inside Set-ExFeedPrivacy is
# what must re-issue it (banner face change = content update, not a fresh post).
$beforeShow4b = Get-ExLogMatchCount 'ShowFeedBanner'
$privacyToggled = $settingsOpen -and (Set-ExFeedPrivacy -Enabled $false)
$hitPriv = Wait-ExNewLog -Pattern 'feed-settings page privacy=false' -Before $beforePriv -TimeoutSec 8
$hitShow4b = Wait-ExNewLog -Pattern 'ShowFeedBanner' -Before $beforeShow4b -TimeoutSec 8
$state4 = Get-ExAppStateNow
$null = Save-ExDisplayShot -Name 'feed-04-privacy-off-title-text.png' -SfDisplayId $sfRear
$privacyPass = $runValid -and $dismissRaised -and $privacyToggled -and (@($hitPriv).Count -gt 0) -and
    ($null -ne $state4) -and ($state4.FeedPrivacyMode -eq $false) -and (@($hitShow4b).Count -gt 0)
Add-ExStep -Step 'privacy-off' -Pass $privacyPass `
    -Note ('5min tier set: {0}; banner up before toggle: {1}; privacy=false line: {2}; STATE privacy: {3}; ShowFeedBanner re-issued: {4}' -f $dismissRaised, (@($hitShow4).Count -gt 0), (@($hitPriv).Count -gt 0), $(if ($state4) { $state4.FeedPrivacyMode } else { 'n/a' }), (@($hitShow4b).Count -gt 0))

# ---- 5. Auto-dismiss stepped to 5s acts immediately -----------------------------------
$dismiss5 = Set-ExFeedAutoDismiss -TargetMs 5000
$beforeShow5 = Get-ExLogMatchCount 'ShowFeedBanner'
$beforeHide5 = Get-ExLogMatchCount 'HideFeedBanner'
Send-ExFeedPost -Tag 'rearcue-feed-d' -Text 'ticket55 five second tier body'
$stampPost5 = Get-ExStamp -Pattern ('pc-feed-post-rearcue-feed-d' + '$')
$hitShow5 = Wait-ExNewLog -Pattern 'ShowFeedBanner' -Before $beforeShow5 -TimeoutSec 12
$hitHide5 = Wait-ExNewLog -Pattern 'HideFeedBanner' -Before $beforeHide5 -TimeoutSec 14
$stampHide5 = Get-ExStamp -Pattern 'HideFeedBanner'
$delta5 = Get-ExDeltaSeconds -From $stampPost5 -To $stampHide5
$auto5Pass = $runValid -and $dismiss5 -and (@($hitShow5).Count -gt 0) -and (@($hitHide5).Count -gt 0) -and
    ($null -ne $delta5) -and ($delta5 -ge 4) -and ($delta5 -le 8)
Add-ExStep -Step 'autodismiss-5s' -Pass $auto5Pass `
    -Note ('tier 5000ms set: {0}; banner up: {1}; hide-post={2}s (want 4..8)' -f $dismiss5, (@($hitShow5).Count -gt 0), $delta5)
$null = Save-ExDisplayShot -Name 'settings-autodismiss-5s.png' -SfDisplayId $sfMain

# ---- 6. both settings survive a process restart ---------------------------------------
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
Start-Sleep -Milliseconds 800
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1
$beforePersist = Get-ExLogMatchCount 'feed-settings privacy=false autoDismiss=5000ms'
Start-ExApp -TimeoutSec 30 | Out-Null
$hitPersist = Wait-ExNewLog -Pattern 'feed-settings privacy=false autoDismiss=5000ms' -Before $beforePersist -TimeoutSec 15
$state6 = Get-ExAppStateNow
$persistPass = $runValid -and (@($hitPersist).Count -gt 0) -and ($null -ne $state6) -and
    ($state6.FeedPrivacyMode -eq $false) -and ($state6.FeedAutoDismissMs -eq 5000)
Add-ExStep -Step 'persist-restart' -Pass $persistPass `
    -Note ('first-read line after restart: {0}; STATE privacy={1} autoDismiss={2}ms' -f (@($hitPersist).Count -gt 0), $(if ($state6) { $state6.FeedPrivacyMode } else { 'n/a' }), $(if ($state6) { $state6.FeedAutoDismissMs } else { 'n/a' }))

# In charging-held mode the restart also killed the Dashboard: re-establish the hold so the
# next leg has a screen again (a fresh process starts with core plugged=false, so the
# unplug -> `set ac 1` pair is the transition the receiver waits for).
$holdBack = $true
if ($mode -eq 'charging-held') {
    $holdBack = Start-ExChargingHold
    Write-ExNote ('charging hold re-established after the restart: {0}' -f $holdBack)
}

# ---- 7. clearing the shown notification drops the banner at once ----------------------
Send-ExFeedPost -Tag 'rearcue-feed-e' -Text 'ticket55 clear-me body'
$beforeShow7 = Get-ExLogMatchCount 'ShowFeedBanner'
$hitShow7 = Wait-ExNewLog -Pattern 'ShowFeedBanner' -Before $beforeShow7 -TimeoutSec 12
$beforeHide7 = Get-ExLogMatchCount 'HideFeedBanner'
$beforeExit7 = Get-ExLogMatchCount 'ExitDashboard'
Set-ExFeedMarker -Name 'pc-feed-clear'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = $shellPkg }
$hitHide7 = Wait-ExNewLog -Pattern 'HideFeedBanner' -Before $beforeHide7 -TimeoutSec 8
$stampClear = Get-ExStamp -Pattern 'pc-feed-clear'
$stampHide7 = Get-ExStamp -Pattern 'HideFeedBanner'
$deltaClear = Get-ExDeltaSeconds -From $stampClear -To $stampHide7
$releaseOk = $true
$releaseNote = ''
if ($mode -eq 'auto') {
    $handedBack7 = Wait-ExRearNotDashboard -TimeoutSec 15
    $hitExit7 = Wait-ExNewLog -Pattern 'ExitDashboard' -Before $beforeExit7 -TimeoutSec 8
    $releaseOk = $handedBack7 -and (@($hitExit7).Count -gt 0)
    $releaseNote = ('handed back after the clear: {0} (ExitDashboard: {1})' -f $handedBack7, (@($hitExit7).Count -gt 0))
} else {
    # Charging holds the screen by design (ticket #57): the claim here is that the BANNER
    # drops at once while the screen stays; the release comes from the charging conjunction
    # and is asserted right below with the charging release itself.
    $stillUp7 = ((Get-ExRearOwnerNow) -eq 'dashboard')
    $releaseOk = $stillUp7
    $releaseNote = ('charging holds: owner still dashboard right after the clear: {0}' -f $stillUp7)
}
$clearPass = $runValid -and $holdBack -and (@($hitShow7).Count -gt 0) -and (@($hitHide7).Count -gt 0) -and
    ($null -ne $deltaClear) -and ($deltaClear -le 3) -and $releaseOk
if ($mode -eq 'charging-held') {
    $released = Stop-ExChargingHold
    $backAfterRelease = Wait-ExRearNotDashboard -TimeoutSec 15
    $clearPass = $clearPass -and $released -and $backAfterRelease
    $releaseNote += ('; charging released: disconnect line {0}, handed back {1}' -f $released, $backAfterRelease)
}
Add-ExStep -Step 'clear-dismiss' -Pass $clearPass `
    -Note ('banner up before clear: {0}; HideFeedBanner {1}s after the clear marker (want <=3); {2}' -f (@($hitShow7).Count -gt 0), $deltaClear, $releaseNote)

# ---- 8. restore defaults, then the DND gate overlap -----------------------------------
$restored = $false
if (Open-ExSettingsPage) {
    $p = Set-ExFeedPrivacy -Enabled $defaultPrivacy
    $a = Set-ExFeedAutoDismiss -TargetMs $defaultDismissMs
    $restored = $p -and $a
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 800
}
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1
Write-ExNote ('settings restored to defaults: {0}' -f $restored)

if ($mode -eq 'auto') {
    # DND gate overlap (needs source=AUTO -> needs the posture gate open).
    Send-ExFeedPost -Tag 'rearcue-feed-f' -Text 'ticket55 dnd gate body'
    $beforeShow8 = Get-ExLogMatchCount 'ShowFeedBanner'
    $hitShow8 = Wait-ExNewLog -Pattern 'ShowFeedBanner' -Before $beforeShow8 -TimeoutSec 12
    $up8 = Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15

    $beforeDndOn = Get-ExLogMatchCount 'dnd on'
    $beforeExit8a = Get-ExLogMatchCount 'ExitDashboard'
    $beforeHide8a = Get-ExLogMatchCount 'HideFeedBanner'
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'priority') -AllowFailure | Out-Null
    $hitDndOn = Wait-ExNewLog -Pattern 'dnd on' -Before $beforeDndOn -TimeoutSec 10
    $hitExit8a = Wait-ExNewLog -Pattern 'ExitDashboard' -Before $beforeExit8a -TimeoutSec 12
    $hitHide8a = Wait-ExNewLog -Pattern 'HideFeedBanner' -Before $beforeHide8a -TimeoutSec 12
    $back8a = Wait-ExRearNotDashboard -TimeoutSec 15
    $withdrawPass = $runValid -and (@($hitShow8).Count -gt 0) -and $up8 -and (@($hitDndOn).Count -gt 0) -and
        (@($hitExit8a).Count -gt 0) -and (@($hitHide8a).Count -gt 0) -and $back8a
    Add-ExStep -Step 'dnd-withdraw' -Pass $withdrawPass `
        -Note ('banner up before DND: {0}; dnd on line: {1}; ExitDashboard: {2}; HideFeedBanner: {3}; handed back: {4}' -f (@($hitShow8).Count -gt 0), (@($hitDndOn).Count -gt 0), (@($hitExit8a).Count -gt 0), (@($hitHide8a).Count -gt 0), $back8a)

    $beforeDndOff = Get-ExLogMatchCount 'dnd off'
    $beforeLaunch8b = Get-ExLogMatchCount 'LaunchDashboard'
    $beforeShow8b = Get-ExLogMatchCount 'ShowFeedBanner'
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
    $hitDndOff = Wait-ExNewLog -Pattern 'dnd off' -Before $beforeDndOff -TimeoutSec 10
    $hitLaunch8b = Wait-ExNewLog -Pattern 'LaunchDashboard' -Before $beforeLaunch8b -TimeoutSec 12
    $hitShow8b = Wait-ExNewLog -Pattern 'ShowFeedBanner' -Before $beforeShow8b -TimeoutSec 12
    $up8b = Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15
    $null = Save-ExDisplayShot -Name 'feed-08-banner-after-dnd-off.png' -SfDisplayId $sfRear
    $state8 = Get-ExAppStateNow
    $recastPass = $runValid -and (@($hitDndOff).Count -gt 0) -and (@($hitLaunch8b).Count -gt 0) -and
        (@($hitShow8b).Count -gt 0) -and $up8b
    Add-ExStep -Step 'dnd-recast' -Pass $recastPass `
        -Note ('dnd off line: {0}; LaunchDashboard: {1}; ShowFeedBanner restored: {2}; owner=dashboard: {3}; posture(faceDown)={4}' -f (@($hitDndOff).Count -gt 0), (@($hitLaunch8b).Count -gt 0), (@($hitShow8b).Count -gt 0), $up8b, $(if ($state8) { $state8.PostureFaceDown } else { 'n/a' }))
} else {
    $skipReason = 'posture gate closed (phone physically face-up; proximity is an on-change sensor with no adb path). The banner is held by the CHARGING source, which DND cannot withdraw by design (ticket #57), so withdraw/recast need source=AUTO -> needs the phone face-down'
    Skip-ExStep -Step 'dnd-withdraw' -Reason $skipReason
    Skip-ExStep -Step 'dnd-recast' -Reason $skipReason
}

# ---- 9. evidence + summary -------------------------------------------------------------
$appLog = @(Get-ExLogcat)
Write-ExArtifact -Name 'notification-feed-logcat.txt' -Lines $appLog | Out-Null

$failedSteps = @($script:Steps | Where-Object { ($null -ne $_.Pass) -and (-not $_.Pass) })
$skippedSteps = @($script:Steps | Where-Object { $_.Skipped })
$judgedNames = @($script:Steps | Where-Object { -not $_.Skipped } | ForEach-Object { $_.Step })
$overall = if (-not $runValid) {
    'FEED-RUN-INVALID (preflight: zen/rear/listener/channel/empty Icon Set/default settings/charging hold not all up)'
} elseif ($failedSteps.Count -gt 0) {
    ('FEED-FAIL (failed leg(s): {0})' -f (($failedSteps | ForEach-Object { $_.Step }) -join ', '))
} elseif ($skippedSteps.Count -gt 0) {
    ('FEED-PASS ({0} legs judged, all pass: {1}) / SKIPPED ({2}: {3} -- environment, see scenario notes)' -f
        $judgedNames.Count, ($judgedNames -join ' / '), $skippedSteps.Count,
        (($skippedSteps | ForEach-Object { $_.Step }) -join ', '))
} else {
    ('FEED-PASS (all {0} legs: {1})' -f $script:Steps.Count, ($judgedNames -join ' / '))
}

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# notification-feed (tickets #55 + #56 / spec 0007: Notification Feed + Feed settings)')
$out.Add('protocol            : post1 -> post2(>=6s) -> expiry -> privacy off @5min tier -> post -> 5s tier -> restart -> clear -> restore -> DND on/off')
$out.Add(('notices driving     : cmd notification post -t <title> <unique tag> <text> (tag = key; a new key is a real Post)'))
$out.Add(('settings driving    : gear tap -> uiautomator dump -> row/stepper tap; judged by feed-settings log + STATE echo'))
$out.Add(('rear visuals        : screencap -d <SurfaceFlinger id> (uniqueId number; logical id 1 rejected on this build)'))
$out.Add(('start               : zen={0} rear={1} owner={2} mode={3} settingsNormalized={4}' -f $zen, (Format-ExStatePair $rear), (Get-ExRearOwnerNow), $mode, $normalized))
foreach ($s in $script:Steps) {
    $verdict = if ($s.Skipped) { 'SKIP' } else { [string]$s.Pass }
    $out.Add(('{0,-20}: pass={1,-5} -- {2}' -f $s.Step, $verdict, $s.Note))
}
$out.Add(('overall             : {0}' -f $overall))
Write-ExArtifact -Name 'notification-feed.txt' -Lines $out.ToArray() | Out-Null
foreach ($line in $out) { Write-ExNote $line }

# ---- 10. restore -----------------------------------------------------------------------
if (-not $NoRestore) {
    Write-ExNote 'restoring: DND off, notifications cleared, settings at defaults, battery override dropped'
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = $shellPkg }
    # Drop the charging override (charging-held mode): the REAL USB charger stays attached, so
    # after reset the system broadcasts POWER_CONNECTED again and the app re-casts the charging
    # screen -- the truthful end state of a plugged-in phone, recorded in the check below.
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'reset') -AllowFailure | Out-Null
    Start-Sleep -Seconds 3
    Write-ExNote ('restore check: zen={0} owner={1} state={2}' -f (Get-ExZenMode), (Get-ExRearOwnerNow),
        $( $s2 = Get-ExAppStateNow; if ($s2) { 'privacy={0} autoDismiss={1}ms castSource={2}' -f $s2.FeedPrivacyMode, $s2.FeedAutoDismissMs, $s2.CastSource } else { 'no-echo' } ))
}

# ---- 11. scenario notes -----------------------------------------------------------------
$notes.Add('## scenario notes (Notification Feed, tickets #55 + #56 / spec 0007)')
$notes.Add('')
$notes.Add('- **Post keys**: `cmd notification post -t "RearCue feed" <tag> <text>` runs as')
$notes.Add('  com.android.shell (allowlisted). A new tag = a new key = a real Post event; the same')
$notes.Add('  key would only be an UPDATE and produce no refresh (README warning).')
$notes.Add('- **Timer measurement**: markers go into the SAME RearCue tag (`log -t RearCue')
$notes.Add('  pc-feed-post-*`), so marker and app lines share one device clock -- no PC skew.')
$notes.Add('  Refresh verdict: Hide lands 8..14s after post2 AND >=12s after post1; a stale timer')
$notes.Add('  (hide ~ post1+10) fails both bounds.')
$notes.Add('- **Settings page**: AllowlistSettingsActivity is not exported (am start denied from')
$notes.Add('  shell), so the script walks MainActivity -> gear (content-desc) -> uiautomator dump.')
$notes.Add('  Every change is judged by the app own `feed-settings page ...` line + the STATE')
$notes.Add('  debug echo, never by the tap itself.')
$notes.Add('- **Rear screenshots**: `screencap -d 1` is rejected on this build (04-drive finding);')
$notes.Add('  the SurfaceFlinger id from `dumpsys display` uniqueId ("local:<id>") works and is')
$notes.Add('  what every rear shot here used.')
$notes.Add('- **Posture Gate decides the mode**: auto first-cast needs both gates open (DND off AND')
$notes.Add('  face-down). The proximity sensor is on-change with no adb path, so the run reads the')
$notes.Add('  committed posture and adapts: `mode=auto` runs the legs as designed; `mode=charging-held`')
$notes.Add('  means the phone physically lies FACE UP (gate closed) -- the banner legs then run on a')
$notes.Add('  Dashboard held by the CHARGING source (gate-exempt, ticket #57) and the two DND gate')
$notes.Add('  legs are SKIPPED (they need source=AUTO). Evidence for the physical reading: proximity')
$notes.Add('  last event 5.00 (far), gravity z=-9.80 (screen up), app line `posture up`; A/B in run')
$notes.Add('  20260926-203147: the same post cast NOTHING with the gate closed and cast immediately')
$notes.Add('  after a restart (default-open) until the next posture commit closed it again.')
$notes.Add('- **Settings open check**: `dumpsys window` prints `mCurrentFocus=` PER DISPLAY and the')
$notes.Add('  rear display''s (null) line comes FIRST -- reading the first line reported "never took')
$notes.Add('  focus" for an activity that HAD resumed (run 20260926-203147 lost its settings legs')
$notes.Add('  this way). The open check now uses the top activity of display 0 instead, and every')
$notes.Add('  node lookup retries (the first dump after an activity transition can be pre-layout).')
$notes.Add('- **Foreign notifications**: any other app posting during a timing window replaces the')
$notes.Add('  feed content (latest-notification semantics) and can hide/re-time the banner; the')
$notes.Add('  step notes carry the interference lines when it happens. The legs cancel only their')
$notes.Add('  own shell notifications -- other apps'' notifications are never touched.')
$notes.Add('- **Auto-dismiss tier ladder** (AutoDismissSteps): 5/10/15/30/60/120/300s + unlimited;')
$notes.Add('  the script walks it with the -/+ steppers and re-reads STATE after every tap.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Overall            = $overall
    Show               = ($script:Steps | Where-Object { $_.Step -eq 'show' }).Pass
    RefreshRestart     = ($script:Steps | Where-Object { $_.Step -eq 'refresh-restart' }).Pass
    ExpiryIconSet      = ($script:Steps | Where-Object { $_.Step -eq 'expiry-iconset' }).Pass
    PrivacyOff         = ($script:Steps | Where-Object { $_.Step -eq 'privacy-off' }).Pass
    AutoDismiss5s      = ($script:Steps | Where-Object { $_.Step -eq 'autodismiss-5s' }).Pass
    PersistRestart     = ($script:Steps | Where-Object { $_.Step -eq 'persist-restart' }).Pass
    ClearDismiss       = ($script:Steps | Where-Object { $_.Step -eq 'clear-dismiss' }).Pass
    DndWithdraw        = ($script:Steps | Where-Object { $_.Step -eq 'dnd-withdraw' }).Pass
    DndRecast          = ($script:Steps | Where-Object { $_.Step -eq 'dnd-recast' }).Pass
}
