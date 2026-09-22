# 06-overlay.ps1 -- E9 (ticket #10): can a SYSTEM_ALERT_WINDOW overlay land on the rear display?
#
# Run through the one-command entry point (`ex.ps1 -Task overlay` = install -> this -> collect), or alone:
#   .\tools\ex\06-overlay.ps1
#   .\tools\ex\06-overlay.ps1 -DisplayId 0        # control group: aim the same window at the main screen
#
# One command does add -> judge -> remove -> archive. The verdict is NEVER the broadcast exit code:
#   * the decisive fact is `dumpsys window windows` -- is the probe window really on the rear display?
#   * the app's own logcat gives the failure reason (no permission / system exception);
#   * system-side WindowManager lines ("add system_window on rear display", Permission Denial) back it up.
# Failure classes (findings must name all three):
#   not granted / rejected by system / blocked from the rear display.

[CmdletBinding()]
param(
    [int] $DisplayId,
    [int] $WaitSeconds = 3,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'overlay' -Serial $Serial | Out-Null }

# ---- 0. preflight: is the overlay permission really granted? -----------------
$appops = Invoke-Adb -Arguments @('shell', 'appops', 'get', $config.Package, 'SYSTEM_ALERT_WINDOW') -AllowFailure
$appopsText = (($appops -join '') -replace "`r?`n", ' ').Trim()
$granted = ($appopsText -match 'SYSTEM_ALERT_WINDOW:\s*allow')
Write-ExNote ('appops SYSTEM_ALERT_WINDOW: {0}' -f $appopsText)

# The rear display id is a runtime fact (never hardcoded 1), same rule as the app's RearDisplayLocator.
$displayDump = Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure
$rear = Get-RearDisplay -DumpsysDisplay ($displayDump -join "`n")
$rearId = if ($rear) { $rear.DisplayId } else { $null }
Write-ExNote ('rear display: id={0} state={1}' -f $rearId, $rear.State)

# ---- 1. clean process, app foreground (debug broadcasts must reach a live process) ----
Clear-ExLogcat
Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $config.Package) -AllowFailure | Out-Null
Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', $config.MainActivity) -AllowFailure | Out-Null
Start-Sleep -Seconds 2

# ---- 2. add the probe window -------------------------------------------------
$extra = if ($PSBoundParameters.ContainsKey('DisplayId')) { @{ displayId = $DisplayId } } else { $null }
Invoke-ExDebugAction -Action 'OVERLAY_ADD' -Extra $extra
Start-Sleep -Seconds $WaitSeconds

$windowBefore = Invoke-Adb -Arguments @('shell', 'dumpsys', 'window', 'windows') -AllowFailure
$appLog = @(Get-ExLogcat)
$systemLog = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure)
$events = Get-OverlayWindowEvents -Logcat ($appLog + $systemLog)
$probe = Get-OverlayProbeWindow -DumpsysWindow ($windowBefore -join "`n")
$target = if ($extra) { $DisplayId } else { $rearId }
$onTarget = ($probe.Found -and ($null -ne $probe.DisplayId) -and ($null -ne $target) -and ($probe.DisplayId -eq $target))

# ---- 3. remove the probe window ----------------------------------------------
Invoke-ExDebugAction -Action 'OVERLAY_REMOVE'
Start-Sleep -Seconds 2
$windowAfter = Invoke-Adb -Arguments @('shell', 'dumpsys', 'window', 'windows') -AllowFailure
$appLogAfter = @(Get-ExLogcat)
$systemLogAfter = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure)
$eventsAfter = Get-OverlayWindowEvents -Logcat ($appLogAfter + $systemLogAfter)
$probeAfter = Get-OverlayProbeWindow -DumpsysWindow ($windowAfter -join "`n")
$removed = ((-not $probeAfter.Found) -and ($eventsAfter.Removed))

