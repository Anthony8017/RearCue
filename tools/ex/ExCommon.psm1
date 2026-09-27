# ExCommon.psm1 -- shared helpers for the RearCue PC experiment script set (ticket #7).
#
# Two halves, deliberately separated:
#   * PURE parsers (Get-DisplayInfoBlocks / Get-RearDisplay / Get-DisplayTopActivity /
#     Get-RearScreenOwner / Get-RearCueEvent / Test-RearCueCrash / Get-ExChainSummary /
#     Get-ExLockSampleFacts / Get-ExWakeSampleFacts / Get-ExWakeTickFacts /
#     Get-ExPowerGroupEvents / Get-ExWakePollution / Get-ExTaskPlacement /
#     ConvertTo-ExServiceCallResult / Get-ExTaskMoveEvents / Get-ExTaskMoveSampleFacts /
#     Get-ExShuidProbeFacts / Get-ExShuidProbeWindow / Get-ExShuidWindowEvents /
#     Get-ExGreezeEvents / Get-ExFreezeTimeline / Get-ExFreezeSampleFacts):
#     dumpsys or logcat text in, structured facts out. No device, no adb -- these are the
#     JVM-free seam unit-tested by tools/ex/tests/ExCommon.Tests.ps1 (Pester).
#   * DEVICE helpers (Invoke-Adb / New-ExSession / Write-ExArtifact / Wait-Ex*): thin adb wrappers.
#
# NOTE: this file is deliberately ASCII-only. Windows PowerShell 5.1 (the only PowerShell on
# this machine) reads BOM-less .ps1 files as ANSI/GBK, so Chinese literals in the source would
# turn into mojibake or parse errors -- the same trap that already bit the AIDL/manifest files
# (docs/poc-findings.md, ticket #4). Chinese captured FROM the device is data, never source.

Set-StrictMode -Version 2.0

# adb prints UTF-8 (app logs carry Chinese); decode native output as UTF-8 instead of the
# console's GBK default, otherwise every captured log line comes out as mojibake.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$script:ExRepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:ExAdb = $null
$script:ExSerial = $null
$script:ExSessionDir = $null

$script:ExConfig = [pscustomobject]@{
    Package           = 'com.rearcue.poc'
    MainActivity      = 'com.rearcue.poc/.ui.MainActivity'
    DashboardActivity = 'com.rearcue.poc/.rear.RearDashboardActivity'
    ListenerComponent = 'com.rearcue.poc/com.rearcue.poc.notify.RearNotificationListener'
    DebugReceiver     = 'com.rearcue.poc/.DebugCommandReceiver'
    ActionPrefix      = 'com.rearcue.poc.action.'
    LogTag            = 'RearCue'
    OverlayTitle      = 'RearCueOverlayProbe'
    Apk               = 'app\build\outputs\apk\debug\app-debug.apk'
    LogDir            = 'docs\poc-logs'
    StartShizuku      = 'tools\ex\device\start-shizuku.sh'
    # MIUI autostart management page (ticket #27, manifest + live verified 2026-09-23).
    AutostartAction   = 'miui.intent.action.OP_AUTO_START'
    AutostartCategory = 'android.intent.category.DEFAULT'
    AutostartActivity = 'com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity'
    AutostartProvider = 'content://com.lbe.security.miui.autostartmgr'
}

function Get-ExConfig { $script:ExConfig }

function Get-ExRepoRoot { $script:ExRepoRoot }

function Get-ExPath {
    param([Parameter(Mandatory, Position = 0)][string] $Relative)
    Join-Path $script:ExRepoRoot $Relative
}

function Get-ExSessionDir { $script:ExSessionDir }

function Set-ExDevice {
    param([string] $Serial)
    $script:ExSerial = $Serial
}

function Get-ExDevice { $script:ExSerial }

function Resolve-ExAdb {
    if ($script:ExAdb) { return $script:ExAdb }

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:REARCUE_ADB) { $candidates.Add($env:REARCUE_ADB) }
    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA 'RearCue-tools\android-sdk\platform-tools\adb.exe'))
    }
    $properties = Get-ExPath 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        foreach ($line in (Get-Content -LiteralPath $properties -Encoding UTF8)) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+)$') {
                # local.properties escapes the Windows path (C\:\\Users\\...).
                $sdk = $Matches[1].Trim() -replace '\\\\', '\' -replace '\\:', ':'
                $candidates.Add((Join-Path $sdk 'platform-tools\adb.exe'))
            }
        }
    }
    $onPath = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
    if ($onPath) { $candidates.Add($onPath.Source) }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            $script:ExAdb = (Resolve-Path -LiteralPath $candidate).Path
            return $script:ExAdb
        }
    }
    throw 'adb not found: set REARCUE_ADB, or install the SDK under %LOCALAPPDATA%\RearCue-tools\android-sdk.'
}

function Write-ExNote {
    param([Parameter(Mandatory, Position = 0)][string] $Message)
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $Message
    Write-Host $line
    if ($script:ExSessionDir) {
        Add-Content -LiteralPath (Join-Path $script:ExSessionDir 'transcript.txt') -Value $line -Encoding UTF8
    }
}

function Invoke-Adb {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][string[]] $Arguments,
        [switch] $AllowFailure
    )
    $adb = Resolve-ExAdb
    $full = @()
    if ($script:ExSerial) { $full += @('-s', $script:ExSerial) }
    $full += $Arguments

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $adb @full 2>&1 | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() } else { [string]$_ }
        }
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }

    if ($script:ExSessionDir) {
        Add-Content -LiteralPath (Join-Path $script:ExSessionDir 'transcript.txt') `
            -Value ('$ adb {0}' -f ($full -join ' ')) -Encoding UTF8
    }
    if (-not $AllowFailure -and $exit -ne 0) {
        throw ('adb {0} failed (exit {1}): {2}' -f ($full -join ' '), $exit, ($output -join ' | '))
    }
    # `@($output)` on an EMPTY pipeline is a one-element array holding $null (`@($null)` trap),
    # which then fails every [string[]] binder downstream ("Cannot bind argument ... it is null").
    return @($output | Where-Object { $null -ne $_ })
}

function New-ExDeviceSession {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][string] $Name,
        [string] $Serial
    )
    $config = Get-ExConfig
    if ($Serial) { Set-ExDevice -Serial $Serial }
    if (-not $script:ExSerial) {
        # `adb devices` lines look like: `94250f9e               device product:pandora model:...`
        $devices = @(Invoke-Adb -Arguments @('devices') | Where-Object { $_ -match '^\S+\s+device(\s|$)' })
        if ($devices.Count -ne 1) {
            throw ('expected exactly one adb device, found {0}: {1}; pass -Serial' -f $devices.Count, ($devices -join ' / '))
        }
        Set-ExDevice -Serial (($devices[0] -split '\s+')[0])
    }

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $dir = Join-Path (Get-ExPath $config.LogDir) ('{0}-{1}' -f $stamp, $Name)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $script:ExSessionDir = $dir

    $meta = @(
        '# RearCue PC experiment session (tools/ex, ticket #7)',
        ('started  : {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')),
        ('device   : {0}' -f $script:ExSerial),
        ('session  : {0}' -f $dir),
        ('apk      : {0}' -f $config.Apk),
        '',
        '## device facts'
    ) + @(Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.display.id') -AllowFailure) +
      @(Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.model') -AllowFailure) +
      @(Invoke-Adb -Arguments @('shell', 'getprop', 'ro.miui.ui.version.name') -AllowFailure)

    Write-ExArtifact -Name 'session.md' -Lines $meta
    Write-ExNote ('session started on {0}' -f $script:ExSerial)
    Write-ExNote ('artifacts go to {0}' -f $dir)
    return $dir
}

function Write-ExArtifact {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][string] $Name,
        [Parameter(Mandatory, Position = 1)][AllowEmptyCollection()][AllowEmptyString()][string[]] $Lines,
        [string] $Directory,
        [switch] $Append
    )
    $target = if ($Directory) { $Directory } else { $script:ExSessionDir }
    if (-not $target) { throw 'no session directory: call New-ExDeviceSession first.' }
    $path = Join-Path $target $Name
    $parent = Split-Path -Parent $path
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    # adb output can hand back $null entries (empty commands); WriteAllLines wants real strings.
    $clean = @($Lines | ForEach-Object { if ($null -eq $_) { '' } else { [string]$_ } })
    $encoding = New-Object System.Text.UTF8Encoding($false)
    if ($Append -and (Test-Path -LiteralPath $path)) {
        [System.IO.File]::AppendAllLines($path, [string[]]$clean, $encoding)
    } else {
        [System.IO.File]::WriteAllLines($path, [string[]]$clean, $encoding)
    }
    return $path
}

function Get-ExLogcat {
    param([string[]] $Tag = @((Get-ExConfig).LogTag))
    $arguments = @('logcat', '-d', '-s') + $Tag
    Invoke-Adb -Arguments $arguments -AllowFailure
}

function Get-ExRearCueSummary {
    <#
      logcat -> parsed events -> the facts a scenario asserts on (one call instead of three
      lines). Collect through a List on purpose: `@(Get-ExLogcat)` inside an IF EXPRESSION is
      unrolled at the pipeline boundary, so an empty capture used to bind as $null and throw
      ("Cannot bind argument to parameter 'Logcat' because it is null") on runs without app logs.
    #>
    param([Parameter(Position = 0)][AllowEmptyCollection()][AllowNull()][string[]] $Logcat = @())
    $source = if ($null -ne $Logcat -and $Logcat.Count -gt 0) { $Logcat } else { Get-ExLogcat }
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($line in @($source)) {
        if ($null -ne $line) { $lines.Add([string]$line) }
    }
    return Get-ExChainSummary -Events (Get-RearCueEvent -Logcat $lines.ToArray())
}

function Invoke-ExDebugAction {
    <#
      Fire one debug-only broadcast. The broadcast can fail silently (wrong action name, app not
      running), which would look like "the app did nothing" in the evidence, so report what adb said.
    #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Action,
        [hashtable] $Extra
    )
    $config = Get-ExConfig
    $arguments = @('shell', 'am', 'broadcast', '-n', $config.DebugReceiver, '-a', ($config.ActionPrefix + $Action))
    if ($Extra) {
        foreach ($key in $Extra.Keys) {
            # int extras must go as --ei: a --es string makes getIntExtra return its default, and
            # the receiver would silently fall back (ticket #10 control run aimed the rear display
            # instead of display 0 because of exactly this).
            if ($Extra[$key] -is [int]) { $arguments += @('--ei', $key, [string]$Extra[$key]) }
            else { $arguments += @('--es', $key, [string]$Extra[$key]) }
        }
    }
    $output = @(Invoke-Adb -Arguments $arguments -AllowFailure)
    if (($output -join '') -notmatch 'Broadcast completed') {
        Write-ExNote ('debug action {0} not accepted: {1}' -f $Action, (($output -join ' ').Trim()))
    }
    return $output
}

function Wait-ExLog {
    param(
        [Parameter(Mandatory, Position = 0)][string] $Pattern,
        [int] $TimeoutSec = 15
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $hit = @(Get-ExLogcat | Where-Object { $_ -match $Pattern })
        if ($hit.Count -gt 0) { return ,$hit }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return ,@()
}

function Get-ExLogMatchCount {
    <#
      Matching lines in the CURRENT buffer. Capture this BEFORE issuing a trigger: adb round
      trips are slow enough that the app's reaction line can land in the buffer before a wait
      would take its own "before" snapshot (ticket #52 first run: every wait raced and lost).
    #>
    param([Parameter(Mandatory)][string] $Pattern)
    return @(@(Get-ExLogcat) | Where-Object { $_ -match $Pattern }).Count
}

function Wait-ExNewLog {
    <#
      Wait for the line count matching the pattern to grow past $Before (returns only the new
      matches). Keeps the full logcat intact for the session evidence -- unlike Clear-ExLogcat.
    #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Pattern,
        [Parameter(Mandatory, Position = 1)][int] $Before,
        [int] $TimeoutSec = 15
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        Start-Sleep -Milliseconds 500
        $hit = @(@(Get-ExLogcat) | Where-Object { $_ -match $Pattern })
        if ($hit.Count -gt $Before) { return , @($hit | Select-Object -Skip $Before) }
    } while ((Get-Date) -lt $deadline)
    return , @()
}

function Wait-ExRearNotDashboard {
    <# Inverse of Wait-ExRearOwner: poll until the rear owner is anything but our dashboard. #>
    param([int] $TimeoutSec = 15)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        if ((Get-ExRearOwnerNow) -ne 'dashboard') { return $true }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Get-ExZenMode {
    <# Current zen mode from dumpsys notification (system-side proof a set_dnd toggle took;
      never judge by the app log alone). #>
    $dump = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'notification') -AllowFailure) -join "`n"
    if ($dump -match 'mZenMode=(\S+)') { return $Matches[1] }
    return 'unknown'
}


function Get-ExRearOwnerNow {
    param([int] $DisplayId = 1)
    $dumpsys = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure
    Get-RearScreenOwner -DumpsysActivities ($dumpsys -join "`n") -DisplayId $DisplayId
}

function Wait-ExRearOwner {
    param(
        [Parameter(Mandatory, Position = 0)][string] $Owner,
        [int] $DisplayId = 1,
        [int] $TimeoutSec = 15
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = 'unknown'
    do {
        $last = Get-ExRearOwnerNow -DisplayId $DisplayId
        if ($last -eq $Owner) { return $true }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Write-ExNote ('wait for rear owner={0} timed out after {1}s (last={2})' -f $Owner, $TimeoutSec, $last)
    return $false
}

function Clear-ExLogcat {
    Invoke-Adb -Arguments @('logcat', '-c') -AllowFailure | Out-Null
}

function Start-ExApp {
    <#
      Force-stop then start the debug build and wait for the listener to connect.

      Starting from a clean process is what makes a run repeatable, but on this HyperOS build the
      system refuses to rebind the listener afterwards (AutoStartManagerService rejects the service;
      user_setup has MIUI autostart off), so the first wait usually times out. The known recovery is
      to toggle the listener grant -- the same `cmd notification disallow_listener/allow_listener`
      cycle the ticket #5 run used by hand. Doing it here keeps the one-command run self-healing;
      enabling Settings > Apps > RearCue > Autostart on the phone removes the need for it.
    #>
    [CmdletBinding()]
    param(
        [int] $TimeoutSec = 30,
        [switch] $NoRebind
    )

    $config = Get-ExConfig
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $config.Package) -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', $config.MainActivity) -AllowFailure | Out-Null

    # NOT `@(Wait-ExLog ...)`: Wait-ExLog follows the `,$arr` return convention, and an `@()` wrap
    # turns its EMPTY result into a one-element array holding an empty array -- `.Count` then reads
    # 1 and the function would report "connected" for a listener that never connected (the exact
    # `@()`+`,$arr` trap already documented twice in findings; ticket #24 hit it as a silent
    # E13-NO-BASELINE). Bare capture keeps the real array.
    $hit = Wait-ExLog -Pattern 'listener connected active=' -TimeoutSec $TimeoutSec
    if ($hit.Count -gt 0) { return $true }
    if ($NoRebind) {
        Write-ExNote ('listener did not connect within {0}s (MIUI autostart blocked the rebind)' -f $TimeoutSec)
        return $false
    }

    Write-ExNote 'listener did not connect: forcing a rebind (MIUI blocks the first bind after install)'
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'disallow_listener', $config.ListenerComponent) -AllowFailure | Out-Null
    Start-Sleep -Seconds 3
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'allow_listener', $config.ListenerComponent) -AllowFailure | Out-Null
    $hit = Wait-ExLog -Pattern 'listener connected active=' -TimeoutSec $TimeoutSec
    if ($hit.Count -gt 0) {
        Write-ExNote 'listener connected after the rebind'
        return $true
    }
    Write-ExNote ('listener still not connected after the rebind ({0}s)' -f $TimeoutSec)
    return $false
}

function Stop-ExApp {
    $config = Get-ExConfig
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $config.Package) -AllowFailure | Out-Null
}

function Get-ExFirstPid {
    <# First pid of a process name, or $null when it is not running (`pidof` prints nothing). #>
    param([Parameter(Mandatory, Position = 0)][string] $ProcessName)
    $out = Invoke-Adb -Arguments @('shell', 'pidof', $ProcessName) -AllowFailure
    $pid = ($out -join ' ').Trim()
    if (-not $pid) { return $null }
    return [int]($pid -split '\s+')[0]
}

function Get-ExAppPid { Get-ExFirstPid -ProcessName (Get-ExConfig).Package }

function Get-ExShizukuServerPid { Get-ExFirstPid -ProcessName 'shizuku_server' }

function Test-ExListenerEnabled {
    $config = Get-ExConfig
    $out = Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners') -AllowFailure
    return (($out -join '') -like ('*' + $config.ListenerComponent + '*'))
}

function Get-ExWakefulness {
    <# `Awake` / `Dozing` / `Asleep` -- used to lock the phone deliberately for E3. #>
    $out = Invoke-Adb -Arguments @('shell', 'dumpsys', 'power') -AllowFailure
    $line = $out | Where-Object { $_ -match 'mWakefulness=' } | Select-Object -First 1
    if (($line -join '') -match 'mWakefulness=(\w+)') { return $Matches[1] }
    return 'unknown'
}

function Test-ExKeyguardLockedText {
    <#
      Pure: is the keyguard up, according to a `dumpsys window` dump?
      This matters because HyperOS denies third-party rear-display launches while the keyguard is
      locked (ActivityStarterImpl: rearDisplay check locked -> deny, ticket #6 / E2), which makes
      an "automatic projection" run fail for a reason that has nothing to do with the app.

      `KeyguardServiceDelegate` in `dumpsys window policy` carries the REAL state (`showing=`);
      the WMS `mDreamingLockscreen=true` / `isKeyguardShowing=true` lines can linger while a
      doze dream settles and read "locked" for an already-dismissed keyguard. Prefer the
      delegate; fall back to the WMS lines only when the delegate section is absent.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysWindow)

    if ($DumpsysWindow -match 'KeyguardServiceDelegate[\s\S]{0,200}?showing=(true|false)') {
        return ($Matches[1] -eq 'true')
    }
    if ($DumpsysWindow -match 'mDreamingLockscreen=true') { return $true }
    if ($DumpsysWindow -match 'isKeyguardShowing=true') { return $true }
    return $false
}

