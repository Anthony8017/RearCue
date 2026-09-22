# 14-kill-recover.ps1 -- ticket #21: does the Wake Keep-alive recover when the PROCESS is rebuilt?
#
# The claim under test: "进程重建后按当前 Icon Set 恢复" (after a process rebuild the loop comes
# back per the current Icon Set). One run = baseline up -> app keep-alive ticking -> `am
# force-stop` (a real process death) -> the REAL-WORLD recovery trigger: one more allowlist
# notification. The dead listener gets rebound by NotificationManagerService when the new
# notification arrives, the process is rebuilt, IconSetFeed resyncs the active notifications,
# the core re-projects and WakeKeepAlive starts again -- or it doesn`t, and this says so.
#
# Verdict words:
#   REBUILD-RECOVERED     process rebuilt AND Dashboard back on the rear AND heartbeats resumed
#   REBUILD-NO-LISTENER   the process never came back after the kill (the listener rebind did
#                         not revive it -- nothing downstream can happen)
#   REBUILD-NO-REPROJECT  process back but the Dashboard did not return to the rear display
#   REBUILD-NO-KEEPALIVE  Dashboard back but no fresh `wake-keep-alive` markers (loop dead)
#   REBUILD-RUN-INVALID   external interference voided the round (pollution words from E13)
#
# Run through the one-command entry point (`ex.ps1 -Task kill-recover` = authorize -> this ->
# collect), or alone:
#   .\tools\ex\14-kill-recover.ps1
#   .\tools\ex\14-kill-recover.ps1 -WatchSeconds 45
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $WakeIntervalMs = 500,
    [int] $WatchSeconds = 45,
    [int] $SampleSeconds = 3,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'kill-recover' -Serial $Serial | Out-Null }

function Get-ExRecoverSnapshot {
    $dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
    $rear = Get-RearDisplay -DumpsysDisplay $dump
    $owner = 'none'
    if ($rear) { $owner = Get-ExRearOwnerNow -DisplayId $rear.DisplayId }
    $pidText = ((Invoke-Adb -Arguments @('shell', 'pidof', $config.Package) -AllowFailure) -join '').Trim()
    return [pscustomobject]@{
        RearPair = (Format-ExStatePair $rear)
        RearId   = if ($rear) { $rear.DisplayId } else { $null }
        Owner    = $owner
        Pid      = if ($pidText) { $pidText } else { '<none>' }
    }
}

# ---- 0. baseline: one allowlist notification -> Dashboard on the rear + keep-alive --------
Clear-ExLogcat
Start-ExApp -TimeoutSec 30 | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Start-Sleep -Seconds 2
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex',
    'kill recover baseline') -AllowFailure | Out-Null
Invoke-ExDebugAction -Action 'POST_TEST'
Invoke-ExDebugAction -Action 'WAKE_INTERVAL' -Extra @{ ms = $WakeIntervalMs }
$snap0 = Get-ExRecoverSnapshot
$dashboardUp = $false
if ($null -ne $snap0.RearId) { $dashboardUp = Wait-ExRearOwner -Owner 'dashboard' -DisplayId $snap0.RearId -TimeoutSec 20 }
Write-ExNote ('baseline: rear={0} owner={1} pid={2} dashboard-up={3}' -f $snap0.RearPair, $snap0.Owner, $snap0.Pid, $dashboardUp)
if (-not $dashboardUp) {
    Write-ExNote 'WARNING: the Dashboard did not reach the rear display before the kill -- this run'
    Write-ExNote '         can only report REBUILD-* from a broken baseline (read it as NO-BASELINE).'
}
Start-Sleep -Seconds 6

# ---- 1. kill the process ---------------------------------------------------------
Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-kill-recover-issued') -AllowFailure | Out-Null
$killT0 = ((Invoke-Adb -Arguments @('shell', "date '+%m-%d %H:%M:%S'") -AllowFailure) -join '').Trim()
Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $config.Package) -AllowFailure | Out-Null
Start-Sleep -Seconds 2
$snapKilled = Get-ExRecoverSnapshot
Write-ExNote ('killed at {0}: pid now {1}' -f $killT0, $snapKilled.Pid)

# ---- 2. real-world recovery trigger: one more allowlist notification ----------------
# (the system must rebind the dead NotificationListenerService to deliver it -- that rebind is
# what revives the process; the rebuilt app then resyncs active notifications = current Icon Set)
Start-Sleep -Seconds 3
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex2',
    'kill recover revive') -AllowFailure | Out-Null

# ---- 3. watch --------------------------------------------------------------------
$start = Get-Date
$deadline = $start.AddSeconds($WatchSeconds)
$samples = New-Object System.Collections.Generic.List[string]
do {
    Start-Sleep -Seconds $SampleSeconds
    $elapsed = [int]((Get-Date) - $start).TotalSeconds
    $snap = Get-ExRecoverSnapshot
    $line = Format-ExWakeSampleLine -Elapsed $elapsed -RearPair $snap.RearPair -MainPair (Format-ExStatePair $null) -Owner $snap.Owner
    $line = $line + (' pid={0}' -f $snap.Pid)
    $samples.Add($line)
    Write-ExNote $line
} while ((Get-Date) -lt $deadline)

