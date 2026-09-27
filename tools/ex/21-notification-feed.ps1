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
#                      tap the gear (content-desc) -> uiautomator dump -> tap the Privacy row,
#                      tap the unit chip, focus the numeric value field (content-desc) and
#                      `input text` the digits; every change is judged from the app's own
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
#   FEED-5S-PASS          auto-dismiss typed to 5000ms -> next banner hides 4..8s after post
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
# Auto-dismiss control handles (strings.xml, spec 0007 range-complete input): the value field
# carries an ASCII content-desc, the unit chips and the unlimited row are Chinese labels --
# written as \uXXXX escapes, this file has to stay ASCII (Windows PowerShell 5.1 reads BOM-less
# .ps1 as ANSI/GBK otherwise).
$autoDismissDesc = 'Auto-dismiss value'                             # settings_autodismiss_value_cd
$unitSecond = [regex]::Unescape('\u79D2')                           # settings_unit_second
$unitMinute = [regex]::Unescape('\u5206')                           # settings_unit_minute
$unitHour = [regex]::Unescape('\u65F6')                             # settings_unit_hour
$unlimitedLabel = [regex]::Unescape('\u65E0\u4E0A\u9650')          # settings_autodismiss_unlimited
$defaultPrivacy = $true
$defaultDismissMs = 10000L
$unlimitedMs = 9223372036854775807L                                 # AutoDismissPolicy.UNLIMITED_MS

# The step ledger, the stamp/delta helpers and the verdict rendering come from ExCommon
# (module scope -- 22-charging shares them; this script keeps only what it drives itself).
Reset-ExSteps

function Set-ExFeedMarker {
    <# One line into the app's own tag, so marker and app lines share the device clock. #>
    param([Parameter(Mandatory)][string] $Name)
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, $Name) -AllowFailure | Out-Null
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
    <# Dump the settings page and find a node by TEXT (label) or by ContentDesc (the numeric
      value field), over three passes: as the page sits -> scrolled DOWN (a control below the
      fold) -> scrolled back to the TOP (focusing the value field auto-scrolls, which can push
      the Privacy row above the viewport). A one-shot lookup reports "not found" for a node that
      is really there (run 20260926-203147 lost the 5min tier leg that way); the first dump
      right after an activity transition can also land before the page is laid out. #>
    param([string] $Text, [string] $ContentDesc, [int] $Passes = 3)
    if (-not $Text -and -not $ContentDesc) { throw 'Find-ExSettingsNode needs -Text or -ContentDesc' }
    for ($pass = 0; $pass -lt $Passes; $pass++) {
        $dump = Get-ExWindowDump
        $node = if ($ContentDesc) {
            Get-ExNodeCenter -WindowDump $dump -ContentDesc $ContentDesc
        } else {
            Get-ExNodeCenter -WindowDump $dump -Text $Text
        }
        if ($null -ne $node) { return $node }
        if ($pass -eq 0) {
            # finger up = page scrolls down (reveal what is below the fold)
            Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '2300', '550', '700', '350') -AllowFailure | Out-Null
        } elseif ($pass -eq 1) {
            # finger down twice = page back to the top (Privacy row + banner card near the top)
            Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '700', '550', '2300', '350') -AllowFailure | Out-Null
            Start-Sleep -Milliseconds 400
            Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '700', '550', '2300', '350') -AllowFailure | Out-Null
        }
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

function Close-ExIme {
    <# Hide the soft keyboard when it is REALLY up (dumpsys input_method) -- a blind BACK would
      navigate away from the settings page on the no-keyboard path. #>
    $dump = (@(Invoke-Adb -Arguments @('shell', 'dumpsys', 'input_method') -AllowFailure) -join "`n")
    if ($dump -match 'mInputShown=true' -or $dump -match 'mIsInputViewShown=true') {
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 700
        return $true
    }
    return $false
}