function Test-ExKeyguardLocked {
    $out = Invoke-Adb -Arguments @('shell', 'dumpsys', 'window', 'policy') -AllowFailure
    $policy = $out -join "`n"
    if ($policy -match 'KeyguardServiceDelegate') {
        return (Test-ExKeyguardLockedText -DumpsysWindow $policy)
    }
    $out = Invoke-Adb -Arguments @('shell', 'dumpsys', 'window') -AllowFailure
    return (Test-ExKeyguardLockedText -DumpsysWindow ($out -join "`n"))
}

function Unlock-ExScreen {
    <#
      Best-effort unlock: wake, swipe up, MENU key, dismiss-keyguard. A secure lock (PIN/pattern)
      cannot be dismissed from adb -- when that is the case this returns $false and the caller has
      to ask the human to unlock the phone.
    #>
    [CmdletBinding()]
    param()
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    Invoke-Adb -Arguments @('shell', 'input', 'swipe', '610', '2300', '610', '700', '200') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', '82') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    Invoke-ExKeyguardDismiss | Out-Null
    Start-Sleep -Seconds 2
    return (-not (Test-ExKeyguardLocked))
}

function Set-ExScreenAwake {
    <# Install and lock experiments both need the screen on; KEYCODE_POWER toggles, so ask first. #>
    if ((Get-ExWakefulness) -ne 'Awake') {
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
        Start-Sleep -Seconds 2
    }
    Invoke-Adb -Arguments @('shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
}

function Get-ExWindowDump {
    <# `uiautomator dump` of what is on the main screen right now (one round trip, empty on failure). #>
    $dump = (Invoke-Adb -Arguments @('exec-out', 'uiautomator', 'dump', '/dev/tty') -AllowFailure) -join ''
    if ($dump -match '<hierarchy') { return $dump }
    Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', '/sdcard/ex-window.xml') -AllowFailure | Out-Null
    return ((Invoke-Adb -Arguments @('shell', 'cat', '/sdcard/ex-window.xml') -AllowFailure) -join '')
}

function Get-ExCurrentFocus {
    <# Focused window as `package/Activity` -- a cheap "is a system dialog on screen" probe (~0.3s).

      This device has TWO displays, and `dumpsys window` prints an `mCurrentFocus=` line PER
      display: the rear display's comes FIRST and reads `null` while the main display's real
      focus window follows (observed 2026-09-26: `mCurrentFocus=null` for the SubScreenLauncher
      group, then `mCurrentFocus=Window{... com.rearcue.poc/...AllowlistSettingsActivity}`).
      Taking the first line therefore always returned $null and made 21-notification-feed's
      settings-open check report "never took focus" for an activity that HAD resumed -- so this
      returns the first NON-null focus and only falls back to $null when nothing is focused. #>
    $out = Invoke-Adb -Arguments @('shell', 'dumpsys', 'window') -AllowFailure
    foreach ($line in ($out | Where-Object { $_ -match 'mCurrentFocus=' })) {
        if ($line -match 'mCurrentFocus=(\S+)') {
            if ($Matches[1] -ne 'null' -and $line -match 'mCurrentFocus=Window\{[^}]*\s([^\s}]+)\}') {
                return $Matches[1]
            }
        }
    }
    return $null
}

function Get-ExNodeCenter {
    <#
      Center of a node in a `uiautomator dump`, addressed by its text or its resource id.
      Pure: dump text in, coordinates out (see the fixture ui-usb-install-dialog.xml).
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $WindowDump,
        [string] $Text,
        [string] $ResourceId,
        # Icon-only nodes (the settings gear) carry only a content-desc; Chinese labels must be
        # passed as [regex]::Unescape('\uXXXX') escapes by ASCII-only callers.
        [string] $ContentDesc
    )
    if (-not $Text -and -not $ResourceId -and -not $ContentDesc) {
        throw 'Get-ExNodeCenter needs -Text, -ResourceId or -ContentDesc'
    }
    foreach ($segment in ($WindowDump -split '<node')) {
        if ($Text -and $segment -notmatch [regex]::Escape('text="' + $Text + '"')) { continue }
        if ($ResourceId -and $segment -notmatch [regex]::Escape('resource-id="' + $ResourceId + '"')) { continue }
        if ($ContentDesc -and $segment -notmatch [regex]::Escape('content-desc="' + $ContentDesc + '"')) { continue }
        if ($segment -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
            return [pscustomobject]@{
                Text        = $Text
                Id          = $ResourceId
                ContentDesc = $ContentDesc
                X           = [int](($x1 + $x2) / 2)
                Y           = [int](($y1 + $y2) / 2)
            }
        }
    }
    return $null
}

function Confirm-ExUsbInstallDialog {
    <#
      Press MIUI's USB-install prompt if it is on screen. HyperOS shows
      "USB install prompt / <app> / Installing this app over USB, continue?" and rejects the
      install (INSTALL_FAILED_USER_RESTRICTED) unless somebody answers within ~8 seconds.

      The label is written with \uXXXX escapes and decoded at runtime on purpose: this file has to
      stay ASCII, otherwise Windows PowerShell 5.1 reads the Chinese as ANSI/GBK and matches nothing.
    #>
    [CmdletBinding()]
    param()

    $focus = Get-ExCurrentFocus
    if ($focus -and $focus -notlike '*securitycenter*') { return $false }   # dialog not up yet

    $dump = Get-ExWindowDump
    $marker = [regex]::Unescape('\u6B63\u5728\u901A\u8FC7USB\u5B89\u88C5')   # "installing over USB"
    if ($dump -notmatch [regex]::Escape($marker)) { return $false }
    if ($script:ExSessionDir) {
        # keep the dialog as evidence (and as the fixture the unit tests parse)
        Write-ExArtifact -Name 'usb-install-dialog.xml' -Lines @($dump) | Out-Null
    }

    $confirmLabel = [regex]::Unescape('\u7EE7\u7EED\u5B89\u88C5')             # "continue install"
    $node = Get-ExNodeCenter -WindowDump $dump -Text $confirmLabel
    if (-not $node) {
        # MIUI's positive button carries android:id/button2 in this dialog (button1 is the reject).
        $node = Get-ExNodeCenter -WindowDump $dump -ResourceId 'android:id/button2'
    }
    if (-not $node) { return $false }

    Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$node.X, [string]$node.Y) -AllowFailure | Out-Null
    Write-ExNote ('pressed the USB install dialog at {0},{1}' -f $node.X, $node.Y)
    return $true
}

function Invoke-ExInstallApk {
    <#
      Install with the MIUI prompt handled: run `adb install` in its own process, watch the screen
      while it waits, press the confirm button, then report what adb said.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][string] $Apk,
        [int] $DialogTimeoutSec = 30,
        [switch] $NoDialog
    )
    if ($script:ExSessionDir) { New-Item -ItemType Directory -Force -Path $script:ExSessionDir | Out-Null }
    $adb = Resolve-ExAdb
    $stamp = Get-Date -Format 'HHmmssfff'
    $outFile = Join-Path $env:TEMP ('ex-install-{0}.out' -f $stamp)
    $errFile = $outFile + '.err'
    $arguments = @()
    if ($script:ExSerial) { $arguments += @('-s', $script:ExSerial) }
    $arguments += @('install', '-r', '-t', $Apk)

    $process = Start-Process -FilePath $adb -ArgumentList $arguments -NoNewWindow -PassThru `
        -RedirectStandardOutput $outFile -RedirectStandardError $errFile
    $dialogPressed = $false
    $deadline = (Get-Date).AddSeconds($DialogTimeoutSec)
    while (-not $process.HasExited -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 1200
        if (-not $NoDialog -and -not $dialogPressed) {
            $dialogPressed = Confirm-ExUsbInstallDialog
        }
    }
    if (-not $process.HasExited) { $process.WaitForExit(20000) | Out-Null }

    $text = (@(Get-Content -LiteralPath $outFile -ErrorAction SilentlyContinue) +
        @(Get-Content -LiteralPath $errFile -ErrorAction SilentlyContinue)) -join "`n"
    if ($script:ExSessionDir) {
        Add-Content -LiteralPath (Join-Path $script:ExSessionDir 'transcript.txt') `
            -Value ('$ adb {0}' -f ($arguments -join ' ')) -Encoding UTF8
        Add-Content -LiteralPath (Join-Path $script:ExSessionDir 'transcript.txt') -Value $text -Encoding UTF8
    }
    Remove-Item -LiteralPath $outFile, $errFile -ErrorAction SilentlyContinue
    return [pscustomobject]@{
        Success         = ($text -match 'Success')
        DialogConfirmed = $dialogPressed
        Output          = $text
    }
}

# ---------------------------------------------------------------------------
# Pure parsers
# ---------------------------------------------------------------------------

function Get-DisplayInfoBlocks {
    <#
      Parse `dumpsys display` into one object per display. Only `mBaseDisplayInfo` lines are
      read: the same display also shows up as `mOverrideDisplayInfo`, and counting both would
      double every display.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysDisplay)

    $blocks = New-Object System.Collections.Generic.List[object]
    foreach ($line in ($DumpsysDisplay -split "`r?`n")) {
        if ($line -notmatch 'mBaseDisplayInfo=\s*DisplayInfo\{(.*)$') { continue }
        $body = $Matches[1]
        if ($body -notmatch 'displayId (\d+)') { continue }
        $displayId = [int]$Matches[1]

        $width = $null; $height = $null
        if ($body -match 'real (\d+) x (\d+)') { $width = [int]$Matches[1]; $height = [int]$Matches[2] }
        $density = $null
        if ($body -match 'density (\d+)[\s,(]') { $density = [int]$Matches[1] }
        $state = $null; $committed = $null
        if ($body -match 'state (\w+), committedState (\w+)') { $state = $Matches[1]; $committed = $Matches[2] }
        $type = $null
        if ($body -match 'type (\w+),') { $type = $Matches[1] }
        $uniqueId = $null
        if ($body -match 'uniqueId "([^"]+)"') { $uniqueId = $Matches[1] }
        $groupId = $null
        if ($body -match 'displayGroupId (\d+)') { $groupId = [int]$Matches[1] }

        $flags = @([regex]::Matches($body, 'FLAG_[A-Z_]+') | ForEach-Object { $_.Value } | Select-Object -Unique)

        $blocks.Add([pscustomobject]@{
            DisplayId      = $displayId
            DisplayGroupId = $groupId
            Width          = $width
            Height         = $height
            Density        = $density
            State          = $state
            CommittedState = $committed
            Type           = $type
            UniqueId       = $uniqueId
            Flags          = $flags
            IsDefault      = ($displayId -eq 0)
        })
    }
    # `,` keeps an empty result an empty array instead of collapsing it to $null.
    return ,$blocks.ToArray()
}

function Get-RearDisplay {
    <#
      Rear display = the non-default INTERNAL display flagged FLAG_PRESENTATION and
      FLAG_OWN_DISPLAY_GROUP. Same rule as the app's RearDisplayLocator (no hardcoded id).
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysDisplay)

    $blocks = Get-DisplayInfoBlocks -DumpsysDisplay $DumpsysDisplay
    return ($blocks | Where-Object {
            (-not $_.IsDefault) -and
            ($_.Type -eq 'INTERNAL') -and
            ($_.Flags -contains 'FLAG_PRESENTATION') -and
            ($_.Flags -contains 'FLAG_OWN_DISPLAY_GROUP')
        } | Select-Object -First 1)
}

function Get-DisplayActivitySection {
    <# The `Display #<id> (activities from top to bottom):` block of `dumpsys activity activities`. #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysActivities,
        [Parameter(Mandatory, Position = 1)][int] $DisplayId
    )
    $section = New-Object System.Collections.Generic.List[string]
    $inside = $false
    foreach ($line in ($DumpsysActivities -split "`r?`n")) {
        if ($line -match '^\s*Display #(\d+)') {
            if ($inside) { break }   # next display block starts: the wanted section ended
            $inside = ([int]$Matches[1] -eq $DisplayId)
            continue
        }
        if ($inside) { $section.Add($line) }
    }
    return ,$section.ToArray()
}

function Get-DisplayTopActivity {
    <#
      The activity on top of one display, as `package/.Class`. Falls back to the first
      ActivityRecord of the block: the Native Rear Screen shows up as `mLastPausedActivity` with
      no topResumedActivity line at all (docs/poc-logs/ticket6-e3-e6-lock-evidence-round3.txt [D]).
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysActivities,
        [Parameter(Mandatory, Position = 1)][int] $DisplayId
    )
    $section = Get-DisplayActivitySection -DumpsysActivities $DumpsysActivities -DisplayId $DisplayId
    if ($section.Count -eq 0) { return $null }

    foreach ($line in $section) {
        if ($line -match 'topResumedActivity=\s*ActivityRecord\{[^}]*?\s([^\s}]+/[^\s}]+)\s+t\d+\}') {
            return $Matches[1]
        }
    }
    foreach ($line in $section) {
        if ($line -match 'ActivityRecord\{[^}]*?\s([^\s}]+/[^\s}]+)\s+t\d+\}') {
            return $Matches[1]
        }
    }
    return $null
}

function Get-RearScreenOwner {
    <# Who holds the rear display right now: dashboard | native | other | none. #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysActivities,
        [int] $DisplayId = 1
    )
    $activity = Get-DisplayTopActivity -DumpsysActivities $DumpsysActivities -DisplayId $DisplayId
    if (-not $activity) { return 'none' }
    $config = Get-ExConfig
    if ($activity -like ('*RearDashboardActivity*') -and $activity -like ('*' + $config.Package + '*')) {
        return 'dashboard'
    }
    if ($activity -like '*com.xiaomi.subscreencenter*') { return 'native' }
    return 'other'
}

function ConvertTo-ExRearCueEvent {
    <#
      One `adb logcat -s RearCue` line -> one structured event. Chinese log text is never matched
      by literal: every rule anchors on an ASCII token the app logs on purpose (effect labels,
      `project iconSet=`, `server=`, `Dashboard attach`, `posted`, ...).
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $Line)

    if ($Line -notmatch '^(?<time>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(?<pid>\d+)\s+(?<tid>\d+)\s+(?<level>[VDIWEF])\s+RearCue\s*:\s?(?<message>.*)$') {
        return $null
    }
    $time = $Matches['time']
    $pid = [int]$Matches['pid']
    $level = $Matches['level']
    $message = $Matches['message']

    $kind = 'other'
    $package = $null
    $iconSet = @()
    $displayId = $null
    $count = $null
    $shizuku = [pscustomobject]@{ Server = $null; Granted = $null; UserService = $null }
    $rear = $null

    if ($message -match '^app start') {
        $kind = 'app-start'
    } elseif ($message -match 'listener connected active=(\d+)') {
        $kind = 'listener-connected'; $count = [int]$Matches[1]
    } elseif ($message -match 'listener disconnected') {
        $kind = 'listener-disconnected'
    } elseif ($message -match '^(posted|removed) ([A-Za-z0-9_.]+)') {
        $kind = $Matches[1]; $package = $Matches[2]
    } elseif ($message -match '^snapshot (\d+)') {
        $kind = 'snapshot'; $count = [int]$Matches[1]
    } elseif ($message -match '^project iconSet=\[(.*?)\]') {
        $kind = 'project'
        $iconSet = @($Matches[1] -split ',\s*' | Where-Object { $_ })
    } elseif ($message -match '^update iconSet=\[(.*?)\]') {
        $kind = 'update'
        $iconSet = @($Matches[1] -split ',\s*' | Where-Object { $_ })
    } elseif ($message -match '^exit ') {
        $kind = 'exit'
    } elseif ($message -match '^Dashboard attach') {
        $kind = 'attach'
    } elseif ($message -match '^Dashboard detach') {
        $kind = 'detach'
    } elseif ($message -match '^state AppState') {
        $kind = 'state'
        if ($message -match 'iconSet=\[([^\]]*)\]') {
            $iconSet = @($Matches[1] -split ',\s*' | Where-Object { $_ })
        }
    } elseif ($message -match 'server=(true|false) granted=(true|false) userService=(true|false)') {
        $kind = 'diagnostic'
        $shizuku = [pscustomobject]@{ Server = $Matches[1]; Granted = $Matches[2]; UserService = $Matches[3] }
        if ($message -match 'rear=(\d+|null)') {
            if ($Matches[1] -ne 'null') { $rear = [int]$Matches[1] }
        }
    }

    # Icon Set transition is logged by every event that changed it: `iconSet [a] -> [a, b]`.
    if ($message -match 'iconSet \[([^\]]*)\] -> \[([^\]]*)\]') {
        $iconSet = @($Matches[2] -split ',\s*' | Where-Object { $_ })
    }
    if ($iconSet.Count -eq 0 -and $message -match 'iconSet=\[([^\]]*)\]') {
        $iconSet = @($Matches[1] -split ',\s*' | Where-Object { $_ })
    }

    $effects = @([regex]::Matches($message, '\b(LaunchDashboard|UpdateIconSet|ExitDashboard|Degrade)\b') |
        ForEach-Object { $_.Groups[1].Value })

    $action = $null
    $actionMatches = [regex]::Matches($message, '((?:miui|android)\.intent\.action\.[A-Z_]+)')
    if ($actionMatches.Count -eq 1) {
        $action = $actionMatches[0].Groups[1].Value
        if ($kind -eq 'other') { $kind = 'signal' }
    }
    if ($message -match 'displayId=(\d+)') { $displayId = [int]$Matches[1] }

    return [pscustomobject]@{
        Time      = $time
        Pid       = $pid
        Level     = $level
        Kind      = $kind
        Package   = $package
        IconSet   = $iconSet
        Effects   = $effects
        DisplayId = $displayId
        Rear      = $rear
        Action    = $action
        Count     = $count
        Shizuku   = $shizuku
        Message   = $message
        Raw       = $Line
    }
}

