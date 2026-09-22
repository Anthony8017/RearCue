# 10-shuid-overlay.ps1 -- SH-UID probe (ticket #17): does the HyperOS rear-display window policy
# admit a TYPE_APPLICATION_OVERLAY window added by a SHELL-UID (2000) process?
#
# Run through the one-command entry point (`ex.ps1 -Task shuid-overlay`), or alone:
#   .\tools\ex\10-shuid-overlay.ps1                          # control (display 0) + rear, judged
#   .\tools\ex\10-shuid-overlay.ps1 -RawOnly                 # fixture collection round (raw only)
#   .\tools\ex\10-shuid-overlay.ps1 -WindowPackage system    # bare system context attribution
#   .\tools\ex\10-shuid-overlay.ps1 -HoldSeconds 8
#
# One command does: build the device probe dex (javac + d8, failures loudly reported) -> push it
# -> control phase (same code aimed at display 0, must succeed) -> remove phase -> rear phase ->
# judge from device facts -> archive.
#
# The probe runs `app_process` under `adb shell`, i.e. shell uid 2000 -- the same uid identity a
# Shizuku UserService runs with (the binder path itself is not exercised; the equivalence
# boundary is recorded in the findings and in scenario-notes.md).
#
# Verdict words (the table lives in docs/poc-findings.md, ticket #17 section):
#   SH-UID-WINDOW-PASS         the rear add succeeded and the window is visible on the rear
#                              display in `dumpsys window windows`
#   SH-UID-WINDOW-BLOCKED      the rear-display window policy refused it; the verdict quotes the
#                              system-side WindowManager line verbatim
#   SH-UID-WINDOW-INCONCLUSIVE the control (display 0) also failed, or there is no refusal fact
#                              to attribute anything to the rear door
#   SH-UID-RUN-INVALID         external pollution or probe self-failure (dex/process did not
#                              come up) -- reported as-is, never hard-judged
#
# The verdict is NEVER a command exit code. Device facts only: `dumpsys window windows` (probe
# window on the target display, mDisplayId / ty= / owner uid), system-side WindowManager log
# lines, and the probe process's own failure output.
#
# This run NEVER presses KEYCODE_POWER (the phone must stay unlocked for the next ticket) and it
# restores the screen stay-on setting it changes (recorded in shuid-screen-settings.txt).
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [string] $WindowPackage = 'com.rearcue.poc',
    [int] $HoldSeconds = 6,
    [int] $SettleSeconds = 2,
    [switch] $RawOnly,
    [string] $SessionName = 'shuid-overlay',
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name $SessionName -Serial $Serial | Out-Null }

$deviceDex = '/data/local/tmp/shuid-overlay.dex'
$probeTitle = 'RearCueShUidProbe'

function Get-ExShuidToolchain {
    <# Locate javac / java / android.jar / d8.jar under %LOCALAPPDATA%\RearCue-tools. #>
    $tools = Join-Path $env:LOCALAPPDATA 'RearCue-tools'
    $jdk = @(Get-ChildItem -LiteralPath $tools -Directory | Where-Object { $_.Name -like 'jdk*' } | Sort-Object Name | Select-Object -Last 1)
    $platforms = @(Get-ChildItem -LiteralPath (Join-Path $tools 'android-sdk\platforms') -Directory -ErrorAction SilentlyContinue | Sort-Object Name)
    $buildTools = @(Get-ChildItem -LiteralPath (Join-Path $tools 'android-sdk\build-tools') -Directory -ErrorAction SilentlyContinue | Sort-Object Name)
    $androidJar = $null
    foreach ($platform in $platforms) {
        $candidate = Join-Path $platform.FullName 'android.jar'
        if (Test-Path -LiteralPath $candidate) { $androidJar = $candidate }
    }
    $d8Jar = $null
    foreach ($bt in $buildTools) {
        $candidate = Join-Path $bt.FullName 'lib\d8.jar'
        if (Test-Path -LiteralPath $candidate) { $d8Jar = $candidate }
    }
    $javac = $null; $java = $null
    if ($jdk.Count -gt 0) {
        $javac = Join-Path $jdk[0].FullName 'bin\javac.exe'
        $java = Join-Path $jdk[0].FullName 'bin\java.exe'
    }
    return [pscustomobject]@{
        Javac      = $javac
        Java       = $java
        AndroidJar = $androidJar
        D8Jar      = $d8Jar
    }
}

