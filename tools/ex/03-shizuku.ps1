# 03-shizuku.ps1 -- make sure the Shizuku server is up, and run the E8 kill/restart experiment.
#
# Run through the one-command entry point (`ex.ps1`), or alone:
#   .\tools\ex\03-shizuku.ps1              # ensure the server is running (start it if it is not)
#   .\tools\ex\03-shizuku.ps1 -Restart     # E8: kill the server, watch the app, start it again
#
# Facts this script has to live with (docs/poc-findings.md, tickets #4/#6):
#   * The fallback channel needs `Shizuku.pingBinder()` plus the API permission. On this machine
#     the server is started by a third-party starter as bare shell uid, so the client app's
#     ShizukuProvider rejects it (requires moe.shizuku.manager.permission.API_V23) and
#     `granted=false` forever. A run therefore reports `fallback-usable=false` even when the
#     server is back -- that is a finding, not a script failure.
#   * "App must not crash and notifications must keep flowing" is what E8 can actually prove here.

[CmdletBinding()]
param(
    [switch] $Restart,
    [string] $StartScript = '/data/local/tmp/start-shizuku.sh',
    [int] $TimeoutSec = 30,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'shizuku' -Serial $Serial | Out-Null }

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('# 03-shizuku')
$lines.Add(('restart={0} start-script={1}' -f [bool]$Restart, $StartScript))

$serverBefore = Get-ExShizukuServerPid
$lines.Add(('shizuku_server before : {0}' -f $serverBefore))

# A locked phone freezes the app (MIUI GreezeManager), so its Shizuku lifecycle lines never reach
# logcat and the E8 verdicts come back empty for a reason that is not the app's behaviour.
$unlocked = Unlock-ExScreen
$lines.Add(('phone unlocked        : {0}' -f $unlocked))
if (-not $unlocked) {
    Write-ExNote 'WARNING: the phone is still locked; the app will be frozen and E8 will only see pids'
}
if (-not (Get-ExAppPid)) {
    Write-ExNote 'app not running: starting it so the listener can observe the Shizuku lifecycle'
    Start-ExApp -TimeoutSec 30 | Out-Null
} else {
    # keep it in the foreground for the duration of the experiment
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', (Get-ExConfig).MainActivity) -AllowFailure | Out-Null
}
$appPidBefore = Get-ExAppPid
$lines.Add(('app pid before        : {0}' -f $appPidBefore))

function Start-ExShizukuServer {
    $exists = Invoke-Adb -Arguments @('shell', 'ls', $StartScript) -AllowFailure
    if (($exists -join '') -match 'No such file') {
        # Keep the runbook self-contained: ship the starter from the repo instead of assuming the
        # device already has one (a wiped /data/local/tmp would otherwise silently skip the step).
        $local = Get-ExPath (Get-ExConfig).StartShizuku
        if (-not (Test-Path -LiteralPath $local)) {
            Write-ExNote ('start script {0} missing on device and {1} missing in the repo' -f $StartScript, $local)
            return $false
        }
        Write-ExNote ('pushing {0} to {1}' -f (Get-ExConfig).StartShizuku, $StartScript)
        # `.gitattributes` pins *.sh to LF, but a CRLF copy would still run fine on the PC and only
        # explode on the phone -- strip CR here so the pushed script is always LF.
        $text = (Get-Content -LiteralPath $local -Raw -Encoding UTF8) -replace "`r`n", "`n"
        $temp = Join-Path $env:TEMP ('ex-start-shizuku-{0}.sh' -f (Get-Date -Format 'HHmmssfff'))
        [System.IO.File]::WriteAllText($temp, $text, (New-Object System.Text.UTF8Encoding($false)))
        Invoke-Adb -Arguments @('push', $temp, $StartScript) -AllowFailure | Out-Null
        Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue
    }
    Write-ExNote ('starting Shizuku server via {0}' -f $StartScript)
    $out = Invoke-Adb -Arguments @('shell', 'sh', $StartScript) -AllowFailure
    $lines.Add('# start script output')
    foreach ($line in $out) { $lines.Add('  ' + $line) }
    return $true
}