function Get-RearCueEvent {
    <# Parse logcat lines into RearCue events (non-RearCue lines are dropped). #>
    [CmdletBinding()]
    param([Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][AllowEmptyString()][string[]] $Logcat)

    begin { $events = New-Object System.Collections.Generic.List[object] }
    process {
        foreach ($line in $Logcat) {
            $event = ConvertTo-ExRearCueEvent -Line $line
            if ($null -ne $event) { $events.Add($event) }
        }
    }
    end { return ,$events.ToArray() }
}

function Test-RearCueCrash {
    <# Crash / ANR evidence for the ticket #6 E8 "app must not die" criterion. #>
    [CmdletBinding()]
    param([Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][AllowEmptyString()][string[]] $Logcat)

    begin { $hits = New-Object System.Collections.Generic.List[string] }
    process {
        foreach ($line in $Logcat) {
            if ($line -match 'FATAL EXCEPTION' -or $line -match 'ANR in com\.rearcue\.poc') { $hits.Add($line) }
        }
    }
    end { return ,$hits.ToArray() }
}

function Get-ExOverlayPermission {
    <#
      Read the SYSTEM_ALERT_WINDOW appop and decide admission (ticket #10 / E9). One helper so the
      install step and the overlay scenario cannot drift apart on what "granted" means.
      Returns @{ Text = <appops get output, one line>; Granted = <bool> }.
    #>
    [CmdletBinding()]
    param([string] $Package = (Get-ExConfig).Package)

    $appops = Invoke-Adb -Arguments @('shell', 'appops', 'get', $Package, 'SYSTEM_ALERT_WINDOW') -AllowFailure
    $text = (($appops -join '') -replace "`r?`n", ' ').Trim()
    return [pscustomobject]@{
        Text    = $text
        Granted = ($text -match 'SYSTEM_ALERT_WINDOW:\s*allow')
    }
}

function Get-OverlayProbeWindow {
    <#
      E9 decisive fact (ticket #10): is the overlay probe window REALLY on the rear display?

      Pure parser over `dumpsys window windows`. Window blocks look like
        Window #2 Window{9e4d2c1 u0 RearCueOverlayProbe}:
          mDisplayId=1 mSession=...
          mOwnerUid=10332 ... package=com.rearcue.poc appop=SYSTEM_ALERT_WINDOW
      Only block headers (`Window #N Window{...}`) may identify the probe: titles also echo inside
      other blocks' `-topChild=` lines, and matching those would report a window that is not there.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysWindow,
        [string] $Title = (Get-ExConfig).OverlayTitle
    )

    $found = $false
    $displayId = $null
    $package = $null
    $appop = $null
    foreach ($line in ($DumpsysWindow -split "`r?`n")) {
        if ($line -match '^\s*Window #\d+ Window\{') {
            if ($found) { break }   # probe block ended at the next window header
            $found = ($line -match [regex]::Escape($Title))
            continue
        }
        if (-not $found) { continue }
        if ($null -eq $displayId -and $line -match 'mDisplayId=(\d+)') { $displayId = [int]$Matches[1] }
        if ($null -eq $package -and $line -match 'package=(\S+)') { $package = $Matches[1] }
        if ($null -eq $appop -and $line -match 'appop=(\S+)') { $appop = $Matches[1] }
    }
    return [pscustomobject]@{
        Found     = $found
        DisplayId = $displayId
        Package   = $package
        Appop     = $appop
    }
}

function Get-OverlayWindowEvents {
    <#
      Classify the E9 evidence lines: the app-side add/remove results (RearCue tag) and the
      system-side WindowManager lines.

      The decisive HyperOS fact (2026-09-22 run): rear-display overlay denial logs
        `WindowManager: Not allow non-system app com.rearcue.poc add system_window on rear display`
      which CONTAINS the "add system_window on rear display" substring -- without the Not-allow
      guard that line would count as add evidence. Pure parser: logcat in, facts out; the verdict
      never trusts the broadcast exit code.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyCollection()][string[]] $Logcat,
        [string] $Package = (Get-ExConfig).Package,
        [string] $Title = (Get-ExConfig).OverlayTitle
    )

    $added = $false
    $failed = $false
    $failureReason = $null
    $removed = $false
    $rearPolicyDeny = $false
    $systemAdd = New-Object System.Collections.Generic.List[string]
    $systemDeny = New-Object System.Collections.Generic.List[string]

    foreach ($line in $Logcat) {
        if ($line -match ' RearCue *:') {
            if ($line -match 'overlay added display=(\d+)') { $added = $true }
            elseif ($line -match 'overlay add failed reason=(.*)$') { $failed = $true; $failureReason = $Matches[1].Trim() }
            elseif ($line -match 'overlay removed') { $removed = $true }
            continue
        }
        if ($line -match 'Not allow non-system app') {
            # The HyperOS rear-display overlay policy line (also a plain deny for other windows).
            $systemDeny.Add($line)
            if ($line -match 'system_window on rear display') { $rearPolicyDeny = $true }
            continue
        }
        if ($line -match 'add system_window on rear display') { $systemAdd.Add($line) }
        elseif ($line -match [regex]::Escape($Title) -and $line -match 'WindowManager') { $systemAdd.Add($line) }
        if ($line -match 'Permission Denial' -and $line -match [regex]::Escape($Package)) { $systemDeny.Add($line) }
        elseif ($line -match 'SecurityException' -and $line -match 'SYSTEM_ALERT_WINDOW') { $systemDeny.Add($line) }
    }

    return [pscustomobject]@{
        Added          = $added
        AddFailed      = $failed
        FailureReason  = $failureReason
        Removed        = $removed
        RearPolicyDeny = $rearPolicyDeny
        SystemAdd      = [string[]]$systemAdd.ToArray()
        SystemDeny     = [string[]]$systemDeny.ToArray()
    }
}

function Get-ExShuidProbeFacts {
    <#
      SH-UID probe (ticket #17): the probe process's own stdout -> structured facts.

      Wire format (printed by tools/ex/device/shuid-overlay/ShuidOverlayProbe.java):
        probe: start mode=add display=0 pid=21361 uid=2000 title=RearCueShUidProbe hold=6 pkg=com.rearcue.poc
        probe: context ok source=package:com.rearcue.poc opPackage=android
        probe: attempt strategy=window-context context-ok type=2038
        probe: attempt strategy=window-context failed reason=java.lang.IllegalStateException: ...
        probe: add ok display=0 title=RearCueShUidProbe strategy=display-context
        probe: add failed reason=...
        probe: remove ok
        probe: self-fail reason=...
        probe: register attempt method=attachApplication
        probe: register ok method=attachApplication     (or failed reason=... / timeout ...)
        probe: done mode=add added=false removed=false
      Malformed lines are skipped, never guessed at. A run that dies before `probe: done` stays
      Done=false with RegisterResult=$null -- the ticket #17 register check kills the probe
      process exactly there, and that silence is the fact this parser must not paper over.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $ProbeLines)

    $started = $false; $mode = $null; $displayId = $null; $probePid = $null; $probeUid = $null
    $title = $null; $hold = $null; $pkg = $null
    $contextOk = $false; $contextSource = $null; $contextOpPackage = $null
    $attemptFailures = New-Object System.Collections.Generic.List[string]
    $addOk = $false; $addStrategy = $null; $addFailed = $false; $addFailure = $null
    $removeOk = $false; $removeFailed = $false; $removeFailure = $null
    $selfFail = $false; $selfFailure = $null
    $registerAttempted = $false; $registerResult = $null; $registerReason = $null
    $done = $false; $doneAdded = $false; $doneRemoved = $false

    foreach ($line in $ProbeLines) {
        if ($line -notmatch '^\s*probe:\s+(\S+)\s*(.*)$') { continue }
        $verb = $Matches[1]
        $body = $Matches[2]
        if ($verb -eq 'start') {
            if ($body -match 'mode=(\S+) display=(-?\d+) pid=(\d+) uid=(\d+) title=(\S+) hold=(\d+) pkg=(\S+)') {
                $started = $true; $mode = $Matches[1]; $displayId = [int]$Matches[2]
                $probePid = [int]$Matches[3]; $probeUid = [int]$Matches[4]
                $title = $Matches[5]; $hold = [int]$Matches[6]; $pkg = $Matches[7]
            }
        } elseif ($verb -eq 'context') {
            if ($body -match '^ok source=(\S+)(?:\s+opPackage=(\S+))?\s*$') {
                $contextOk = $true; $contextSource = $Matches[1]; $contextOpPackage = $Matches[2]
            }
        } elseif ($verb -eq 'attempt') {
            if ($body -match '^strategy=(\S+) failed reason=(.*)$') { $attemptFailures.Add($Matches[2].Trim()) }
        } elseif ($verb -eq 'add') {
            if ($body -match '^ok display=(-?\d+) title=(\S+)(?: strategy=(\S+))?\s*$') {
                $addOk = $true; $addStrategy = $Matches[3]
            } elseif ($body -match '^failed reason=(.*)$') {
                $addFailed = $true; $addFailure = $Matches[1].Trim()
            }
        } elseif ($verb -eq 'remove') {
            if ($body -match '^ok') { $removeOk = $true }
            elseif ($body -match '^failed reason=(.*)$') { $removeFailed = $true; $removeFailure = $Matches[1].Trim() }
        } elseif ($verb -eq 'self-fail') {
            if ($body -match '^reason=(.*)$') { $selfFail = $true; $selfFailure = $Matches[1].Trim() }
        } elseif ($verb -eq 'register') {
            if ($body -match '^attempt') { $registerAttempted = $true }
            elseif ($body -match '^ok method=(\S+)') { $registerResult = 'ok' }
            elseif ($body -match '^failed reason=(.*)$') { $registerResult = 'failed'; $registerReason = $Matches[1].Trim() }
            elseif ($body -match '^timeout') { $registerResult = 'timeout' }
            elseif ($body -match '^skip reason=(.*)$') { $registerResult = 'skip'; $registerReason = $Matches[1].Trim() }
        } elseif ($verb -eq 'done') {
            if ($body -match '^mode=(\S+) added=(true|false) removed=(true|false)') {
                $done = $true; $doneAdded = ($Matches[2] -eq 'true'); $doneRemoved = ($Matches[3] -eq 'true')
            }
        }
    }

    return [pscustomobject]@{
        Started            = $started
        Mode               = $mode
        DisplayId          = $displayId
        Pid                = $probePid
        Uid                = $probeUid
        Title              = $title
        HoldSeconds        = $hold
        RequestedPackage   = $pkg
        ContextOk          = $contextOk
        ContextSource      = $contextSource
        ContextOpPackage   = $contextOpPackage
        # Flat array on purpose (no `,$arr` wrap): indexing must hit one string, not a wrapper.
        AttemptFailures    = $attemptFailures.ToArray()
        AddOk              = $addOk
        AddStrategy        = $addStrategy
        AddFailed          = $addFailed
        AddFailureReason   = $addFailure
        RemoveOk           = $removeOk
        RemoveFailed       = $removeFailed
        RemoveFailureReason = $removeFailure
        SelfFail           = $selfFail
        SelfFailureReason  = $selfFailure
        RegisterAttempted  = $registerAttempted
        RegisterResult     = $registerResult
        RegisterReason     = $registerReason
        Done               = $done
        DoneAdded          = $doneAdded
        DoneRemoved        = $doneRemoved
    }
}

function Get-ExShuidProbeWindow {
    <#
      SH-UID decisive fact (ticket #17): is the probe window REALLY on the target display?

      Pure parser over `dumpsys window windows`, same block shape as Get-OverlayProbeWindow but
      carrying the owner identity the ticket asks about (mOwnerUid / mSession pid / ty=):
        Window #10 Window{17a3eb4 u0 RearCueShUidProbe}:
          mDisplayId=0 mSession=Session{2928d7 3075:u0a10333} mClient=...
          mOwnerUid=10333 showForAllUsers=false package=com.rearcue.poc appop=SYSTEM_ALERT_WINDOW
          mAttrs={(0,0)(320x160) ... ty=APPLICATION_OVERLAY ...
      Only block headers (`Window #N Window{...}`) may identify the probe -- titles also echo
      inside other blocks (`-topChild=`, Surface names). The verbatim header/display/owner lines
      are returned so the verdict can quote them instead of paraphrasing.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysWindow,
        [string] $Title = 'RearCueShUidProbe'
    )

    $found = $false
    $header = $null; $displayLine = $null; $ownerLine = $null
    $displayId = $null; $ownerUid = $null; $ownerPid = $null
    $package = $null; $appop = $null; $type = $null
    foreach ($line in ($DumpsysWindow -split "`r?`n")) {
        if ($line -match '^\s*Window #\d+ Window\{') {
            if ($found) { break }   # probe block ended at the next window header
            $found = ($line -match [regex]::Escape($Title))
            if ($found) { $header = $line }
            continue
        }
        if (-not $found) { continue }
        if ($null -eq $displayLine -and $line -match 'mDisplayId=') {
            $displayLine = $line
            if ($line -match 'mDisplayId=(\d+)') { $displayId = [int]$Matches[1] }
            if ($line -match 'mSession=Session\{\S+\s+(\d+):') { $ownerPid = [int]$Matches[1] }
        }
        if ($null -eq $ownerLine -and $line -match 'mOwnerUid=') {
            $ownerLine = $line
            if ($line -match 'mOwnerUid=(\d+)') { $ownerUid = [int]$Matches[1] }
            if ($line -match 'package=(\S+)') { $package = $Matches[1] }
            if ($line -match 'appop=(\S+)') { $appop = $Matches[1] }
        }
        if ($null -eq $type -and $line -match '[\s{]ty=(\S+)') { $type = $Matches[1] }
    }

    return [pscustomobject]@{
        Found       = $found
        DisplayId   = $displayId
        OwnerUid    = $ownerUid
        OwnerPid    = $ownerPid
        Package     = $package
        Appop       = $appop
        Type        = $type
        Header      = $header
        DisplayLine = $displayLine
        OwnerLine   = $ownerLine
    }
}

function Get-ExShuidWindowEvents {
    <#
      SH-UID (ticket #17): system-side WindowManager lines around a shell-uid window add ->
      structured facts, with the lines kept VERBATIM so a verdict can quote them.

      Two refusal shapes live on this build and must never be conflated:
        rear policy   `WindowManager: Not allow non-system app <pkg> add system_window on rear
                      display` (the ticket #10 E9 gate -- BLOCKED quotes this line)
        unknown proc  `WindowManager: attachWindowContextToDisplayArea: calling from
                      non-existing process pid=<pid> uid=2000` and
                      `WindowManager: Window Manager Crash java.lang.IllegalStateException:
                      Unknown pid=<pid> uid=2000` (the earlier gate a bare shell-uid process
                      hits on ANY display -- classified as ProcessUnknownLines, never as a
                      rear-policy denial). `Not allow non-system app ... add system_window on
                      rear display` CONTAINS the add substring: the Not-allow guard runs first.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][string[]] $Logcat,
        [string] $Title = 'RearCueShUidProbe'
    )

    begin {
        $deny = New-Object System.Collections.Generic.List[string]
        $unknown = New-Object System.Collections.Generic.List[string]
        $titleLines = New-Object System.Collections.Generic.List[string]
        $rearPolicyDeny = $false
        $rearPolicyLine = $null
    }
    process {
        foreach ($line in $Logcat) {
            if ($line -match 'Unknown pid=' -or $line -match 'non-existing process') {
                $unknown.Add($line)
                continue
            }
            if ($line -match [regex]::Escape($Title)) { $titleLines.Add($line) }
            if ($line -match 'Not allow non-system app' -or ($line -match 'not allow' -and $line -match 'rear display')) {
                $deny.Add($line)
                if ($line -match 'system_window on rear display') {
                    $rearPolicyDeny = $true
                    if ($null -eq $rearPolicyLine) { $rearPolicyLine = $line }
                }
                continue
            }
            if ($line -match 'SecurityException' -and $line -match 'SYSTEM_ALERT_WINDOW') { $deny.Add($line); continue }
            if ($line -match 'Permission Denial' -and $line -match ('SYSTEM_ALERT_WINDOW|' + [regex]::Escape($Title))) { $deny.Add($line) }
        }
    }
    end {
        return [pscustomobject]@{
            RearPolicyDeny     = $rearPolicyDeny
            RearPolicyLine     = $rearPolicyLine
            DenyLines          = $deny.ToArray()
            ProcessUnknown     = ($unknown.Count -gt 0)
            ProcessUnknownLines = $unknown.ToArray()
            TitleLines         = $titleLines.ToArray()
        }
    }
}

function Get-ExLogcatTime {
    <#
      Pure: the `MM-dd HH:mm:ss.fff` stamp of one logcat line as a DateTime. The year is made
      up (ParseExact needs one) -- the value is only ever used for deltas within one run, never
      as an absolute time. Returns $null for lines without a stamp.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $Line)

    if ($Line -match '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') {
        return [datetime]::ParseExact($Matches[1], 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
    }
    return $null
}

function Get-ExBatteryFacts {
    <#
      Pure: one `dumpsys battery` reading as facts. Measured values only -- $null means "not
      measured" (Get-ExDelaySeconds rule): a field missing from the text is never faked as 0.

        Level            battery percent (int)
        TemperatureDc    battery temperature, deci-degrees C (`temperature: 355` = 35.5 C)
        VoltageMv        mV
        ChargeCounterUah the battery's own remaining-charge counter, micro-amp-hours -- the only
                         REAL drain meter here (and only while the charger is NOT feeding the
                         load: a USB-powered phone shows no drift)
        AcPowered / UsbPowered / WirelessPowered   who is feeding the load (strings `true`/`false`)
        Status           charger-status int (5 = full on this build)

      Ticket #21 cost legs (12-wake-cost.ps1) compare two of these readings; the script decides
      from AcPowered/UsbPowered whether a charge-counter delta is meaningful at all.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $Battery)

    $text = ($Battery -join "`n")
    $intOrNull = {
        param($Pattern)
        if ($text -match $Pattern) { return [int]$Matches[1] }
        return $null
    }
    # Returns the matched TEXT ('true'/'false') or $null -- never a [bool]: $false and
    # "not measured" must stay distinguishable (the $null = not-measured rule).
    $textOrNull = {
        param($Pattern)
        if ($text -match $Pattern) { return $Matches[1] }
        return $null
    }
    return [pscustomobject]@{
        Level            = (& $intOrNull '(?m)^\s*level:\s*(\d+)')
        TemperatureDc    = (& $intOrNull '(?m)^\s*temperature:\s*(\d+)')
        VoltageMv        = (& $intOrNull '(?m)^\s*voltage:\s*(\d+)')
        ChargeCounterUah = (& $intOrNull '(?m)^\s*Charge counter:\s*(\d+)')
        AcPowered        = (& $textOrNull 'AC powered:\s*(true|false)')
        UsbPowered       = (& $textOrNull 'USB powered:\s*(true|false)')
        WirelessPowered  = (& $textOrNull 'Wireless powered:\s*(true|false)')
        Status           = (& $intOrNull '(?m)^\s*status:\s*(\d+)')
    }
}