function Set-ExFeedAutoDismiss {
    <# Type $TargetMs into the settings page's numeric field (spec 0007 range-complete control:
      any integer >= 5s plus the separate unlimited row) and judge from STATE (the single source
      of truth) plus the `page autoDismiss=<n>ms` log line.

      Drive order -- every step judged from the app's own log + STATE echo, never from tap codes:
        (1) an entry sitting on the unlimited row leaves it first (the field is disabled there);
        (2) the unit chip is set to the unit AutoDismissPolicy.entryOf renders for the target
            (hours > minutes > seconds), so typed digits == displayed digits;
        (3) the field is focused (the app select-alls on focus) and `input text` commits the
            digits in ONE shot -- the selection is replaced wholesale, one parse, one apply;
        (4) the keyboard is hidden again when it really was up.
      Traps already closed and kept: the baseline counts the TARGET line only (run
      20260926-210259: a baseline over all `autoDismiss=` lines can never be passed), and an
      entry already at the target returns True immediately -- there will be no new line. #>
    param([long] $TargetMs)

    $want = ('feed-settings page autoDismiss={0}ms' -f $TargetMs)
    $wantPattern = [regex]::Escape($want)
    $before = Get-ExLogMatchCount $wantPattern
    $entry = Get-ExAppStateNow
    if (($null -ne $entry) -and ($entry.FeedAutoDismissMs -eq $TargetMs)) {
        Write-ExNote ('auto-dismiss already at {0}ms (no typing needed)' -f $TargetMs)
        return $true
    }

    # (1) leave the unlimited row: with it on, the value field is disabled by design.
    if (($null -ne $entry) -and ($entry.FeedAutoDismissMs -ge $unlimitedMs)) {
        $node = Find-ExSettingsNode -Text $unlimitedLabel
        if ($null -eq $node) { Write-ExNote 'unlimited row not found on the settings page'; return $false }
        Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$node.X, [string]$node.Y) -AllowFailure | Out-Null
        $left = $false
        $deadline = (Get-Date).AddSeconds(10)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 600
            $entry = Get-ExAppStateNow
            if (($null -ne $entry) -and ($entry.FeedAutoDismissMs -lt $unlimitedMs)) { $left = $true; break }
        }
        if (-not $left) { Write-ExNote 'still on the unlimited row (value field stays disabled)'; return $false }
        Start-Sleep -Milliseconds 500
    }

    # (2) unit chip: the same unit the app renders (entryOf rule), so `input text` $amount is
    # the value that shows up -- 10000ms -> "10" s, 300000ms -> "5" min, 7200000ms -> "2" h.
    if (($TargetMs % 3600000) -eq 0) {
        $unitLabel = $unitHour
        $amount = [string][long]($TargetMs / 3600000)
    } elseif (($TargetMs % 60000) -eq 0) {
        $unitLabel = $unitMinute
        $amount = [string][long]($TargetMs / 60000)
    } else {
        $unitLabel = $unitSecond
        $amount = [string][long]($TargetMs / 1000)
    }
    $unitNode = Find-ExSettingsNode -Text $unitLabel
    if ($null -eq $unitNode) {
        Write-ExNote ('unit chip "{0}" not found on the settings page' -f $unitLabel)
        return $false
    }
    Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$unitNode.X, [string]$unitNode.Y) -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 700

    # (3) focus + type: one commit replaces the selection the app made on focus; one retry
    # covers a tap that landed off the field (the digits then go nowhere).
    $typed = $false
    $state = $null
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        $fieldNode = Find-ExSettingsNode -ContentDesc $autoDismissDesc
        if ($null -eq $fieldNode) {
            Write-ExNote 'auto-dismiss value field not found on the settings page'
            return $false
        }
        Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$fieldNode.X, [string]$fieldNode.Y) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 700
        Invoke-Adb -Arguments @('shell', 'input', 'text', $amount) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 700
        $hit = Wait-ExNewLog -Pattern $wantPattern -Before $before -TimeoutSec 6
        $state = Get-ExAppStateNow
        if ((@($hit).Count -gt 0) -and ($null -ne $state) -and ($state.FeedAutoDismissMs -eq $TargetMs)) {
            $typed = $true
            break
        }
        Write-ExNote ('auto-dismiss typing attempt {0} missed (state now {1})' -f $attempt,
            $(if ($state) { $state.FeedAutoDismissMs } else { 'n/a' }))
    }
    Close-ExIme | Out-Null
    if (-not $typed) {
        $state = Get-ExAppStateNow
        Write-ExNote ('auto-dismiss did not reach {0}ms (now {1})' -f $TargetMs,
            $(if ($state) { $state.FeedAutoDismissMs } else { 'n/a' }))
        return $false
    }
    return $true
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

