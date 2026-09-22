# 16-freeze-probe.ps1 -- ticket #29: HyperOS GreezeManager freeze probe (facts only).
#
# What is under test (ticket #29 / spec 0004): the reproduction conditions of the GreezeManager
# freeze of THIS app, the observable behavior of notification events DURING the freeze (held
# until thaw -- or not), and the device facts a countermeasure list can stand on. No product
# code, no countermeasure implementation -- this script only measures and archives.
#
# One run = control leg (live delivery while unfrozen) -> reproduce (HOME -> tobg freeze)
# -> frozen window (two REAL allowlist posts + cgroup.freeze sampling) -> thaw probes
# (explicit debug broadcast first, Activity Start second) -> visible-skip leg (Dashboard on
# the rear display = freeze escape?) -> countermeasure facts (appops / dumpsys greezer /
# deviceidle whitelist). Every timestamp is the DEVICE clock: the post markers go into logcat
# via `log` (same clock as the app lines), the freeze samples read `date` on the device.
#
# Verdict words:
#   FZ-REPRODUCED          froze after going to background (latency + FZ reason in the text)
#   FZ-NOT-REPRODUCED      no freeze within the watch (event legs read as EVT-NOT-APPLICABLE)
#   FZ-RUN-INVALID         external interference (fingerprint / power button) voids the round
#   EVT-HELD-UNTIL-THAW    posts made while frozen: 0 delivered live, all delivered after the
#                          freeze ended (per-event delay = delivery - post, one device clock)
#   EVT-DELIVERED-LIVE     a control/on-rear post delivered while not frozen (delay in text)
#   EVT-LOST               a post stayed undelivered even after the freeze ended
#   EVT-NO-LISTENER        the control leg saw no delivery at all: timeline unobservable
#   EVT-NOT-APPLICABLE     no freeze was reproduced, so "held until thaw" is untestable
#   THAW-BY-BROADCAST      the explicit broadcast thawed the frozen app
#   THAW-NOT-BY-BROADCAST  the explicit broadcast did NOT thaw it (events stayed queued)
#   THAW-NOT-APPLICABLE    no freeze to thaw from
#   FZ-SKIP-VISIBLE        Dashboard on the rear kept the app out of the freezer (skip lines)
#   FZ-FROZEN-ON-REAR      the app froze even with the Dashboard on the rear (counter-fact)
#   FZ-NO-REAR-BASELINE    the Dashboard never reached the rear (keyguard / channel), the
#                          visible-skip question is untested this round
#
# Run through the one-command entry point (`ex.ps1 -Task freeze-probe` = authorize -> this ->
# collect), or alone:
#   .\tools\ex\16-freeze-probe.ps1
#   .\tools\ex\16-freeze-probe.ps1 -ObserveSeconds 90 -OnRearSeconds 45
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $ObserveSeconds = 60,
    [int] $OnRearSeconds = 30,
    [int] $SampleSeconds = 2,
    # How to get the app frozen. `tobg` = plain HOME (the ticket #5 trigger -- flaky: it fired
    # in 20260923-030402 and not in -032045/-034557 while oom_score_adj stayed 0); `sleep` =
    # KEYCODE_SLEEP (the `reason : screen off` freeze, 2/2 in the Greezer history); `auto` =
    # tobg attempt first, escalate to sleep. THAW probes: explicit broadcast first (the
    # countermeasure question), then KEYCODE_WAKEUP (`reason : screen on`), then Activity Start.
    [ValidateSet('auto', 'tobg', 'sleep')][string] $FreezeTrigger = 'auto',
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'freeze-probe' -Serial $Serial | Out-Null }

# Notification tags must be unique per RUN: `cmd notification post` with a tag that already
# exists is a KEY UPDATE and fires NO onNotificationPosted (ticket #3 finding) -- reusing tags
# across runs silently made every probe invisible (bit in 20260923-033746: listener healthy,
# `listener connected active=24`, three probes, zero `posted` lines).
# ALSO (bit in 20260923-034104): `adb shell` SPACE-JOINS its argv into the remote command, so
# `post -t 'RearCue ex' <tag> <text>` reached the device as `post -t RearCue ex <tag> <text>`
# -> title=RearCue, TAG=ex, TEXT='<tag> <text>' -- every probe in every round collapsed onto
# the ONE key `0|com.android.shell|2020|ex|2000` as updates. All post arguments are therefore
# single tokens: no -t title flag, tag = run-unique, text = underscored.
$runTag = Get-Date -Format 'HHmmss'

function Get-ExDeviceStamp {
    # Second-grain stamp of the DEVICE clock (the one logcat lines carry).
    ((Invoke-Adb -Arguments @('shell', "date '+%m-%d %H:%M:%S'") -AllowFailure) -join '').Trim()
}

function Get-ExCgroupFreeze {
    <# uid-level + pid-level `cgroup.freeze` of the app as one wire pair (`0`/`1`/`err`).
       NB: never name a local `$pid` -- it is a read-only automatic variable and every
       assignment silently throws (bit once, 20260923-030402: the wire then carried the
       PowerShell process id and the run saw "never frozen" while the log showed FZ). #>
    param([string] $UidDir, [string] $PidDir)
    $vals = ((Invoke-Adb -Arguments @('shell', ('cat {0}/cgroup.freeze {1}/cgroup.freeze 2>/dev/null' -f $UidDir, $PidDir)) -AllowFailure) -join ' ')
    $tokens = @($vals -split '\s+' | Where-Object { $_ -match '^[01]$' })
    $uidVal = 'err'
    $pidVal = 'err'
    if ($tokens.Count -ge 1) { $uidVal = $tokens[0] }
    if ($tokens.Count -ge 2) { $pidVal = $tokens[1] }
    return ('uid={0} pid={1}' -f $uidVal, $pidVal)
}