function Get-ExPowerEstimateLines {
    <#
      Pure: the power-accounting excerpt of `dumpsys batterystats` -- the "Estimated power use"
      block plus every `mAh` line. This is Android's MODEL ESTIMATE of drain (cpu time x power
      profile), never a measurement: callers must label it as the estimate it is.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $Batterystats)

    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($line in $Batterystats) {
        if (($line -match 'Estimated power use') -or ($line -match 'mAh') -or ($line -match 'Uid u\d+')) { $lines.Add($line) }
    }
    return ,$lines.ToArray()
}

function Format-ExPlacementField {
    <#
      Pure: one Get-ExTaskPlacement fact as the wire field `t<id>@d<display>` or `absent`.
      Originally local to 09-task-move.ps1; shared now because 15-lock-firstcast (ticket #22)
      prints the same wire.
    #>
    param([Parameter(Position = 0)][AllowNull()][object] $Place)
    if (-not $Place -or -not $Place.Found) { return 'absent' }
    return ('t{0}@d{1}' -f $Place.TaskId, $Place.DisplayId)
}

function Get-ExAppKeepAliveFacts {
    <#
      Pure: the APP's Wake Keep-alive evidence from the app logcat (ticket #21, `-AppKeepAlive`
      regression mode). The app logs ASCII `wake-keep-alive ...` markers per WakeKeepAlive.kt
      KDoc (the words are a contract with this parser):

        Started     $true when a `wake-keep-alive start displayId=` line is present
        Heartbeats  count of `wake-keep-alive ok ticks=N` lines
        MaxTicks    the highest N seen in those heartbeats (the loop's own count -- the
                    interval-independent tick evidence; script-loop tick files stay empty)
        Fails       count of `wake-keep-alive fail ...` / `wake-keep-alive tick-exception ...`
        StopLine    the last `wake-keep-alive stop ticks=...` line (trimmed) or $null

      $null = not measured where the log simply has nothing (StopLine); zeros are real zeros.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $Logcat)

    $started = $false
    $heartbeats = 0
    $maxTicks = 0
    $fails = 0
    $stopLine = $null
    foreach ($line in $Logcat) {
        # Anchored on ` : wake-keep-alive ` (the tag separator) on purpose (ticket #24): the
        # ShizukuShell `sh [<command>]` line PRINTS the whole keep-alive loop command, whose text
        # contains every anchor word shape literally -- unanchored matches then count the command
        # line as a phantom heartbeat + fail + stop (real round 20260923-044316, see its erratum).
        if ($line -match ' : wake-keep-alive start displayId=') { $started = $true }
        if ($line -match ' : wake-keep-alive ok ticks=') {
            $heartbeats++
            if ($line -match ' : wake-keep-alive ok ticks=(\d+)') {
                $n = [int]$Matches[1]
                if ($n -gt $maxTicks) { $maxTicks = $n }
            }
        }
        if ($line -match ' : wake-keep-alive (fail|tick-exception)') { $fails++ }
        if ($line -match ' : wake-keep-alive stop ticks=') { $stopLine = $line.Trim() }
    }
    return [pscustomobject]@{
        Started    = $started
        Heartbeats = $heartbeats
        MaxTicks   = $maxTicks
        Fails      = $fails
        StopLine   = $stopLine
    }
}

function Get-ExTaskMoveAppFacts {
    <#
      Pure: the app's task-move (ticket #22 lock-screen first cast) evidence from the app logcat.
      The app logs ASCII `task-move ...` markers per attempt (RearDisplayBackend.kt KDoc: the
      words are a contract with this parser):

        `task-move create-task exit=N out=...`                        (default-display task create)
        `task-move txn-raw exit=N out=...`                            (the service call, archived only)
        `task-move word=W taskId=T displayId=D onDisplay=X reason=...` (the judged attempt line)

        Attempts     count of `word=` attempt lines
        LastWord     last word: OK | NO-TASK | TXN-BROKEN | NO-EFFECT ($null when none)
        TaskId       task id of the last attempt (int) or $null
        OnDisplay    last attempt's onDisplay as text 'True'/'False' or $null
        Creates      count of create-task lines
        TxnRaws      count of txn-raw lines

      $null = not measured (nothing in the log); zeros are real zeros.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $Logcat)

    $attempts = 0
    $lastWord = $null
    $taskId = $null
    $onDisplay = $null
    $creates = 0
    $txnRaws = 0
    foreach ($line in $Logcat) {
        if ($line -match 'task-move create-task exit=') { $creates++ }
        if ($line -match 'task-move txn-raw exit=') { $txnRaws++ }
        if ($line -match 'task-move word=(\S+) taskId=(\S+) displayId=\S+ onDisplay=(\S+)') {
            $attempts++
            $lastWord = $Matches[1]
            $wordId = $Matches[2]
            $onDisplay = $Matches[3]
            $taskId = if ($wordId -match '^\d+$') { [int]$wordId } else { $null }
        }
    }
    return [pscustomobject]@{
        Attempts  = $attempts
        LastWord  = $lastWord
        TaskId    = $taskId
        OnDisplay = $onDisplay
        Creates   = $creates
        TxnRaws   = $txnRaws
    }
}

function Get-ExSignedDeltaSeconds {
    <#
      Pure: seconds from $From to $To on the device clock, folded across midnight into
      (-43200, 43200]. One place owns the rollover rule (it used to live inline at every call
      site, once per parse function). Both stamps come from Get-ExLogcatTime / ParseExact on the
      same made-up year -- only deltas within one run are meaningful (Get-ExLogcatTime rule).
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][datetime] $From,
        [Parameter(Mandatory, Position = 1)][datetime] $To
    )
    $delta = ($To - $From).TotalSeconds
    if ($delta -gt 43200) { return $delta - 86400 }
    if ($delta -le -43200) { return $delta + 86400 }
    return $delta
}

function Format-ExStatePair {
    <# `State/CommittedState` text of one DisplayInfo block for the sample wire (`ON/OFF`). #>
    param([Parameter(Position = 0)][AllowNull()][object] $Block)
    if ($Block) { '{0}/{1}' -f $Block.State, $Block.CommittedState } else { 'no-display' }
}

function Get-ExSecondStamp {
    <#
      `MM-dd HH:mm:ss.fff` -> its `MM-dd HH:mm:ss` prefix: second grain on the same device clock
      (the format the run's `date` injection stamps use). The precision loss is the point -- do
      not use this where the milliseconds matter.
    #>
    param([Parameter(Position = 0)][AllowEmptyString()][AllowNull()][string] $Time)
    if (-not $Time) { return '' }
    return $Time.Substring(0, [math]::Min(14, $Time.Length))
}

function Invoke-ExKeyguardDismiss {
    <#
      Best-effort keyguard dismiss: `wm dismiss-keyguard` is a silent no-op on this HyperOS
      keyguard (ticket #7), while a swipe up actually dismisses it (verified 2026-09-22). Both
      are cheap, so run both; a secure lock still needs the human (Unlock-ExScreen reports it).
      On this phone the first swipe of a dozing lock only wakes the lock-screen dream and a
      second swipe is the one that dismisses -- so retry the swipe (up to 3x), keyed on the
      real keyguard state (`KeyguardServiceDelegate showing=`). Returns $true when the keyguard
      is really gone (callers that don't care MUST pipe to Out-Null -- a bare call would leak
      the bool into their output stream).
    #>
    [CmdletBinding()]
    param()
    Invoke-Adb -Arguments @('shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
    for ($i = 0; $i -lt 3; $i++) {
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', '540', '1800', '540', '600', '200') -AllowFailure | Out-Null
        Start-Sleep -Seconds 1
        if (-not (Test-ExKeyguardLocked)) { return $true }
    }
    return $false
}

function Get-ExDelaySeconds {
    <#
      Pure: seconds between a marker logcat line and the first line matching a pattern AFTER it.

      E10/E11 / Activity comparison (ticket #11): 07-lock-compare.ps1 marks the lock moment
      straight into the device log (`adb shell log -t RearCue pc-lock-issued`), so the marker
      and the app's `Dashboard detach` line share the device clock -- the "how long until the
      system reclaimed it" number carries no PC/device clock skew. Only lines after the marker
      count: earlier detach lines belong to the setup phase. Returns $null (not 0) when either
      end of the measurement is missing -- "not measured" must not read as "instantaneous".
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyCollection()][string[]] $Logcat,
        [Parameter(Mandatory, Position = 1)][string] $Marker,
        [Parameter(Mandatory, Position = 2)][string] $Pattern
    )

    $markerIndex = -1
    for ($i = 0; $i -lt $Logcat.Count; $i++) {
        if ($Logcat[$i] -match $Marker) { $markerIndex = $i; break }
    }
    if ($markerIndex -lt 0) { return $null }

    for ($i = $markerIndex + 1; $i -lt $Logcat.Count; $i++) {
        if ($Logcat[$i] -match $Pattern) {
            $from = Get-ExLogcatTime -Line $Logcat[$markerIndex]
            $to = Get-ExLogcatTime -Line $Logcat[$i]
            if ($null -eq $from -or $null -eq $to) { return $null }
            $delta = ($to - $from).TotalSeconds
            if ($delta -lt 0) { $delta += 86400 }   # the run crossed midnight
            return [math]::Round($delta, 1)
        }
    }
    return $null
}

function Get-ExRearCueMessage {
    <#
      Pure: one logcat line -> the bare RearCue message (stamp / pid / tid / tag stripped).
      The stamp is `09-22 12:59:59.882  12659 12659 I RearCue : msg` -- pid/tid are DOUBLE-space
      padded, so the anchor must be \s+; a single-space anchor silently keeps the whole header
      (that bug shipped once before this helper existed).
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $Line)

    return (($Line -replace '^\S+\s+\S+\s+\d+\s+\d+\s+[VDIWEF]\s+RearCue\s*:\s?', '').Trim())
}

function Format-ExLockSampleLine {
    <#
      Pure: one lock-watch sampling point -> the wire line Get-ExLockSampleFacts parses back.
      The writer lives NEXT TO its parser so the format cannot drift between the scenario
      script and the parsing seam. $null WindowDisplayId = the probe window was absent.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][int] $Elapsed,
        [Parameter(Position = 1)][AllowNull()][object] $WindowDisplayId,
        [Parameter(Mandatory, Position = 2)][string] $Owner,
        [Parameter(Mandatory, Position = 3)][string] $StatePair,
        [Parameter(Position = 4)][AllowEmptyString()][string] $Last = ''
    )

    $windowField = if ($null -eq $WindowDisplayId) { 'absent' } else { ('present({0})' -f $WindowDisplayId) }
    return ('[+{0,4}s] window={1} owner={2,-9} rear={3,-18} last={4}' -f $Elapsed, $windowField, $Owner, $StatePair, $Last)
}

function Get-ExLockSampleFacts {
    <#
      Pure: the archived lock-watch sample lines of 07-lock-compare.ps1 -> survival facts.

      One line per sampling point, written by the scenario itself:
        [+   4s] window=present(1) owner=dashboard rear=ON/ON           last=<latest RearCue line>
      `window=present(<displayId>)` says the probe overlay window was in `dumpsys window
      windows` on that display; `absent` says it was not. Facts derived:
        WindowEverPresent / WindowSurvivedLock / WindowFirstAbsentSec / WindowLastSeenSec
        OwnerHeldThroughout / OwnerFirstLostSec     (owner != dashboard = the Activity left)
        RearHeldOnThroughout / RearFirstNonOnSec    (rear state left ON)
      Malformed lines are skipped, never guessed at. Samples holds the parsed per-point records
      so callers can ask their own questions (e.g. "was the window on THIS display").
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $SampleLines)

    $samples = New-Object System.Collections.Generic.List[object]
    foreach ($line in $SampleLines) {
        if ($line -notmatch '^\[\+\s*(\d+)s\]\s+window=(present\((\d+)\)|absent)\s+owner=(\S+)\s+rear=(\w+)/(\w+)(?:\s+last=(.*))?$') {
            continue
        }
        $present = ($Matches[2] -like 'present*')
        $displayId = if ($Matches[3]) { [int]$Matches[3] } else { $null }
        $samples.Add([pscustomobject]@{
                Elapsed       = [int]$Matches[1]
                WindowPresent = $present
                WindowDisplayId = $displayId
                Owner         = $Matches[4]
                RearState     = $Matches[5]
                RearCommitted = $Matches[6]
                Last          = $Matches[7]
            })
    }

    $windowEverPresent = $false
    $windowSurvived = ($samples.Count -gt 0)
    $windowFirstAbsent = $null
    $windowLastSeen = $null
    $ownerHeld = ($samples.Count -gt 0)
    $ownerFirstLost = $null
    $rearHeldOn = ($samples.Count -gt 0)
    $rearFirstNonOn = $null
    foreach ($sample in $samples) {
        if ($sample.WindowPresent) { $windowEverPresent = $true; $windowLastSeen = $sample.Elapsed }
        else {
            $windowSurvived = $false
            if ($null -eq $windowFirstAbsent) { $windowFirstAbsent = $sample.Elapsed }
        }
        if ($sample.Owner -ne 'dashboard') {
            $ownerHeld = $false
            if ($null -eq $ownerFirstLost) { $ownerFirstLost = $sample.Elapsed }
        }
        if ($sample.RearState -ne 'ON') {
            $rearHeldOn = $false
            if ($null -eq $rearFirstNonOn) { $rearFirstNonOn = $sample.Elapsed }
        }
    }

    return [pscustomobject]@{
        # Flat array on purpose (NO `,$arr` wrap): a wrapped array makes Samples[0]/Samples[-1]
        # hit the inner array and member access silently enumerates the whole run (System.Object[]).
        Samples               = $samples.ToArray()
        SampleCount           = $samples.Count
        WindowEverPresent     = $windowEverPresent
        WindowSurvivedLock    = $windowSurvived
        WindowFirstAbsentSec  = $windowFirstAbsent
        WindowLastSeenSec     = $windowLastSeen
        OwnerHeldThroughout   = $ownerHeld
        OwnerFirstLostSec     = $ownerFirstLost
        RearHeldOnThroughout  = $rearHeldOn
        RearFirstNonOnSec     = $rearFirstNonOn
    }
}

function Format-ExRearBehavior {
    <#
      Pure: E12 watch facts -> the one-line rear behavior sentence both legs share
      (ticket #16 review: the baseline/keep-alive wording blocks were one shape twice).
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][object] $Facts,
        [Parameter(Mandatory, Position = 1)][string] $WatchLabel
    )
    if ($Facts.RearHeldOnThroughout) {
        ('rear stayed ON through the whole {0}' -f $WatchLabel)
    } elseif ($null -ne $Facts.RearFirstNonOnSec) {
        ('rear left ON at +{0}s{1}, ending {2}' -f $Facts.RearFirstNonOnSec,
            $(if ($Facts.RearReturnedOnAfterLoss) { ' (ON again later)' } else { ' and never ON again' }),
            $Facts.RearEndStatePair)
    } else {
        'no readable rear state'
    }
}

function Format-ExWakeSampleLine {
    <#
      Pure: one E12 wake keep-alive sampling point -> the wire line Get-ExWakeSampleFacts parses
      back (ticket #16). The writer lives NEXT TO its parser so the format cannot drift; the
      archived sample files of the 2026-09-22 collection round pin it to the real wire format.
      `RearPair`/`MainPair` are `state/committedState` pairs, `Owner` is the rear display owner.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][int] $Elapsed,
        [Parameter(Mandatory, Position = 1)][string] $RearPair,
        [Parameter(Mandatory, Position = 2)][string] $MainPair,
        [Parameter(Mandatory, Position = 3)][string] $Owner
    )
    return ('[+{0,4}s] rear={1,-18} main={2,-18} owner={3}' -f $Elapsed, $RearPair, $MainPair, $Owner)
}

