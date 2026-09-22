# 05-collect.ps1 -- pull every observation into the session directory and write summary.md.
#
# Run through the one-command entry point (`ex.ps1`), or alone:
#   .\tools\ex\05-collect.ps1
#
# Collected (all inside docs/poc-logs/<stamp>-<name>/):
#   logcat-rearcue.txt      full `adb logcat -d -s RearCue`
#   logcat-system-rear.txt  the system-side rear-display / BAL / freeze lines (ActivityStarterImpl,
#                           Background activity launch blocked, GreezeManager, subscreencenter)
#   dumpsys-display.txt     `dumpsys display` (rear display flags, size, state)
#   dumpsys-activities.txt  `dumpsys activity activities` (who owns display #1)
#   state.txt               pids, listener grant, active notification count
#   app-logs/               the app's own Shizuku transcript (adb pull of its external files dir)
#   screenshots/            main-screen capture; the rear screen capture is attempted and its
#                           failure recorded (HyperOS 3.0.304+ cannot `screencap -d 1`)
#   summary.md              parsed facts + the verdicts found in the step artifacts

[CmdletBinding()]
param([string] $Serial)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'collect' -Serial $Serial | Out-Null }
$session = Get-ExSessionDir

Write-ExNote 'collecting evidence'
Write-ExArtifact -Name 'logcat-rearcue.txt' -Lines @(Get-ExLogcat) | Out-Null

$systemLines = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure |
    Where-Object { $_ -match 'ActivityStarterImpl|rear display|Background activity launch blocked|GreezeManager|subscreencenter|SubScreen|PowerGroup|going to sleep|wm_finish_activity|wm_on_destroy|Show when locked' } |
    Select-Object -Last 600)
Write-ExArtifact -Name 'logcat-system-rear.txt' -Lines $systemLines | Out-Null

$displayDump = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure)
Write-ExArtifact -Name 'dumpsys-display.txt' -Lines $displayDump | Out-Null

$activityDump = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure)
Write-ExArtifact -Name 'dumpsys-activities.txt' -Lines $activityDump | Out-Null

# E9 (ticket #10): the window dump is the decisive overlay fact (which display hosts the probe).
$windowDump = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'window', 'windows') -AllowFailure)
Write-ExArtifact -Name 'dumpsys-window.txt' -Lines $windowDump | Out-Null

# Current app state straight from the debug hook (goes to logcat as `state AppState(...)`).
Invoke-ExDebugAction -Action 'STATE'
Start-Sleep -Seconds 1
$stateLines = @(Get-ExLogcat | Where-Object { $_ -match 'state AppState|debug cancel|refresh ' })
$notifications = @(Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'list -n') -AllowFailure)
$state = @(
    '# state',
    ('app pid            : {0}' -f (Get-ExAppPid)),
    ('shizuku_server pid : {0}' -f (Get-ExShizukuServerPid)),
    ('listener enabled   : {0}' -f (Test-ExListenerEnabled)),
    ('rear display owner : {0}' -f (Get-ExRearOwnerNow)),
    '',
    ('active notifications ({0})' -f $notifications.Count)
) + ($notifications | ForEach-Object { '  ' + $_ }) + @('', '# app state lines') + ($stateLines | ForEach-Object { '  ' + $_ })
Write-ExArtifact -Name 'state.txt' -Lines $state | Out-Null

# Screenshots: main screen works, rear screen is expected to fail on this build (recorded, not hidden).
$shotDir = Join-Path $session 'screenshots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
$screenshotNotes = New-Object System.Collections.Generic.List[string]

Invoke-Adb -Arguments @('shell', 'screencap', '-p', '/sdcard/ex-main.png') -AllowFailure | Out-Null
$pullMain = Invoke-Adb -Arguments @('pull', '/sdcard/ex-main.png', (Join-Path $shotDir 'main.png')) -AllowFailure
$screenshotNotes.Add('main screen : ' + (($pullMain | Select-Object -Last 1) -join ''))

$rearAttempt = Invoke-Adb -Arguments @('shell', 'screencap', '-p', '-d', '1', '/sdcard/ex-rear.png') -AllowFailure
$screenshotNotes.Add('rear screen : ' + (($rearAttempt -join ' | ').Trim()))
if (($rearAttempt -join '') -notmatch 'is not valid|Error|Exception') {
    Invoke-Adb -Arguments @('pull', '/sdcard/ex-rear.png', (Join-Path $shotDir 'rear.png')) -AllowFailure | Out-Null
}
Invoke-Adb -Arguments @('shell', 'rm', '-f', '/sdcard/ex-main.png', '/sdcard/ex-rear.png') -AllowFailure | Out-Null
Write-ExArtifact -Name 'screenshots/notes.txt' -Lines $screenshotNotes.ToArray() | Out-Null

# The app's own Shizuku transcript (written to its external files dir, see ShellTranscript).
# Best effort: the directory only exists once the Shizuku fallback actually ran a command.
$appLogDir = Join-Path $session 'app-logs'
$pull = Invoke-Adb -Arguments @('pull', '/sdcard/Android/data/com.rearcue.poc/files/poc-logs',
    (Join-Path $appLogDir 'poc-logs')) -AllowFailure
