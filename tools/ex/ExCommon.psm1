# ExCommon.psm1 -- shared helpers for the RearCue PC experiment script set (ticket #7).
#
# Two halves, deliberately separated:
#   * PURE parsers (Get-DisplayInfoBlocks / Get-RearDisplay / Get-DisplayTopActivity /
#     Get-RearScreenOwner / Get-RearCueEvent / Test-RearCueCrash / Get-ExChainSummary):
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
    return @($output)
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
    <# logcat -> parsed events -> the facts a scenario asserts on (one call instead of three lines). #>
    param([Parameter(Position = 0)][AllowEmptyCollection()][string[]] $Logcat = @())
    $lines = if ($Logcat.Count -gt 0) { $Logcat } else { @(Get-ExLogcat) }
    return Get-ExChainSummary -Events (Get-RearCueEvent -Logcat $lines)
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

    $hit = @(Wait-ExLog -Pattern 'listener connected active=' -TimeoutSec $TimeoutSec)
    if ($hit.Count -gt 0) { return $true }
    if ($NoRebind) {
        Write-ExNote ('listener did not connect within {0}s (MIUI autostart blocked the rebind)' -f $TimeoutSec)
        return $false
    }

    Write-ExNote 'listener did not connect: forcing a rebind (MIUI blocks the first bind after install)'
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'disallow_listener', $config.ListenerComponent) -AllowFailure | Out-Null
    Start-Sleep -Seconds 3
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'allow_listener', $config.ListenerComponent) -AllowFailure | Out-Null
    $hit = @(Wait-ExLog -Pattern 'listener connected active=' -TimeoutSec $TimeoutSec)
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
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory, Position = 0)][AllowEmptyString()][string] $DumpsysWindow)

    if ($DumpsysWindow -match 'mDreamingLockscreen=true') { return $true }
    if ($DumpsysWindow -match 'isKeyguardShowing=true') { return $true }
    return $false
}

function Test-ExKeyguardLocked {
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
    Invoke-Adb -Arguments @('shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
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
    <# Focused window as `package/Activity` -- a cheap "is a system dialog on screen" probe (~0.3s). #>
    $out = Invoke-Adb -Arguments @('shell', 'dumpsys', 'window') -AllowFailure
    $line = ($out | Where-Object { $_ -match 'mCurrentFocus=' } | Select-Object -First 1) -join ''
    if ($line -match 'mCurrentFocus=Window\{[^}]*\s([^\s}]+)\}') { return $Matches[1] }
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
        [string] $ResourceId
    )
    if (-not $Text -and -not $ResourceId) { throw 'Get-ExNodeCenter needs -Text or -ResourceId' }
    foreach ($segment in ($WindowDump -split '<node')) {
        if ($Text -and $segment -notmatch [regex]::Escape('text="' + $Text + '"')) { continue }
        if ($ResourceId -and $segment -notmatch [regex]::Escape('resource-id="' + $ResourceId + '"')) { continue }
        if ($segment -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
            return [pscustomobject]@{
                Text = $Text
                Id   = $ResourceId
                X    = [int](($x1 + $x2) / 2)
                Y    = [int](($y1 + $y2) / 2)
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
    param([Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][string[]] $Logcat)

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
    param([Parameter(Mandatory, ValueFromPipeline, Position = 0)][AllowEmptyCollection()][string[]] $Logcat)

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

Export-ModuleMember -Function @(
    'Get-ExConfig', 'Get-ExRepoRoot', 'Get-ExPath', 'Get-ExSessionDir', 'Set-ExDevice', 'Get-ExDevice',
    'Resolve-ExAdb', 'Write-ExNote', 'Invoke-Adb', 'New-ExDeviceSession', 'Write-ExArtifact',
    'Get-ExLogcat', 'Get-ExRearCueSummary', 'Invoke-ExDebugAction', 'Wait-ExLog', 'Get-ExRearOwnerNow',
    'Wait-ExRearOwner', 'Clear-ExLogcat', 'Start-ExApp', 'Stop-ExApp', 'Get-ExFirstPid', 'Get-ExAppPid',
    'Get-ExShizukuServerPid', 'Test-ExListenerEnabled', 'Get-ExWakefulness', 'Set-ExScreenAwake',
    'Get-ExWindowDump', 'Get-ExNodeCenter', 'Confirm-ExUsbInstallDialog', 'Invoke-ExInstallApk',
    'Get-ExCurrentFocus', 'Test-ExKeyguardLocked', 'Test-ExKeyguardLockedText', 'Unlock-ExScreen',
    'Get-DisplayInfoBlocks', 'Get-RearDisplay', 'Get-DisplayActivitySection', 'Get-DisplayTopActivity',
    'Get-RearScreenOwner', 'ConvertTo-ExRearCueEvent', 'Get-RearCueEvent', 'Test-RearCueCrash', 'Get-ExChainSummary',
    'Get-OverlayProbeWindow', 'Get-OverlayWindowEvents', 'Get-ExOverlayPermission'
)