function Write-ExFreezeSample {
    # One sampling point of the freeze watch -> the wire line Get-ExFreezeSampleFacts reads.
    # `adj=` is the app's oom_score_adj: the missing variable of 20260923-032045 (the app froze
    # in one run and not in the next with everything else equal -- cached vs service adj is the
    # leading candidate), so the wire carries it alongside the two cgroup.freeze values.
    param([string] $Name, [string] $UidDir, [string] $PidDir, [string] $AppPid, [string] $Header)
    $stamp = Get-ExDeviceStamp
    $pair = Get-ExCgroupFreeze -UidDir $UidDir -PidDir $PidDir
    $adj = 'err'
    if ($AppPid) {
        $adjRaw = ((Invoke-Adb -Arguments @('shell', ('cat /proc/{0}/oom_score_adj 2>/dev/null' -f $AppPid)) -AllowFailure) -join '').Trim()
        if ($adjRaw -match '^-?\d+$') { $adj = $adjRaw }
    }
    $lines = New-Object System.Collections.Generic.List[string]
    if ($Header) { $lines.Add($Header) }
    $lines.Add(('{0} {1} adj={2}' -f $stamp, $pair, $adj))
    Write-ExArtifact -Name $Name -Lines $lines.ToArray() -Append | Out-Null
    return $stamp
}

# ---- 0. environment facts (device truth the verdicts will need later) -----------------
$envLines = New-Object System.Collections.Generic.List[string]
$envLines.Add('# freeze-probe environment (ticket #29)')
$envLines.Add(('run-start : {0}' -f (Get-ExDeviceStamp)))
$envLines.Add(('keyguard  : locked={0}' -f (Test-ExKeyguardLocked)))

# Bring the app to the foreground WITHOUT force-stop: the control leg doubles as the
# listener liveness check, and a force-stop would put us into the MIUI rebind dance for no
# reason (Start-ExApp is for runs that need a clean process -- this one needs a live listener).
Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', $config.MainActivity) -AllowFailure | Out-Null
Start-Sleep -Seconds 2

$appPid = Get-ExAppPid
$pidDirRaw = ''
if ($null -ne $appPid) {
    $pidDirRaw = ((Invoke-Adb -Arguments @('shell', ('ls -d /sys/fs/cgroup/apps/uid_*/pid_{0} 2>/dev/null' -f $appPid)) -AllowFailure) -join '').Trim()
}
$uidDir = ''
$pidDir = ''
if ($pidDirRaw -match '(/sys/fs/cgroup/apps/uid_\d+)/pid_\d+') {
    $uidDir = $Matches[1]
    $pidDir = $pidDirRaw
}
$uid = ''
if ($uidDir -match 'uid_(\d+)') { $uid = $Matches[1] }

$envLines.Add(('app pid   : {0}' -f $appPid))
$envLines.Add(('uid       : {0}' -f $uid))
$envLines.Add(('cgroup    : uid-dir={0} pid-dir={1}' -f $uidDir, $pidDir))
$envLines.Add(('listener  : enabled={0}' -f (Test-ExListenerEnabled)))
$envLines.Add(('cgroup.freeze now : {0}' -f (Get-ExCgroupFreeze -UidDir $uidDir -PidDir $pidDir)))
$envLines.Add('')
$envLines.Add('# greeze settings knobs (`dumpsys greezer` Settings block)')
$greezerBefore = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'greezer') -AllowFailure)
foreach ($line in $greezerBefore) {
    if ($line -match '^\s*(enable|debug|monkey|fz_timeout|monitor)=') { $envLines.Add(('  ' + $line.Trim())) }
}
$envLines.Add('')
$envLines.Add('# MIUI autostart facts: the string op has no adb interface, the numeric MIUIOP does')
$autoStartTry = @(Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'set', $config.Package, 'AUTO_START', 'allow') -AllowFailure)
$envLines.Add(('appops set AUTO_START allow : {0}' -f (($autoStartTry -join ' | ').Trim())))
$miuiOps = @(Invoke-Adb -Arguments @('shell', 'appops', 'get', $config.Package) -AllowFailure)
foreach ($line in $miuiOps) {
    if ($line -match 'MIUIOP|POST_NOTIFICATION|SYSTEM_ALERT_WINDOW') { $envLines.Add(('  ' + $line.Trim())) }
}
$envLines.Add('')
$envLines.Add('# deviceidle whitelist membership (countermeasure fact)')
$whitelist = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist') -AllowFailure)
$wlHits = @($whitelist | Where-Object { $_ -match [regex]::Escape($config.Package) })
$envLines.Add(('deviceidle whitelist hits for {0} : {1}' -f $config.Package, $wlHits.Count))
foreach ($line in $wlHits) { $envLines.Add(('  ' + $line.Trim())) }
Write-ExArtifact -Name 'freeze-environment.txt' -Lines $envLines.ToArray() | Out-Null
Write-ExArtifact -Name 'dumpsys-greezer.txt' -Lines (@('# dumpsys greezer -- BEFORE the run') + $greezerBefore) | Out-Null

if ($null -eq $appPid -or -not $uidDir) {
    Write-ExNote 'FATAL: the app process / cgroup dirs are missing -- the freeze watch needs both'
    Write-ExNote '       (is the app installed and running? `cgroup.freeze` is the ground truth).'
    return @{ Fz = 'FZ-RUN-INVALID (no app process or no cgroup dir -- nothing to watch)' }
}

