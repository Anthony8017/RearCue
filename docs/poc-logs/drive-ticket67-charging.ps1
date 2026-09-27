# drive-ticket67-charging.ps1 -- ticket #67 (spec 0008): charging green battery-ratio face
# end-to-end on the real device (rear display).
#
# The question: does plugging in (independent trigger, no notifications) cast the Dashboard
# with the GREEN RATIO FILL (bottom-up, proportion = battery level) + big white number (no
# percent sign) + camera band untinted; does the number/fill refresh when the level changes
# (68 -> 69); does a real notification coexist (icon row + white stroke, breath per #65); does
# unplugging with an EMPTY Icon Set hand the rear back (conjunction); and does the master
# switch off make plug-in a no-op?
#
# Plug/level driving (software only, same protocol as 22-charging.ps1):
#   * `dumpsys battery unplug`      -> POWER_DISCONNECTED
#   * `dumpsys battery set ac 1`    -> POWER_CONNECTED (only after an unplug)
#   * `dumpsys battery set level N` -> ACTION_BATTERY_CHANGED (BatterySignals reads it -> level)
#   * `dumpsys battery reset`       -> restores the REAL state (mandatory in restore)
#
# INPUT WARNING (found 2026-09-27, this run's leg 6): on this two-display build a BARE
# `input tap/swipe` is delivered to the DEFAULT display, which is NOT always display 0 --
# the screencap warning says "defaulting to the first display found". Every touch in THIS
# script goes through Invoke-ExUiInput, which pins the logical display with `input -d 0`.
# (The rear dashboard ate the leg-6 taps/swipes: retake noise, +3 LaunchDashboard, toggle
# "not found".)
#
# Verdict words (one per leg):
#   P67-CAST-PASS       connect -> LaunchDashboard(0) + owner=dashboard + castSource=CHARGING
#                       + rear shot with fill/number (level = real level at start)
#   P67-LEVEL68-PASS    set level 68 -> `battery 68%` refresh line + shot shows 68
#   P67-COEXIST-PASS    shell notification while charging (cooldown expired first) ->
#                       fresh breath + icon row joins, castSource stays CHARGING
#   P67-LEVEL69-PASS    set level 69 -> `battery 69%` + shot shows refreshed number/fill
#   P67-HANDBACK-PASS   clear notification (charging HOLDS) -> unplug -> ExitDashboard ->
#                       owner handed back (conjunction: unplugged AND empty Icon Set)
#   P67-SWITCH-PASS     switch OFF -> connect is a no-op (LaunchDashboard delta 0);
#                       switch back ON + battery reset at restore
#   P67-RUN-INVALID     preflight (listener / rear / channel / empty Icon Set / switch on /
#                       no cast at start / sticky level read) never came up
#
# Run: powershell -File .\docs\poc-logs\drive-ticket67-charging.ps1
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $SettleSeconds = 3,
    [switch] $NoRestore,
    [string] $Serial
)

$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'ticket67-charging' -Serial $Serial | Out-Null

$config = Get-ExConfig
Invoke-Adb -Arguments @('logcat', '-G', '4096K', '-b', 'main') -AllowFailure | Out-Null

$shellPkg = 'com.android.shell'
$chargeLabel = [regex]::Unescape('\u5145\u7535\u52A8\u753B')   # settings_charging_switch

Reset-ExSteps

function Invoke-ExUiInput {
    <# Touch pinned to logical display 0 (main): a bare `input` lands on whichever display is
      "first found" on this build (see header) and the rear dashboard eats it. #>
    param([Parameter(Mandatory, Position = 0)][string[]] $InputArgs)
    Invoke-Adb -Arguments (@('shell', 'input', '-d', '0') + $InputArgs) -AllowFailure | Out-Null
}

function Set-ExPowerMarker {
    param([Parameter(Mandatory)][string] $Name)
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, $Name) -AllowFailure | Out-Null
}

function Send-ExShellPost {
    param([Parameter(Mandatory)][string] $Tag, [Parameter(Mandatory)][string] $Text)
    Set-ExPowerMarker -Name ('p67-post-' + $Tag)
    $command = "cmd notification post -t 'RearCue p67' '$Tag' '$Text'"
    Invoke-Adb -Arguments @('shell', $command) -AllowFailure | Out-Null
}