function Get-ExWakeSampleFacts {
    <#
      Pure: the archived E12 watch sample lines (ticket #16) -> the display facts the verdict
      asserts on. One line per sampling point, written by the scenario itself:
        [+   2s] rear=ON/ON              main=OFF/OFF            owner=native
      Facts derived:
        RearHeldOnThroughout / RearFirstNonOnSec / RearReturnedOnAfterLoss / RearEndStatePair
        MainHeldOffThroughout / MainFirstOnSec   (main display lit during the watch = the price
                                                  of a wake key that is not display-targeted)
        OwnerHeldThroughout / OwnerFirstLostSec / OwnerEnd   (the rear display owner of every
                                                  point -- E13 (ticket #19) survival facts over
                                                  the same wire; "held" means owner=dashboard)
      Malformed lines are skipped, never guessed at. An empty watch claims nothing (the "held"
      facts start from "at least one sample was read").
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $SampleLines)

    $samples = New-Object System.Collections.Generic.List[object]
    foreach ($line in $SampleLines) {
        if ($line -notmatch '^\[\+\s*(\d+)s\]\s+rear=(\w+)/(\w+)\s+main=(\w+)/(\w+)\s+owner=(\S+)\s*$') { continue }
        $samples.Add([pscustomobject]@{
                Elapsed       = [int]$Matches[1]
                RearState     = $Matches[2]
                RearCommitted = $Matches[3]
                MainState     = $Matches[4]
                MainCommitted = $Matches[5]
                Owner         = $Matches[6]
            })
    }

    $rearHeld = ($samples.Count -gt 0)
    $rearFirstNonOn = $null
    $rearBackOn = $false
    $mainHeld = ($samples.Count -gt 0)
    $mainFirstOn = $null
    $ownerHeld = ($samples.Count -gt 0)
    $ownerFirstLost = $null
    foreach ($sample in $samples) {
        if ($sample.RearState -ne 'ON') {
            $rearHeld = $false
            if ($null -eq $rearFirstNonOn) { $rearFirstNonOn = $sample.Elapsed }
        } elseif ($null -ne $rearFirstNonOn) {
            $rearBackOn = $true
        }
        if ($sample.MainState -eq 'ON') {
            $mainHeld = $false
            if ($null -eq $mainFirstOn) { $mainFirstOn = $sample.Elapsed }
        }
        if ($sample.Owner -ne 'dashboard') {
            $ownerHeld = $false
            if ($null -eq $ownerFirstLost) { $ownerFirstLost = $sample.Elapsed }
        }
    }
    $endPair = if ($samples.Count -gt 0) {
        '{0}/{1}' -f $samples[-1].RearState, $samples[-1].RearCommitted
    } else { '' }
    $ownerEnd = if ($samples.Count -gt 0) { $samples[-1].Owner } else { '' }

    return [pscustomobject]@{
        # Flat array on purpose (no `,$arr` wrap): indexing must hit one record, not a wrapper.
        Samples                 = $samples.ToArray()
        SampleCount             = $samples.Count
        RearHeldOnThroughout    = $rearHeld
        RearFirstNonOnSec       = $rearFirstNonOn
        RearReturnedOnAfterLoss = $rearBackOn
        RearEndStatePair        = $endPair
        MainHeldOffThroughout   = $mainHeld
        MainFirstOnSec          = $mainFirstOn
        OwnerHeldThroughout     = $ownerHeld
        OwnerFirstLostSec       = $ownerFirstLost
        OwnerEnd                = $ownerEnd
    }
}

function Get-ExSurviveFacts {
    <#
      Pure: the app's own logcat around one deliberate lock -> the E13 lock-survival facts
      (ticket #19). The lock is marked straight into the RearCue tag (`adb shell log -t RearCue
      pc-e13-lock-issued`), so the marker, the racing re-projection and the `Dashboard detach`
      line share the device clock -- no PC skew. Built on ConvertTo-ExRearCueEvent, so every
      anchor is an ASCII token (`Dashboard detach`, `Dashboard attach`, `RearDashboardActivity
      onCreate display=`, the `LaunchDashboard` effect label); Chinese log text is never matched
      by literal. Facts:
        LockFound / LockAt / LockRaw       LockAt is the raw `MM-dd HH:mm:ss.fff` marker stamp
        ReprojectAfterLock / ReprojectSec / ReprojectCount
                                           the app's own re-projection racing the reclaim; the
                                           app logs TWO effect lines per dispatch, so the count
                                           counts lines and only the first Sec is a time
        ClearedAfterLock / ClearedSec / ClearedAt / ClearedInstances / ClearedRaw
                                           first `Dashboard detach` after the marker
        ReturnedAfterClear / ReturnSec / ReturnGapMs / ReturnDisplayId / ReturnRaw
                                           first `Dashboard attach` after that detach; the gap
                                           is milliseconds (the real race decides in single-digit
                                           ms), the display comes from the paired `onCreate`
        DetachCountAfterLock / AttachCountAfterLock / EndsAttached
        Events                             the post-lock trail for the evidence file:
                                           lock-marker | reproject | detach | oncreate | attach
      $null means "not measured" (Get-ExDelaySeconds rule): with no marker in the buffer nothing
      is measurable -- a bare detach must never read as "cleared after the lock" -- and
      EndsAttached is $null when the trail holds no detach/attach at all. Everything before the
      marker is setup and never counted.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $Logcat,
        [string] $MarkerPattern = 'pc-\S*lock-issued'
    )

    $markerRe = '^(?:{0})$' -f $MarkerPattern
    $events = Get-RearCueEvent -Logcat $Logcat
    $trail = New-Object System.Collections.Generic.List[object]

    $lockFound = $false
    $lockAt = $null
    $lockRaw = $null
    $lockTime = $null
    $lastOnCreateDisplay = $null
    $cleared = $null
    $returned = $null
    $reprojectFirstSec = $null
    $reprojectCount = 0
    $detachCount = 0
    $attachCount = 0
    $lastAttachDetach = $null

    foreach ($event in $events) {
        $message = $event.Message
        if (-not $lockFound) {
            if ($message -match $markerRe) {
                $lockFound = $true
                $lockAt = $event.Time
                $lockRaw = $event.Raw
                $lockTime = Get-ExLogcatTime -Line $event.Raw
                $trail.Add([pscustomobject]@{
                        Kind = 'lock-marker'; Sec = 0.0; Time = $event.Time
                        DisplayId = $null; Instances = $null; Raw = $event.Raw
                    })
            }
            continue
        }

        $at = Get-ExLogcatTime -Line $event.Raw
        $sec = $null
        if ($null -ne $at -and $null -ne $lockTime) {
            $sec = [math]::Round((Get-ExSignedDeltaSeconds -From $lockTime -To $at), 1)
        }

        if ($event.Kind -eq 'detach') {
            $instances = $null
            if ($message -match '(\d+)\s*$') { $instances = [int]$Matches[1] }
            $detachCount++
            $lastAttachDetach = 'detach'
            $lastOnCreateDisplay = $null
            $trail.Add([pscustomobject]@{
                    Kind = 'detach'; Sec = $sec; Time = $event.Time
                    DisplayId = $null; Instances = $instances; Raw = $event.Raw
                })
            if ($null -eq $cleared) {
                $cleared = [pscustomobject]@{ Sec = $sec; At = $event.Time; Instances = $instances; Raw = $event.Raw; Time = $at }
            }
        } elseif ($event.Kind -eq 'attach') {
            $instances = $null
            if ($message -match '(\d+)\s*$') { $instances = [int]$Matches[1] }
            $attachCount++
            $lastAttachDetach = 'attach'
            $trail.Add([pscustomobject]@{
                    Kind = 'attach'; Sec = $sec; Time = $event.Time
                    DisplayId = $lastOnCreateDisplay; Instances = $instances; Raw = $event.Raw
                })
            if (($null -ne $cleared) -and ($null -eq $returned)) {
                $gapMs = $null
                if ($null -ne $at -and $null -ne $cleared.Time) {
                    $gap = ($at - $cleared.Time).TotalMilliseconds
                    if ($gap -lt 0) { $gap += 86400000 }
                    $gapMs = [int][math]::Round($gap, 0)
                }
                $returned = [pscustomobject]@{
                    Sec = $sec; GapMs = $gapMs; DisplayId = $lastOnCreateDisplay; Raw = $event.Raw
                }
            }
            $lastOnCreateDisplay = $null
        } elseif ($message -match '^RearDashboardActivity onCreate display=(\d+)') {
            $lastOnCreateDisplay = [int]$Matches[1]
            $trail.Add([pscustomobject]@{
                    Kind = 'oncreate'; Sec = $sec; Time = $event.Time
                    DisplayId = $lastOnCreateDisplay; Instances = $null; Raw = $event.Raw
                })
        } elseif ($event.Effects -contains 'LaunchDashboard') {
            $reprojectCount++
            if ($null -eq $reprojectFirstSec) { $reprojectFirstSec = $sec }
            $trail.Add([pscustomobject]@{
                    Kind = 'reproject'; Sec = $sec; Time = $event.Time
                    DisplayId = $null; Instances = $null; Raw = $event.Raw
                })
        }
    }

    $clearedAfter = if ($lockFound) { ($null -ne $cleared) } else { $null }
    $returnedAfter = if ($clearedAfter -eq $true) { ($null -ne $returned) } elseif ($clearedAfter -eq $false) { $false } else { $null }
    $endsAttached = if ($null -eq $lastAttachDetach) { $null } else { ($lastAttachDetach -eq 'attach') }

    return [pscustomobject]@{
        LockFound            = $lockFound
        LockAt               = $lockAt
        LockRaw              = $lockRaw
        ReprojectAfterLock   = if ($lockFound) { ($reprojectCount -gt 0) } else { $null }
        ReprojectSec         = $reprojectFirstSec
        ReprojectCount       = $reprojectCount
        ClearedAfterLock     = $clearedAfter
        ClearedSec           = if ($cleared) { $cleared.Sec } else { $null }
        ClearedAt            = if ($cleared) { $cleared.At } else { $null }
        ClearedInstances     = if ($cleared) { $cleared.Instances } else { $null }
        ClearedRaw           = if ($cleared) { $cleared.Raw } else { $null }
        ReturnedAfterClear   = $returnedAfter
        ReturnSec            = if ($returned) { $returned.Sec } else { $null }
        ReturnGapMs          = if ($returned) { $returned.GapMs } else { $null }
        ReturnDisplayId      = if ($returned) { $returned.DisplayId } else { $null }
        ReturnRaw            = if ($returned) { $returned.Raw } else { $null }
        DetachCountAfterLock = $detachCount
        AttachCountAfterLock = $attachCount
        EndsAttached         = $endsAttached
        Events               = $trail.ToArray()
    }
}

function Get-ExWakeTickFacts {
    <#
      Pure: the device-side tick log of the E12 injection loop (ticket #16) -> injection facts.
      The loop (tools/ex/device/wake-keepalive.sh) appends one line per iteration carrying the
        loop start 09-22 15:58:30 pid=7606 display=1 sleep=0.5
        tick 09-22 15:58:30 rc=0
        loop exit 09-22 15:59:13 pid=7606
      `input` exit code -- this file is the device-side proof that the loop really ran and that
      the injection commands were accepted ("the command did not error" on the PC side is not).
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $TickLines)

    $started = $false
    $loopPid = $null
    $displayId = $null
    $sleepSeconds = $null
    $exited = $false
    $ticks = New-Object System.Collections.Generic.List[object]
    foreach ($line in $TickLines) {
        if ($line -match '^loop start (\S+ \S+) pid=(\d+) display=(\d+) sleep=(\S+)') {
            $started = $true
            $loopPid = [int]$Matches[2]
            $displayId = [int]$Matches[3]
            $sleepSeconds = $Matches[4]
            continue
        }
        if ($line -match '^loop exit ') { $exited = $true; continue }
        if ($line -match '^tick (\S+ \S+) rc=(-?\d+)') {
            $ticks.Add([pscustomobject]@{ Time = $Matches[1]; Rc = [int]$Matches[2]; Raw = $line })
        }
    }
    $errorCount = @($ticks | Where-Object { $_.Rc -ne 0 }).Count
    $firstTick = if ($ticks.Count -gt 0) { $ticks[0].Time } else { $null }
    $lastTick = if ($ticks.Count -gt 0) { $ticks[-1].Time } else { $null }

    return [pscustomobject]@{
        Started      = $started
        Pid          = $loopPid
        DisplayId    = $displayId
        SleepSeconds = $sleepSeconds
        Ticks        = $ticks.ToArray()
        TickCount    = $ticks.Count
        ErrorCount   = $errorCount
        FirstTick    = $firstTick
        LastTick     = $lastTick
        Exited       = $exited
    }
}

function Get-ExPowerGroupEvents {
    <#
      Pure: system-side power transitions of `logcat -b all` (ticket #16) -> structured events.
      Two device lines matter to the E12 verdict:
        PowerGroup: Powering off display group due to power_button (groupId= 1, ...)
        PowerGroup: Waking up power group from Dozing (groupId=1, uid=1000,
                    reason=WAKE_REASON_WAKE_KEY, details=android.policy:KEY)...
      The first is the lock really reaching the rear display group, the second is the injected
      wake key really reaching the power layer (with its reason + details, so a human's
      fingerprint/power-button wake can never be mistaken for our injection). Neighbouring lines
      that merely mention a power group (DreamManagerService, goToSleepInternal, ...) are not
      events. Time is the raw `MM-dd HH:mm:ss.fff` stamp: lexicographic order = chronological.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][AllowEmptyString()][string[]] $Logcat)

    begin { $events = New-Object System.Collections.Generic.List[object] }
    process {
        foreach ($line in $Logcat) {
            $time = $null
            if ($line -match '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') { $time = $Matches[1] }
            if ($line -match 'PowerGroup: Powering off display group due to (\S+) \(groupId=\s*(\d+),') {
                $reason = $Matches[1]
                $groupId = [int]$Matches[2]
                $events.Add([pscustomobject]@{ Time = $time; Kind = 'power-off'; GroupId = $groupId; Reason = $reason; Details = $null; Raw = $line })
            } elseif ($line -match 'PowerGroup: Waking up power group from \S+ \(groupId=\s*(\d+), uid=\s*\d+, reason=(\S+), details=(.*)$') {
                $groupId = [int]$Matches[1]
                $reason = $Matches[2]
                $details = ($Matches[3].Trim() -replace '\)\.\.\.?\s*$', '').Trim()
                $events.Add([pscustomobject]@{ Time = $time; Kind = 'wake'; GroupId = $groupId; Reason = $reason; Details = $details; Raw = $line })
            }
        }
    }
    end { return ,$events.ToArray() }
}

function Get-ExWakePollution {
    <#
      Pure: external interference with a wake keep-alive watch (ticket #16) -> flagged lines.
      A human touching the phone wakes or sleeps it behind the script's back and invalidates the
      window:
        fingerprint     a biometric wake (`details=android.policy:FINGERPRINT:...`) -- the device
                        logs more than one variant (ticket #11 saw `finishCallBack`, the E12 run
                        saw `UnlockFinishT`), so the anchor is the `android.policy:FINGERPRINT`
                        details token, never one variant's tail
        power-button    a power_button transition (sleep) or a WAKE_REASON_POWER_BUTTON wake --
                        the script knows its own press times and attributes the surplus to hands
      The injected wake key (WAKE_REASON_WAKE_KEY) is never flagged. What this cannot see: rear
      display gestures (SubScreenCenter_GestureInputHelper) -- no verbatim device line of one was
      ever archived (ticket #7), so it stays a documented manual check in findings.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][AllowEmptyString()][string[]] $Logcat)

    begin { $hits = New-Object System.Collections.Generic.List[object] }
    process {
        foreach ($line in $Logcat) {
            $class = $null
            if ($line -match 'android\.policy:FINGERPRINT') {
                $class = 'fingerprint'
            } elseif ($line -match 'PowerGroup: Powering off display group due to power_button') {
                $class = 'power-button'
            } elseif ($line -match 'PowerGroup: Waking up power group' -and $line -match 'reason=WAKE_REASON_POWER_BUTTON') {
                $class = 'power-button'
            }
            if (-not $class) { continue }
            $time = $null
            if ($line -match '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') { $time = $Matches[1] }
            $hits.Add([pscustomobject]@{ Time = $time; Class = $class; Raw = $line })
        }
    }
    end { return ,$hits.ToArray() }
}

function Select-ExExternalPollution {
    <#
      Pure: flagged pollution lines (Get-ExWakePollution, tickets #16/#19) minus what this run
      did itself. Two signatures are ours:
        * a power-button transition within +-3s of one of our own input injections -- lock/reset
          power presses AND wake pokes are stamped with `date` on the device, the same clock as
          the log (a directed rear-display KEYCODE_WAKEUP can log a `power_button` power-off of
          the MAIN group as a mode flip, so every injection gets a stamp, not just the presses)
        * a `power_button` power-off within 200ms AFTER a WAKE_REASON_WAKE_KEY event (-WakeEvents):
          the injected wake key only ever comes from our own loop/pokes and its mode flip lands in
          the same instant chain (this device: 3ms apart, fixture logcat-e13-wake-flip.txt)
      A fingerprint wake is never ours. What survives this filter is external interference (a hand
      on the phone) and voids the watch round -- the samples go to erratum.md instead of the
      verdict. $PowerPresses entries carry `DeviceTime` as `MM-dd HH:mm:ss`. What the 200ms flip
      rule can theoretically hide: a human power press landing in the same 200ms after an
      injection-induced wake -- narrower than any stamp window, accepted.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyCollection()][AllowNull()][object[]] $Hits,
        [Parameter(Position = 1)][AllowEmptyCollection()][AllowNull()][object[]] $PowerPresses = @(),
        [Parameter(Position = 2)][AllowEmptyCollection()][AllowNull()][object[]] $WakeEvents = @()
    )

    $external = New-Object System.Collections.Generic.List[object]
    foreach ($hit in @($Hits)) {
        if ($null -eq $hit) { continue }
        $ours = $false
        if (($hit.Class -eq 'power-button') -and $hit.Time) {
            $hitTime = [datetime]::ParseExact($hit.Time, 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
            foreach ($press in @($PowerPresses)) {
                if (($null -eq $press) -or (-not $press.DeviceTime)) { continue }
                $pressTime = [datetime]::ParseExact($press.DeviceTime, 'MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
                $delta = [math]::Abs((Get-ExSignedDeltaSeconds -From $pressTime -To $hitTime))
                if ($delta -le 3) { $ours = $true; break }
            }
            if ((-not $ours) -and ($hit.Raw -match 'Powering off display group due to power_button')) {
                foreach ($wake in @($WakeEvents)) {
                    if (($null -eq $wake) -or ($wake.Kind -ne 'wake') -or ($wake.Reason -ne 'WAKE_REASON_WAKE_KEY') -or (-not $wake.Time)) { continue }
                    $wakeTime = [datetime]::ParseExact($wake.Time, 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
                    $delta = Get-ExSignedDeltaSeconds -From $wakeTime -To $hitTime
                    if (($delta -ge 0) -and ($delta -le 0.2)) { $ours = $true; break }
                }
            }
        }
        if (-not $ours) { $external.Add($hit) }
    }
    return ,$external.ToArray()
}

function Get-ExTaskPlacement {
    <#
      E14 (ticket #18): where does one root task ACTUALLY sit? Pure parser over
      `dumpsys activity activities` -> structured placement facts (text in, facts out).

      The verdict of the task-move probe rests on this: `service call` replies and command exit
      codes never decide anything (the ticket is explicit), only the task stack does. Look the task
      up by id (`12985` or the `t12985` form used in dumpsys and in the ticket) or by its
      ActivityRecord component (prep: find the Dashboard task id before it is known).

      Block shape (Android 16 / HyperOS 3 real output):
        Display #1 (activities from top to bottom):
          * Task{3879b2d #12985 type=standard A=10333:com.rearcue.poc U=0 ... sz=1}
            topResumedActivity=ActivityRecord{... com.rearcue.poc/.rear.RearDashboardActivity t12985}
              rootOfTask=true task=Task{3879b2d #12985 type=standard A=10333:com.rearcue.poc}
      Only `* Task{` lines start a block -- the `rootOfTask=true task=Task{...}` self-references
      inside a block must not be read as task headers. A nested task header carries
      `rootTaskId=<root>` (that id is what moveRootTaskToDisplay wants); a root task does not.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysActivities,
        [string] $TaskId,
        [string] $Component
    )
    if (-not $TaskId -and -not $Component) { throw 'Get-ExTaskPlacement needs -TaskId or -Component' }
    $wanted = $null
    if ($TaskId) { $wanted = [int]($TaskId -replace '^t', '') }

    $blocks = New-Object System.Collections.Generic.List[object]
    $records = New-Object System.Collections.Generic.List[object]
    $display = $null
    $open = $null
    foreach ($line in ($DumpsysActivities -split "`r?`n")) {
        if ($line -match '^\s*Display #(\d+)') {
            if ($open) { $blocks.Add($open); $open = $null }
            $display = [int]$Matches[1]
            continue
        }
        if ($line -match '^\s*\* Task\{[0-9a-f]+\s+#(\d+)') {
            if ($open) { $blocks.Add($open) }
            $id = [int]$Matches[1]
            $root = $id
            if ($line -match 'rootTaskId=(\d+)') { $root = [int]$Matches[1] }
            $affinity = $null
            if ($line -match 'A=\d+:(\S+)') { $affinity = $Matches[1] }
            $open = [pscustomobject]@{
                TaskId     = $id
                RootTaskId = $root
                Affinity   = $affinity
                Header     = $line
                DisplayId  = $display
                Component  = $null
                Resumed    = $false
            }
            continue
        }
        if ($line -match 'ActivityRecord\{[^}]*?\s([^\s}]+/[^\s}]+)\s+t(\d+)\}') {
            $recordTask = [int]$Matches[2]
            $records.Add([pscustomobject]@{ TaskId = $recordTask; Component = $Matches[1]; DisplayId = $display })
            if ($open -and $recordTask -eq $open.TaskId -and (-not $open.Component)) { $open.Component = $Matches[1] }
            if ($open -and $recordTask -eq $open.TaskId -and ($line -match 'topResumedActivity=')) { $open.Resumed = $true }
        }
    }
    if ($open) { $blocks.Add($open) }

    $hit = $null
    if ($null -ne $wanted) {
        $hit = $blocks | Where-Object { $_.TaskId -eq $wanted } | Select-Object -First 1
        if (-not $hit) {
            $record = $records | Where-Object { $_.TaskId -eq $wanted } | Select-Object -First 1
            if ($record) {
                $hit = [pscustomobject]@{
                    TaskId = $record.TaskId; RootTaskId = $record.TaskId; Affinity = $null
                    Header = $null; DisplayId = $record.DisplayId; Component = $record.Component; Resumed = $false
                }
            }
        }
    } else {
        # Component matching must accept BOTH print forms: dumpsys shows the short
        # `pkg/.Cls` (ComponentName.flattenToShortString, see RearProjectionVerifierTest) while
        # callers pass the full `pkg/pkg.Cls` -- exact -eq missed the short form and reported
        # `absent` for a task that was right there (20260923-011503 firstcast samples).
        $expandClass = {
            param($Comp)
            # Canonical = the fully-qualified CLASS: expand the short `pkg/.Cls` to `pkg.Cls`,
            # take the bare class from the full `pkg/pkg.Cls`.
            if ($Comp -match '^([^/]+)/(.*)$') {
                $pkg = $Matches[1]
                $cls = $Matches[2]
                if ($cls.StartsWith('.')) { return $pkg + $cls }
                return $cls
            }
            return $Comp
        }
        $wantedClass = & $expandClass $Component
        $record = $records | Where-Object { (& $expandClass $_.Component) -eq $wantedClass } | Select-Object -First 1
        if ($record) {
            $hit = $blocks | Where-Object { $_.TaskId -eq $record.TaskId } | Select-Object -First 1
            if (-not $hit) {
                $hit = [pscustomobject]@{
                    TaskId = $record.TaskId; RootTaskId = $record.TaskId; Affinity = $null
                    Header = $null; DisplayId = $record.DisplayId; Component = $record.Component; Resumed = $false
                }
            }
        }
    }

    if (-not $hit) {
        return [pscustomobject]@{
            Found     = $false
            TaskId    = $wanted
            DisplayId = $null
            RootTaskId = $null
            Affinity  = $null
            Component = $null
            Resumed   = $false
            Header    = $null
        }
    }
    return [pscustomobject]@{
        Found      = $true
        TaskId     = $hit.TaskId
        DisplayId  = $hit.DisplayId
        RootTaskId = $hit.RootTaskId
        Affinity   = $hit.Affinity
        Component  = $hit.Component
        Resumed    = [bool]$hit.Resumed
        Header     = $hit.Header
    }
}

function ConvertTo-ExServiceCallResult {
    <#
      E14 (ticket #18): one raw `service call` reply line -> structured facts, so the archived
      transaction output can be read and compared. This is EVIDENCE ONLY: the verdict never looks
      at the reply parcel (a void success reply does not mean the task moved, and the ticket bans
      reply/exit-code judging outright).

        Result: Parcel( 00000000    '....')                                    <- void success
        Result: Parcel(Error: 0xffffffffffffffb6 "Not a data message")         <- no such code
    #>
    [CmdletBinding()]
    param([Parameter(Position = 0)][AllowEmptyString()][AllowNull()][string] $Line)

    $raw = if ($null -eq $Line) { '' } else { $Line.Trim() }
    if ($raw -notmatch '^Result:\s*Parcel\((.*)\)\s*$') {
        return [pscustomobject]@{ Found = $false; IsError = $false; Status = $null; ErrorText = $null; Body = $null; Raw = $raw }
    }
    $body = $Matches[1]
    $isError = $false
    $errorText = $null
    if ($body -match 'Error:\s*\S+\s+"([^"]*)') { $isError = $true; $errorText = $Matches[1] }
    $status = $null
    $hex = [regex]::Match($body, '(0x[0-9a-fA-F]+|\b[0-9a-fA-F]{8}\b)')
    if ($hex.Success) { $status = $hex.Value }
    return [pscustomobject]@{
        Found     = $true
        IsError   = $isError
        Status    = $status
        ErrorText = $errorText
        Body      = $body.Trim()
        Raw       = $raw
    }
}

function Get-ExTaskMoveEvents {
    <#
      E14 (ticket #18): system-side evidence around a task-move attempt -> structured facts.

      The REJECTED verdict must quote a real system refusal line, so the classifier collects the
      verbatim lines instead of summarising them away:
        rearDisplay check locked: com.rearcue.poc -> deny        <- the lock gate (LockedDeny)
        aborted activity = ActivityRecord{...} show on rear display
        wm_finish_activity: [...,com.rearcue.poc/.rear.RearDashboardActivity,remove-task]
                                                                 <- the system reclaimed the task
      `Permission Denial` / `SecurityException` / `Not allow` lines are denies too (a binder
      transaction can be refused at the permission layer). Neighbouring chatter is not collected.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][string[]] $Logcat)

    begin {
        $deny = New-Object System.Collections.Generic.List[string]
        $aborted = New-Object System.Collections.Generic.List[string]
        $reap = New-Object System.Collections.Generic.List[string]
        $move = New-Object System.Collections.Generic.List[string]
        $lockedDeny = $false
    }
    process {
        foreach ($line in $Logcat) {
            if ($line -match 'rearDisplay check locked') {
                $lockedDeny = $true
                $deny.Add($line)
            } elseif ($line -match 'aborted activity') {
                $aborted.Add($line)
                $deny.Add($line)
            } elseif ($line -match 'Permission Denial' -and $line -match 'ActivityTask|activity_task|ActivityManager|rear|Shell') {
                $deny.Add($line)
            } elseif ($line -match 'SecurityException' -and $line -match 'ActivityTask|activity_task|moveRootTask|ActivityManager|rear|Shell command') {
                $deny.Add($line)
            } elseif ($line -match 'Not allow non-system app' -or ($line -match 'not allow' -and $line -match 'rear display')) {
                $deny.Add($line)
            } elseif ($line -match 'wm_finish_activity' -and $line -match 'RearDashboardActivity') {
                $reap.Add($line)
            } elseif ($line -match 'moveRootTaskToDisplay|move-stack|moveTaskToDisplay') {
                $move.Add($line)
            }
        }
    }
    end {
        return [pscustomobject]@{
            DenyLines   = $deny.ToArray()
            LockedDeny  = $lockedDeny
            AbortedLines = $aborted.ToArray()
            ReapLines   = $reap.ToArray()
            MoveLines   = $move.ToArray()
        }
    }
}

function Format-ExTaskMoveSampleLine {
    <#
      Pure: one E14 watch sampling point -> the wire line Get-ExTaskMoveSampleFacts parses back
      (ticket #18). Writer next to its parser, so the format cannot drift. `$null` TaskId = the
      Dashboard task was gone from `dumpsys activity activities` at that point (`task=absent`).
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][int] $Elapsed,
        [Parameter(Position = 1)][AllowNull()][object] $TaskId,
        [Parameter(Position = 2)][AllowNull()][object] $TaskDisplay,
        [Parameter(Mandatory, Position = 3)][string] $Owner,
        [Parameter(Mandatory, Position = 4)][string] $StatePair,
        [Parameter(Position = 5)][AllowEmptyString()][string] $Last = ''
    )
    $taskField = 'absent'
    if ($null -ne $TaskId -and ('{0}' -f $TaskId) -ne '') {
        $taskField = ('t{0}@d{1}' -f $TaskId, $TaskDisplay)
    }
    return ('[+{0,4}s] task={1} owner={2,-9} rear={3,-18} last={4}' -f $Elapsed, $taskField, $Owner, $StatePair, $Last)
}

function Get-ExTaskMoveSampleFacts {
    <#
      Pure: the archived E14 watch sample lines (ticket #18) -> the placement facts the verdict
      asserts on. One line per sampling point, written by the scenario itself:
        [+   3s] task=t12985@d1 owner=dashboard rear=ON/ON           last=<latest RearCue line>
      `task=t<id>@d<display>` is the task's placement in `dumpsys activity activities` at that
      point; `task=absent` says the task was gone. Facts derived (RearDisplayId is identified at
      runtime from display flags -- never hardcoded into the conclusion):
        TaskEverOnRear / TaskFirstOnRearSec / TaskLastOnRearSec
        TaskMissingFirstSec (first point with the task gone)
        TaskEndPlacement ('d0' / 'd1' / 'absent')
        OwnerFirstDashboardSec / RearEndStatePair
      Malformed lines are skipped, never guessed at; an empty watch claims nothing. Samples is a
      flat array on purpose (a wrapped array makes Samples[-1] hit the wrapper, ticket #11).
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][AllowEmptyCollection()][string[]] $SampleLines,
        [Parameter(Mandatory, Position = 1)][int] $RearDisplayId
    )

    $samples = New-Object System.Collections.Generic.List[object]
    foreach ($line in $SampleLines) {
        if ($line -notmatch '^\[\+\s*(\d+)s\]\s+task=(absent|t(\d+)@d(\d+))\s+owner=(\S+)\s+rear=(\w+)/(\w+)(?:\s+last=(.*))?$') {
            continue
        }
        $taskId = $null
        $taskDisplay = $null
        if ($Matches[2] -ne 'absent') { $taskId = [int]$Matches[3]; $taskDisplay = [int]$Matches[4] }
        $samples.Add([pscustomobject]@{
                Elapsed       = [int]$Matches[1]
                TaskId        = $taskId
                TaskDisplay   = $taskDisplay
                OnRear        = (($null -ne $taskDisplay) -and ($taskDisplay -eq $RearDisplayId))
                Owner         = $Matches[5]
                RearState     = $Matches[6]
                RearCommitted = $Matches[7]
                Last          = $Matches[8]
            })
    }

    $firstOnRear = $null
    $lastOnRear = $null
    $firstMissing = $null
    $firstDashboard = $null
    foreach ($sample in $samples) {
        if ($sample.OnRear) {
            if ($null -eq $firstOnRear) { $firstOnRear = $sample.Elapsed }
            $lastOnRear = $sample.Elapsed
        }
        if ($null -eq $sample.TaskId -and $null -eq $firstMissing) { $firstMissing = $sample.Elapsed }
        if ($sample.Owner -eq 'dashboard' -and $null -eq $firstDashboard) { $firstDashboard = $sample.Elapsed }
    }
    $endPlacement = 'absent'
    $endPair = ''
    if ($samples.Count -gt 0) {
        if ($null -ne $samples[-1].TaskId) { $endPlacement = ('d{0}' -f $samples[-1].TaskDisplay) }
        $endPair = '{0}/{1}' -f $samples[-1].RearState, $samples[-1].RearCommitted
    }

    return [pscustomobject]@{
        Samples                = $samples.ToArray()
        SampleCount            = $samples.Count
        TaskEverOnRear         = ($null -ne $firstOnRear)
        TaskFirstOnRearSec     = $firstOnRear
        TaskLastOnRearSec      = $lastOnRear
        TaskMissingFirstSec    = $firstMissing
        TaskEndPlacement       = $endPlacement
        OwnerFirstDashboardSec = $firstDashboard
        RearEndStatePair       = $endPair
    }
}

function Get-ExChainSummary {
    <# Collapse parsed events into the facts an experiment asserts on (pure, unit-tested). #>
    [CmdletBinding()]
    param([Parameter(Position = 0)][AllowEmptyCollection()][object[]] $Events)

    # NB: do not name a loop variable $event while a parameter called $Events exists --
    # PowerShell is case-insensitive and the collision silently breaks property access.
    $list = @($Events)
    $kinds = @($list | ForEach-Object { $_.Kind })
    $effects = @($list | ForEach-Object { $_.Effects } | Where-Object { $_ } | Select-Object -Unique)

    $lastIconSet = @()
    foreach ($item in $list) {
        if (@($item.IconSet).Count -gt 0) { $lastIconSet = @($item.IconSet) }
    }

    $diagnostics = @($list | Where-Object { $_.Kind -eq 'diagnostic' })
    $servers = @($diagnostics | ForEach-Object { $_.Shizuku.Server } | Where-Object { $_ })
    $grants = @($diagnostics | ForEach-Object { $_.Shizuku.Granted } | Where-Object { $_ })
    $serverNow = if ($servers.Count -gt 0) { $servers[-1] } else { $null }
    $grantNow = if ($grants.Count -gt 0) { $grants[-1] } else { $null }
    # "went down" = the app saw a binder and then lost it. A run where the app never sees a binder
    # (`server=false` from the first line, this machine's normal state) is NOT a drop.
    $wentDown = $false
    $cameBack = $false
    $firstUp = [array]::IndexOf($servers, 'true')
    if (($firstUp -ge 0) -and ($firstUp -lt ($servers.Count - 1))) {
        $after = @($servers[($firstUp + 1)..($servers.Count - 1)])
        $wentDown = ($after -contains 'false')
        if ($wentDown) {
            $firstDown = [array]::IndexOf($after, 'false')
            if ($firstDown -lt ($after.Count - 1)) {
                $cameBack = (@($after[($firstDown + 1)..($after.Count - 1)]) -contains 'true')
            }
        }
    }

    return [pscustomobject]@{
        EventCount            = $list.Count
        Kinds                 = $kinds
        Effects               = $effects
        PostedPackages        = @($list | Where-Object { $_.Kind -eq 'posted' } | ForEach-Object { $_.Package })
        RemovedPackages       = @($list | Where-Object { $_.Kind -eq 'removed' } | ForEach-Object { $_.Package })
        LaunchRequested       = ($effects -contains 'LaunchDashboard')
        LaunchSent            = (($kinds -contains 'project') -or ($kinds -contains 'update'))
        LaunchConfirmed       = ($kinds -contains 'attach')
        IconSetUpdates        = @($kinds | Where-Object { $_ -eq 'update' }).Count
        ExitRequested         = ($effects -contains 'ExitDashboard')
        Exited                = (($kinds -contains 'exit') -or ($kinds -contains 'detach'))
        SignalActions         = @($list | Where-Object { $_.Kind -eq 'signal' } | ForEach-Object { $_.Action })
        ListenerConnected     = ($kinds -contains 'listener-connected')
        LastIconSet           = $lastIconSet
        ShizukuServer         = $serverNow
        ShizukuServerWentDown = $wentDown
        ShizukuServerCameBack = $cameBack
        ShizukuGranted        = $grantNow
    }
}

function Get-ExMiuiOpFacts {
    <#
      `appops get <package>` text -> one mode per MIUIOP(<num>) line. Pure (ticket #27).

      MIUI's per-app autostart whitelist has NO named appop (`appops get <pkg> AUTO_START` is an
      Unknown operation string), but the settings toggle writes numeric MIUI ops: flipping the
      MIUI autostart switch flips MIUIOP(10008) and MIUIOP(10053) allow<->ignore on that package
      (2026-09-23 toggle diff; docs/poc-findings.md, ticket #27).
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyCollection()][AllowEmptyString()][string[]] $AppopsText)

    $facts = @{}
    # one string[] element can hold many log lines (fixtures load -Raw): split before matching,
    # otherwise only the first MIUIOP line of a blob would ever be read.
    foreach ($line in (($AppopsText -join "`n") -split "`r?`n")) {
        if ($line -match 'MIUIOP\((\d+)\):\s*([A-Za-z_]+)') {
            $facts[[int]$Matches[1]] = $Matches[2]
        }
    }
    return $facts
}

function Compare-ExMiuiOpFacts {
    <#
      Two Get-ExMiuiOpFacts maps -> the ops whose mode changed (the toggle diff). Pure (ticket #27).
      An op present on one side only is reported with the other side $null.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][hashtable] $Before,
        [Parameter(Mandatory, Position = 1)][hashtable] $After
    )

    $ops = New-Object System.Collections.Generic.List[int]
    foreach ($op in $Before.Keys) { $ops.Add([int]$op) }
    foreach ($op in $After.Keys) { $ops.Add([int]$op) }

    $changed = New-Object System.Collections.Generic.List[object]
    foreach ($op in ($ops.ToArray() | Sort-Object -Unique)) {
        $beforeMode = if ($Before.ContainsKey($op)) { [string]$Before[$op] } else { $null }
        $afterMode = if ($After.ContainsKey($op)) { [string]$After[$op] } else { $null }
        if ($beforeMode -ne $afterMode) {
            $changed.Add([pscustomobject]@{ Op = [int]$op; Before = $beforeMode; After = $afterMode })
        }
    }
    return ,$changed.ToArray()
}

function Get-ExUiSwitchRow {
    <#
      One list row of a `uiautomator dump`: the row whose id/title text is -Label and its visible
      toggle. Pure (ticket #27, MIUI autostart rows).

      The MIUI autostart row is a row-sized clickable Switch wrapping icon + title + the visible
      toggle (`com.miui.securitycenter:id/sliding_button`; its `checked` attribute IS the whitelist
      state). Switches pair with titles by vertical distance: the dump is a flat node list, rows
      reshuffle between the allow/block sections the moment any switch flips, and the title text
      of interest is ASCII (the app label), so no Chinese literal is matched here.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $WindowDump,
        [Parameter(Mandatory, Position = 1)][string] $Label
    )

    $missing = [pscustomobject]@{ Found = $false; Checked = $false; X = 0; Y = 0 }
    $segments = $WindowDump -split '<node'

    $titleY = $null
    foreach ($seg in $segments) {
        if ($seg -match 'id/title' -and $seg -match ('text="' + [regex]::Escape($Label) + '"')) {
            if ($seg -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
                $titleY = ([int]$Matches[2] + [int]$Matches[4]) / 2
            }
            break
        }
    }
    if ($null -eq $titleY) { return $missing }

    $bestDistance = 1000000
    $bestX = 0
    $bestY = 0
    $bestChecked = $false
    foreach ($seg in $segments) {
        if ($seg -match 'sliding_button' -and $seg -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $centerY = ([int]$Matches[2] + [int]$Matches[4]) / 2
            $distance = [Math]::Abs($centerY - $titleY)
            if ($distance -lt $bestDistance) {
                $bestDistance = $distance
                $bestX = ([int]$Matches[1] + [int]$Matches[3]) / 2
                $bestY = $centerY
                $bestChecked = ($seg -match 'checked="true"')
            }
        }
    }
    if ($bestDistance -gt 60) { return $missing }
    return [pscustomobject]@{ Found = $true; Checked = $bestChecked; X = $bestX; Y = $bestY }
}

function Get-ExAutostartPageFacts {
    <#
      Is this MIUI's autostart management page, and how many apps does each section hold? Pure
      (ticket #27). Page markers from the real dump (2026-09-23):
        resource-id com.miui.securitycenter:id/auto_start_list            (the app list)
        header_title texts "<allowed>N...autostart" / "<blocked>N...autostart" (section headers)
      The Chinese header literals are \uXXXX escapes decoded at run time (this file is ASCII-only;
      Windows PowerShell 5.1 would read raw Chinese source as ANSI/GBK). Counts are $null when the
      list is scrolled past the headers: only the visible page is read.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $WindowDump)

    $allowedMark = [regex]::Unescape('\u5141\u8BB8')                    # "allowed"
    $blockedMark = [regex]::Unescape('\u7981\u6B62')                    # "blocked"
    $tailMark = [regex]::Unescape('\u4E2A\u5E94\u7528\u81EA\u542F\u52A8')   # "app(s) autostart"

    $isPage = ($WindowDump -match 'auto_start_list')
    $allowedCount = $null
    $blockedCount = $null
    foreach ($seg in ($WindowDump -split '<node')) {
        if ($seg -notmatch 'header_title') { continue }
        if ($seg -notmatch 'text="([^"]*)"') { continue }
        $text = $Matches[1]
        if ($text -match ('^' + [regex]::Escape($allowedMark) + '(\d+)' + [regex]::Escape($tailMark) + '$')) {
            $allowedCount = [int]$Matches[1]
        }
        elseif ($text -match ('^' + [regex]::Escape($blockedMark) + '(\d+)' + [regex]::Escape($tailMark) + '$')) {
            $blockedCount = [int]$Matches[1]
        }
    }
    return [pscustomobject]@{
        IsAutostartPage = $isPage
        AllowedCount    = $allowedCount
        BlockedCount    = $blockedCount
    }
}

function Get-ExAutostartJumpFacts {
    <#
      One jump attempt: `am start` output + `dumpsys activity activities` text -> facts. Pure
      (ticket #27). Verdict words (16-autostart-probe.ps1 / docs/poc-findings.md):
        JUMP-PASS        the autostart activity is among the resumed activities
        JUMP-NO-TASK     the component/action does not resolve (ActivityNotFoundException shapes)
        JUMP-WRONG-PAGE  something started, but not the autostart activity
        JUMP-NO-EFFECT   no start and no error line (nothing to attribute)
      `am start` output only reports the launch attempt (a silently aborted launch looks the same
      as success), so the verdict reads the resumed activity, never the command exit code. The
      dump holds one topResumedActivity per display; the settings page must be one of them.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyCollection()][AllowEmptyString()][string[]] $AmOutput,
        [Parameter(Position = 1)][AllowEmptyCollection()][AllowEmptyString()][string[]] $DumpsysActivities = @(),
        [string] $AutostartActivity = (Get-ExConfig).AutostartActivity
    )

    $amText = ($AmOutput -join "`n")
    $started = ($amText -match 'Starting: Intent')
    $noTask = ($amText -match 'Activity class \{[^}]*\} does not exist' -or
        $amText -match 'ActivityNotFoundException' -or
        $amText -match 'Error type 3')
    $errorText = $null
    if ($amText -match 'Error:\s*(.*)$') { $errorText = $Matches[1].Trim() }

    $resumedComponent = $null
    $onAutostartPage = $false
    foreach ($line in $DumpsysActivities) {
        if ($line -match 'topResumedActivity=ActivityRecord\{\S+\s\S+\s(\S+)\s') {
            if ($null -eq $resumedComponent) { $resumedComponent = $Matches[1] }
            if ($Matches[1] -eq $AutostartActivity) { $onAutostartPage = $true }
        }
    }
    return [pscustomobject]@{
        Started          = $started
        NoTask           = $noTask
        ErrorText        = $errorText
        ResumedComponent = $resumedComponent
        OnAutostartPage  = $onAutostartPage
    }
}