# ---- 1. control leg: live delivery while unfrozen (also the listener liveness proof) ----
# A post that lands while NotificationListenerService is NOT connected shows up in the initial
# snapshot instead of a `posted` line (bit twice: 20260923-030402 / 20260923-032045 -- the
# control post vanished without a line and then stole the next marker's delivery in the
# pairing). So the control leg is a PROBE LOOP: post with a FRESH tag (a same-tag re-post is
# an UPDATE and never fires onNotificationPosted -- ticket #3), require a PAIRED `posted`
# delivery within 5s, and only a probe that really DELIVERS opens the timeline. On a miss:
# toggle the listener grant (Start-ExApp's rebind dance) and retry. Each attempt records the
# `cmd notification post` output verbatim -- a failed post is otherwise invisible.
Clear-ExLogcat
$probeOk = $false
$probeId = 'ctl-c0'
for ($probeAttempt = 1; $probeAttempt -le 3 -and -not $probeOk; $probeAttempt++) {
    if ($probeAttempt -gt 1) {
        # The known recovery dance (Start-ExApp): toggle the listener grant to force a rebind.
        # Then WAIT for the `listener connected active=` line before probing again: the rebind
        # takes 9-13s (ticket #3) and a post that lands mid-rebind arrives through the initial
        # snapshot WITHOUT a `posted` line -- dancing faster than the rebind just restarts its
        # clock (bit in 20260923-033533: three probes inside one rebind window, 0 deliveries).
        Write-ExNote ('probe {0} did not deliver; forcing a listener rebind and waiting for connect' -f $probeId)
        Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'disallow_listener', $config.ListenerComponent) -AllowFailure | Out-Null
        Start-Sleep -Seconds 2
        Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'allow_listener', $config.ListenerComponent) -AllowFailure | Out-Null
        $bindLine = Wait-ExLog -Pattern 'listener connected active=' -TimeoutSec 30
        $bindHits = @($bindLine | Where-Object { $null -ne $_ })
        if ($bindHits.Count -gt 0) {
            Write-ExNote ('listener reconnected: {0}' -f (Get-ExRearCueMessage -Line $bindHits[0]))
            Write-ExArtifact -Name 'freeze-post-outputs.txt' -Lines @('', ('## listener reconnected before probe attempt {0}' -f $probeAttempt), ('  ' + $bindHits[0])) -Append | Out-Null
        } else {
            Write-ExNote 'no `listener connected active=` line within 30s of the rebind (probing anyway)'
        }
    }
    $probeId = 'ctl-c{0}' -f $probeAttempt
    $probeTag = 'rearcue-fz-{0}-c{1}' -f $runTag, $probeAttempt
    $stamp = Get-ExDeviceStamp
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, ('pc-freeze-{0}' -f $probeId)) -AllowFailure | Out-Null
    $postOut = @(Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', $probeTag,
            'freeze_probe_control') -AllowFailure)
    $postLines = New-Object System.Collections.Generic.List[string]
    $postLines.Add(('## control probe {0} posted at {1}' -f $probeId, $stamp))
    foreach ($line in $postOut) { $postLines.Add(('  ' + $line)) }
    Write-ExArtifact -Name 'freeze-post-outputs.txt' -Lines $postLines.ToArray() -Append | Out-Null
    Write-ExNote ('control probe {0} at {1}: {2}' -f $probeId, $stamp, (($postOut -join ' ').Trim()))
    Start-Sleep -Seconds 5
    $probeTimeline = Get-ExFreezeTimeline -Logcat @(Get-ExLogcat)
    $probePair = @($probeTimeline.Pairs | Where-Object { $_.Id -eq $probeId })
    if ($probePair.Count -gt 0) {
        $probeOk = $true
        Write-ExNote ('control probe {0} DELIVERED with delay {1}s (listener alive)' -f $probeId, $probePair[0].DelaySec)
    }
}
if (-not $probeOk) {
    Write-ExNote 'FATAL: no control probe delivery in 3 attempts -- the notification listener is'
    Write-ExNote '       not delivering `posted` lines, the event timeline is unobservable.'
    $failOut = New-Object System.Collections.Generic.List[string]
    $failOut.Add('# freeze-probe (ticket #29) -- aborted: no control delivery (listener not observable)')
    $failOut.Add('')
    $failOut.Add('# verdicts')
    $failOut.Add('fz       : FZ-NOT-REPRODUCED (run aborted before the freeze watch)')
    $failOut.Add('events   : EVT-NO-LISTENER (3 control probes, 0 `posted` deliveries)')
    $failOut.Add('thaw     : THAW-NOT-APPLICABLE (no freeze to thaw from)')
    $failOut.Add('on-rear  : FZ-NO-REAR-BASELINE (run aborted before the visible-skip leg)')
    Write-ExArtifact -Name 'freeze-probe.txt' -Lines $failOut.ToArray() | Out-Null
    # Teardown even on abort: a leftover probe notification becomes the next run's key-update
    # trap all over again (the 20260923-034104 round inherited exactly one such leftover).
    Invoke-ExDebugAction -Action 'CANCEL_TEST' | Out-Null
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' } | Out-Null
    return @{ Fz = 'FZ-NOT-REPRODUCED (aborted)'; Events = 'EVT-NO-LISTENER' }
}

# ---- 2. reproduce: background the app -> GreezeManager freeze -------------------------
# Teardown the control notification FIRST (a live allowlist post keeps the Dashboard on the
# rear = visible = freeze-exempt, which would poison the reproduce leg).
Invoke-ExDebugAction -Action 'CANCEL_TEST' | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' } | Out-Null
# The Dashboard must be OFF the rear before the backgrounding: a visible app is freeze-exempt
# (that is exactly the leg-5 question), so a lingering Dashboard here would poison the leg.
Wait-ExRearOwner -Owner 'native' -TimeoutSec 10 | Out-Null

