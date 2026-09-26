# 22-charging.ps1 -- ticket #57 (spec 0007): Charging Animation end-to-end (plug-in cast +
# gate exemption + exit conjunction + master switch).
#
# The question: with NO notifications, does plugging in put the Dashboard (charging animation
# only) on the rear; does it still cast while the DND gate is CLOSED (and under whatever
# posture the phone physically is); is a charging screen immune to gate flips; does a real
# notification share the screen with the animation; does UNPLUGGING hand the rear back only
# under the full conjunction (unplugged AND empty Icon Set AND no banner); and does the master
# switch make the plug a no-op when off, recovering when turned on again?
#
# Driving the plug WITHOUT the cable: the receiver (PowerSignals) is a runtime-registered
# ACTION_POWER_CONNECTED/DISCONNECTED listener, and `am broadcast` is REFUSED for these
# protected broadcasts (Permission Denial from uid 2000, verified 2026-09-26). The system
# itself emits them when the battery service plug state changes, so:
#   * `adb shell dumpsys battery unplug`  -> POWER_DISCONNECTED
#   * `adb shell dumpsys battery set ac 1` -> POWER_CONNECTED (only after an unplug: a bare
#     `set ac 1` while the phone is already USB-powered changes no plug type -> no broadcast)
#   * `adb shell dumpsys battery reset`   -> restores the REAL state (mandatory in restore)
# `dumpsys battery` never sends a broadcast by itself in any other command -- every leg here
# is judged from the app's own `power-connected` / `power-disconnected` refresh lines, never
# from the shell exit code.
#
# Verdict words (one per leg):
#   CHG-CAST-PASS       no notifications + connect -> LaunchDashboard(0) + owner=dashboard +
#                       STATE castSource=CHARGING (animation-only screen, bolt on the rear)
#   CHG-EXEMPT-PASS     DND ON (zen != OFF) -> connect still casts (gate exemption); the
#                       posture reading is recorded (the proximity sensor is on-change and
#                       has no adb path, so the posture leg is exercised only when the phone
#                       is physically face-up during the run)
#   CHG-HOLD-PASS       gate flips (DND off -> on -> off) while charging is on screen produce
#                       an ExitDashboard delta of 0 and the owner stays 'dashboard'
#   CHG-COEXIST-PASS    a real notification lands while charging is on screen ->
#                       ShowFeedBanner + Icon Set row, castSource still CHARGING
#   CHG-HANDBACK-PASS   clear the notification (charging HOLDS: exit delta 0, banner hides) ->
#                       disconnect -> ExitDashboard -> owner handed back (the conjunction)
#   CHG-SWITCH-PASS     switch OFF -> connect is a no-op (power-connected -> no effect,
#                       LaunchDashboard delta 0) -> switch ON while plugged -> immediate cast
#   CHG-RUN-INVALID     preflight (listener / rear / channel / empty Icon Set / switch on /
#                       no cast at start) never came up
#
# Run: `ex.ps1 -Task charging`, or alone .\tools\ex\22-charging.ps1
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
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'charging' -Serial $Serial | Out-Null }

# Same buffer rule as 19/20/21: the app log is chatty, the anchors must survive the run.
Invoke-Adb -Arguments @('logcat', '-G', '4096K', '-b', 'main') -AllowFailure | Out-Null

$shellPkg = 'com.android.shell'
$chargeLabel = [regex]::Unescape('\u5145\u7535\u52A8\u753B')   # settings_charging_switch

# The step ledger, the stamp/delta helpers and the verdict rendering come from ExCommon
# (module scope -- 21-notification-feed shares them; this script keeps only what it drives).
Reset-ExSteps

function Set-ExPowerMarker {
    param([Parameter(Mandatory)][string] $Name)
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, $Name) -AllowFailure | Out-Null
}

function Send-ExShellPost {
    <# One argv element after `shell`: adb joins MULTIPLE args with spaces and drops the
      quoting, which would parse `-t 'RearCue charge' <tag> <text>` on the device as
      title=RearCue / tag=charge / text=<our tag> -- every post then hitting the SAME key
      (see 21-notification-feed run 20260926-210259). One string reaches `sh -c` verbatim. #>
    param([Parameter(Mandatory)][string] $Tag, [Parameter(Mandatory)][string] $Text)
    Set-ExPowerMarker -Name ('pc-charge-post-' + $Tag)
    $command = "cmd notification post -t 'RearCue charge' '$Tag' '$Text'"
    Invoke-Adb -Arguments @('shell', $command) -AllowFailure | Out-Null
}