function Get-ExGreezeEvents {
    <#
      Pure: GreezeManager lines -> structured freeze events (ticket #29 freeze probe). The
      source is either `logcat -s GreezeManager` or the `dumpsys greezer` history block --
      the SAME events print in two shapes:
        logcat   `09-23 02:55:06.456  5157  8991 D GreezeManager: FZ uid = 10336 reason =tobg success !`
        history  `2026-09-22T19:51:50.020473 - FZ uid = 10424 pid = [ 20600 ]  reason : from system caller : 1`
      Kinds (one per device line shape):
        freeze          `FZ uid = <uid> [pid = [ ... ]] reason =<r> success !` / `reason : <r> caller : <c>`
        thaw            `THAW uid = <uid> pid = [ ... ] reason : <r> caller : <c>` (reason can be
                        two words: `Activity Start`, `from system`, `screen on`)
        freeze-skipped  `freezeUid uid=<uid> return:<WHY>` (the freezer chose NOT to freeze)
        skip-visible    `Uid <uid> was show on screen, skip it` (visible = freeze-exempt)
        subscreen-uid   `setSubScreenUid uid=<uid>` (the uid currently showing on the rear)
        binder-trans    `Receive frozen binder trans: dstUid=... callerUid=... delay=..ms`
      Time is the raw `MM-dd HH:mm:ss.fff` stamp where one exists (logcat or history ISO,
      normalized to the logcat shape) -- lexicographic order = chronological within one day.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][AllowEmptyString()][string[]] $Logcat)

    begin { $events = New-Object System.Collections.Generic.List[object] }
    process {
        foreach ($line in $Logcat) {
            # logcat lines carry the `GreezeManager:` tag; `dumpsys greezer` history lines carry
            # an ISO stamp instead (`2026-09-22T23:41:00.454925 - FZ uid = ...`) -- gate on either
            # (bit once: gating on the tag alone silently emptied the history parse).
            if ($line -notmatch 'GreezeManager' -and $line -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}') { continue }
            $time = $null
            if ($line -match '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') { $time = $Matches[1] }
            elseif ($line -match '^\d{4}-(\d{2}-\d{2})T(\d{2}:\d{2}:\d{2}\.\d{3})') { $time = ($Matches[1] + ' ' + $Matches[2]) }

            $kind = $null; $uid = $null; $pids = @(); $reason = $null; $caller = $null; $delayMs = $null
            # NB: copy every $Matches group into a local BEFORE any further -match -- a filter
            # scriptblock's `$_ -match ...` overwrites $Matches (bit once, 20260923-030402:
            # `$Matches[3].Trim()` after a `Where-Object { $_ -match }` read back as $null).
            if ($line -match 'THAW uid = (\d+)(?: pid = \[ ([^\]]*) \])?[^\n]*?reason : (.+?)(?: caller : (\d+))?\s*$') {
                $kind = 'thaw'
                $uid = $Matches[1]
                $pidText = $Matches[2]
                $reason = $Matches[3]
                $caller = $Matches[4]
                $pids = @(($pidText -split '\s+') | Where-Object { $_ -match '^\d+$' })
                $reason = ($reason.Trim() -replace '\s*failed = \[[^\]]*\]\s*', ' ').Trim()
            } elseif ($line -match 'FZ uid = (\d+)(?: pid = \[ ([^\]]*) \])?\s+reason : (.+?)(?: caller : (\d+))?\s*$') {
                $kind = 'freeze'
                $uid = $Matches[1]
                $pidText = $Matches[2]
                $reason = $Matches[3].Trim()
                $caller = $Matches[4]
                $pids = @(($pidText -split '\s+') | Where-Object { $_ -match '^\d+$' })
            } elseif ($line -match 'FZ uid = (\d+) reason =(\S+) success') {
                $kind = 'freeze'; $uid = $Matches[1]; $reason = $Matches[2]
            } elseif ($line -match 'freezeUid uid=(\d+) return:(\S+)') {
                $kind = 'freeze-skipped'; $uid = $Matches[1]; $reason = $Matches[2]
            } elseif ($line -match 'Died uid = (\d+)') {
                $kind = 'died'; $uid = $Matches[1]
            } elseif ($line -match 'Uid (\d+) was show on screen, skip it') {
                $kind = 'skip-visible'; $uid = $Matches[1]
            } elseif ($line -match 'setSubScreenUid uid=(\d+)') {
                $kind = 'subscreen-uid'; $uid = $Matches[1]
            } elseif ($line -match 'Receive frozen binder trans: dstUid=(\d+) dstPid=(\d+) callerUid=(\d+) callerPid=(\d+) callerTid=\d+ delay=(\d+)ms') {
                $kind = 'binder-trans'; $uid = $Matches[1]
                $dstPid = $Matches[2]
                $reason = ('callerUid={0} callerPid={1}' -f $Matches[3], $Matches[4])
                $delayMs = [int]$Matches[5]
                $pids = @($dstPid)
            }
            if (-not $kind) { continue }
            $events.Add([pscustomobject]@{
                    Time = $time; Kind = $kind; Uid = $uid; Pids = $pids
                    Reason = $reason; Caller = $caller; DelayMs = $delayMs; Raw = $line
                })
        }
    }
    end { return ,$events.ToArray() }
}