$sampleName = 'freeze-samples.txt'
$sampleHeader = '# wire: MM-dd HH:mm:ss uid=<0|1|err> pid=<0|1|err> adj=<n|err> (uid_/pid_ cgroup.freeze; adj = oom_score_adj)'
Write-ExFreezeSample -Name $sampleName -UidDir $uidDir -PidDir $pidDir -AppPid $appPid -Header $sampleHeader | Out-Null

$fzTriggerUsed = 'tobg'
$bgStamp = $null
$frozenStamp = $null
$watchFreeze = {
    param([int] $MaxHalfSeconds)
    for ($i = 0; $i -lt $MaxHalfSeconds; $i++) {
        Start-Sleep -Milliseconds 500
        $pair = Get-ExCgroupFreeze -UidDir $uidDir -PidDir $pidDir
        if ($pair -match 'uid=1|pid=1') {
            $hit = Get-ExDeviceStamp
            $lines = New-Object System.Collections.Generic.List[string]
            $lines.Add(('{0} {1} adj=pending' -f $hit, $pair))
            Write-ExArtifact -Name $sampleName -Lines $lines.ToArray() -Append | Out-Null
            return $hit
        }
    }
    return $null
}

if ($FreezeTrigger -ne 'sleep') {
    $bgStamp = Get-ExDeviceStamp
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-freeze-bg1') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
    Write-ExNote ('HOME at {0}; watching cgroup.freeze for the tobg freeze' -f $bgStamp)
    $maxHalfSeconds = if ($FreezeTrigger -eq 'tobg') { 30 } else { 20 }
    $frozenStamp = & $watchFreeze $maxHalfSeconds
}
if (-not $frozenStamp -and $FreezeTrigger -ne 'tobg') {
    # Escalate to the reliable trigger: the `reason : screen off` freeze (2/2 in the Greezer
    # history for this uid). KEYCODE_SLEEP is the clean sleep key -- no power-button semantics.
    $fzTriggerUsed = 'screen-off'
    $bgStamp = Get-ExDeviceStamp
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-freeze-sleep') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_SLEEP') -AllowFailure | Out-Null
    Write-ExNote ('KEYCODE_SLEEP at {0}; watching for the screen-off freeze' -f $bgStamp)
    $frozenStamp = & $watchFreeze 30
}

$fzReproduced = [bool]$frozenStamp
$fzLatency = $null
if ($fzReproduced) {
    $from = [datetime]::ParseExact((Get-ExSecondStamp $bgStamp), 'MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
    $to = [datetime]::ParseExact((Get-ExSecondStamp $frozenStamp), 'MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
    $fzLatency = [math]::Round((Get-ExSignedDeltaSeconds -From $from -To $to), 1)
    Write-ExNote ('FROZEN at {0} (+{1}s after {2})' -f $frozenStamp, $fzLatency, $fzTriggerUsed)
} else {
    Write-ExNote 'NOT frozen within the watch -- the freeze did not reproduce this round'
}

# ---- 3. frozen window: two real allowlist posts + sampling ---------------------------
$holdStamp1 = $null
$holdStamp2 = $null
if ($fzReproduced) {
    $holdStamp1 = Get-ExDeviceStamp
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-freeze-post-n1') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', ('rearcue-fz-{0}-n1' -f $runTag),
        'freeze_probe_hold_one') -AllowFailure | Out-Null
    Write-ExNote ('frozen post n1 at {0}' -f $holdStamp1)

    $start = Get-Date
    $deadline = $start.AddSeconds($ObserveSeconds)
    $postedSecond = $false
    do {
        Write-ExFreezeSample -Name $sampleName -UidDir $uidDir -PidDir $pidDir -AppPid $appPid -Header $null | Out-Null
        $elapsed = [int]((Get-Date) - $start).TotalSeconds
        if (-not $postedSecond -and $elapsed -ge 10) {
            $holdStamp2 = Get-ExDeviceStamp
            Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-freeze-post-n2') -AllowFailure | Out-Null
            Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', ('rearcue-fz-{0}-n2' -f $runTag),
                'freeze_probe_hold_two') -AllowFailure | Out-Null
            $postedSecond = $true
            Write-ExNote ('frozen post n2 at {0}' -f $holdStamp2)
        }
        Start-Sleep -Seconds $SampleSeconds
    } while ((Get-Date) -lt $deadline)
}

# ---- 4. thaw probes: explicit broadcast first, Activity Start second -----------------
$bcastThawStamp = $null
if ($fzReproduced) {
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-freeze-thaw-bcast') -AllowFailure | Out-Null
    Invoke-ExDebugAction -Action 'STATE' | Out-Null
    Write-ExNote 'explicit debug broadcast sent to the frozen app (STATE); watching for a thaw'
    for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Seconds 1
        $pair = Get-ExCgroupFreeze -UidDir $uidDir -PidDir $pidDir
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add(('{0} {1}' -f (Get-ExDeviceStamp), $pair))
        Write-ExArtifact -Name $sampleName -Lines $lines.ToArray() -Append | Out-Null
        if ($pair -match 'uid=0|pid=0' -and $pair -notmatch 'uid=1|pid=1') {
            $bcastThawStamp = Get-ExDeviceStamp
            break
        }
    }
    if ($bcastThawStamp) { Write-ExNote ('THAWED by the broadcast at {0}' -f $bcastThawStamp) }
    else { Write-ExNote 'no thaw within 12s of the broadcast (the events stay queued)' }
}