# ---- 4. evidence + verdicts -------------------------------------------------------
$appLog = @(Get-ExLogcat)
$appKeep = Get-ExAppKeepAliveFacts -Logcat $appLog
# Heartbeats AFTER the kill = the recovered loop; before-kill lines are the old process.
$postKillKeep = Get-ExAppKeepAliveFacts -Logcat @($appLog | Where-Object {
        (Get-ExSecondStamp $_) -ge $killT0
    })
$postSamples = @($samples | Where-Object { $_ -match 'pid=\d' })
$finalSnap = Get-ExRecoverSnapshot
$processBack = ($finalSnap.Pid -ne '<none>')
$dashboardBack = ($finalSnap.Owner -eq 'dashboard')
$loopBack = ($postKillKeep.Started -and ($postKillKeep.Heartbeats -ge 1) -and ($postKillKeep.Fails -eq 0))

$rebuild = if (-not $dashboardUp) {
    'REBUILD-NO-BASELINE (the Dashboard was never up before the kill; nothing to recover)'
} elseif (-not $processBack) {
    'REBUILD-NO-LISTENER (the process never came back after `am force-stop` -- the listener rebind did not revive it)'
} elseif (-not $dashboardBack) {
    'REBUILD-NO-REPROJECT (process rebuilt but the Dashboard did not return to the rear display)'
} elseif (-not $loopBack) {
    'REBUILD-NO-KEEPALIVE (Dashboard back but no fresh wake-keep-alive markers after the kill)'
} else {
    ('REBUILD-RECOVERED (process rebuilt pid={0}; Dashboard back on the rear; post-kill keep-alive: start={1} heartbeats={2} max-ticks={3} fails={4})' -f
        $finalSnap.Pid, $postKillKeep.Started, $postKillKeep.Heartbeats, $postKillKeep.MaxTicks, $postKillKeep.Fails)
}

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# rebuild-recover (ticket #21: does the Wake Keep-alive recover after a process rebuild?)')
$out.Add(('kill-t0                 : {0} (`am force-stop` right after this device-clock stamp)' -f $killT0))
$out.Add(('pid-before / after-kill / final : {0} / {1} / {2}' -f $snap0.Pid, $snapKilled.Pid, $finalSnap.Pid))
$out.Add(('keep-alive before kill  : start={0} heartbeats={1} max-ticks={2} fails={3}' -f $appKeep.Started, $appKeep.Heartbeats, $appKeep.MaxTicks, $appKeep.Fails))
$out.Add(('keep-alive after kill   : start={0} heartbeats={1} max-ticks={2} fails={3}' -f $postKillKeep.Started, $postKillKeep.Heartbeats, $postKillKeep.MaxTicks, $postKillKeep.Fails))
$out.Add(('dashboard               : up-before={0} back-after={1} (owner={2})' -f $dashboardUp, $dashboardBack, $finalSnap.Owner))
$out.Add(('rebuild                 : {0}' -f $rebuild))
Write-ExArtifact -Name 'rebuild-recover.txt' -Lines $out.ToArray() | Out-Null
Write-ExArtifact -Name 'rebuild-samples.txt' -Lines (@('# wire: ' + 'mm-dd HH:mm:ss elapsed=N rear=<pair> main=no-display owner=<owner> pid=<pid>') + $samples.ToArray()) | Out-Null
foreach ($line in $out) { Write-ExNote $line }

# ---- 5. teardown ------------------------------------------------------------------
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }

# ---- 6. scenario notes ------------------------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (process rebuild recovery, ticket #21)')
$notes.Add('')
$notes.Add('- **What is killed**: `am force-stop` -- a real process death (not a thread restart).')
$notes.Add('- **Recovery trigger is the real-world one**: one more allowlist notification ~3s after')
$notes.Add('  the kill. NotificationManagerService must rebind the dead listener to deliver it; that')
$notes.Add('  rebind revives the process, IconSetFeed resyncs `getActiveNotifications` (= the current')
$notes.Add('  Icon Set), the core re-projects and WakeKeepAlive starts again.')
$notes.Add('- **Evidence split**: `rebuild-samples.txt` carries the pid per sample (the rebuild itself);')
$notes.Add('  `rebuild-recover.txt` splits the keep-alive markers into before/after the kill stamp, so')
$notes.Add('  the "loop came back" claim rests on markers from the NEW process only.')
$notes.Add('- **Deviation**: the main display state is not sampled (this question is about the rear');')
$notes.Add('  chain only); the wire keeps the same slot with `main=no-display`.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Rebuild    = $rebuild
    ProcessBack = $processBack
    DashboardBack = $dashboardBack
    LoopBack   = $loopBack
}