function Get-ExFreezeTimeline {
    <#
      Pure: the freeze-probe timeline (ticket #29) out of `logcat -s RearCue` lines -- post
      markers paired with the app's `posted <pkg>` delivery lines on ONE device clock.

      Markers are shell `log` lines `pc-freeze-<id>` written just BEFORE each `cmd notification
      post` (ids: `ctl-*` control leg, `post-*` while frozen, `onrear-*` with the Dashboard on
      the rear display); delivery lines are the app's own `posted <pkg>` lines. Only
      `posted` lines of $DeliveryPackage pair (the automation posts from com.android.shell, an
      Allowlist App) -- `removed` lines and other packages stay in Deliveries but unpaired ON
      PURPOSE (`cmd notification` has no cancel on this build, so no removal marker exists).

      Pairing is order-preserving: each marker takes the first UNUSED delivery line after it.
      Returns { Markers, Deliveries, Pairs, UnpairedMarkers, UnpairedDeliveries }; a pair is
      { Id, PostTime, DeliveryTime, DelaySec } with DelaySec via Get-ExSignedDeltaSeconds.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][AllowEmptyString()][string[]] $Logcat,
        [string] $DeliveryPackage = 'com.android.shell'
    )

    begin {
        $markers = New-Object System.Collections.Generic.List[object]
        $deliveries = New-Object System.Collections.Generic.List[object]
    }
    process {
        foreach ($line in $Logcat) {
            if ($line -match 'pc-freeze-((?:ctl|post|onrear)-[A-Za-z0-9_-]+)') {
                $markerId = $Matches[1]
                $time = $null
                if ($line -match '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') { $time = $Matches[1] }
                $markers.Add([pscustomobject]@{ Id = $markerId; Time = $time; Raw = $line })
                continue
            }
            $event = ConvertTo-ExRearCueEvent -Line $line
            if ($null -eq $event) { continue }
            if ($event.Kind -eq 'posted' -or $event.Kind -eq 'removed') {
                $deliveries.Add([pscustomobject]@{
                        Kind = $event.Kind; Package = $event.Package; Time = $event.Time; Raw = $line
                    })
            }
        }
    }
    end {
        $pairs = New-Object System.Collections.Generic.List[object]
        $used = New-Object System.Collections.Generic.List[int]
        for ($m = 0; $m -lt $markers.Count; $m++) {
            $marker = $markers[$m]
            # Live probes (`ctl-*` / `onrear-*`) deliver within seconds BY DEFINITION: their
            # delivery must land before the NEXT marker, so a probe that never delivered can
            # never steal the next probe's delivery (bit twice, 20260923-030402/-032045).
            # Frozen posts (`post-*`) are the thing under measurement and stay unbounded --
            # their delivery legitimately arrives at the thaw, after later markers.
            $bound = $null
            if ($marker.Id -notlike 'post-*' -and ($m + 1) -lt $markers.Count) {
                $bound = $markers[$m + 1].Time
            }
            for ($i = 0; $i -lt $deliveries.Count; $i++) {
                if ($used -contains $i) { continue }
                $delivery = $deliveries[$i]
                if ($delivery.Kind -ne 'posted') { continue }
                if ($DeliveryPackage -and $delivery.Package -ne $DeliveryPackage) { continue }
                if ($delivery.Time -le $marker.Time) { continue }
                if ($bound -and $delivery.Time -ge $bound) { continue }
                $from = Get-ExLogcatTime -Line $marker.Raw
                $to = Get-ExLogcatTime -Line $delivery.Raw
                $delay = $null
                if ($null -ne $from -and $null -ne $to) { $delay = [math]::Round((Get-ExSignedDeltaSeconds -From $from -To $to), 1) }
                $used.Add($i)
                $pairs.Add([pscustomobject]@{
                        Id = $marker.Id; PostTime = $marker.Time
                        DeliveryTime = $delivery.Time; DelaySec = $delay
                    })
                break
            }
        }
        $unpairedMarkers = @($markers | Where-Object {
                $id = $_.Id
                -not ($pairs | Where-Object { $_.Id -eq $id })
            })
        # Unpaired = the delivery lines no pair consumed ($used tracks consumption by index).
        $unpairedDeliveries = New-Object System.Collections.Generic.List[object]
        for ($i = 0; $i -lt $deliveries.Count; $i++) {
            if ($used -notcontains $i) { $unpairedDeliveries.Add($deliveries[$i]) }
        }
        return [pscustomobject]@{
            Markers            = $markers.ToArray()
            Deliveries         = $deliveries.ToArray()
            Pairs              = $pairs.ToArray()
            UnpairedMarkers    = $unpairedMarkers
            UnpairedDeliveries = $unpairedDeliveries.ToArray()
        }
    }
}

function Get-ExFreezeSampleFacts {
    <#
      Pure: the cgroup.freeze sample wire of 16-freeze-probe.ps1 (ticket #29) -> facts. Wire:
        `MM-dd HH:mm:ss uid=<0|1|err> pid=<0|1|err>`  one sampling point (uid + pid level
                                                      `cgroup.freeze`; err = unreadable)
        `# mark <id> <stamp>`                         accepted, ignored (reserved)
      FrozenStamps carries the stamp of every frozen point (uid=1 or pid=1); the boundary
      fields are $null when there is no frozen point at all (the not-measured rule -- "no
      freeze" must not read like "frozen at 00:00:00"). EndFrozen = $true only when the last
      sample is frozen, $false when a frozen point exists and the run ended thawed, $null
      otherwise.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyCollection()][AllowEmptyString()][string[]] $Samples)

    $points = 0
    $frozen = 0
    $errPoints = 0
    $first = $null
    $last = $null
    $endFrozen = $null
    $stamps = New-Object System.Collections.Generic.List[string]
    foreach ($line in $Samples) {
        if (-not $line -or $line -match '^\s*#') { continue }
        if ($line -notmatch '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+uid=(\S+)\s+pid=(\S+)(?:\s+adj=(-?\S+|\S+))?') { continue }
        $stamp = $Matches[1]
        $uidVal = $Matches[2]
        $pidVal = $Matches[3]
        $points++
        if ($uidVal -eq 'err' -or $pidVal -eq 'err') { $errPoints++ }
        $isFrozen = ($uidVal -eq '1' -or $pidVal -eq '1')
        if ($isFrozen) {
            $frozen++
            $stamps.Add($stamp)
            if ($null -eq $first) { $first = $stamp }
            $last = $stamp
        }
        $endFrozen = $isFrozen
    }
    return [pscustomobject]@{
        Points           = $points
        FrozenPoints     = $frozen
        ErrPoints        = $errPoints
        FirstFrozenStamp = $first
        LastFrozenStamp  = $last
        EndFrozen        = if ($frozen -gt 0) { $endFrozen } else { $null }
        FrozenStamps     = $stamps.ToArray()
    }
}