# The `screen on` thaw probe (KEYCODE_WAKEUP) -- the real-world "user comes back" trigger.
# On the tobg path the screen is already on and this is a no-op (falls through); on the sleep
# path this is the expected thaw (`THAW ... reason : screen on` in the Greezer corpus).
$wakeThawStamp = $null
$wakeStamp = $null
if ($fzReproduced) {
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-freeze-thaw-wake') -AllowFailure | Out-Null
    $wakeStamp = Get-ExDeviceStamp
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Write-ExNote ('KEYCODE_WAKEUP at {0}; watching for the screen-on thaw' -f $wakeStamp)
    for ($i = 0; $i -lt 8; $i++) {
        Start-Sleep -Seconds 1
        $pair = Get-ExCgroupFreeze -UidDir $uidDir -PidDir $pidDir
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add(('{0} {1} adj=pending' -f (Get-ExDeviceStamp), $pair))
        Write-ExArtifact -Name $sampleName -Lines $lines.ToArray() -Append | Out-Null
        if ($pair -match 'uid=0|pid=0' -and $pair -notmatch 'uid=1|pid=1') {
            $wakeThawStamp = Get-ExDeviceStamp
            Write-ExNote ('THAWED by the wake key at {0}' -f $wakeThawStamp)
            break
        }
    }
    if (-not $wakeThawStamp) { Write-ExNote 'no thaw within 8s of KEYCODE_WAKEUP' }
}

Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-freeze-thaw-activity') -AllowFailure | Out-Null
$actStamp = Get-ExDeviceStamp
Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', $config.MainActivity) -AllowFailure | Out-Null
Write-ExNote ('Activity Start at {0} (the ticket #5 thaw trigger)' -f $actStamp)
Start-Sleep -Seconds 3
for ($i = 0; $i -lt 5; $i++) {
    Write-ExFreezeSample -Name $sampleName -UidDir $uidDir -PidDir $pidDir -Header $null | Out-Null
    Start-Sleep -Seconds 1
}

# ---- 5. visible-skip leg: Dashboard on the rear display -> background again ----------
$rearUp = $false
$rearInfo = Get-RearDisplay -DumpsysDisplay ((Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n")
if ($null -ne $rearInfo) {
    $rearUp = Wait-ExRearOwner -Owner 'dashboard' -DisplayId $rearInfo.DisplayId -TimeoutSec 15
    if (-not $rearUp) {
        # The thaw batch normally re-projects (the held posts arrive as a burst); force it.
        Invoke-ExDebugAction -Action 'PROJECT_REAR' | Out-Null
        $rearUp = Wait-ExRearOwner -Owner 'dashboard' -DisplayId $rearInfo.DisplayId -TimeoutSec 15
    }
}

$onRearStamp = $null
$holdStamp3 = $null
if ($rearUp) {
    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-freeze-rearhome') -AllowFailure | Out-Null
    $onRearStamp = Get-ExDeviceStamp
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
    Write-ExNote ('HOME at {0} with the Dashboard ON the rear display; watching the freezer' -f $onRearStamp)
    $start = Get-Date
    $deadline = $start.AddSeconds($OnRearSeconds)
    $postedOnRear = $false
    do {
        Write-ExFreezeSample -Name $sampleName -UidDir $uidDir -PidDir $pidDir -AppPid $appPid -Header $null | Out-Null
        $elapsed = [int]((Get-Date) - $start).TotalSeconds
        if (-not $postedOnRear -and $elapsed -ge 10) {
            $holdStamp3 = Get-ExDeviceStamp
            Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-freeze-onrear-c2') -AllowFailure | Out-Null
            Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', ('rearcue-fz-{0}-r1' -f $runTag),
                'freeze_probe_on_rear') -AllowFailure | Out-Null
            $postedOnRear = $true
            Write-ExNote ('on-rear post c2 at {0}' -f $holdStamp3)
        }
        Start-Sleep -Seconds $SampleSeconds
    } while ((Get-Date) -lt $deadline)
} else {
    Write-ExNote 'the Dashboard did not reach the rear display (keyguard / channel) -- the'
    Write-ExNote 'visible-skip question is untested this round (FZ-NO-REAR-BASELINE).'
}

# ---- 6. countermeasure facts (no implementation -- raw facts only) -------------------
$cm = New-Object System.Collections.Generic.List[string]
$cm.Add('# countermeasure facts (ticket #29 -- facts only, nothing is implemented)')
$cm.Add(('run-end : {0}' -f (Get-ExDeviceStamp)))
$cm.Add('')
$cm.Add('# fact: MIUI autostart via adb')
$cm.Add(('#   `cmd appops set {0} AUTO_START allow` -> {1}' -f $config.Package, (($autoStartTry -join ' | ').Trim())))
$cm.Add('#   (string op unknown on this build; the numeric MIUIOP(10008)/(10020) appops DO take --')
$cm.Add('#    see freeze-environment.txt -- but they reset on reinstall, ticket #7)')
$cm.Add('')
$cm.Add('# fact: does a deviceidle whitelist membership exist / accept shell? (attempt + revert)')
$wlAdd = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist', ('+{0}' -f $config.Package)) -AllowFailure)
$cm.Add(('whitelist +pkg : {0}' -f (($wlAdd -join ' | ').Trim())))
$wlAfter = @((Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist') -AllowFailure) |
    Where-Object { $_ -match [regex]::Escape($config.Package) })
$cm.Add(('whitelist hits after attempt : {0}' -f $wlAfter.Count))
$wlRemove = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist', ('-{0}' -f $config.Package)) -AllowFailure)
$cm.Add(('whitelist -pkg (revert) : {0}' -f (($wlRemove -join ' | ').Trim())))
$cm.Add('')
$cm.Add('# raw: `cmd appops set AUTO_START allow` output')
foreach ($line in $autoStartTry) { $cm.Add(('  ' + $line)) }
Write-ExArtifact -Name 'freeze-countermeasures.txt' -Lines $cm.ToArray() | Out-Null

$greezerAfter = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'greezer') -AllowFailure)
Write-ExArtifact -Name 'dumpsys-greezer.txt' -Lines (@('', '# dumpsys greezer -- AFTER the run') + $greezerAfter) -Append | Out-Null