$pullTail = ($pull | Select-Object -Last 1) -join ''
if ($pullTail -match 'No such file') {
    Write-ExNote 'app transcript: none on the device (the Shizuku fallback never ran a command)'
} else {
    New-Item -ItemType Directory -Force -Path $appLogDir | Out-Null
    Write-ExNote ('app transcript: ' + $pullTail)
}

# ---- summary.md -------------------------------------------------------------

$lines = @(Get-Content -LiteralPath (Join-Path $session 'logcat-rearcue.txt') -Encoding UTF8)
$summary = Get-ExRearCueSummary -Logcat $lines
$crashes = Test-RearCueCrash -Logcat $lines
$rear = Get-RearDisplay -DumpsysDisplay ($displayDump -join "`n")
$owner = Get-RearScreenOwner -DumpsysActivities ($activityDump -join "`n") -DisplayId 1

$verdictFiles = Get-ChildItem -LiteralPath $session -Filter '*.txt' |
    Where-Object { $_.Name -match '^(e1-drive|e7-drive|e3-lock|03-shizuku|e9-overlay)\.txt$' }

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# RearCue PC experiment summary')
$out.Add('')
$out.Add(('session : {0}' -f $session))
$out.Add(('device  : {0} / {1}' -f
        ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.model') -AllowFailure) -join '').Trim(),
        ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.display.id') -AllowFailure) -join '').Trim()))
$out.Add('')
$out.Add('## rear display')
if ($rear) {
    $out.Add(('- displayId {0}, {1}x{2}, density {3}, state {4} (committed {5})' -f $rear.DisplayId, $rear.Width,
            $rear.Height, $rear.Density, $rear.State, $rear.CommittedState))
    $out.Add(('- flags: {0}' -f ($rear.Flags -join ', ')))
} else {
    $out.Add('- NOT FOUND in `dumpsys display` (no non-default INTERNAL display with PRESENTATION + OWN_DISPLAY_GROUP)')
}
$out.Add(('- owner of display #1 now: **{0}**' -f $owner))
$probe = Get-OverlayProbeWindow -DumpsysWindow ($windowDump -join "`n")
if ($probe.Found) {
    $out.Add(('- overlay probe window: found on display {0} (package {1}, appop {2})' -f $probe.DisplayId, $probe.Package, $probe.Appop))
} else {
    $out.Add('- overlay probe window: not found in `dumpsys window windows`')
}
$out.Add('')
$out.Add('## chain facts (parsed from logcat)')
$out.Add('')
$out.Add('| fact | value |')
$out.Add('|---|---|')
# Keep the interesting packages, not all ~30 system ones: the raw logcat sits next to this file.
$testPackages = @('com.rearcue.poc', 'com.android.shell', 'com.tencent.mm', 'com.tencent.mobileqq')
$postedAllowlist = @(@($summary.PostedPackages) | Where-Object { $testPackages -contains $_ } | Select-Object -Unique)
$removedAllowlist = @(@($summary.RemovedPackages) | Where-Object { $testPackages -contains $_ } | Select-Object -Unique)
$signals = @($summary.SignalActions | Select-Object -Unique)
$out.Add(('| RearCue events | {0} |' -f $summary.EventCount))
$out.Add(('| listener connected | {0} |' -f $summary.ListenerConnected))
$out.Add(('| posted / removed | {0} / {1} (allowlist hits: {2} / {3}) |' -f
        @($summary.PostedPackages).Count, @($summary.RemovedPackages).Count,
        ($postedAllowlist -join ','), ($removedAllowlist -join ',')))
$out.Add(('| effects | {0} |' -f (($summary.Effects) -join ', ')))
$out.Add(('| projection sent / confirmed | {0} / {1} |' -f $summary.LaunchSent, $summary.LaunchConfirmed))
$out.Add(('| icon set updates | {0} |' -f $summary.IconSetUpdates))
$out.Add(('| exit requested / detached | {0} / {1} |' -f $summary.ExitRequested, $summary.Exited))
$out.Add(('| takeover signals | {0} |' -f ($signals -join ', ')))
$out.Add(('| shizuku server / granted | {0} / {1} |' -f $summary.ShizukuServer, $summary.ShizukuGranted))
$out.Add(('| crashes (FATAL/ANR) | {0} |' -f $crashes.Count))
$out.Add('')
$out.Add('## step verdicts')
foreach ($file in $verdictFiles) {
    $out.Add('')
    $out.Add(('### {0}' -f $file.Name))
    $out.Add('')
    $out.Add('```')
    $body = Get-Content -LiteralPath $file.FullName -Encoding UTF8
    $inVerdicts = $false
    foreach ($line in $body) {
        if ($line -match '^# verdicts') { $inVerdicts = $true; continue }
        if ($inVerdicts -and $line -match '^#') { break }
        if ($inVerdicts -and $line.Trim()) { $out.Add($line) }
    }
    $out.Add('```')
}
$out.Add('')
$out.Add('## artifacts')
foreach ($file in (Get-ChildItem -LiteralPath $session -Recurse -File | Sort-Object FullName)) {
    $out.Add(('- {0} ({1:N0} bytes)' -f $file.FullName.Substring($session.Length + 1), $file.Length))
}
Write-ExArtifact -Name 'summary.md' -Lines $out.ToArray() | Out-Null

Write-ExNote ('summary written: {0}' -f (Join-Path $session 'summary.md'))
return (Join-Path $session 'summary.md')