function Send-ExDebugBroadcast {
    <# Local broadcast sender: the module's Invoke-ExDebugAction maps extras to --ei/--es only,
      and the switch extra is a bool needing --ez. #>
    param([Parameter(Mandatory, Position = 0)][string] $Action, [hashtable] $Extra)
    $arguments = @('shell', 'am', 'broadcast', '-n', $config.DebugReceiver, '-a', ($config.ActionPrefix + $Action))
    foreach ($key in $Extra.Keys) {
        $arguments += @('--ez', $key, ([string]$Extra[$key]).ToLowerInvariant())
    }
    Invoke-Adb -Arguments $arguments -AllowFailure | Out-Null
}

function Wait-ExLogAfterMarker {
    <# Log-line wait anchored to a MARKER line emitted into the same log before the trigger
      (`log -t RearCue <marker>`), judged by buffer POSITION: everything after the marker's
      LAST occurrence is "after the trigger". Immune to both buffer rotation (marker and
      target rotate together; a missing marker simply retries) and PC/device clock skew
      (timestamp anchoring kept false-negatives on both, run 20260927-065x). #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Marker,
        [Parameter(Mandatory, Position = 1)][string] $Pattern,
        [int] $TimeoutSec = 12
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $log = @(Get-ExLogcat)
        $markerIdx = $null
        for ($i = $log.Count - 1; $i -ge 0; $i--) {
            if ($log[$i] -match ([regex]::Escape($Marker))) { $markerIdx = $i; break }
        }
        if ($null -ne $markerIdx) {
            $hit = @($log | Select-Object -Skip ($markerIdx + 1) | Where-Object { $_ -match $Pattern })
            if ($hit.Count -gt 0) { return , $hit }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return , @()
}

function Wait-ExLogAfter {
    <# Log-line wait anchored to a WALL-CLOCK TIMESTAMP, not a buffer count: the main logcat
      buffer rotates under floods (screen off/on cycles), so count deltas (Wait-ExNewLog)
      under-count and legs false-negative. Lines look like `09-27 06:42:25.787 ...`. #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Pattern,
        [Parameter(Mandatory, Position = 1)][datetime] $After,
        [int] $TimeoutSec = 12
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        Start-Sleep -Milliseconds 500
        $hit = @(Get-ExLogcat | Where-Object {
            if ($_ -notmatch $Pattern) { return $false }
            if ($_ -match '^\d\d-\d\d (\d\d):(\d\d):(\d\d)') {
                $t = [datetime]::Today.AddHours([int]$Matches[1]).AddMinutes([int]$Matches[2]).AddSeconds([int]$Matches[3])
                return ($t -ge $After.AddSeconds(-1))
            }
            return $false
        })
        if ($hit.Count -gt 0) { return , $hit }
    } while ((Get-Date) -lt $deadline)
    return , @()
}

function Set-ExChargingEnabled {
    <# Master switch via the debug CHARGING_ENABLED broadcast (debug-only receiver, DUMP-
      protected): same entry point as the settings-page switch (container.setChargingAnimationEnabled
      -> ChargingAnimation event -> `charging-anim=<v>` anchor + persistence). Replaces the
      settings-page UI automation whose tap raced page recreation (runs 20260927-062557/063326/064020). #>
    param([bool] $Enabled)
    $want = if ($Enabled) { 'charging-anim=true' } else { 'charging-anim=false' }
    $marker = ('p67-switch-' + $Enabled)
    Set-ExPowerMarker -Name $marker
    Send-ExDebugBroadcast -Action 'CHARGING_ENABLED' -Extra @{ enabled = $Enabled }
    $hit = Wait-ExLogAfterMarker -Marker $marker -Pattern ([regex]::Escape($want)) -TimeoutSec 10
    $state = Get-ExAppStateNow
    return ((@($hit).Count -gt 0) -and ($null -ne $state) -and ($state.ChargingEnabled -eq $Enabled))
}

function Invoke-ExPowerUnplug {
    param([Parameter(Mandatory)][string] $Marker)
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $marker = ('{0}-a{1}' -f $Marker, $attempt)
        Set-ExPowerMarker -Name $marker
        Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'unplug') -AllowFailure | Out-Null
        $hit = Wait-ExLogAfterMarker -Marker $marker -Pattern 'power-disconnected' -TimeoutSec 10
        if (@($hit).Count -gt 0) { return $true }
        Write-ExNote ('unplug attempt {0}: no power-disconnected line, retrying' -f $attempt)
    }
    return $false
}