# The verdict word and the per-leg lines come from the shared ledger in ExCommon (one rule for
# both scenario scripts: preflight invalid > any failed leg > SKIPPED legs > all pass).
$overall = Get-ExStepOverall -RunValid $runValid -Prefix 'FEED' `
    -InvalidNote 'preflight: zen/rear/listener/channel/empty Icon Set/default settings/charging hold not all up'

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# notification-feed (tickets #55 + #56 / spec 0007: Notification Feed + Feed settings)')
$out.Add('protocol            : post1 -> post2(>=6s) -> expiry -> privacy off @5min tier -> post -> 5s tier -> restart -> clear -> restore -> DND on/off')
$out.Add(('notices driving     : cmd notification post -t <title> <unique tag> <text> (tag = key; a new key is a real Post)'))
$out.Add(('settings driving    : gear tap -> uiautomator dump -> row tap / unit chip / value field + input text; judged by feed-settings log + STATE echo'))
$out.Add(('rear visuals        : screencap -d <SurfaceFlinger id> (uniqueId number; logical id 1 rejected on this build)'))
$out.Add(('start               : zen={0} rear={1} owner={2} mode={3} settingsNormalized={4}' -f $zen, (Format-ExStatePair $rear), (Get-ExRearOwnerNow), $mode, $normalized))
# `# verdicts` marks the block 05-collect embeds in summary.md (same marker as the E-series
# artifacts) -- without it the summary's "step verdicts" section stays empty.
$out.Add('# verdicts')
foreach ($line in (Format-ExStepVerdictLines)) { $out.Add($line) }
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
$notes.Add('  com.android.shell (allowlisted). A new tag = a new key = a real Post event. The same')
$notes.Add('  key with a CHANGED body is a content update: the repository reports Updated, the')
$notes.Add('  banner refreshes and re-timers, and the Icon Set does not re-count (spec 0007 story')
$notes.Add('  1/4 review fix). The same key with the SAME body stays a no-op. The legs therefore')
$notes.Add('  always post under a UNIQUE tag -- a new key keeps the Post path under test.')
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
$notes.Add('- **Auto-dismiss is a range-complete numeric input** (AutoDismissPolicy, review fix):')
$notes.Add('  any integer >= 5s plus a separate unlimited row -- 7s/10min/2h are all reachable,')
$notes.Add('  the old 8-tier ladder is gone. The script sets a value by (1) leaving the unlimited')
$notes.Add('  row when the entry sits there (the field is disabled by design), (2) tapping the unit')
$notes.Add('  chip that entryOf would render for the target (hours > minutes > seconds), (3)')
$notes.Add('  focusing the field (the app select-alls on focus) and committing the digits in one')
$notes.Add('  `input text`, then (4) hiding the keyboard when it really was up. Every value is')
$notes.Add('  judged from STATE + the `page autoDismiss=<n>ms` line, never from the tap.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Overall            = $overall
    Show               = (Get-ExSteps | Where-Object { $_.Step -eq 'show' }).Pass
    RefreshRestart     = (Get-ExSteps | Where-Object { $_.Step -eq 'refresh-restart' }).Pass
    ExpiryIconSet      = (Get-ExSteps | Where-Object { $_.Step -eq 'expiry-iconset' }).Pass
    PrivacyOff         = (Get-ExSteps | Where-Object { $_.Step -eq 'privacy-off' }).Pass
    AutoDismiss5s      = (Get-ExSteps | Where-Object { $_.Step -eq 'autodismiss-5s' }).Pass
    PersistRestart     = (Get-ExSteps | Where-Object { $_.Step -eq 'persist-restart' }).Pass
    ClearDismiss       = (Get-ExSteps | Where-Object { $_.Step -eq 'clear-dismiss' }).Pass
    DndWithdraw        = (Get-ExSteps | Where-Object { $_.Step -eq 'dnd-withdraw' }).Pass
    DndRecast          = (Get-ExSteps | Where-Object { $_.Step -eq 'dnd-recast' }).Pass
}