function Get-ExAppStateFacts {
    <#
      Pure: one `state AppState(...) rear=...` debug line (the ACTION_STATE echo, spec 0007) ->
      the feed/charging facts a scenario asserts on. Only keys that appear EXACTLY once in the
      line are read, and a missing key stays $null (the not-measured rule -- never faked):
        IconSet / Allowlist             comma lists inside [...]
        ListenerConnected / ChannelReady / DndActive / PostureFaceDown / FeedPrivacyMode /
        ChargingEnabled                 booleans
        ActiveNotificationCount / FeedAutoDismissMs   ints (unlimited = 9223372036854775807)
        CastSource                      AUTO | MANUAL | CHARGING | null
      `lastEvent` is free text written BEFORE those keys, so anchoring on `key=` keeps the
      parse unambiguous even when the event text itself carries arrows or parentheses.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $Line)

    $facts = [pscustomobject]@{
        Found                  = $false
        IconSet                = @()
        ListenerConnected      = $null
        ActiveNotificationCount = $null
        ChannelReady           = $null
        Allowlist              = @()
        DndActive              = $null
        PostureFaceDown        = $null
        CastSource             = $null
        FeedPrivacyMode        = $null
        FeedAutoDismissMs      = $null
        ChargingEnabled        = $null
        Raw                    = $Line
    }
    if ($Line -notmatch 'state AppState\(') { return $facts }
    $facts.Found = $true
    if ($Line -match 'iconSet=\[([^\]]*)\]') { $facts.IconSet = @($Matches[1] -split ',\s*' | Where-Object { $_ }) }
    if ($Line -match 'listenerConnected=(true|false)') { $facts.ListenerConnected = ($Matches[1] -eq 'true') }
    if ($Line -match 'activeNotificationCount=(\d+)') { $facts.ActiveNotificationCount = [int]$Matches[1] }
    if ($Line -match 'channelReady=(true|false)') { $facts.ChannelReady = ($Matches[1] -eq 'true') }
    if ($Line -match 'allowlist=\[([^\]]*)\]') { $facts.Allowlist = @($Matches[1] -split ',\s*' | Where-Object { $_ }) }
    if ($Line -match 'dndActive=(true|false)') { $facts.DndActive = ($Matches[1] -eq 'true') }
    if ($Line -match 'postureFaceDown=(true|false)') { $facts.PostureFaceDown = ($Matches[1] -eq 'true') }
    if ($Line -match 'castSource=(null|[A-Z]+)') { $facts.CastSource = $Matches[1] }
    if ($Line -match 'feedPrivacyMode=(true|false)') { $facts.FeedPrivacyMode = ($Matches[1] -eq 'true') }
    if ($Line -match 'feedAutoDismissMs=(\d+)') { $facts.FeedAutoDismissMs = [long]$Matches[1] }
    if ($Line -match 'chargingEnabled=(true|false)') { $facts.ChargingEnabled = ($Matches[1] -eq 'true') }
    return $facts
}

function Get-ExUiToggleForLabel {
    <#
      Pure: the checkable row/switch NEAREST to a text label in a `uiautomator dump` (spec 0007
      settings page: the Privacy Mode row wraps its label; the charging Switch sits beside the
      `charging animation` label but is NOT the label's parent, so label-tap alone cannot reach
      it). The dump is a flat node list; switches pair with labels by vertical distance the same
      way Get-ExUiSwitchRow pairs MIUI switches with titles. Chinese labels come in as
      [regex]::Unescape('\uXXXX') escapes (ASCII-only source). Returns Found=$false rather than
      a guess when no checkable node sits within $MaxDistance of the label center.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $WindowDump,
        [Parameter(Mandatory, Position = 1)][string] $Label,
        [int] $MaxDistance = 100
    )

    $missing = [pscustomobject]@{ Found = $false; Checked = $false; X = 0; Y = 0 }
    $segments = $WindowDump -split '<node'

    $labelY = $null
    foreach ($seg in $segments) {
        if ($seg -match [regex]::Escape('text="' + $Label + '"')) {
            if ($seg -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
                $labelY = ([int]$Matches[2] + [int]$Matches[4]) / 2
            }
            break
        }
    }
    if ($null -eq $labelY) { return $missing }

    $bestDistance = 1000000
    $bestX = 0
    $bestY = 0
    $bestChecked = $false
    foreach ($seg in $segments) {
        if ($seg -notmatch 'checkable="true"') { continue }
        if ($seg -notmatch 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') { continue }
        $centerY = ([int]$Matches[2] + [int]$Matches[4]) / 2
        $distance = [Math]::Abs($centerY - $labelY)
        if ($distance -lt $bestDistance) {
            $bestDistance = $distance
            $bestX = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $bestY = $centerY
            $bestChecked = ($seg -match 'checked="true"')
        }
    }
    if ($bestDistance -gt $MaxDistance) { return $missing }
    return [pscustomobject]@{ Found = $true; Checked = $bestChecked; X = $bestX; Y = $bestY }
}

function Get-ExAppStateNow {
    <#
      Ask the running app for its state (the debug STATE action) and parse the echo line. The
      app logs `state AppState(...) rear=...` once per request; the count-before/after pair
      (Get-ExLogMatchCount + Wait-ExNewLog) keeps a slow receiver from being read as "no answer".
      Returns $null (not-measured) when the line never lands -- callers must treat $null as an
      unjudgeable fact, never as a default.
    #>
    [CmdletBinding()]
    param([int] $TimeoutSec = 8)

    $before = Get-ExLogMatchCount 'state AppState'
    Invoke-ExDebugAction -Action 'STATE'
    $hit = Wait-ExNewLog 'state AppState' -Before $before -TimeoutSec $TimeoutSec
    $lines = @($hit)
    if ($lines.Count -eq 0) { return $null }
    return Get-ExAppStateFacts -Line (Get-ExRearCueMessage -Line $lines[-1])
}

function Save-ExDisplayShot {
    <#
      Screenshot one display into the session's screenshots/ directory. The rear display cannot
      be addressed by its logical id on this build (`screencap -d 1` -> "Display Id '1' is not
      valid", 04-drive finding); screencap DOES accept the SurfaceFlinger display ids from
      `dumpsys SurfaceFlinger --display-id`, which equal the number in `dumpsys display`'s
      uniqueId ("local:<id>") -- so callers pass ($RearDisplay.UniqueId -replace '^local:','').
      Returns the local path, or $null with the failure noted (a failed shot is evidence too).
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][string] $Name,
        [string] $SfDisplayId
    )
    $session = Get-ExSessionDir
    if (-not $session) { return $null }
    if (-not $SfDisplayId) {
        Write-ExNote ('screenshot {0} SKIPPED: no SurfaceFlinger display id' -f $Name)
        return $null
    }
    $shotDir = Join-Path $session 'screenshots'
    New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
    $remote = '/sdcard/ex-shot.png'
    Invoke-Adb -Arguments @('shell', 'screencap', '-p', '-d', $SfDisplayId, $remote) -AllowFailure | Out-Null
    $local = Join-Path $shotDir $Name
    $pull = Invoke-Adb -Arguments @('pull', $remote, $local) -AllowFailure
    Invoke-Adb -Arguments @('shell', 'rm', '-f', $remote) -AllowFailure | Out-Null
    if (Test-Path -LiteralPath $local) {
        Write-ExNote ('screenshot {0}: {1} bytes (display {2})' -f $Name, (Get-Item -LiteralPath $local).Length, $SfDisplayId)
        return $local
    }
    Write-ExNote ('screenshot {0} FAILED: {1}' -f $Name, (($pull -join ' ').Trim()))
    return $null
}

function Test-ExSettingsPageOpen {
    <#
      Is the Allowlist settings page the TOP ACTIVITY of display 0? Judged from
      `dumpsys activity activities` (the decisive surface), never from `mCurrentFocus`: on this
      two-display build the first focus line belongs to the REAR display and reads null.
    #>
    [CmdletBinding()]
    $dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure) -join "`n"
    $top = Get-DisplayTopActivity -DumpsysActivities $dump -DisplayId 0
    return ($top -like '*AllowlistSettingsActivity*')
}

function Open-ExSettingsPage {
    <#
      Bring MainActivity to the front, tap the gear (content-desc only, no text) and verify the
      AllowlistSettingsActivity really came up (top activity of display 0). The activity is NOT
      exported, so `am start` is denied from shell -- the gear tap through the real UI path is
      the only adb route. Three attempts; the gear lookup uses a FRESH dump right before the
      tap (a stale dump taps last frame's coordinates and silently does nothing).
    #>
    [CmdletBinding()]
    param([int] $TimeoutSec = 12)

    $config = Get-ExConfig
    $gearDesc = [regex]::Unescape('\u6253\u5F00 Allowlist \u7BA1\u7406\u8BBE\u7F6E')   # "open Allowlist settings"
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (Test-ExSettingsPageOpen) {
            Start-Sleep -Milliseconds 800
            return $true
        }
        Set-ExScreenAwake
        Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', $config.MainActivity) -AllowFailure | Out-Null
        Start-Sleep -Seconds 2
        $dump = Get-ExWindowDump
        $node = Get-ExNodeCenter -WindowDump $dump -ContentDesc $gearDesc
        if ($null -eq $node) {
            Write-ExNote ('settings gear not found (attempt {0})' -f $attempt)
            continue
        }
        Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$node.X, [string]$node.Y) -AllowFailure | Out-Null
        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        while ((Get-Date) -lt $deadline) {
            if (Test-ExSettingsPageOpen) {
                Start-Sleep -Milliseconds 800
                return $true
            }
            Start-Sleep -Milliseconds 600
        }
        Write-ExNote ('settings page did not come up after the gear tap (attempt {0})' -f $attempt)
    }
    Write-ExNote 'AllowlistSettingsActivity never became the top activity of display 0'
    return $false
}

# ---- step ledger + verdict (21-notification-feed / 22-charging shared machinery) -----------
# Both scenario scripts judge their run as a list of named legs and render one verdict word
# from that list; the ledger lives HERE (module scope) so the two scripts carry no copy of the
# Add/Skip/overall/format code. 18/19/20 still define their own local Add-ExStep (their step
# objects carry extra Zen/Owner fields) -- a script-local function shadows this one, so those
# runs are untouched by this migration.

$script:ExSteps = New-Object System.Collections.Generic.List[object]

function Reset-ExSteps {
    <# Fresh ledger for one run (each scenario script calls it once before its first leg). #>
    $script:ExSteps = New-Object System.Collections.Generic.List[object]
}

function Add-ExStep {
    <# One judged leg: pass/fail plus the note that carries every measured fact. #>
    param([string] $Step, [bool] $Pass, [string] $Note)
    $script:ExSteps.Add([pscustomobject]@{ Step = $Step; Pass = $Pass; Skipped = $false; Note = $Note })
    Write-ExNote ('step {0}: pass={1} ({2})' -f $Step, $Pass, $Note)
}

function Skip-ExStep {
    <# A leg this ENVIRONMENT cannot judge (never counted as a pass, never silently dropped):
      it lands in the verdict as SKIPPED with the physical reason spelled out. #>
    param([string] $Step, [string] $Reason)
    $script:ExSteps.Add([pscustomobject]@{ Step = $Step; Pass = $null; Skipped = $true; Note = ('SKIPPED: ' + $Reason) })
    Write-ExNote ('step {0}: SKIPPED ({1})' -f $Step, $Reason)
}

function Get-ExSteps {
    <# The ledger as an array: callers read leg verdicts off it for their return hash. #>
    return @($script:ExSteps.ToArray())
}

function Get-ExStamp {
    <# Device-clock stamp (Get-ExLogcatTime) of the LAST logcat line matching $Pattern, or $null
      when the marker never landed (not-measured rule -- never 0). #>
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

function Get-ExStepOverall {
    <#
      The one verdict word for a run, computed from the ledger: an invalid preflight outranks
      everything, then any failed leg, then "all judged pass / SKIPPED (n)" when the environment
      could not judge some legs, then the plain all-pass form. $Prefix is the scenario word
      (FEED / CHG); $InvalidNote spells which preflight facts had to come up.
    #>
    param([bool] $RunValid, [string] $Prefix, [string] $InvalidNote)
    $steps = Get-ExSteps
    $failed = @($steps | Where-Object { ($null -ne $_.Pass) -and (-not $_.Pass) })
    $skipped = @($steps | Where-Object { $_.Skipped })
    $judged = @($steps | Where-Object { -not $_.Skipped })
    if (-not $RunValid) {
        return ('{0}-RUN-INVALID ({1})' -f $Prefix, $InvalidNote)
    }
    if ($failed.Count -gt 0) {
        return ('{0}-FAIL (failed leg(s): {1})' -f $Prefix, (($failed | ForEach-Object { $_.Step }) -join ', '))
    }
    if ($skipped.Count -gt 0) {
        return ('{0}-PASS ({1} legs judged, all pass: {2}) / SKIPPED ({3}: {4} -- environment, see scenario notes)' -f
            $Prefix, $judged.Count, (($judged | ForEach-Object { $_.Step }) -join ' / '), $skipped.Count,
            (($skipped | ForEach-Object { $_.Step }) -join ', '))
    }
    return ('{0}-PASS (all {1} legs: {2})' -f $Prefix, $steps.Count, (($judged | ForEach-Object { $_.Step }) -join ' / '))
}

function Format-ExStepVerdictLines {
    <# The per-leg verdict lines of the artifact file (`<step> : pass=<v> -- <note>`); a skipped
      leg renders SKIP so the artifact never claims a verdict the run did not make. #>
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($s in (Get-ExSteps)) {
        $verdict = if ($s.Skipped) { 'SKIP' } else { [string]$s.Pass }
        $lines.Add(('{0,-20}: pass={1,-5} -- {2}' -f $s.Step, $verdict, $s.Note))
    }
    return $lines.ToArray()
}

Export-ModuleMember -Function @(
    'Get-ExConfig', 'Get-ExRepoRoot', 'Get-ExPath', 'Get-ExSessionDir', 'Set-ExDevice', 'Get-ExDevice',
    'Resolve-ExAdb', 'Write-ExNote', 'Invoke-Adb', 'New-ExDeviceSession', 'Write-ExArtifact',
    'Get-ExLogcat', 'Get-ExRearCueSummary', 'Invoke-ExDebugAction', 'Wait-ExLog', 'Get-ExRearOwnerNow',
    'Wait-ExRearOwner', 'Clear-ExLogcat', 'Start-ExApp', 'Stop-ExApp', 'Get-ExFirstPid', 'Get-ExAppPid',
    'Get-ExLogMatchCount', 'Wait-ExNewLog', 'Wait-ExRearNotDashboard', 'Get-ExZenMode',
    'Get-ExShizukuServerPid', 'Test-ExListenerEnabled', 'Get-ExWakefulness', 'Set-ExScreenAwake',
    'Get-ExWindowDump', 'Get-ExNodeCenter', 'Confirm-ExUsbInstallDialog', 'Invoke-ExInstallApk',
    'Get-ExCurrentFocus', 'Test-ExKeyguardLocked', 'Test-ExKeyguardLockedText', 'Unlock-ExScreen',
    'Get-DisplayInfoBlocks', 'Get-RearDisplay', 'Get-DisplayActivitySection', 'Get-DisplayTopActivity',
    'Get-RearScreenOwner', 'ConvertTo-ExRearCueEvent', 'Get-RearCueEvent', 'Test-RearCueCrash', 'Get-ExChainSummary',
    'Get-OverlayProbeWindow', 'Get-OverlayWindowEvents', 'Get-ExOverlayPermission',
    'Get-ExShuidProbeFacts', 'Get-ExShuidProbeWindow', 'Get-ExShuidWindowEvents',
    'Get-ExLogcatTime', 'Get-ExSignedDeltaSeconds', 'Format-ExStatePair', 'Get-ExSecondStamp',
    'Invoke-ExKeyguardDismiss', 'Get-ExBatteryFacts', 'Get-ExPowerEstimateLines', 'Get-ExAppKeepAliveFacts', 'Get-ExTaskMoveAppFacts',
    'Get-ExDelaySeconds', 'Get-ExLockSampleFacts',
    'Get-ExRearCueMessage', 'Format-ExLockSampleLine',
    'Format-ExWakeSampleLine', 'Get-ExWakeSampleFacts', 'Get-ExWakeTickFacts',
    'Get-ExPowerGroupEvents', 'Get-ExWakePollution', 'Select-ExExternalPollution', 'Format-ExRearBehavior',
    'Get-ExSurviveFacts',
    'Get-ExTaskPlacement', 'Format-ExPlacementField', 'ConvertTo-ExServiceCallResult', 'Get-ExTaskMoveEvents',
    'Format-ExTaskMoveSampleLine', 'Get-ExTaskMoveSampleFacts',
    'Get-ExMiuiOpFacts', 'Compare-ExMiuiOpFacts', 'Get-ExUiSwitchRow', 'Get-ExAutostartPageFacts',
    'Get-ExAutostartJumpFacts', 'Get-ExGreezeEvents', 'Get-ExFreezeTimeline', 'Get-ExFreezeSampleFacts',
    'Get-ExAppStateFacts', 'Get-ExUiToggleForLabel', 'Get-ExAppStateNow', 'Save-ExDisplayShot',
    'Open-ExSettingsPage', 'Test-ExSettingsPageOpen',
    'Reset-ExSteps', 'Add-ExStep', 'Skip-ExStep', 'Get-ExSteps', 'Get-ExStamp', 'Get-ExDeltaSeconds',
    'Get-ExStepOverall', 'Format-ExStepVerdictLines'
)