# ---- 7. evidence + verdicts ----------------------------------------------------------
$appLog = @(Get-ExLogcat)
$greezeLog = @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all', '-s', 'GreezeManager') -AllowFailure)
Write-ExArtifact -Name 'logcat-greeze.txt' -Lines $greezeLog | Out-Null

$timeline = Get-ExFreezeTimeline -Logcat $appLog
$timelineLines = New-Object System.Collections.Generic.List[string]
$timelineLines.Add('# freeze timeline (ticket #29) -- markers vs app deliveries, ONE device clock')
$timelineLines.Add('')
$timelineLines.Add('## markers')
foreach ($m in $timeline.Markers) { $timelineLines.Add(('  {0}  {1}' -f $m.Time, $m.Id)) }
$timelineLines.Add('')
$timelineLines.Add('## pairs (id -> delivery, delay = delivery - post)')
foreach ($p in $timeline.Pairs) {
    $timelineLines.Add(('  {0}  post={1} delivered={2} delay={3}s' -f $p.Id, $p.PostTime, $p.DeliveryTime, $p.DelaySec))
}
$timelineLines.Add('')
$timelineLines.Add('## unpaired markers (a frozen post that never delivered)')
foreach ($m in $timeline.UnpairedMarkers) { $timelineLines.Add(('  {0}  {1}' -f $m.Time, $m.Id)) }
$timelineLines.Add('')
$timelineLines.Add('## unpaired deliveries (removed lines / other packages -- on purpose)')
foreach ($d in $timeline.UnpairedDeliveries) { $timelineLines.Add(('  {0}  {1} {2}' -f $d.Time, $d.Kind, $d.Package)) }
$timelineLines.Add('')
$timelineLines.Add('## raw RearCue logcat')
foreach ($line in $appLog) { $timelineLines.Add(('  ' + $line)) }
Write-ExArtifact -Name 'freeze-timeline.txt' -Lines $timelineLines.ToArray() | Out-Null
$sampleLines = @(Get-Content -LiteralPath (Join-Path (Get-ExSessionDir) $sampleName) -Encoding UTF8 |
    Where-Object { $_ -notmatch '^#' })
$sampleFacts = Get-ExFreezeSampleFacts -Samples $sampleLines
$greezeEvents = Get-ExGreezeEvents -Logcat $greezeLog
$ourThaw = @($greezeEvents | Where-Object { $_.Kind -eq 'thaw' -and $_.Uid -eq $uid } | Select-Object -First 2)
$ourFreeze = @($greezeEvents | Where-Object { $_.Kind -eq 'freeze' -and $_.Uid -eq $uid })
$skipLines = @($greezeEvents | Where-Object { ($_.Kind -eq 'skip-visible' -or $_.Kind -eq 'subscreen-uid') -and $_.Uid -eq $uid })
$pollution = Get-ExWakePollution -Logcat @(Invoke-Adb -Arguments @('logcat', '-d', '-b', 'all') -AllowFailure)

$pairsWithPrefix = {
    param([string] $IdPrefix)
    @($timeline.Pairs | Where-Object { $_.Id -like ($IdPrefix + '*') })
}
$ctlPairs = @(& $pairsWithPrefix 'ctl-')
$postPairs = @(& $pairsWithPrefix 'post-')
$onrearPairs = @(& $pairsWithPrefix 'onrear-')

# Frozen-window boundaries on ONE clock. The window opens at the first frozen sample
# (freeze-samples.txt, second grain) and closes at the `pc-freeze-thaw-bcast` marker (a
# logcat line written right BEFORE the broadcast probe). A delivery in [open, close) would
# be a live delivery INSIDE the freeze; everything at/after the marker is thaw territory --
# the strict boundary keeps a same-second thaw batch out of the false-positive zone.
$frozenStart = $sampleFacts.FirstFrozenStamp
$markerLine = {
    param([string] $Needle)
    foreach ($line in $appLog) {
        if ($line -match $Needle) { return $line }
    }
    return $null
}
$markerStamp = {
    param([string] $Needle)
    $line = & $markerLine $Needle
    if ($line -and $line -match '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2})') { return $Matches[1] }
    return $null
}
$bcastBoundary = & $markerStamp 'pc-freeze-thaw-bcast'
if (-not $bcastBoundary) { $bcastBoundary = & $markerStamp 'pc-freeze-thaw-activity' }

$deliveredInFrozen = @($timeline.Deliveries | Where-Object {
        $frozenStart -and $bcastBoundary -and
        (Get-ExSecondStamp $_.Time) -ge $frozenStart -and (Get-ExSecondStamp $_.Time) -lt $bcastBoundary
    })