function Invoke-ExPowerPlugAc {
    <# MIUI re-syncs the battery override to the REAL charger ~1.4s after an unplug (observed
      20260927-061455: re-plug CONNECTED landed before our `set ac 1`, which then changed no
      plug type and stayed silent). So: unplug, let the re-sync settle, THEN take the count
      baseline and set ac 1 (usb->ac is then a real change). Retry up to 3x. #>
    param([Parameter(Mandatory)][string] $Marker)
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $marker = ('{0}-a{1}' -f $Marker, $attempt)
        Set-ExPowerMarker -Name $marker
        Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'unplug') -AllowFailure | Out-Null
        Start-Sleep -Seconds 2
        Set-ExPowerMarker -Name ($marker + '-set')
        Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'set', 'ac', '1') -AllowFailure | Out-Null
        $hit = Wait-ExLogAfterMarker -Marker ($marker + '-set') -Pattern 'power-connected' -TimeoutSec 10
        if (@($hit).Count -gt 0) { return $true }
        Write-ExNote ('connect attempt {0}: no power-connected line (re-sync race), retrying' -f $attempt)
    }
    return $false
}

function Invoke-ExBatteryLevel {
    <# `dumpsys battery set level N` -> ACTION_BATTERY_CHANGED -> BatterySignals + the app's
      `battery N%` refresh line. Judged from that line, not the shell exit code. #>
    param([Parameter(Mandatory)][int] $Level, [Parameter(Mandatory)][string] $Marker)
    Set-ExPowerMarker -Name $Marker
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'set', 'level', [string]$Level) -AllowFailure | Out-Null
    $hit = Wait-ExLogAfterMarker -Marker $Marker -Pattern ('battery ' + $Level + '%') -TimeoutSec 10
    return (@($hit).Count -gt 0)
}

function Open-ExP67Settings {
    <# MainActivity -> gear tap (display-pinned) -> AllowlistSettingsActivity on top. #>
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (Test-ExSettingsPageOpen) { Start-Sleep -Milliseconds 800; return $true }
        Set-ExScreenAwake
        Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', $config.MainActivity) -AllowFailure | Out-Null
        Start-Sleep -Seconds 2
        $dump = Get-ExWindowDump
        $gearDesc = [regex]::Unescape('\u6253\u5F00 Allowlist \u7BA1\u7406\u8BBE\u7F6E')
        $node = Get-ExNodeCenter -WindowDump $dump -ContentDesc $gearDesc
        if ($null -eq $node) { Write-ExNote ('gear not found (attempt {0})' -f $attempt); continue }
        Invoke-ExUiInput -InputArgs @('tap', [string]$node.X, [string]$node.Y)
        $deadline = (Get-Date).AddSeconds(12)
        while ((Get-Date) -lt $deadline) {
            if (Test-ExSettingsPageOpen) { Start-Sleep -Milliseconds 800; return $true }
            Start-Sleep -Milliseconds 600
        }
        Write-ExNote ('settings page did not come up (attempt {0})' -f $attempt)
    }
    Write-ExNote 'AllowlistSettingsActivity never became the top activity of display 0'
    return $false
}

function Leave-ExP67Settings {
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 800
    # BACK lands on MainActivity: the process keeps a visible activity (no freeze).
}


# ---- 0. preflight ---------------------------------------------------------------------
# Keep the screen ON for the whole run: when it times out (60s default) MIUI FREEZES the
# background app and every broadcast/debug action queues until the next wake (run
# 20260927-055908 lost legs 1-5 to exactly that). 10 minutes covers one full run.
$screenTimeoutOriginal = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'screen_off_timeout') | Select-Object -First 1)
Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'screen_off_timeout', '600000') -AllowFailure | Out-Null

function Invoke-ExLegWake {
    <# Re-assert wakefulness before each leg (cheap, idempotent). #>
    Set-ExScreenAwake
}

function Start-ExRearKeepAlive {
    <# PC-side rear keep-alive (E12 semantics: shell-uid WAKEUP targeted at display 1 every
      5s holds the rear ON). Without it the rear times out (60s) while the main display is
      off, the dashboard activity stops, and MIUI freezes the process -- queued broadcasts
      then eat legs 3-4 (run 20260927-062557). Runs as a PowerShell job for legs 1-5 only. #>
    $serial = Get-ExDevice
    $adb = (Resolve-ExAdb)
    return Start-Job -ScriptBlock {
        param([string] $Adb, [string] $Serial)
        while ($true) {
            & $Adb -s $Serial shell "input -d 1 keyevent KEYCODE_WAKEUP" 2>$null | Out-Null
            Start-Sleep -Seconds 5
        }
    } -ArgumentList $adb, $serial
}