function Invoke-ExPowerUnplug {
    <# `dumpsys battery unplug` -> the system broadcasts POWER_DISCONNECTED (a no-op broadcast
      when the override already reads unplugged). Returns whether the app's own refresh line
      landed -- the shell exit code decides nothing. #>
    param([Parameter(Mandatory)][string] $Marker)
    Set-ExPowerMarker -Name $Marker
    $before = Get-ExLogMatchCount 'power-disconnected'
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'unplug') -AllowFailure | Out-Null
    $hit = Wait-ExNewLog -Pattern 'power-disconnected' -Before $before -TimeoutSec 10
    return (@($hit).Count -gt 0)
}

function Invoke-ExPowerPlugAc {
    <# `dumpsys battery set ac 1` -> POWER_CONNECTED, but only when the plug state really
      changes (call Invoke-ExPowerUnplug first when it might already read plugged). #>
    param([Parameter(Mandatory)][string] $Marker)
    Set-ExPowerMarker -Name $Marker
    $before = Get-ExLogMatchCount 'power-connected'
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'set', 'ac', '1') -AllowFailure | Out-Null
    $hit = Wait-ExNewLog -Pattern 'power-connected' -Before $before -TimeoutSec 10
    return (@($hit).Count -gt 0)
}

function Set-ExChargingEnabled {
    <# Toggle the master switch on the settings page (the card sits BELOW the fold, so scroll
      until its toggle is in the dump). Judged by STATE + the `charging-anim=<v>` log line.

      The count baseline uses the SAME pattern as the wait (run 20260926-212101: a baseline
      counted over ALL `charging-anim=` lines can never be passed by the wait counted over
      only `charging-anim=<v>` -- the toggle WORKED, both lines are in the log, and the leg
      still reported False). #>
    param([bool] $Enabled)
    $state = Get-ExAppStateNow
    if (($null -ne $state) -and ($state.ChargingEnabled -eq $Enabled)) {
        Write-ExNote ('charging switch already {0} (no tap needed)' -f $Enabled)
        return $true
    }
    $want = if ($Enabled) { 'charging-anim=true' } else { 'charging-anim=false' }
    $before = Get-ExLogMatchCount ([regex]::Escape($want))
    $toggle = $null
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $dump = Get-ExWindowDump
        $toggle = Get-ExUiToggleForLabel -WindowDump $dump -Label $chargeLabel
        if ($toggle.Found) { break }
        # page down to the charging card (it renders under the banner card)
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', '900', '2400', '900', '700', '500') -AllowFailure | Out-Null
        Start-Sleep -Seconds 1
    }
    if (-not $toggle.Found) {
        Write-ExNote 'charging switch not found on the settings page'
        return $false
    }
    Invoke-Adb -Arguments @('shell', 'input', 'tap', [string][int]$toggle.X, [string][int]$toggle.Y) -AllowFailure | Out-Null
    $hit = Wait-ExNewLog -Pattern ([regex]::Escape($want)) -Before $before -TimeoutSec 8
    $after = Get-ExAppStateNow
    return ((@($hit).Count -gt 0) -and ($null -ne $after) -and ($after.ChargingEnabled -eq $Enabled))
}

# ---- 0. preflight ---------------------------------------------------------------------
# Drop any battery override a previous leg/run left behind BEFORE the app starts (the real
# USB charger stays attached; a fresh process must begin with core plugged=false so the first
# deliberate transition of THIS run is the one that casts).
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
$sfRear = if ($rear -and $rear.UniqueId) { ($rear.UniqueId -replace '^local:', '') } else { $null }
$zen = Get-ExZenMode
$batteryLines = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery') -AllowFailure)
$battery = Get-ExBatteryFacts -Battery $batteryLines
$state0 = Get-ExAppStateNow

# A leftover switch-off from an earlier run would make every leg a no-op: normalize it ON
# first (recorded as setup, not as a verdict).
$normalized = $false
if ($state0 -and ($state0.ChargingEnabled -ne $true)) {
    if (Open-ExSettingsPage) {
        $null = Set-ExChargingEnabled -Enabled $true
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 800
        $normalized = $true
    }
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    $state0 = Get-ExAppStateNow
    Write-ExNote 'charging switch normalized to ON at preflight (persisted value was off)'
}
$null = Wait-ExRearNotDashboard -TimeoutSec 15
$state0 = Get-ExAppStateNow