function Build-ExShuidDex {
    <# javac -> d8 -> classes.dex. Every tool output line goes into the log; failures are loud. #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $SourceFile,
        [Parameter(Mandatory, Position = 1)][object] $Toolchain
    )
    $log = New-Object System.Collections.Generic.List[string]
    $buildDir = Join-Path (Split-Path -Parent $SourceFile) 'build'
    $classesDir = Join-Path $buildDir 'classes'
    foreach ($missing in @($Toolchain.Javac, $Toolchain.Java, $Toolchain.AndroidJar, $Toolchain.D8Jar)) {
        if (-not $missing -or -not (Test-Path -LiteralPath $missing)) {
            $log.Add(('tool missing: {0}' -f $missing))
            return [pscustomobject]@{ Success = $false; Log = $log.ToArray(); DexPath = $null }
        }
    }
    if (Test-Path -LiteralPath $buildDir) { Remove-Item -LiteralPath $buildDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

    $log.Add('$ javac -source 8 -target 8 -bootclasspath <android.jar> -d <classes> <src>')
    $javacExe = $Toolchain.Javac
    $javacOut = & $javacExe -source 8 -target 8 -bootclasspath $Toolchain.AndroidJar -d $classesDir $SourceFile 2>&1 |
        ForEach-Object { [string]$_ }
    $javacExit = $LASTEXITCODE
    foreach ($line in $javacOut) { $log.Add('  ' + $line) }
    $log.Add(('javac exit: {0}' -f $javacExit))
    $classFile = Join-Path $classesDir 'ShuidOverlayProbe.class'
    if ($javacExit -ne 0 -or -not (Test-Path -LiteralPath $classFile)) {
        return [pscustomobject]@{ Success = $false; Log = $log.ToArray(); DexPath = $null }
    }

    $log.Add('$ java -cp <d8.jar> com.android.tools.r8.D8 --lib <android.jar> --output <build> <classes>')
    $javaExe = $Toolchain.Java
    $d8Jar = $Toolchain.D8Jar
    # All compiled classes go to d8 (the probe has an anonymous inner class too); this d8 build
    # refuses a bare directory as input ("Unsupported source file type"), so list the files.
    $classFiles = @(Get-ChildItem -LiteralPath $classesDir -Filter '*.class' | Sort-Object Name | ForEach-Object { $_.FullName })
    $d8Out = & $javaExe -cp $d8Jar 'com.android.tools.r8.D8' --lib $Toolchain.AndroidJar --output $buildDir @classFiles 2>&1 |
        ForEach-Object { [string]$_ }
    $d8Exit = $LASTEXITCODE
    foreach ($line in $d8Out) { $log.Add('  ' + $line) }
    $log.Add(('d8 exit: {0}' -f $d8Exit))
    $dex = Join-Path $buildDir 'classes.dex'
    if ($d8Exit -ne 0 -or -not (Test-Path -LiteralPath $dex)) {
        return [pscustomobject]@{ Success = $false; Log = $log.ToArray(); DexPath = $null }
    }
    $log.Add(('dex: {0} ({1:N0} bytes)' -f $dex, (Get-Item -LiteralPath $dex).Length))
    return [pscustomobject]@{ Success = $true; Log = $log.ToArray(); DexPath = $dex }
}

function Get-ExStayOnSetting {
    ((Invoke-Adb -Arguments @('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in') -AllowFailure) -join '').Trim()
}

function Set-ExStayOnSetting {
    param([Parameter(Mandatory, Position = 0)][string] $Value)
    if ($Value -match '^\d+$') {
        Invoke-Adb -Arguments @('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', $Value) -AllowFailure | Out-Null
    } else {
        # 'null' = the setting was absent: deleting restores exactly that.
        Invoke-Adb -Arguments @('shell', 'settings', 'delete', 'global', 'stay_on_while_plugged_in') -AllowFailure | Out-Null
    }
}

function Get-ExShuidProbeOutput {
    param([Parameter(Mandatory, Position = 0)][string] $DeviceFile)
    @(Invoke-Adb -Arguments @('shell', 'cat', $DeviceFile) -AllowFailure | Where-Object { $null -ne $_ })
}