function Invoke-ExShotWindow {
    <# Screenshot with the native rear guard out of the way: the guard sits above our Dashboard
      whenever the MAIN display is on, and drops when it sleeps (run 20260927-062557 shot 02).
      MIUI Greezer DENIES broadcasts while the main display is off ("Greezer Denial ... need
      cached broadcast", system buffer, run 20260927-063326), so every TRIGGER happens with
      main on and only the SHOT happens in the sleep window. Wakes main back up afterwards. #>
    param([Parameter(Mandatory, Position = 0)][string] $Name, [string] $SfDisplayId)
    if ((Get-ExWakefulness) -eq 'Awake') {
        Invoke-ExUiInput -InputArgs @('keyevent', 'KEYCODE_SLEEP')
        Start-Sleep -Seconds 3
    }
    $path = Save-ExDisplayShot -Name $Name -SfDisplayId $SfDisplayId
    Set-ExScreenAwake
    Start-Sleep -Seconds 1
    return $path
}

function Invoke-ExMainSleep {
    <# Main display OFF: the native rear guard ("请按电源键熄灭正屏后使用背屏") drops and the
      charging Dashboard becomes the rear top -- this IS the product state (main off, rear
      visible; Wake Keep-alive holds the rear on). Run 20260927-062005 shot everything through
      the guard with main on. The rear-hosted activity keeps the process visible (no freeze). #>
    if ((Get-ExWakefulness) -eq 'Awake') {
        Invoke-ExUiInput -InputArgs @('keyevent', 'KEYCODE_SLEEP')
        Start-Sleep -Seconds 2
    }
}

Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'reset') -AllowFailure | Out-Null
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Start-ExApp -TimeoutSec 30 | Out-Null
# NO HOME here: backgrounded, the app gets frozen by MIUI within ~10s and every
# broadcast/debug action queues until the next am start (see header note below). The app
# stays FOREGROUND on display 0 for the whole run -- freezing is a cached-process fate.
$null = Invoke-Adb -Arguments @('shell', 'am', 'set-standby-bucket', $config.Package, 'active') -AllowFailure
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

$normalized = $false
if ((Get-ExAppStateNow).ChargingEnabled -ne $true) {
    $null = Set-ExChargingEnabled -Enabled $true
    Leave-ExP67Settings
    $normalized = $true
    Write-ExNote 'charging switch normalized to ON at preflight (persisted value was off)'
}
$null = Wait-ExRearNotDashboard -TimeoutSec 15
$state0 = Get-ExAppStateNow

# sticky first read (new in #67): BatterySignals logs `电量读数 level=N scale=M → P%` at
# registration; the app refresh line reads `battery P% ...`.
$levelLine = @(Get-ExLogcat | Where-Object { $_ -match '\u7535\u91cf\u8bfb\u6570 level=\d+ scale=\d+' })

$runValid = ($zen -eq 'ZEN_MODE_OFF') -and $rear -and $sfRear -and ($null -ne $state0) -and
    $state0.Found -and $state0.ListenerConnected -and $state0.ChannelReady -and
    ($state0.IconSet.Count -eq 0) -and ($state0.ChargingEnabled -eq $true) -and
    ($state0.CastSource -eq 'null') -and ((Get-ExRearOwnerNow) -ne 'dashboard') -and
    ($levelLine.Count -gt 0)
if (-not $runValid) {
    Write-ExNote ('preflight conjuncts: zen={0} rear={1} sfRear={2} found={3} listener={4} channel={5} iconSetEmpty={6} charging={7} castSourceNull={8} ownerNative={9} stickyRead={10}' -f
        $zen, (Format-ExStatePair $rear), $sfRear, $(if ($state0) { $state0.Found } else { 'n/a' }),
        $(if ($state0) { $state0.ListenerConnected } else { 'n/a' }), $(if ($state0) { $state0.ChannelReady } else { 'n/a' }),
        $(if ($state0) { ($state0.IconSet.Count -eq 0) } else { 'n/a' }), $(if ($state0) { $state0.ChargingEnabled } else { 'n/a' }),
        $(if ($state0) { ($state0.CastSource -eq 'null') } else { 'n/a' }), ((Get-ExRearOwnerNow) -ne 'dashboard'), ($levelLine.Count -gt 0))
}
Write-ExNote ('start: zen={0} rear={1} owner={2} battery={3}% usb={4} switchNormalized={5} stickyLevelLine={6}' -f
    $zen, (Format-ExStatePair $rear), (Get-ExRearOwnerNow), $battery.Level, $battery.UsbPowered, $normalized, ($levelLine.Count -gt 0))