$evt = 'EVT-NOT-APPLICABLE (no freeze reproduced -- "held until thaw" is untestable this round)'
if ($pollution.Count -gt 0) {
    $evt = 'EVT-NOT-APPLICABLE (run voided by external interference)'
} elseif (-not $fzReproduced) {
    $evt = 'EVT-NOT-APPLICABLE (no freeze reproduced -- "held until thaw" is untestable this round)'
} elseif ($ctlPairs.Count -eq 0) {
    $evt = 'EVT-NO-LISTENER (the control post was never delivered: the timeline is unobservable)'
} elseif ($postPairs.Count -lt 2 -and $timeline.UnpairedMarkers.Count -ge 2) {
    $evt = ('EVT-LOST (frozen posts never delivered even after the thaw: unpaired markers {0})' -f
        ((@($timeline.UnpairedMarkers | ForEach-Object { $_.Id })) -join ','))
} elseif ($deliveredInFrozen.Count -gt 0) {
    $evt = ('EVT-DELIVERED-LIVE ({0} delivery line(s) landed INSIDE the frozen window -- read the timeline artifact)' -f $deliveredInFrozen.Count)
} else {
    $delays = @($postPairs | ForEach-Object { $_.DelaySec })
    $evt = ('EVT-HELD-UNTIL-THAW ({0} posts while frozen, 0 delivered live, all delivered after the freeze ended; delay {1}s .. {2}s = delivery - post on the device clock)' -f
        $postPairs.Count, (($delays | Measure-Object -Minimum).Minimum), (($delays | Measure-Object -Maximum).Maximum))
}

$fzWord = 'FZ-NOT-REPRODUCED (cgroup.freeze stayed 0 for 15s after HOME AND no GreezeManager FZ line for our uid)'
if ($pollution.Count -gt 0) {
    $fzWord = 'FZ-RUN-INVALID (external fingerprint/power interference: {0} hit(s) -- see the timeline artifact / summary)' -f $pollution.Count
} elseif ($fzReproduced -or $ourFreeze.Count -gt 0) {
    $fzReason = 'unknown'
    if ($ourFreeze.Count -gt 0) { $fzReason = $ourFreeze[0].Reason }
    # Latency anchor prefers the GreezeManager FZ line (millisecond logcat clock, delta from
    # the bg marker line); the cgroup wire (second grain) is the fallback.
    $fzLatencyText = '{0}s' -f $fzLatency
    if ($ourFreeze.Count -gt 0) {
        $latencyMarker = 'pc-freeze-bg1'
        if ($fzTriggerUsed -eq 'screen-off') { $latencyMarker = 'pc-freeze-sleep' }
        $bgLine = & $markerLine $latencyMarker
        if ($bgLine) {
            $fzFrom = Get-ExLogcatTime -Line $bgLine
            $fzTo = Get-ExLogcatTime -Line $ourFreeze[0].Raw
            if ($null -ne $fzFrom -and $null -ne $fzTo) {
                $fzLatencyText = ('{0}s (GreezeManager FZ line vs bg marker)' -f [math]::Round((Get-ExSignedDeltaSeconds -From $fzFrom -To $fzTo), 1))
            }
        }
    } elseif ($null -ne $fzLatency) {
        $fzLatencyText = ('{0}s (cgroup wire)' -f $fzLatency)
    }
    $anchor = 'cgroup wire + GreezeManager line'
    if (-not $fzReproduced) { $anchor = 'GreezeManager line only (the cgroup wire missed it)' }
    elseif ($ourFreeze.Count -eq 0) { $anchor = 'cgroup wire only (no FZ line captured)' }
    $fzWord = ('FZ-REPRODUCED (trigger={4}: frozen {0} after the trigger; GreezeManager FZ uid={1} reason={2}; anchor: {3})' -f
        $fzLatencyText, $uid, $fzReason, $anchor, $fzTriggerUsed)
}

$thawWord = 'THAW-NOT-APPLICABLE (no freeze to thaw from)'
if ($fzReproduced -and $pollution.Count -eq 0) {
    if ($bcastThawStamp) {
        $thawWord = ('THAW-BY-BROADCAST (cgroup.freeze back to 0 at {0}, within 12s of the explicit broadcast)' -f $bcastThawStamp)
    } else {
        $thawCloser = 'the Activity Start at {0}' -f $actStamp
        if ($wakeThawStamp) { $thawCloser = 'KEYCODE_WAKEUP at {0}' -f $wakeThawStamp }
        $thawWord = ('THAW-NOT-BY-BROADCAST (still frozen 12s after the broadcast; the thaw only came with {0})' -f $thawCloser)
    }
}

$rearWord = 'FZ-NO-REAR-BASELINE (the Dashboard never reached the rear display -- the visible-skip question is untested)'
if (Test-ExKeyguardLocked) {
    $rearWord = 'FZ-NO-REAR-BASELINE (keyguard up after the sleep path -- rear projection is denied while locked; the visible-skip fact stands on 20260923-030402 / -034557)'
    $rearUp = $false
}
if ($rearUp -and $onRearStamp) {
    # Frozen sample points at/after the on-rear HOME stamp (second-grain string order; a run
    # crossing midnight would need the fold -- noted in findings, not handled here).
    $onRearFrozen = @($sampleFacts.FrozenStamps | Where-Object { $_ -ge $onRearStamp })
    if ($onRearFrozen.Count -gt 0) {
        $rearWord = ('FZ-FROZEN-ON-REAR (cgroup.freeze=1 at {0} point(s) with the Dashboard on the rear -- the visible exemption did NOT hold)' -f $onRearFrozen.Count)
    } elseif ($skipLines.Count -gt 0) {
        $rearWord = ('FZ-SKIP-VISIBLE (Dashboard on the rear: cgroup.freeze stayed 0 for {0}s after HOME; GreezeManager skip/subscreen lines {1})' -f
            $OnRearSeconds, $skipLines.Count)
    } else {
        $rearWord = ('FZ-SKIP-VISIBLE (cgroup.freeze stayed 0 for {0}s after HOME with the Dashboard on the rear; no GreezeManager skip line captured in logcat -- check dumpsys-greezer.txt history)' -f $OnRearSeconds)
    }
}

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# freeze-probe (ticket #29: HyperOS GreezeManager freeze -- reproduction, event timeline, countermeasure facts)')
$out.Add('')
$out.Add(('app uid / pid           : {0} / {1}' -f $uid, $appPid))
$out.Add(('HOME at (bg)            : {0}' -f $bgStamp))
$out.Add(('first frozen stamp      : {0}' -f $frozenStamp))
$out.Add(('frozen samples          : points={0} frozen={1} window=({2} .. {3})' -f
        $sampleFacts.Points, $sampleFacts.FrozenPoints, $sampleFacts.FirstFrozenStamp, $sampleFacts.LastFrozenStamp))