function Wait-ExShuidProbeDone {
    <# The probe process prints `probe: done ...` as its last line; poll its output file. #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $DeviceFile,
        [int] $TimeoutSec = 20
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $lines = Get-ExShuidProbeOutput -DeviceFile $DeviceFile
        if (($lines -join "`n") -match '(?m)^probe: done ') { return ,$lines }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Write-ExNote ('probe output {0} has no `probe: done` line within {1}s' -f $DeviceFile, $TimeoutSec)
    return ,@(Get-ExShuidProbeOutput -DeviceFile $DeviceFile)
}

function Invoke-ExShuidProbePhase {
    <#
      One probe phase: clear the log buffers -> start the probe as a background app_process
      (shell uid 2000) -> snapshot `dumpsys window windows` while the window is held -> wait for
      the probe to finish -> snapshot again -> archive raw outputs.
    #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Label,
        [Parameter(Mandatory, Position = 1)][int] $PhaseDisplayId,
        [Parameter(Mandatory, Position = 2)][string] $Mode,
        [int] $Hold = 6,
        [string] $TryRegister = 'off',
        [int] $WaitTimeoutSec = 0
    )
    if ($WaitTimeoutSec -le 0) { $WaitTimeoutSec = $Hold + 10 }
    $deviceOut = '/data/local/tmp/shuid-overlay-' + $Label + '.out'
    Invoke-Adb -Arguments @('logcat', '-b', 'all', '-c') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'rm', '-f', $deviceOut) -AllowFailure | Out-Null
    $clock = ((Invoke-Adb -Arguments @('shell', "date '+%m-%d %H:%M:%S'") -AllowFailure) -join '').Trim()

    $remote = 'nohup app_process -Djava.class.path=' + $deviceDex + ' /system/bin ShuidOverlayProbe ' +
        $PhaseDisplayId + ' ' + $Mode + ' ' + $Hold + ' ' + $WindowPackage + ' ' + $TryRegister + ' > ' + $deviceOut + ' 2>&1 &'
    Write-ExNote ('{0}: {1} @ {2}' -f $Label, $remote, $clock)
    Invoke-Adb -Arguments @('shell', $remote) -AllowFailure | Out-Null

    Start-Sleep -Seconds $SettleSeconds
    $windowHeld = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'window', 'windows') -AllowFailure)
    $logHeld = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure | Where-Object { $null -ne $_ })
    $probeLines = Wait-ExShuidProbeDone -DeviceFile $deviceOut -TimeoutSec $WaitTimeoutSec
    Start-Sleep -Milliseconds 800
    $windowAfter = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'window', 'windows') -AllowFailure)
    $logAfter = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure | Where-Object { $null -ne $_ })

    $systemFilter = 'WindowManager|system_window|APPLICATION_OVERLAY|RearCueShUidProbe|SYSTEM_ALERT_WINDOW|Permission Denial|SecurityException|Not allow|BadToken|am_kill|Killing|libprocessgroup'
    $systemLines = @(($logHeld + $logAfter) | Where-Object { $_ -match $systemFilter } | Select-Object -Unique)

    Write-ExArtifact -Name ('shuid-probe-' + $Label + '.txt') -Lines $probeLines | Out-Null
    Write-ExArtifact -Name ('dumpsys-window-' + $Label + '-held.txt') -Lines $windowHeld | Out-Null
    Write-ExArtifact -Name ('dumpsys-window-' + $Label + '-after.txt') -Lines $windowAfter | Out-Null
    Write-ExArtifact -Name ('logcat-shuid-' + $Label + '-system.txt') -Lines $systemLines | Out-Null
    Write-ExArtifact -Name ('logcat-shuid-' + $Label + '-all.txt') -Lines ($logHeld + $logAfter) | Out-Null

    return [pscustomobject]@{
        Label       = $Label
        DisplayId   = $PhaseDisplayId
        Mode        = $Mode
        DeviceTime  = $clock
        Remote      = $remote
        ProbeLines  = $probeLines
        WindowHeld  = $windowHeld
        WindowAfter = $windowAfter
        SystemLines = $systemLines
        AllLog      = @($logHeld + $logAfter)
    }
}
# ---- 0. preflight: device reachable (3 tries), rear display known at runtime, no locking -----
$online = $false
foreach ($try in 1..3) {
    $state = ((Invoke-Adb -Arguments @('get-state') -AllowFailure) -join '').Trim()
    if ($state -eq 'device') { $online = $true; break }
    Write-ExNote ('device not online (get-state: {0}), retry {1}/3' -f $state, $try)
    Start-Sleep -Seconds 2
}
if (-not $online) {
    Write-ExArtifact -Name 'shuid-overlay.txt' -Lines @('# shuid-overlay (ticket #17)',
        'sh-uid : SH-UID-RUN-INVALID (device offline: adb get-state failed three times)') | Out-Null
    return @{ ShUid = 'SH-UID-RUN-INVALID (device offline)' }
}