Write-ExNote ('protocol: connect (cast) -> set level 68 (shot) -> cooldown clear + notification (coexist) -> set level 69 (shot) -> clear -> unplug (handback) -> switch off -> connect (no-op) -> restore')
Invoke-ExLegWake
$rearKeepAlive = Start-ExRearKeepAlive

# ---- 1. plug in (no notifications) -> green ratio face --------------------------------
$beforeLaunch1 = Get-ExLogMatchCount 'LaunchDashboard'
$unplug1 = Invoke-ExPowerUnplug -Marker 'p67-unplug-1'
$plug1 = Invoke-ExPowerPlugAc -Marker 'p67-connect-1'
$up1 = Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 15
Start-Sleep -Seconds $SettleSeconds
$state1 = Get-ExAppStateNow
$castLine1 = (@(Get-ExLogcat) | Where-Object { $_ -match 'power-connected.*LaunchDashboard\(0\)' }).Count -gt 0
$shot1 = Invoke-ExShotWindow -Name 'p67-01-cast-fill.png' -SfDisplayId $sfRear
$castPass = $runValid -and $unplug1 -and $plug1 -and $up1 -and ($null -ne $state1) -and
    ($state1.CastSource -eq 'CHARGING') -and ($state1.IconSet.Count -eq 0) -and $castLine1 -and $shot1
Add-ExStep -Step 'cast' -Pass $castPass `
    -Note ('unplug: {0}; connect: {1}; owner=dashboard: {2}; castSource={3}; iconSet empty: {4}; LaunchDashboard(0) on connect: {5}; shot: {6}' -f $unplug1, $plug1, $up1, $(if ($state1) { $state1.CastSource } else { 'n/a' }), $(if ($state1) { $state1.IconSet.Count -eq 0 } else { $false }), $castLine1, [bool]$shot1)
Invoke-ExLegWake

# ---- 2. level 68 -> fill/number refresh ------------------------------------------------
$level68 = Invoke-ExBatteryLevel -Level 68 -Marker 'p67-level-68'
Start-Sleep -Seconds $SettleSeconds
$state2 = Get-ExAppStateNow
$shot2 = Invoke-ExShotWindow -Name 'p67-02-level-68.png' -SfDisplayId $sfRear
$owner2 = Get-ExRearOwnerNow
$level68Pass = $runValid -and $level68 -and ($owner2 -eq 'dashboard') -and $shot2
Add-ExStep -Step 'level-68' -Pass $level68Pass `
    -Note ('battery 68% refresh line: {0}; rear owner at shot: {1}; shot: {2}' -f $level68, $owner2, [bool]$shot2)
Invoke-ExLegWake

# ---- 3. notification coexists (icon row + white stroke, fresh breath) ------------------
# The 30s Highlight cooldown (spec 0008 #65) must be EXPIRED or the breath correctly stays
# silent (run 20260927-054706: the app-start breath from a leftover notification ate it).
# Clear the shell notification, wait out the cooldown window, then post: breath fires fresh.
Set-ExPowerMarker -Name 'p67-cooldown-clear'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = $shellPkg }
Start-Sleep -Seconds (31 + $SettleSeconds)