$posture = if ($state0) { $state0.PostureFaceDown } else { $null }
$runValid = ($zen -eq 'ZEN_MODE_OFF') -and $rear -and $sfRear -and ($null -ne $state0) -and
    $state0.Found -and $state0.ListenerConnected -and $state0.ChannelReady -and
    ($state0.IconSet.Count -eq 0) -and ($state0.ChargingEnabled -eq $true) -and
    ($state0.CastSource -eq 'null') -and ((Get-ExRearOwnerNow) -ne 'dashboard')
if (-not $runValid) {
    Write-ExNote ('preflight incomplete: zen={0} rear={1} owner={2} state={3}' -f $zen,
        (Format-ExStatePair $rear), (Get-ExRearOwnerNow), $(if ($state0) { 'ok' } else { 'no-echo' }))
}
Write-ExNote ('start: zen={0} rear={1} owner={2} postureFaceDown={3} battery={4}% usbPowered={5} switchNormalized={6}' -f
    $zen, (Format-ExStatePair $rear), (Get-ExRearOwnerNow), $posture, $battery.Level, $battery.UsbPowered, $normalized)
Write-ExNote ('protocol: connect (cast) -> unplug (setup) -> DND on -> connect (exempt) -> gate flips -> notification -> clear+unplug -> switch off/on -> reset')

# ---- 1. plug in with no notifications -> animation-only Dashboard ---------------------
$beforeLaunch1 = Get-ExLogMatchCount 'LaunchDashboard'
$unplug1 = Invoke-ExPowerUnplug -Marker 'pc-power-unplug-1'
$plug1 = Invoke-ExPowerPlugAc -Marker 'pc-power-connect-1'
$up1 = Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15
$state1 = Get-ExAppStateNow
$castLine1 = (@(Get-ExLogcat) | Where-Object { $_ -match 'power-connected.*LaunchDashboard\(0\)' }).Count -gt 0
$null = Save-ExDisplayShot -Name 'charging-01-bolt-only.png' -SfDisplayId $sfRear
$castPass = $runValid -and $unplug1 -and $plug1 -and $up1 -and ($null -ne $state1) -and
    ($state1.CastSource -eq 'CHARGING') -and ($state1.IconSet.Count -eq 0) -and $castLine1
Add-ExStep -Step 'cast' -Pass $castPass `
    -Note ('disconnected line: {0}; connected line: {1}; owner=dashboard: {2}; castSource={3}; iconSet empty: {4}; LaunchDashboard(0) on connect: {5}' -f $unplug1, $plug1, $up1, $(if ($state1) { $state1.CastSource } else { 'n/a' }), $(if ($state1) { $state1.IconSet.Count -eq 0 } else { $false }), $castLine1)

# ---- 2. gates closed (DND on) -> connect still casts ----------------------------------
$unplug2 = Invoke-ExPowerUnplug -Marker 'pc-power-unplug-2'
$back2 = Wait-ExRearNotDashboard -TimeoutSec 15
$beforeDnd = Get-ExLogMatchCount 'dnd on'
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'priority') -AllowFailure | Out-Null
$hitDnd = Wait-ExNewLog -Pattern 'dnd on' -Before $beforeDnd -TimeoutSec 10
$zenOn = Get-ExZenMode
$state2 = Get-ExAppStateNow
$posture2 = if ($state2) { $state2.PostureFaceDown } else { $null }
$plug2 = Invoke-ExPowerPlugAc -Marker 'pc-power-connect-2'
$up2 = Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15
$state2b = Get-ExAppStateNow
$exemptPass = $runValid -and $unplug2 -and $back2 -and (@($hitDnd).Count -gt 0) -and
    ($zenOn -ne 'ZEN_MODE_OFF') -and $plug2 -and $up2 -and ($null -ne $state2b) -and
    ($state2b.CastSource -eq 'CHARGING')
Add-ExStep -Step 'exempt-dnd' -Pass $exemptPass `
    -Note ('handed back before: {0}; zen at connect: {1} (want != ZEN_MODE_OFF); connected line: {2}; owner=dashboard: {3}; castSource={4}; postureFaceDown={5} (gate closed only when false)' -f $back2, $zenOn, $plug2, $up2, $(if ($state2b) { $state2b.CastSource } else { 'n/a' }), $posture2)