$displayDump = Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure
$rear = Get-RearDisplay -DumpsysDisplay ($displayDump -join "`n")
if (-not $rear) {
    Write-ExNote 'ERROR: no rear display in `dumpsys display` (no non-default INTERNAL display with'
    Write-ExNote '       PRESENTATION + OWN_DISPLAY_GROUP): the rear question cannot be asked here.'
    Write-ExArtifact -Name 'shuid-overlay.txt' -Lines @('# shuid-overlay (ticket #17)',
        'sh-uid : SH-UID-RUN-INVALID (no rear display found at runtime)') | Out-Null
    return @{ ShUid = 'SH-UID-RUN-INVALID (no rear display)' }
}
$rearId = $rear.DisplayId
if (Test-ExKeyguardLocked) {
    Write-ExNote 'WARNING: the phone is keyguard-locked. Ticket #17 expects the unlocked state'
    Write-ExNote '         (same precondition as the ticket #10 E9 run) -- the verdict will still be'
    Write-ExNote '         device facts, but comparability with E9 is weakened. This run never locks.'
}
Write-ExNote ('rear display: id={0} state={1}/{2}' -f $rearId, $rear.State, $rear.CommittedState)

# ---- 1. build the probe dex (javac + d8) and push it ------------------------------------------
$sourceFile = Join-Path $PSScriptRoot 'device\shuid-overlay\ShuidOverlayProbe.java'
$toolchain = Get-ExShuidToolchain
$build = Build-ExShuidDex -SourceFile $sourceFile -Toolchain $toolchain
Write-ExArtifact -Name 'shuid-build.txt' -Lines (@('# shuid build (javac + d8)', ('source : {0}' -f $sourceFile),
        ('javac  : {0}' -f $toolchain.Javac), ('android.jar : {0}' -f $toolchain.AndroidJar),
        ('d8.jar : {0}' -f $toolchain.D8Jar), '') + $build.Log) | Out-Null
if (-not $build.Success) {
    Write-ExNote 'ERROR: building the probe dex failed (see shuid-build.txt). The probe cannot run:'
    Write-ExNote '       stopping here (SH-UID-RUN-INVALID), no device verdict is claimed.'
    Write-ExArtifact -Name 'shuid-overlay.txt' -Lines @('# shuid-overlay (ticket #17)',
        ('sh-uid : SH-UID-RUN-INVALID (probe dex build failed; shuid-build.txt has javac/d8 output)') ) | Out-Null
    return @{ ShUid = 'SH-UID-RUN-INVALID (dex build failed)' }
}
$push = @(Invoke-Adb -Arguments @('push', $build.DexPath, $deviceDex))
Write-ExNote ('dex pushed: {0}' -f (($push | Select-Object -Last 1) -join ''))

