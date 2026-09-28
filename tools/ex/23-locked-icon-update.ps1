# 23-locked-icon-update.ps1 -- ticket #6 follow-up: does the rear Dashboard Icon Set keep
# updating while the phone is FULLY LOCKED (main screen off, keyguard up)?
#
# The report (2026-09-28, owner): on a fully locked phone the icons on the black rear screen
# still look stale -- e.g. a Feishu message arrives and the rear screen keeps showing the old
# Icon Set. This driver turns that report into a repeatable loop and splits the failure into
# legs, so a bad run lands on exactly one word:
#
#   LOCKED-PASS         listener fired + Icon Set gained the app + rear repaint observed
#   LOCKED-NO-EVENT     no `posted <pkg>` at all                    -> callback never arrived
#                                                                     (process frozen / delayed)
#   LOCKED-NO-ICONSET   callback arrived but the Icon Set did not gain the app
#   LOCKED-CORE-ONLY    Icon Set moved but the rear pixels did not  -> repaint leg is broken
#   LOCKED-NO-REAR      the rear screen was not ours at probe time (owner != dashboard)
#   LOCKED-CANCEL-FAIL  the "clear the fixture first" step did not clear -> probe not comparable
#
# Method: each probe starts from a known 1-app baseline (fixture notifications cancelled), takes
# a "before" frame of the rear screen, fires ONE notification (fixture or a real Feishu message),
# waits, then takes an "after" frame and the app log. The icon band is compared pixel-wise, so
# "the log says it updated but the screen did not" is a distinguishable outcome.
#
# Driving:
#   * lock        `input keyevent KEYCODE_POWER` (screen off == keyguard locked on this device);
#                 the rear screen stays on through Wake Keep-alive (spec 0003 / ticket #21)
#   * fixture     `cmd notification post -t RearCue <tag> <text>` (a NEW tag = a new key)
#   * feishu      real message through the bot (`lark-cli ... im +messages-send`, --SkipFeishu skips)
#   * rear pixels `exec-out screencap -d <SurfaceFlinger id>` (the logical id 1 is rejected here)
#   * freeze      `dumpsys greezer` history for our uid, dumped next to every probe
#
# Run:  powershell -ExecutionPolicy Bypass -File tools\ex\23-locked-icon-update.ps1
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [string] $Serial,
    [int] $UnlockedProbes = 2,
    [int] $LockedProbes = 5,
    [int] $SettleSeconds = 8,
    [switch] $SkipFeishu,
    [switch] $NoRestore
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }
Add-Type -AssemblyName System.Drawing

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'locked-icon-update' -Serial $Serial | Out-Null }
$session = Get-ExSessionDir
$script:adb = Resolve-ExAdb
$script:dev = Get-ExDevice
$script:sfId = $null
$script:uid = $null
$script:results = New-Object System.Collections.ArrayList

function Adb {
    # `Adb @('a','b')` passes ONE array argument in PowerShell; flatten before handing to the exe
    # (the same trap that made `adb unknown command install -r ...` in the spec 0013 driver).
    param([Parameter(ValueFromRemainingArguments = $true)][object[]] $Args)
    $flat = @()
    foreach ($a in $Args) { if ($a -is [System.Array]) { $flat += $a } else { $flat += $a } }
    return (& $script:adb -s $script:dev @flat)
}
function Sh { param([string] $Cmd) return ((Adb shell $Cmd) -join "`n") }
function Note { param([string] $Text) Write-Host $Text; Write-ExNote $Text | Out-Null }

function Get-RearShot {
    param([Parameter(Mandatory, Position = 0)][string] $Name)
    $path = Join-Path (Get-ExSessionDir) $Name
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    cmd /c "`"$($script:adb)`" -s $script:dev exec-out screencap -d $script:sfId -p > `"$path`"" | Out-Null
    if (-not (Test-Path -LiteralPath $path)) { throw ('rear screencap failed: {0}' -f $Name) }
    return $path
}