# The posture half of the exemption (#57: face-up still casts): only judgeable when the gate
# is physically CLOSED at cast time -- the sensor is on-change with no adb path, so an open
# gate (phone face-down/covered) has nothing to exempt and must not be read as a pass.
if ($posture2 -eq $false) {
    $postureExemptPass = $runValid -and $up2 -and ($zenOn -ne 'ZEN_MODE_OFF')
    Add-ExStep -Step 'exempt-posture' -Pass $postureExemptPass `
        -Note ('posture gate CLOSED at cast time (postureFaceDown=false: phone physically face-up, proximity reads far) and the charging cast still landed (owner=dashboard: {0}) -> posture exemption proven under a closed gate' -f $up2)
} else {
    Skip-ExStep -Step 'exempt-posture' -Reason 'posture gate OPEN at cast time (postureFaceDown=true -- phone face-down/covered): the closed-gate posture leg needs the phone physically face-up; the DND leg above carries the exemption proof'
}

# ---- 3. charging on screen survives gate flips -----------------------------------------
$beforeExit3 = Get-ExLogMatchCount 'ExitDashboard'
$beforeDndOff = Get-ExLogMatchCount 'dnd off'
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
$null = Wait-ExNewLog -Pattern 'dnd off' -Before $beforeDndOff -TimeoutSec 10
Start-Sleep -Seconds 2
$beforeDndOn2 = Get-ExLogMatchCount 'dnd on'
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'priority') -AllowFailure | Out-Null
$null = Wait-ExNewLog -Pattern 'dnd on' -Before $beforeDndOn2 -TimeoutSec 10
Start-Sleep -Seconds 2
$beforeDndOff2 = Get-ExLogMatchCount 'dnd off'
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
$null = Wait-ExNewLog -Pattern 'dnd off' -Before $beforeDndOff2 -TimeoutSec 10
Start-Sleep -Seconds 2
$afterExit3 = Get-ExLogMatchCount 'ExitDashboard'
$owner3 = Get-ExRearOwnerNow
$state3 = Get-ExAppStateNow
$holdPass = $runValid -and ($afterExit3 -eq $beforeExit3) -and ($owner3 -eq 'dashboard') -and
    ($null -ne $state3) -and ($state3.CastSource -eq 'CHARGING')
Add-ExStep -Step 'hold-vs-gates' -Pass $holdPass `
    -Note ('ExitDashboard {0}->{1} across DND off/on/off (delta must be 0); owner={2}; castSource={3}; posture flip not drivable over adb (recorded, not asserted)' -f $beforeExit3, $afterExit3, $owner3, $(if ($state3) { $state3.CastSource } else { 'n/a' }))

# ---- 4. a notification coexists with the animation -------------------------------------
$beforeShow4 = Get-ExLogMatchCount 'ShowFeedBanner'
Send-ExShellPost -Tag 'rearcue-charge-a' -Text 'ticket57 coexist body'
$hitShow4 = Wait-ExNewLog -Pattern 'ShowFeedBanner' -Before $beforeShow4 -TimeoutSec 12
$owner4 = Get-ExRearOwnerNow
$state4 = Get-ExAppStateNow
$null = Save-ExDisplayShot -Name 'charging-04-bolt-with-notification.png' -SfDisplayId $sfRear
$coexistPass = $runValid -and (@($hitShow4).Count -gt 0) -and ($owner4 -eq 'dashboard') -and
    ($null -ne $state4) -and ($state4.CastSource -eq 'CHARGING') -and ($state4.IconSet -contains $shellPkg)
Add-ExStep -Step 'coexist' -Pass $coexistPass `
    -Note ('ShowFeedBanner: {0}; owner={1}; castSource={2}; Icon Set holds {3}: {4}' -f (@($hitShow4).Count -gt 0), $owner4, $(if ($state4) { $state4.CastSource } else { 'n/a' }), $shellPkg, $(if ($state4) { $state4.IconSet -contains $shellPkg } else { $false }))

# ---- 5. clear (charging HOLDS) -> unplug (conjunction) -> hand back --------------------
$beforeExit5 = Get-ExLogMatchCount 'ExitDashboard'
$beforeHide5 = Get-ExLogMatchCount 'HideFeedBanner'
Set-ExPowerMarker -Name 'pc-charge-clear'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = $shellPkg }
$hitHide5 = Wait-ExNewLog -Pattern 'HideFeedBanner' -Before $beforeHide5 -TimeoutSec 8
Start-Sleep -Seconds $SettleSeconds
$owner5Hold = Get-ExRearOwnerNow
$afterExit5Hold = Get-ExLogMatchCount 'ExitDashboard'
$state5 = Get-ExAppStateNow
$iconsEmpty5 = ($null -ne $state5) -and ($state5.IconSet.Count -eq 0)

$beforeExit5b = Get-ExLogMatchCount 'ExitDashboard'
$unplug5 = Invoke-ExPowerUnplug -Marker 'pc-power-unplug-5'
$back5 = Wait-ExRearNotDashboard -TimeoutSec 15
$hitExit5 = Wait-ExNewLog -Pattern 'ExitDashboard' -Before $beforeExit5b -TimeoutSec 10
$handbackPass = $runValid -and (@($hitHide5).Count -gt 0) -and ($afterExit5Hold -eq $beforeExit5) -and
    ($owner5Hold -eq 'dashboard') -and $iconsEmpty5 -and $unplug5 -and $back5 -and (@($hitExit5).Count -gt 0)
Add-ExStep -Step 'handback-conjunction' -Pass $handbackPass `
    -Note ('banner hides while charging holds: {0}; ExitDashboard {1}->{2} after the clear (delta 0 = hold); owner still dashboard: {3}; Icon Set empty: {4}; disconnect line: {5}; handed back: {6}; ExitDashboard after unplug: {7}' -f (@($hitHide5).Count -gt 0), $beforeExit5, $afterExit5Hold, $owner5Hold, $iconsEmpty5, $unplug5, $back5, (@($hitExit5).Count -gt 0))

# ---- 6. master switch: off -> plug is a no-op; on -> immediate recovery -----------------
$settings1 = Open-ExSettingsPage
$offOk = $settings1 -and (Set-ExChargingEnabled -Enabled $false)
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
Start-Sleep -Milliseconds 800
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1

$beforeLaunch6 = Get-ExLogMatchCount 'LaunchDashboard'
$plug6 = Invoke-ExPowerPlugAc -Marker 'pc-power-connect-6'
Start-Sleep -Seconds 3
$owner6 = Get-ExRearOwnerNow
$afterLaunch6 = Get-ExLogMatchCount 'LaunchDashboard'
$state6a = Get-ExAppStateNow
$noopPass = $offOk -and $plug6 -and ($afterLaunch6 -eq $beforeLaunch6) -and ($owner6 -ne 'dashboard') -and
    ($null -ne $state6a) -and ($state6a.ChargingEnabled -eq $false)

$settings2 = Open-ExSettingsPage
$onOk = $settings2 -and (Set-ExChargingEnabled -Enabled $true)
$up6 = Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15
$state6b = Get-ExAppStateNow
$null = Save-ExDisplayShot -Name 'charging-06-switch-on-recovers.png' -SfDisplayId $sfRear
$switchPass = $runValid -and $noopPass -and $onOk -and $up6 -and ($null -ne $state6b) -and
    ($state6b.CastSource -eq 'CHARGING')
Add-ExStep -Step 'master-switch' -Pass $switchPass `
    -Note ('switch off: {0}; connect line: {1}; LaunchDashboard {2}->{3} (delta 0 = no reaction); owner={4}; switch on again: {5}; recovered owner=dashboard: {6}; castSource={7}' -f $offOk, $plug6, $beforeLaunch6, $afterLaunch6, $owner6, $onOk, $up6, $(if ($state6b) { $state6b.CastSource } else { 'n/a' }))
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
Start-Sleep -Milliseconds 800
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Start-Sleep -Seconds 1

# ---- 7. evidence + summary --------------------------------------------------------------
$appLog = @(Get-ExLogcat)
Write-ExArtifact -Name 'charging-logcat.txt' -Lines $appLog | Out-Null

# The verdict word and the per-leg lines come from the shared ledger in ExCommon (one rule for
# both scenario scripts: preflight invalid > any failed leg > SKIPPED legs > all pass).
$overall = Get-ExStepOverall -RunValid $runValid -Prefix 'CHG' `
    -InvalidNote 'preflight: zen/rear/listener/channel/empty Icon Set/switch on/no cast at start not all up'

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# charging (ticket #57 / spec 0007: Charging Animation end-to-end)')
$out.Add('protocol            : unplug -> set ac 1 (cast) -> unplug -> DND on -> set ac 1 (exempt) -> gate flips -> notification -> clear -> unplug (handback) -> switch off/on -> battery reset')
$out.Add('plug driving        : dumpsys battery unplug/set ac 1 (system emits POWER_*; am broadcast is a protected-broadcast denial from uid 2000)')
$out.Add(('posture driving     : none (on-change proximity sensor; postureFaceDown recorded per leg)'))
$out.Add(('start               : zen={0} rear={1} owner={2} battery={3}% usb={4} switchNormalized={5}' -f $zen, (Format-ExStatePair $rear), (Get-ExRearOwnerNow), $battery.Level, $battery.UsbPowered, $normalized))
# `# verdicts` marks the block 05-collect embeds in summary.md (same marker as the E-series
# artifacts) -- without it the summary's "step verdicts" section stays empty.
$out.Add('# verdicts')
foreach ($line in (Format-ExStepVerdictLines)) { $out.Add($line) }
$out.Add(('overall             : {0}' -f $overall))
Write-ExArtifact -Name 'charging.txt' -Lines $out.ToArray() | Out-Null
foreach ($line in $out) { Write-ExNote $line }

# ---- 8. restore -----------------------------------------------------------------------
if (-not $NoRestore) {
    Write-ExNote 'restoring: notifications cleared, DND off, battery override reset'
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = $shellPkg }
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'reset') -AllowFailure | Out-Null
    Start-Sleep -Seconds 3
    # The phone really IS USB-powered: `reset` restores that state and the system broadcasts
    # POWER_CONNECTED again, so the app (switch on) re-casts the charging screen. That is the
    # CORRECT end state, not a leftover -- recorded here instead of being masked.
    $afterReset = Get-ExAppStateNow
    Write-ExNote ('restore check: zen={0} owner={1} castSource={2} (real charger still attached)' -f
        (Get-ExZenMode), (Get-ExRearOwnerNow), $(if ($afterReset) { $afterReset.CastSource } else { 'n/a' }))
}