# ---- 2. screen stay-on while plugged in (recorded + restored at the end) ----------------------
$stayBefore = Get-ExStayOnSetting
$wakeBefore = Get-ExWakefulness
$screenLines = New-Object System.Collections.Generic.List[string]
$screenLines.Add('# screen settings touched by this run (restored at the end)')
$screenLines.Add(('stay_on_while_plugged_in before : {0}' -f $stayBefore))
$screenLines.Add(('wakefulness before              : {0}' -f $wakeBefore))
Invoke-Adb -Arguments @('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '7') -AllowFailure | Out-Null
$screenLines.Add('stay_on_while_plugged_in set to : 7 (stay on while any charger; keeps the run from auto-locking)')
Set-ExScreenAwake
$screenLines.Add(('wakefulness after Set-ExScreenAwake : {0}' -f (Get-ExWakefulness)))

# ---- 3. phases: control (display 0) -> remove cycle -> rear (the question) --------------------
# Hygiene: a probe process of an earlier run must not linger into this run's phases (the probe
# VM can survive its own `done` line on this build). Kill only probes of THIS dex, by cmdline.
$strays = @(Invoke-Adb -Arguments @('shell', 'ps', '-A', '-o', 'PID,args') -AllowFailure |
    Where-Object { $_ -match 'shuid-overlay\.dex' })
foreach ($stray in $strays) {
    $strayPid = (($stray -split '\s+')[0]).Trim()
    if ($strayPid -match '^\d+$') {
        Write-ExNote ('killing stray probe process of an earlier run: {0}' -f $stray.Trim())
        Invoke-Adb -Arguments @('shell', 'kill', '-9', $strayPid) -AllowFailure | Out-Null
    }
}

try {
    Write-ExNote ('protocol: control add on display 0 -> one-shot add/remove -> rear add on display {0} (window attribution pkg={1})' -f $rearId, $WindowPackage)
    $control = Invoke-ExShuidProbePhase -Label 'control' -PhaseDisplayId 0 -Mode 'add' -Hold $HoldSeconds
    $remove = Invoke-ExShuidProbePhase -Label 'remove' -PhaseDisplayId 0 -Mode 'remove' -Hold 1
    $rearPhase = Invoke-ExShuidProbePhase -Label 'rear' -PhaseDisplayId $rearId -Mode 'add' -Hold $HoldSeconds
    # LAST, and only here: the app-attach registration probe (tryRegister=on). It documents WHY
    # the adds above cannot be admitted (this build KILLS an unregistered shell process that
    # calls attachApplication) and must never run before them -- a killed probe can attempt
    # nothing. Facts only: its output stops at the register attempt when the process dies.
    $registerCheck = Invoke-ExShuidProbePhase -Label 'registercheck' -PhaseDisplayId 0 -Mode 'add' -Hold 1 -TryRegister 'on' -WaitTimeoutSec 6
} finally {
    Set-ExStayOnSetting -Value $stayBefore
    $screenLines.Add(('stay_on_while_plugged_in restored : {0}' -f $stayBefore))
    $screenLines.Add(('wakefulness end                  : {0}' -f (Get-ExWakefulness)))
    Write-ExArtifact -Name 'shuid-screen-settings.txt' -Lines $screenLines.ToArray() | Out-Null
}

if ($RawOnly) {
    Write-ExNote 'raw collection round: outputs archived, no verdict claimed (fixture round)'
    Write-ExArtifact -Name 'shuid-overlay.txt' -Lines @('# shuid-overlay (ticket #17)',
        'sh-uid : (raw collection round: no verdict; this session is the fixture round)') | Out-Null
    Write-ExArtifact -Name 'scenario-notes.md' -Lines @(
        '## scenario notes (SH-UID overlay probe, ticket #17)',
        '',
        '- **Fixture collection round** (`-RawOnly`): raw probe output / `dumpsys window windows` /',
        '  logcat archived verbatim for the parsing seam fixtures; no verdict is claimed here.'
    ) | Out-Null
    return @{ ShUid = 'RAW-COLLECT (no verdict)'; Session = (Get-ExSessionDir) }
}

# ---- 4. verdicts from device facts only -------------------------------------------------------
$controlFacts = Get-ExShuidProbeFacts -ProbeLines $control.ProbeLines
$removeFacts = Get-ExShuidProbeFacts -ProbeLines $remove.ProbeLines
$rearFacts = Get-ExShuidProbeFacts -ProbeLines $rearPhase.ProbeLines
$registerFacts = Get-ExShuidProbeFacts -ProbeLines $registerCheck.ProbeLines
$controlWindow = Get-ExShuidProbeWindow -DumpsysWindow ($control.WindowHeld -join "`n") -Title $probeTitle
$controlAfter = Get-ExShuidProbeWindow -DumpsysWindow ($control.WindowAfter -join "`n") -Title $probeTitle
$removeAfter = Get-ExShuidProbeWindow -DumpsysWindow ($remove.WindowAfter -join "`n") -Title $probeTitle
$rearWindow = Get-ExShuidProbeWindow -DumpsysWindow ($rearPhase.WindowHeld -join "`n") -Title $probeTitle
$controlEvents = Get-ExShuidWindowEvents -Logcat $control.AllLog -Title $probeTitle
$rearEvents = Get-ExShuidWindowEvents -Logcat $rearPhase.AllLog -Title $probeTitle
# NO @() wrap here: Get-ExWakePollution returns `,$arr`, and @(...) around that turns an EMPTY
# result into a one-element array holding an empty array (the `,$arr`/`@()` trap from ticket
# #16) -- which then reads as "one pollution hit" that does not exist.
$pollution = Get-ExWakePollution -Logcat ($control.AllLog + $remove.AllLog + $rearPhase.AllLog + $registerCheck.AllLog)

$controlOnTarget = ($controlFacts.AddOk -and $controlWindow.Found -and ($controlWindow.DisplayId -eq 0))
$rearOnTarget = ($rearFacts.AddOk -and $rearWindow.Found -and ($null -ne $rearWindow.DisplayId) -and ($rearWindow.DisplayId -eq $rearId))
$probeSelfFail = ($controlFacts.SelfFail -or $removeFacts.SelfFail -or $rearFacts.SelfFail -or
    (-not $controlFacts.Done) -or (-not $rearFacts.Done))
$removeConfirmed = ($removeFacts.RemoveOk -and (-not $removeAfter.Found))

$denyLine = ''
if ($rearEvents.RearPolicyLine) { $denyLine = $rearEvents.RearPolicyLine.Trim() }

if ($pollution.Count -gt 0) {
    $shUid = ('SH-UID-RUN-INVALID (external pollution during the run: {0} fingerprint/power-button hit(s) this run never pressed KEYCODE_POWER -- samples void, see erratum.md)' -f $pollution.Count)
} elseif ($probeSelfFail) {
    $shUid = ('SH-UID-RUN-INVALID (probe self-failure: control self-fail={0} rear self-fail={1} control done={2} rear done={3}; probe output archived verbatim, no window verdict is claimed)' -f
        $controlFacts.SelfFail, $rearFacts.SelfFail, $controlFacts.Done, $rearFacts.Done)
} elseif (-not $controlOnTarget) {
    $shUid = ('SH-UID-WINDOW-INCONCLUSIVE (the display-0 control did not land either: probe add ok={0}, probe window in dumpsys={1} display={2}; nothing can be attributed to the rear door)' -f
        $controlFacts.AddOk, $controlWindow.Found, $controlWindow.DisplayId)
} elseif ($rearOnTarget) {
    $shUid = ('SH-UID-WINDOW-PASS (shell-uid probe window really on the rear display {0}: {1} / {2})' -f
        $rearId, $rearWindow.Header.Trim(), $rearWindow.DisplayLine.Trim())
} elseif ($denyLine -ne '') {
    $shUid = ('SH-UID-WINDOW-BLOCKED (rear-display window policy refused the shell-uid window; system-side line verbatim: "{0}")' -f $denyLine)
} elseif ($rearFacts.AddFailed) {
    $systemNote = if ($rearEvents.DenyLines.Count -gt 0) { ('system-side denial line: "{0}"' -f (($rearEvents.DenyLines | Select-Object -First 1).Trim())) } else { 'system-side WindowManager line: none captured (evidence gap, stated as fact)' }
    $shUid = ('SH-UID-WINDOW-BLOCKED (the add failed in the probe on the rear display while the display-0 control passed; probe failure reason: "{0}"; {1})' -f
        $rearFacts.AddFailureReason, $systemNote)
} else {
    $shUid = ('SH-UID-WINDOW-INCONCLUSIVE (control passed but the rear add produced neither a window on display {0} nor any refusal fact: nothing to attribute to the rear door; probe add ok={1}, dumpsys window found={2})' -f
        $rearId, $rearFacts.AddOk, $rearWindow.Found)
}

$controlLine = ('{0} (probe add ok={1} dumpsys found={2} displayId={3} ownerUid={4})' -f
    $(if ($controlOnTarget) { 'control-ok' } else { 'control-failed' }),
    $controlFacts.AddOk, $controlWindow.Found, $controlWindow.DisplayId, $controlWindow.OwnerUid)
$removeLine = ('{0} (probe remove ok={1} window absent after remove={2})' -f
    $(if ($removeConfirmed) { 'remove-confirmed' } else { 'remove-unconfirmed' }),
    $removeFacts.RemoveOk, (-not $removeAfter.Found))
$rearLine = ('{0} (probe add ok={1} add failed={2} dumpsys found={3} displayId={4} ownerUid={5})' -f
    $(if ($rearOnTarget) { 'rear-window-on-target' } elseif ($denyLine -ne '') { 'rear-refused' } else { 'rear-absent' }),
    $rearFacts.AddOk, $rearFacts.AddFailed, $rearWindow.Found, $rearWindow.DisplayId, $rearWindow.OwnerUid)
$registerLine = ('attempted={0} register-result={1} probe-done={2}' -f
    $registerFacts.RegisterAttempted,
    $(if ($registerFacts.RegisterResult) { $registerFacts.RegisterResult } else { 'none' }),
    $registerFacts.Done)

# ---- 5. artifacts -----------------------------------------------------------------------------
$out = New-Object System.Collections.Generic.List[string]
$out.Add('# shuid-overlay (ticket #17)')
$out.Add(('probe                    : ShuidOverlayProbe <displayId> <add|remove> [hold] [pkg] via app_process (shell uid {0})' -f $controlFacts.Uid))
$out.Add(('window attribution       : pkg={0} (context source={1}, opPackage={2})' -f $WindowPackage, $controlFacts.ContextSource, $controlFacts.ContextOpPackage))
$out.Add(('rear display             : id={0} state={1}/{2}' -f $rearId, $rear.State, $rear.CommittedState))
$out.Add(('control target display   : 0'))
$out.Add(('hold / settle            : {0}s / {1}s per add phase' -f $HoldSeconds, $SettleSeconds))
$out.Add('')
$out.Add('# verdicts')
$out.Add(('control-display0   : {0}' -f $controlLine))
$out.Add(('probe-remove       : {0}' -f $removeLine))
$out.Add(('rear-display       : {0}' -f $rearLine))
$out.Add(('rear-policy-deny   : {0}' -f $(if ($denyLine -ne '') { ('yes; verbatim: "{0}"' -f $denyLine) } else { 'no rear-window-policy line captured' })))
$out.Add(('system-deny-lines  : {0} (control) / {1} (rear)' -f $controlEvents.DenyLines.Count, $rearEvents.DenyLines.Count))
$out.Add(('register-check     : {0}' -f $registerLine))
$out.Add(('pollution          : {0}' -f $(if ($pollution.Count -eq 0) { 'none detected' } else { '{0} external hit(s) -- see erratum.md' -f $pollution.Count })))
$out.Add(('sh-uid             : {0}' -f $shUid))
$out.Add('')
$out.Add('# probe window facts (dumpsys window windows, while held)')
$out.Add(('  control : found={0} displayId={1} ownerUid={2} ownerPid={3} ty={4} package={5} appop={6}' -f
        $controlWindow.Found, $controlWindow.DisplayId, $controlWindow.OwnerUid, $controlWindow.OwnerPid, $controlWindow.Type, $controlWindow.Package, $controlWindow.Appop))
if ($controlWindow.Found) {
    $out.Add(('            {0}' -f $controlWindow.Header.Trim()))
    $out.Add(('            {0}' -f $controlWindow.DisplayLine.Trim()))
    $out.Add(('            {0}' -f $controlWindow.OwnerLine.Trim()))
}
$out.Add(('  rear    : found={0} displayId={1} ownerUid={2} ownerPid={3} ty={4} package={5} appop={6}' -f
        $rearWindow.Found, $rearWindow.DisplayId, $rearWindow.OwnerUid, $rearWindow.OwnerPid, $rearWindow.Type, $rearWindow.Package, $rearWindow.Appop))
if ($rearWindow.Found) {
    $out.Add(('            {0}' -f $rearWindow.Header.Trim()))
    $out.Add(('            {0}' -f $rearWindow.DisplayLine.Trim()))
    $out.Add(('            {0}' -f $rearWindow.OwnerLine.Trim()))
}
$out.Add('')
$out.Add('# probe output (control, display 0)')
foreach ($line in $control.ProbeLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# probe output (remove cycle, display 0)')
foreach ($line in $remove.ProbeLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# probe output (rear)')
foreach ($line in $rearPhase.ProbeLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# probe output (register check: app attach handshake as the LAST step)')
foreach ($line in $registerCheck.ProbeLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# system-side window lines (control)')
foreach ($line in $control.SystemLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# system-side window lines (rear)')
foreach ($line in $rearPhase.SystemLines) { $out.Add('  ' + $line) }
$out.Add('')
$out.Add('# system-side window lines (register check)')
foreach ($line in $registerCheck.SystemLines) { $out.Add('  ' + $line) }
Write-ExArtifact -Name 'shuid-overlay.txt' -Lines $out.ToArray() | Out-Null

foreach ($line in ($out | Where-Object { $_ -match '^(control-display0|probe-remove|rear-display|rear-policy-deny|system-deny-lines|pollution|sh-uid) ' })) {
    Write-ExNote $line
}

# ---- 6. erratum (external pollution never belongs in the verdict) -----------------------------
if ($pollution.Count -gt 0) {
    $erratum = New-Object System.Collections.Generic.List[string]
    $erratum.Add('# erratum -- external pollution (ticket #17)')
    $erratum.Add('')
    $erratum.Add('The run window below was disturbed from outside the script (fingerprint wake / a')
    $erratum.Add('human pressing the power button or touching the phone). This run never presses')
    $erratum.Add('KEYCODE_POWER, so every power-button transition is external. The round is void')
    $erratum.Add('(SH-UID-RUN-INVALID); raw output is left untouched and this note marks it.')
    $erratum.Add('')
    foreach ($hit in $pollution) { $erratum.Add(('- [{0}] {1}' -f $hit.Class, $hit.Raw.Trim())) }
    Write-ExArtifact -Name 'erratum.md' -Lines $erratum.ToArray() | Out-Null
}

# ---- 7. scenario notes (summary.md embeds these) ----------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (SH-UID overlay probe, ticket #17)')
$notes.Add('')
$notes.Add('- **Equivalence boundary ("via Shizuku UserService")**: the probe process is `adb shell')
$notes.Add('  app_process ... ShuidOverlayProbe`, i.e. shell uid 2000 -- the same UID IDENTITY a')
$notes.Add('  Shizuku UserService runs with. The binder path (app -> Shizuku -> UserService) is NOT')
$notes.Add('  exercised (that would need an APK build + install, and the MIUI USB install dialog is a')
$notes.Add('  manual stop condition). What the verdict answers is "does the rear-display window')
$notes.Add('  policy admit a TYPE_APPLICATION_OVERLAY window added by a uid-2000 process".')
$notes.Add('- **Window attribution**: the window context comes from createPackageContext of the')
$notes.Add(('  requested package ({0}), so the window carries the SAME' -f $WindowPackage))
$notes.Add('  package attribution as the ticket #10 E9 probe (`package=com.rearcue.poc` in dumpsys)')
$notes.Add('  and the ONLY changed variable versus E9 is the calling uid (2000 vs the app uid).')
$notes.Add('  `-WindowPackage system` switches to the bare system context (package "android"); use it')
$notes.Add('  when the package attribution itself needs to be ruled in or out.')
$notes.Add('- **`remove` mode semantics**: a window can only be removed by the process that added it')
$notes.Add('  (the WindowManager view lives in that process), so the ticket''s remove step is a')
$notes.Add('  one-shot add -> remove cycle inside one probe process; the `add` phases remove their own')
$notes.Add('  window after the hold and the after-snapshots prove it left `dumpsys window windows`.')
$notes.Add('- **Build is part of the command**: javac (JDK17 under %LOCALAPPDATA%\RearCue-tools) +')
$notes.Add('  d8 (build-tools) run every invocation, output archived in shuid-build.txt; a build')
$notes.Add('  failure stops the run with SH-UID-RUN-INVALID (no device verdict is claimed).')
$notes.Add('- **Screen handling**: this run NEVER presses KEYCODE_POWER (the phone stays unlocked);')
$notes.Add('  `stay_on_while_plugged_in` is set to 7 for the run and restored to its original value')
$notes.Add('  after it (shuid-screen-settings.txt records both values).')
$notes.Add('- **Why window adds from this process cannot be admitted on this build** (device facts,')
$notes.Add('  see the artifacts): every WindowManager.addView from the uid-2000 app_process probe')
$notes.Add('  is refused with `Unknown pid=<pid> uid=2000` (system side: `WindowManager: Window')
$notes.Add('  Manager Crash java.lang.IllegalStateException: Unknown pid=...` and')
$notes.Add('  `attachWindowContextToDisplayArea: calling from non-existing process pid=... uid=2000`),')
$notes.Add('  on display 0 and on the rear display alike. The app attach handshake that would')
$notes.Add('  register the process (ActivityThread.attach(false)) gets the probe KILLED instead --')
$notes.Add('  the register-check phase documents exactly that and is the LAST phase for that reason.')
$notes.Add(('**Observation window is short by design** (-HoldSeconds {0}s per add phase, -SettleSeconds {1}s).' -f $HoldSeconds, $SettleSeconds))
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

Write-ExNote 'the phone is left UNLOCKED and untouched (no KEYCODE_POWER in this run)'
return @{ ShUid = $shUid; ControlOk = $controlOnTarget; RearOnTarget = $rearOnTarget; Session = (Get-ExSessionDir) }