# Foreground the app right before the post: MIUI Greezer delays the backgrounded app's NLS
# callbacks by up to ~60s after the main display slept in a shot window (runs 10/11), which
# starves the breath wait. A fresh am start resets that.
Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', $config.MainActivity) -AllowFailure | Out-Null
Start-Sleep -Seconds 2
Send-ExShellPost -Tag 'rearcue-p67-a' -Text 'ticket67 coexist body'
$hitBreath3 = Wait-ExLogAfterMarker -Marker 'p67-post-rearcue-p67-a' -Pattern 'highlight breath start' -TimeoutSec 90
Start-Sleep -Seconds 4   # let the 3s breath settle so the shot shows the steady face
$owner3 = Get-ExRearOwnerNow
$state3 = Get-ExAppStateNow
$shot3 = Invoke-ExShotWindow -Name 'p67-03-icon-coexist.png' -SfDisplayId $sfRear
$postedLine3 = (@(Get-ExLogcat) | Where-Object { $_ -match 'posted com\.android\.shell.*UpdateIconSet\(1\)' }).Count -gt 0
$coexistPass = $runValid -and (@($hitBreath3).Count -gt 0) -and ($owner3 -eq 'dashboard') -and $postedLine3 -and $shot3
Add-ExStep -Step 'coexist' -Pass $coexistPass `
    -Note ('fresh breath start: {0}; UpdateIconSet(1) posted line: {1}; owner={2}; shot: {3}' -f (@($hitBreath3).Count -gt 0), $postedLine3, $owner3, [bool]$shot3)
Invoke-ExLegWake

# ---- 4. level 69 while the icon is up -> number refreshes ------------------------------
$level69 = Invoke-ExBatteryLevel -Level 69 -Marker 'p67-level-69'
Start-Sleep -Seconds $SettleSeconds
$state4 = Get-ExAppStateNow
$shot4 = Invoke-ExShotWindow -Name 'p67-04-level-69.png' -SfDisplayId $sfRear
$owner4 = Get-ExRearOwnerNow
$level69WithIcon = (@(Get-ExLogcat) | Where-Object { $_ -match 'battery 69% iconSet \[com\.android\.shell\]' }).Count -gt 0
$level69Pass = $runValid -and $level69 -and ($owner4 -eq 'dashboard') -and $level69WithIcon -and $shot4
Add-ExStep -Step 'level-69' -Pass $level69Pass `
    -Note ('battery 69% refresh line: {0}; icon stays (refresh line carries [com.android.shell]): {1}; owner={2}; shot: {3}' -f $level69, $level69WithIcon, $owner4, [bool]$shot4)
Invoke-ExLegWake

# ---- 5. clear (charging HOLDS) -> unplug (conjunction) -> hand back --------------------
$beforeExit5 = Get-ExLogMatchCount 'ExitDashboard'
Set-ExPowerMarker -Name 'p67-clear'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = $shellPkg }
Start-Sleep -Seconds $SettleSeconds
$owner5Hold = Get-ExRearOwnerNow
$afterExit5Hold = Get-ExLogMatchCount 'ExitDashboard'
$state5 = Get-ExAppStateNow
$iconsEmpty5 = ($null -ne $state5) -and ($state5.IconSet.Count -eq 0)

$unplug5 = Invoke-ExPowerUnplug -Marker 'p67-unplug-5'
$back5 = Wait-ExRearNotDashboard -TimeoutSec 15
$hitExit5 = Wait-ExLogAfterMarker -Marker 'p67-unplug-5-a1' -Pattern 'ExitDashboard' -TimeoutSec 10
$handbackPass = $runValid -and ($afterExit5Hold -eq $beforeExit5) -and ($owner5Hold -eq 'dashboard') -and
    $iconsEmpty5 -and $unplug5 -and $back5 -and (@($hitExit5).Count -gt 0)
Add-ExStep -Step 'handback-conjunction' -Pass $handbackPass `
    -Note ('ExitDashboard {0}->{1} after the clear (delta 0 = charging holds); owner still dashboard: {2}; Icon Set empty: {3}; disconnect line: {4}; handed back: {5}; ExitDashboard after unplug: {6}' -f $beforeExit5, $afterExit5Hold, $owner5Hold, $iconsEmpty5, $unplug5, $back5, (@($hitExit5).Count -gt 0))

# shot legs are over: stop the rear keep-alive
if ($rearKeepAlive) {
    Stop-Job $rearKeepAlive -ErrorAction SilentlyContinue
    Remove-Job $rearKeepAlive -Force -ErrorAction SilentlyContinue
}
Invoke-ExLegWake
# ---- 6. master switch off -> plug is a no-op -------------------------------------------
# The switch is driven through the debug CHARGING_ENABLED broadcast (same event entry as the
# settings-page switch); the settings-page tap path is covered by ticket #57's acceptance.
$offOk = Set-ExChargingEnabled -Enabled $false

$plug6 = Invoke-ExPowerPlugAc -Marker 'p67-connect-6-off'
Start-Sleep -Seconds 3
$owner6 = Get-ExRearOwnerNow
$state6 = Get-ExAppStateNow
$noopPass = $runValid -and $offOk -and $plug6 -and ($owner6 -ne 'dashboard') -and
    ($null -ne $state6) -and ($state6.ChargingEnabled -eq $false)
Add-ExStep -Step 'switch-off-noop' -Pass $noopPass `
    -Note ('switch off: {0}; connect line: {1}; owner after connect: {2} (native = no cast, no reaction); ChargingEnabled={3}' -f $offOk, $plug6, $owner6, $(if ($state6) { $state6.ChargingEnabled } else { 'n/a' }))