# ---- 9. scenario notes ------------------------------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (Charging Animation, ticket #57 / spec 0007)')
$notes.Add('')
$notes.Add('- **How the plug was triggered (software only)**: `am broadcast -a')
$notes.Add('  android.intent.action.ACTION_POWER_CONNECTED` is refused on this build')
$notes.Add('  (`SecurityException: Permission Denial ... from pid=..., uid=2000` -- protected')
$notes.Add('  broadcast, captured in the session transcript). The battery service emits the two')
$notes.Add('  broadcasts itself when its plug state changes, so the legs drive it with')
$notes.Add('  `dumpsys battery unplug` (DISCONNECTED) and `dumpsys battery set ac 1`')
$notes.Add('  (CONNECTED, only after an unplug -- a bare `set ac 1` changes no plug type while')
$notes.Add('  the phone is already USB-powered, therefore no broadcast). `dumpsys battery')
$notes.Add('  reset` restores the real state in the restore step (mandatory).')
$notes.Add('- **Every leg is judged from the app own `power-connected` / `power-disconnected`')
$notes.Add('  refresh lines + the STATE echo (`castSource=CHARGING`), never from shell exit codes.')
$notes.Add('- **Posture Gate**: the proximity sensor is on-change with no adb path, so the run')
$notes.Add('  READS the committed posture: `exempt-posture` is judged only when the gate is')
$notes.Add('  physically closed at cast time (postureFaceDown=false, phone face-up -- the reading')
$notes.Add('  this device had on 2026-09-26: proximity 5.00 far, gravity z=-9.80 screen-up) and')
$notes.Add('  is SKIPPED when the gate is open (nothing to exempt). The DND leg carries the')
$notes.Add('  deterministic exemption proof either way.')
$notes.Add('- **Process start does not read the real plug state** (observed): core `plugged`')
$notes.Add('  starts false and only POWER_CONNECTED/DISCONNECTED broadcasts change it, so every')
$notes.Add('  leg works from a deliberate transition, never from the ambient charger state.')
$notes.Add('- **End state**: the device stays on USB power, so after `battery reset` the app')
$notes.Add('  re-casts the charging screen -- the truthful state of a plugged-in phone.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Overall           = $overall
    Cast              = (Get-ExSteps | Where-Object { $_.Step -eq 'cast' }).Pass
    ExemptDnd         = (Get-ExSteps | Where-Object { $_.Step -eq 'exempt-dnd' }).Pass
    HoldVsGates       = (Get-ExSteps | Where-Object { $_.Step -eq 'hold-vs-gates' }).Pass
    Coexist           = (Get-ExSteps | Where-Object { $_.Step -eq 'coexist' }).Pass
    Handback          = (Get-ExSteps | Where-Object { $_.Step -eq 'handback-conjunction' }).Pass
    MasterSwitch      = (Get-ExSteps | Where-Object { $_.Step -eq 'master-switch' }).Pass
}