function Get-IconSetFromLog {
    param([string[]] $Lines)
    $line = @($Lines | Where-Object { $_ -match 'iconSet' }) | Select-Object -Last 1
    if (-not $line) { return @() }
    $groups = [regex]::Matches([string]$line, '\[([^\[\]]*)\]')
    if ($groups.Count -eq 0) { return @() }
    $inner = $groups[$groups.Count - 1].Groups[1].Value.Trim()
    if (-not $inner) { return @() }
    return @($inner -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Get-IconSetNow { return (Get-IconSetFromLog @(Get-ExLogcat)) }

function Get-AppFreezeState {
    <# PID-level cgroup.freeze of the app process (0 = running, 1 = frozen by GreezeManager).
       The uid directory name is looked up from the pid; burning in a uid would rot on reinstall. #>
    $out = Sh "p=`$(pidof $($config.Package)); d=`$(ls -d /sys/fs/cgroup/apps/uid_*/pid_`$p 2>/dev/null | head -1); cat `$d/cgroup.freeze 2>/dev/null"
    return (($out -join '').Trim())
}

function Get-TapDiff {
    <# Fraction of sampled pixels that differ between two rear frames. -1 when a frame is missing. #>
    param([string] $Before, [string] $After, [int] $Step = 8, [int] $Tolerance = 24)
    if (-not (Test-Path -LiteralPath $Before) -or -not (Test-Path -LiteralPath $After)) { return -1 }
    $a = [System.Drawing.Bitmap]::FromFile($Before)
    $b = [System.Drawing.Bitmap]::FromFile($After)
    if ($a.Width -ne $b.Width -or $a.Height -ne $b.Height) { $a.Dispose(); $b.Dispose(); return 1.0 }
    $changed = 0; $sampled = 0
    for ($x = 0; $x -lt $a.Width; $x += $Step) {
        for ($y = 0; $y -lt $a.Height; $y += $Step) {
            $ca = $a.GetPixel($x, $y); $cb = $b.GetPixel($x, $y)
            $delta = [math]::Abs($ca.R - $cb.R) + [math]::Abs($ca.G - $cb.G) + [math]::Abs($ca.B - $cb.B)
            if ($delta -gt $Tolerance) { $changed++ }
            $sampled++
        }
    }
    $a.Dispose(); $b.Dispose()
    return [math]::Round($changed / $sampled, 4)
}

function Send-FeishuProbe {
    <# Real Feishu message to the owner through the bot: that is the app the report is about. #>
    param([Parameter(Mandatory, Position = 0)][string] $Text)
    $env:LARKSUITE_CLI_NO_UPDATE_NOTIFIER = '1'
    $out = & lark-cli --profile cli_aa8fc94bcf381bb7 im +messages-send --as bot `
        --user-id ou_8c7b69bb80f740d1ebf4fa665163d0bc --text $Text --format json 2>&1
    $text = ($out -join "`n")
    return $text
}

function Invoke-Probe {
    param(
        [Parameter(Mandatory, Position = 0)][string] $Id,
        [Parameter(Mandatory, Position = 1)][string] $Kind   # fixture | feishu
    )
    $pkg = if ($Kind -eq 'feishu') { 'com.ss.android.lark' } else { 'com.android.shell' }
    $prefix = if ($Id -like 'u*') { 'CTRL' } else { 'LOCKED' }

    # 1) known baseline: clear the probe app's notifications first (icon set back to 1 app)
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' } | Out-Null
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.ss.android.lark' } | Out-Null
    Start-Sleep -Seconds 2
    $baseSet = Get-IconSetNow
    $cancelOk = -not ($baseSet -contains $pkg)

    Clear-ExLogcat
    $before = Get-RearShot ('{0}-before.png' -f $Id)
    $ownerBefore = Get-ExRearOwnerNow

    # 2) the probe
    $probeOut = ''
    if ($Kind -eq 'feishu') {
        $probeOut = Send-FeishuProbe ('[RearCue locked-screen probe {0}] rear icon update test' -f $Id)
        Write-ExArtifact -Name ('{0}-feishu.json' -f $Id) -Lines @($probeOut)
    } else {
        Adb shell ('cmd notification post -t RearCue {0} "RearCue locked icon probe {0}"' -f $Id) | Out-Null
    }

    Start-Sleep -Seconds $SettleSeconds
    $after = Get-RearShot ('{0}-after.png' -f $Id)
    $ownerAfter = Get-ExRearOwnerNow
    $log = @(Get-ExLogcat)
    Write-ExArtifact -Name ('{0}.logcat' -f $Id) -Lines $log

    # 3) facts (each leg separately, so the verdict names the broken leg)
    $posted = (@($log | Where-Object { $_ -match ('posted ' + [regex]::Escape($pkg)) }).Count -gt 0)
    $iconset = Get-IconSetFromLog $log
    $inSet = $iconset -contains $pkg
    $diff = Get-TapDiff -Before $before -After $after
    $appPid = Get-ExAppPid                       # $pid is a read-only automatic variable in PowerShell
    $wake = Get-ExWakefulness
    $locked = Test-ExKeyguardLocked
    $freeze = Get-AppFreezeState
    $loop = ((Sh "ps -A -o ARGS | grep -c '[r]earcue-wake-loop'") -join '').Trim()
    $nudges = (@($log | Where-Object { $_ -match 'wake-keep-alive thaw nudges=' }).Count)
    $thawLog = (@($log | Where-Object { $_ -match 'thaw-nudge start' }).Count)
    $greezer = Sh 'dumpsys greezer'
    Write-ExArtifact -Name ('{0}-state.txt' -f $Id) -Lines @(
        ('probe      : {0} ({1})' -f $Id, $Kind),
        ('time       : {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')),
        ('app pid    : {0} ; keep-alive loop procs: {1}' -f $appPid, $loop),
        ('wakefulness: {0} ; keyguard locked: {1}' -f $wake, $locked),
        ('freeze     : {0} (pid-level cgroup.freeze at probe end)' -f $freeze),
        ('rear owner : before={0} after={1}' -f $ownerBefore, $ownerAfter),
        ('icon set   : before-cancel=[{0}] after=[{1}]' -f ($baseSet -join ', '), ($iconset -join ', ')),
        ('posted line: {0}' -f $posted),
        ('tap diff   : {0}' -f $diff),
        ('thaw nudge : loop-log={0} activity-log={1}' -f $nudges, $thawLog),
        ('cancel ok  : {0}' -f $cancelOk),
        '',
        '## greezer',
        $greezer
    )

    $verdict = if (-not $cancelOk) { "$prefix-CANCEL-FAIL" }
        elseif ($ownerAfter -ne 'dashboard') { "$prefix-NO-REAR" }
        elseif (-not $posted) { "$prefix-NO-EVENT" }
        elseif (-not $inSet) { "$prefix-NO-ICONSET" }
        elseif ($diff -ge 0 -and $diff -lt 0.005) { "$prefix-CORE-ONLY" }
        else { "$prefix-PASS" }

    $row = [pscustomobject]@{
        Probe = $Id; Kind = $Kind; Locked = $locked; Wake = $wake
        Owner = $ownerAfter; Posted = $posted; InSet = $inSet; Diff = $diff; Verdict = $verdict
    }
    [void]$script:results.Add($row)
    Note ('  [{0}] {1} kind={2} locked={3} owner={4} posted={5} inSet={6} diff={7} feishu={8}' -f `
        $verdict, $Id, $Kind, $locked, $ownerAfter, $posted, $inSet, $diff, ($probeOut -match '"ok": true|"ok":true'))
    return $row
}

# ---------- 0. preflight ----------
Note '== 0 preflight'
if (-not (Test-ExListenerEnabled)) { throw 'notification listener is not enabled' }
# uid via `cmd package list packages -U` -- `dumpsys package | grep userId=` also matches other
# lines and once produced uid=999 here, which silently pointed every freeze read at a dead path.
$uidLine = Sh ('cmd package list packages -U ' + $config.Package)
if ($uidLine -match 'uid:(\d+)') { $script:uid = $Matches[1] } else { $script:uid = 'unknown' }
$disp = Sh 'dumpsys display'
if ($disp -match 'displayId\s+1,[^\r\n]*?uniqueId\s+"local:(\d+)"') { $script:sfId = $Matches[1] }
if (-not $script:sfId) { throw 'rear display SurfaceFlinger id not found' }
Note ('  sfId={0} uid={1} pid={2} wake={3}' -f $script:sfId, $script:uid, (Get-ExAppPid), (Get-ExWakefulness))

# Charge animation off: the animated water would move pixels on its own and blur the tap diff.
Adb shell 'am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.CHARGING_ENABLED --ez enabled false' | Out-Null
Set-ExScreenAwake
Adb shell 'am start -n com.rearcue.poc/.ui.MainActivity' | Out-Null
Start-Sleep -Seconds 2
Invoke-ExDebugAction -Action 'CANCEL_TEST' | Out-Null

# bring the Dashboard on the rear screen (auto-cast on the first notification)
Adb shell 'cmd notification post -t RearCue rcwarmup "RearCue locked icon probe warmup"' | Out-Null
if (-not (Wait-ExRearOwner -Owner 'dashboard' -TimeoutSec 20)) { Note '  warn: rear owner never became dashboard' }
Start-Sleep -Seconds 2

# ---------- 1. control probes (screen on, unlocked) ----------
Note '== 1 control probes (unlocked)'
for ($i = 1; $i -le $UnlockedProbes; $i++) { Invoke-Probe -Id ('u{0}' -f $i) -Kind 'fixture' | Out-Null }

# ---------- 2. lock ----------
Note '== 2 lock the phone (main screen off)'
Adb shell 'input keyevent KEYCODE_POWER' | Out-Null
$deadline = (Get-Date).AddSeconds(20)
do { Start-Sleep -Milliseconds 500 } while ((Get-ExWakefulness) -eq 'Awake' -and (Get-Date) -lt $deadline)
Note ('  wakefulness={0} keyguardLocked={1} rearOwner={2}' -f (Get-ExWakefulness), (Test-ExKeyguardLocked), (Get-ExRearOwnerNow))
$lockWait = (Get-Date).AddSeconds(25)
do { Start-Sleep -Seconds 1 } while ((Get-ExRearOwnerNow) -ne 'dashboard' -and (Get-Date) -lt $lockWait)
Note ('  after lock settle: rearOwner={0} keyguardLocked={1}' -f (Get-ExRearOwnerNow), (Test-ExKeyguardLocked))

# ---------- 3. locked probes ----------
Note '== 3 locked probes'
for ($i = 1; $i -le $LockedProbes; $i++) {
    $kind = if ((-not $SkipFeishu) -and ($i % 2 -eq 0)) { 'feishu' } else { 'fixture' }
    Invoke-Probe -Id ('l{0}' -f $i) -Kind $kind | Out-Null
}

# ---------- 4. summary + restore ----------
$summary = @(
    '# locked-icon-update summary',
    ('session   : {0}' -f $session),
    ('device    : {0}' -f $script:dev),
    ('lock step : wakefulness={0} keyguardLocked={1} rearOwner={2}' -f (Get-ExWakefulness), (Test-ExKeyguardLocked), (Get-ExRearOwnerNow)),
    '',
    ($script:results | Format-Table -AutoSize | Out-String)
)
Write-ExArtifact -Name 'locked-icon-summary.txt' -Lines $summary

if (-not $NoRestore) {
    Note '== 4 restore'
    Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' } | Out-Null
    Adb shell 'am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.CHARGING_ENABLED --ez enabled true' | Out-Null
    $unlocked = Unlock-ExScreen
    Note ('  unlock attempted, unlocked={0} keyguardLocked={1}' -f $unlocked, (Test-ExKeyguardLocked))
}

Write-Host ''
Write-Host ('summary -> {0}' -f (Join-Path $session 'locked-icon-summary.txt'))
$script:results | Format-Table -AutoSize | Out-String | Write-Host