$serverDown = $null
$downSummary = $null
if ($Restart -and $serverBefore) {
    Write-ExNote ('E8: killing shizuku_server pid={0}' -f $serverBefore)
    Invoke-Adb -Arguments @('shell', 'kill', '-9', [string]$serverBefore) -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    $serverDown = Get-ExShizukuServerPid
    $appPidDown = Get-ExAppPid
    $lines.Add(('shizuku_server after kill : {0}' -f $serverDown))
    $lines.Add(('app pid after kill        : {0}' -f $appPidDown))

    # Notification flow must keep working with the server gone.
    Invoke-ExDebugAction -Action 'POST_TEST'
    Start-Sleep -Seconds 3
    Invoke-ExDebugAction -Action 'CANCEL_TEST'
    Start-Sleep -Seconds 2
    $duringDown = @(Get-ExLogcat)
    $duringDownEvents = Get-RearCueEvent -Logcat $duringDown
    $downSummary = Get-ExChainSummary -Events $duringDownEvents
    $lines.Add('# events while the server was down')
    foreach ($raw in ($duringDown | Where-Object { $_ -match 'RearCue : ' } | Select-Object -Last 12)) { $lines.Add('  ' + $raw) }
}

if (-not (Get-ExShizukuServerPid)) {
    Start-ExShizukuServer | Out-Null
    Start-Sleep -Seconds 3
}

$serverAfter = Get-ExShizukuServerPid
Start-Sleep -Seconds 2
$allLines = @(Get-ExLogcat)
$events = Get-RearCueEvent -Logcat $allLines
$summary = Get-ExChainSummary -Events $events
$crashes = Test-RearCueCrash -Logcat $allLines
$appPidAfter = Get-ExAppPid

$verdicts = [ordered]@{
    'phone-unlocked'        = $unlocked
    'server-process-killed' = ($Restart -and ($null -ne $serverBefore) -and ($null -eq $serverDown))
    'server-process-back'   = ($null -ne $serverAfter)
    'app-alive'             = (($null -ne $appPidAfter) -and ($appPidAfter -eq $appPidBefore))
    'no-fatal'              = ($crashes.Count -eq 0)
    # The listener must keep handling notifications while the Shizuku server is gone
    # (ticket #6's criterion); a fresh `listener connected` line is not required for that.
    'listener-kept-working' = if ($downSummary) {
        (($downSummary.Kinds -contains 'posted') -or ($downSummary.Kinds -contains 'removed'))
    } else {
        (($summary.Kinds -contains 'posted') -or ($summary.Kinds -contains 'removed'))
    }
    # App-side view of the binder. Structurally false on this machine (see the blocker below),
    # which is itself the E8 finding: the app never sees a binder, so it never logs up/down.
    'app-sees-server'       = ($summary.ShizukuServer -eq 'true')
    'app-saw-server-drop'   = $summary.ShizukuServerWentDown
    'fallback-usable'       = ($summary.ShizukuGranted -eq 'true')
}

$lines.Add('')
$lines.Add('# verdicts')
foreach ($key in $verdicts.Keys) { $lines.Add(('  {0,-22} = {1}' -f $key, $verdicts[$key])) }
$lines.Add(('crashes                = {0}' -f $crashes.Count))
$lines.Add(('shizuku_server after   : {0}' -f $serverAfter))
$lines.Add(('app pid after          : {0}' -f $appPidAfter))
$lines.Add(('app-side binder view   : server={0} granted={1}' -f $summary.ShizukuServer, $summary.ShizukuGranted))
if ($downSummary) {
    $lines.Add(('events while server down: posted={0} removed={1}' -f
            (@($downSummary.PostedPackages) -join ','), (@($downSummary.RemovedPackages) -join ',')))
}
$lines.Add('')
$lines.Add('# known blocker when fallback-usable=false')
$lines.Add('#   shizuku_server started by the third-party starter runs as bare shell uid; the app-side')
$lines.Add('#   ShizukuProvider rejects it (requires moe.shizuku.manager.permission.API_V23), so')
$lines.Add('#   pingBinder() stays false and the `am start` fallback never runs. To close E8 the')
$lines.Add('#   server must be started once from inside the Shizuku app (wireless debugging / official')
$lines.Add('#   ADB flow); that step needs a human and is recorded as a manual checkpoint.')

Write-ExArtifact -Name '03-shizuku.txt' -Lines $lines.ToArray() | Out-Null
Write-ExNote ('03-shizuku done: ' + (($verdicts.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }) -join ' '))
return $verdicts