# ---- 4. artifacts + verdicts -------------------------------------------------
Write-ExArtifact -Name 'dumpsys-window-before-remove.txt' -Lines $windowBefore | Out-Null
Write-ExArtifact -Name 'dumpsys-window-after-remove.txt' -Lines $windowAfter | Out-Null
Write-ExArtifact -Name 'logcat-rearcue.txt' -Lines $appLogAfter | Out-Null
$overlaySystem = @($systemLogAfter |
    Where-Object { $_ -match 'WindowManager|system_window|APPLICATION_OVERLAY|RearCueOverlayProbe|SYSTEM_ALERT_WINDOW|Permission Denial' } |
    Select-Object -Last 400)
Write-ExArtifact -Name 'logcat-overlay-system.txt' -Lines $overlaySystem | Out-Null

$verdictClass = if (-not $granted) {
    'E9-BLOCKED-PERMISSION (SYSTEM_ALERT_WINDOW not granted: rerun 01-install)'
} elseif ($events.RearPolicyDeny) {
    $policy = @($events.SystemDeny | Where-Object { $_ -match 'system_window on rear display' } | Select-Object -First 1)
    ('E9-REAR-POLICY-BLOCKED (WindowManager denied a non-system app a system window on the rear display: "{0}")' -f (($policy -join '').Trim()))
} elseif ($events.AddFailed) {
    'E9-SYSTEM-REJECTED (the add failed inside the app)'
} elseif (-not $events.Added) {
    'E9-NO-EFFECT (the broadcast reached nobody: app not running / action not registered)'
} elseif (-not $onTarget) {
    if ($probe.Found) {
        ('E9-NOT-ON-TARGET (window accepted but landed on display {0}, not the target display {1})' -f $probe.DisplayId, $target)
    } else {
        'E9-NOT-ON-TARGET (app said added, but no probe window in dumpsys window)'
    }
} elseif (-not $removed) {
    'E9-PARTIAL (window on the target display, but removal not confirmed)'
} else {
    ('E9-PASS (overlay window really on the target display {0}, then removed)' -f $target)
}

$out = @(
    '# e9-overlay',
    ('appops SYSTEM_ALERT_WINDOW : {0} granted={1}' -f $appopsText, $granted),
    ('rear display                : id={0} state={1}' -f $rearId, $rear.State),
    ('requested target display    : {0}' -f $target),
    (''),
    '# verdicts',
    ('granted            : {0}' -f $granted),
    ('add-accepted(app)  : {0}' -f $events.Added),
    ('add-failed(app)    : {0} reason={1}' -f $events.AddFailed, $events.FailureReason),
    ('rear-policy-deny   : {0} (WindowManager "Not allow non-system app ... system_window on rear display")' -f $events.RearPolicyDeny),
    ('on-target-display  : {0} (probe window found={1} displayId={2})' -f $onTarget, $probe.Found, $probe.DisplayId),
    ('system-window-log  : {0} hit(s), {1} denial(s)' -f $events.SystemAdd.Count, $events.SystemDeny.Count),
    ('removed            : {0} (still in dumpsys after remove: {1})' -f $removed, $probeAfter.Found),
    ('verdict            : {0}' -f $verdictClass),
    '',
    '# probe window facts (dumpsys window, before remove)',
    ('  found={0} displayId={1} package={2} appop={3}' -f $probe.Found, $probe.DisplayId, $probe.Package, $probe.Appop),
    '',
    '# system-side window lines (logcat -b all, filtered)',
    ($overlaySystem | ForEach-Object { '  ' + $_ }),
    '',
    '# app log (RearCue tag)',
    ($appLogAfter | ForEach-Object { '  ' + $_ })
)
Write-ExArtifact -Name 'e9-overlay.txt' -Lines $out | Out-Null

foreach ($line in ($out | Where-Object { $_ -match '^(granted|add-|rear-policy|on-target|system-window-log|removed|verdict) ' })) {
    Write-ExNote $line
}
return $verdictClass