$out.Add(('frozen posts paired     : {0} (unpaired markers: {1})' -f $postPairs.Count, $timeline.UnpairedMarkers.Count))
$out.Add(('control pairs           : {0}' -f $ctlPairs.Count))
$out.Add(('on-rear pairs           : {0}' -f $onrearPairs.Count))
$out.Add(('thaw events (ours)      : {0}' -f (($ourThaw | ForEach-Object { ('{0} reason={1}' -f $_.Time, $_.Reason) }) -join ' ; ')))
$out.Add(('freeze events (ours)    : {0}' -f (($ourFreeze | ForEach-Object { ('{0} reason={1}' -f $_.Time, $_.Reason) }) -join ' ; ')))
$out.Add(('skip/subscreen lines    : {0}' -f (($skipLines | ForEach-Object { $_.Raw.Trim() }) -join ' ; ')))
$out.Add(('external pollution      : {0}' -f $pollution.Count))
$out.Add('')
$out.Add('# verdicts')
$out.Add(('fz       : {0}' -f $fzWord))
$out.Add(('events   : {0}' -f $evt))
$out.Add(('thaw     : {0}' -f $thawWord))
$out.Add(('on-rear  : {0}' -f $rearWord))
$out.Add('')
$out.Add('# measurement basis')
$out.Add('#   one clock: post markers (`log -t RearCue pc-freeze-post-n*`) and app delivery lines')
$out.Add('#   are both logcat lines on the device clock; the marker is written just before each')
$out.Add('#   `cmd notification post`, so every delay is an upper bound by one adb round trip.')
$out.Add('#   the frozen window is the cgroup.freeze sample wire (freeze-samples.txt); GreezeManager')
$out.Add('#   FZ/THAW lines and `dumpsys greezer` history are the corroborating system evidence.')
Write-ExArtifact -Name 'freeze-probe.txt' -Lines $out.ToArray() | Out-Null
foreach ($line in $out) { if ($line) { Write-ExNote $line } }

# ---- 8. teardown ---------------------------------------------------------------------
Invoke-ExDebugAction -Action 'CANCEL_TEST' | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' } | Out-Null
# The sleep path may have brought the keyguard up; dismiss is best-effort (a secure lock needs
# the human -- same boundary as every lock round in this repo) and the state is recorded.
if (Test-ExKeyguardLocked) {
    $dismissed = Invoke-ExKeyguardDismiss
    Write-ExNote ('keyguard after the run: locked; dismiss attempt ok={0}' -f $dismissed)
} else {
    Write-ExNote 'keyguard after the run: not locked'
}

# ---- 9. scenario notes ---------------------------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (GreezeManager freeze probe, ticket #29)')
$notes.Add('')
$notes.Add('- **What is frozen**: the whole app uid (GreezeManager `FZ uid=`), measured at the')
$notes.Add('  `cgroup.freeze` files under `/sys/fs/cgroup/apps/uid_*/` (uid level + pid level).')
$notes.Add('- **Timeline basis (one clock)**: `pc-freeze-post-n*` markers go into logcat via `log`')
$notes.Add('  just BEFORE each `cmd notification post`; app `posted` lines are logcat lines on the')
$notes.Add('  same device clock. Delay = delivery stamp - marker stamp (upper bound by one adb trip).')
$notes.Add('- **No KEYCODE_POWER on purpose**: the tobg freeze reproduces with the screen on and the')
$notes.Add('  phone unlocked, and a locked phone cannot be unlocked over adb (secure keyguard) --')
$notes.Add('  locking here would leave the device locked for every parallel ticket. The screen-off')
$notes.Add('  freeze (`reason =screen off`) is documented from prior sessions, not re-measured.')
$notes.Add('- **The app process is NOT force-stopped**: the control leg doubles as the listener')
$notes.Add('  liveness check, and a force-stop would drag the MIUI rebind dance into a freezer probe.')
$notes.Add('- **Only `posted com.android.shell` lines pair with markers**: the automation posts from')
$notes.Add('  com.android.shell (an Allowlist App); `removed` lines and other packages are reported')
$notes.Add('  as unpaired on purpose (shell cannot cancel its own notifications -- `cmd notification`')
$notes.Add('  has no cancel subcommand on this build, see freeze-environment.txt).')
$notes.Add('- **Notification tags are unique per run on purpose**: a `cmd notification post` re-using')
$notes.Add('  an existing tag is a KEY UPDATE and fires no onNotificationPosted (ticket #3), and a')
$notes.Add('  post that lands while the listener is mid-rebind (9-13s) arrives via the initial')
$notes.Add('  snapshot with no event line either -- both traps are documented in the errata of')
$notes.Add('  20260923-030402 / -032045 / -033533 / -033746 and both are now designed around.')
$notes.Add('- **Deviation from the ticket wording (honest)**: the frozen-window posts cannot include')
$notes.Add('  a removal event (no shell-side cancel exists), so the "posted/removed burst on thaw"')
$notes.Add('  shape is corroborated by the ticket #5 raw (`docs/poc-logs/ticket5-e7-evidence.txt`),')
$notes.Add('  while THIS run measures the per-post hold delay with explicit stamps.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Fz     = $fzWord
    Events = $evt
    Thaw   = $thawWord
    OnRear = $rearWord
}