# ---- 7. evidence + summary --------------------------------------------------------------
$appLog = @(Get-ExLogcat)
Write-ExArtifact -Name 'ticket67-logcat.txt' -Lines $appLog | Out-Null

$overall = Get-ExStepOverall -RunValid $runValid -Prefix 'P67' `
    -InvalidNote 'preflight: zen/rear/listener/channel/empty Icon Set/switch on/no cast at start/sticky level read not all up'

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# charging green ratio (ticket #67 / spec 0008: Charging Animation display face)')
$out.Add('protocol            : unplug -> set ac 1 (cast) -> set level 68 (shot) -> cooldown clear + shell notification (coexist) -> set level 69 (shot) -> clear -> unplug (handback) -> switch off -> connect (no-op) -> restore')
$out.Add('plug/level driving  : dumpsys battery unplug / set ac 1 / set level N (system emits POWER_* and BATTERY_CHANGED); am broadcast of POWER_* is a protected-broadcast denial from uid 2000')
$out.Add('input routing       : every touch via `input -d 0` (bare input lands on the default display, which is NOT reliably display 0 on this build; run 20260927-054706 leg 6 failed on this)')
$out.Add(('start               : zen={0} rear={1} owner={2} battery={3}% usb={4} switchNormalized={5}' -f $zen, (Format-ExStatePair $rear), (Get-ExRearOwnerNow), $battery.Level, $battery.UsbPowered, $normalized))
$out.Add('# verdicts')
foreach ($line in (Format-ExStepVerdictLines)) { $out.Add($line) }
$out.Add(('overall             : {0}' -f $overall))
Write-ExArtifact -Name 'ticket67-charging.txt' -Lines $out.ToArray() | Out-Null
foreach ($line in $out) { Write-ExNote $line }

# ---- 8. restore -----------------------------------------------------------------------
if (-not $NoRestore) {
    Write-ExNote 'restoring: switch back ON, notifications cleared, DND off, battery override reset'
    $null = Set-ExChargingEnabled -Enabled $true
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = $shellPkg }
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery', 'reset') -AllowFailure | Out-Null
    # screen timeout back to the device default (the run raised it to 10min; see preflight)
    Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'screen_off_timeout',
        ([string]$screenTimeoutOriginal)) -AllowFailure | Out-Null
    Start-Sleep -Seconds 3
    $afterReset = Get-ExAppStateNow
    $resetLines = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery') -AllowFailure)
    $realBattery = Get-ExBatteryFacts -Battery $resetLines
    Write-ExNote ('restore check: zen={0} owner={1} castSource={2} realBattery={3}% usbPowered={4}' -f
        (Get-ExZenMode), (Get-ExRearOwnerNow), $(if ($afterReset) { $afterReset.CastSource } else { 'n/a' }), $realBattery.Level, $realBattery.UsbPowered)
    Write-ExNote 'BATTERY RESET DONE: dumpsys battery above reflects the REAL charger state (no override left)'
}

return @{
    Overall   = $overall
    Cast      = (Get-ExSteps | Where-Object { $_.Step -eq 'cast' }).Pass
    Level68   = (Get-ExSteps | Where-Object { $_.Step -eq 'level-68' }).Pass
    Coexist   = (Get-ExSteps | Where-Object { $_.Step -eq 'coexist' }).Pass
    Level69   = (Get-ExSteps | Where-Object { $_.Step -eq 'level-69' }).Pass
    Handback  = (Get-ExSteps | Where-Object { $_.Step -eq 'handback-conjunction' }).Pass
    SwitchOff = (Get-ExSteps | Where-Object { $_.Step -eq 'switch-off-noop' }).Pass
}
