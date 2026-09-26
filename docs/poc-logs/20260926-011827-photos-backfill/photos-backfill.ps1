# photos-backfill.ps1 -- backfill the pending on-device photo evidence (secure-lock backlog):
#   ticket #25: four main-screen states (empty / actions-disabled / iconset / actions-enabled)
#   ticket #28: usability banner states (denied / in-doubt / restored-gone) + jump-button tap
#               proof (JUMP-PASS triad after the tap) + jump-button ui bounds (>=48dp) +
#               full main-window dump for the "banner does not cover Icon Set / key status" check
#
# Runs over adb TCP (phone on WiFi 192.168.50.252; USB kept out of the drain path).
# Button needle comes from needle.txt (UTF-8) so this source stays ASCII-only
# (house rule: BOM-less .ps1 is decoded as ANSI/GBK by Windows PowerShell 5.1).
#
# Device-state hygiene: restores MIUIOP(10008)+MIUIOP(10053) to double-allow and
# screen_off_timeout to 60000 (the pre-backlog original left at 600000 by an earlier session,
# findings line in "wait-for-fix" state).
#
# Run:  powershell -ExecutionPolicy Bypass -File .\photos-backfill.ps1
#   or: pwsh -File .\photos-backfill.ps1

[CmdletBinding()]
param(
    [string] $Serial = '192.168.50.252:5555',
    [string] $Dir = ''
)
# $PSScriptRoot is empty inside a param() default under powershell -File (PS 5.1); resolve in body.
if (-not $Dir) { $Dir = $PSScriptRoot }

# Continue (house style): adb stderr ("Warning: Activity not started...") must not kill the run;
# critical checks throw explicitly instead.
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe'
if (Test-Path env:REARCUE_ADB) { $adb = $env:REARCUE_ADB }
$pkg = 'com.rearcue.poc'
$shots = Join-Path $Dir 'screenshots'
New-Item -ItemType Directory -Force -Path $shots | Out-Null
$transcript = Join-Path $Dir 'transcript.txt'

function Log([string]$msg) {
    $line = '{0}  {1}' -f (Get-Date).ToString('HH:mm:ss'), $msg
    Add-Content -Path $transcript -Value $line -Encoding UTF8
    Write-Host $line
}

function Adb {
    # plain $args capture: keeps dash-prefixed adb tokens (-n/-a/...) out of parameter binding
    $AdbArgs = @($args)
    $out = & $adb -s $Serial @AdbArgs 2>&1 | ForEach-Object { "$_" }
    Log ("$ adb {0}" -f ($AdbArgs -join ' '))
    return $out
}

function Save([string[]]$lines, [string]$name) {
    $path = Join-Path $Dir $name
    [System.IO.File]::WriteAllLines($path, [string[]]$lines, [System.Text.UTF8Encoding]::new($false))
    Log ("saved {0}" -f $name)
}

function Wake {
    Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    Adb shell wm dismiss-keyguard | Out-Null
    Start-Sleep -Milliseconds 800
}

function Shot([string]$name) {
    Wake
    Adb shell screencap -p /sdcard/rc-shot.png | Out-Null
    & $adb -s $Serial pull /sdcard/rc-shot.png (Join-Path $shots $name) | Out-Null
    Log ("shot screenshots/{0}" -f $name)
}

function Dump([string]$name) {
    Adb shell uiautomator dump /sdcard/rc-ui.xml | Out-Null
    & $adb -s $Serial pull /sdcard/rc-ui.xml (Join-Path $Dir $name) | Out-Null
    Log ("dump {0}" -f $name)
}

function Broadcast([string]$action, [string]$extra = '') {
    Adb shell am broadcast -n "$pkg/.DebugCommandReceiver" -a $action $extra | Out-Null
}

function ScrollTop {
    Adb shell input swipe 600 800 600 2200 300 | Out-Null
    Start-Sleep -Seconds 1
}

function ScrollBottom {
    Adb shell input swipe 600 2200 600 800 300 | Out-Null
    Start-Sleep -Seconds 1
}

# Force ON_RESUME (the autostart recheck trigger): HOME then relaunch.
function Reopen {
    Adb shell input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Seconds 1
    Adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null
    Start-Sleep -Seconds 2
}

function AppOpsSnapshot([string]$name) {
    Save (Adb shell appops get $pkg) $name
}

function LcSlice([string]$name) {
    Save (Adb logcat -d -s RearCue) $name
}

$m = [System.Threading.Mutex]::new($false, 'Global\RearCueDevice')
try {
    try { $m.WaitOne() | Out-Null } catch [System.Threading.AbandonedMutexException] { }

    "photos-backfill started: $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))" | Out-File $transcript -Encoding utf8
    Log ("serial: {0}" -f $Serial)
    Save (Adb shell dumpsys battery) 'battery-at-start.txt'
    AppOpsSnapshot 'appops-at-start.txt'

    # keep the main screen on for the whole capture window
    $oldTimeout = (Adb shell settings get system screen_off_timeout | Out-String).Trim()
    Log ("screen_off_timeout before: {0}" -f $oldTimeout)
    Adb shell settings put system screen_off_timeout 600000 | Out-Null

    Adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null
    Start-Sleep -Seconds 2

    # postTestNotification() silently skips when POST_NOTIFICATIONS is denied (first run hit
    # exactly that: Icon Set stayed empty). Grant via adb so the icon-set shots carry icons.
    Adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS | Out-Null
    Log 'granted POST_NOTIFICATIONS (device-state fix, see erratum)'

    # ---- phase A: ticket #25 four main-screen states (same protocol as 20260923-030129 evidence2) ----
    Log 'phase A start: ticket #25 states'
    Broadcast com.rearcue.poc.action.CANCEL_PACKAGE '--es pkg com.android.shell'
    Broadcast com.rearcue.poc.action.CANCEL_PACKAGE "--es pkg $pkg"
    Start-Sleep -Seconds 3

    ScrollTop
    Shot '05-main-empty-portrait.png'
    Dump 'ui-05-empty.xml'

    ScrollBottom
    Shot '06-main-actions-disabled-portrait.png'
    Dump 'ui-06-actions-disabled.xml'

    Broadcast com.rearcue.poc.action.POST_TEST
    Start-Sleep -Seconds 2
    ScrollTop
    Shot '07-main-iconset-portrait.png'

    Broadcast com.rearcue.poc.action.PROJECT_REAR
    Start-Sleep -Seconds 3
    ScrollBottom
    Shot '08-main-actions-enabled-portrait.png'
    Dump 'ui-08-actions-enabled.xml'

    Broadcast com.rearcue.poc.action.EXIT_REAR
    Start-Sleep -Seconds 2
    Log 'phase A done'

    # ---- phase B: ticket #28 banner states + tap proof ----
    Log 'phase B start: usability banner'
    Adb logcat -c | Out-Null
    Broadcast com.rearcue.poc.action.POST_TEST
    Start-Sleep -Seconds 2

    # B1 baseline: double allow -> banner hidden, Icon Set populated
    Adb shell appops set $pkg 10008 allow | Out-Null
    Adb shell appops set $pkg 10053 allow | Out-Null
    Reopen
    ScrollTop
    Shot '09-banner-none-baseline.png'
    Dump 'ui-09-banner-none.xml'
    AppOpsSnapshot 'appops-b1-granted.txt'
    LcSlice 'chain-b1-logcat.txt'

    # B2 double ignore -> AUTOSTART_DENIED banner appears
    Adb shell appops set $pkg 10008 ignore | Out-Null
    Adb shell appops set $pkg 10053 ignore | Out-Null
    Reopen
    ScrollTop
    Shot '10-banner-denied.png'
    Dump 'ui-10-banner-denied.xml'
    AppOpsSnapshot 'appops-b2-denied.txt'
    LcSlice 'chain-b2-logcat.txt'

    # B2b tap proof: locate the jump button in ui-10 (needle from needle.txt) and tap it.
    # The needle hits the inner TextView; the touch target is the nearest clickable ancestor
    # (Compose ActionButton, heightIn(min = RearCueTouch.minTarget) = 48dp) -- measure that.
    $needle = (Get-Content (Join-Path $Dir 'needle.txt') -Encoding UTF8 -Raw).Trim()
    [xml]$ui = Get-Content (Join-Path $Dir 'ui-10-banner-denied.xml') -Encoding UTF8
    $node = $ui.SelectNodes('//node') | Where-Object { $_.text -eq $needle } | Select-Object -First 1
    if (-not $node) { throw "jump button not found in ui-10-banner-denied.xml (needle: $needle)" }
    $textNode = $node
    while ($node -and $node.clickable -ne 'true') { $node = $node.ParentNode }
    if (-not $node -or -not $node.bounds) { throw 'no clickable ancestor for the jump button label' }
    $bounds = $node.bounds
    if ($bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $l = [int]$Matches[1]; $t = [int]$Matches[2]; $r = [int]$Matches[3]; $b = [int]$Matches[4]
        $cx = [int](($l + $r) / 2); $cy = [int](($t + $b) / 2)
        $density = 520.0
        $d = (Adb shell wm density | Out-String)
        if ($d -match '(\d+)') { $density = [double]$Matches[1] }
        $scale = $density / 160.0
        Save @(
            ('node text : {0}' -f $needle),
            ('text node : {0} bounds {1}' -f $textNode.class, $textNode.bounds),
            ('touch node: {0} (nearest clickable ancestor)' -f $node.class),
            ('bounds    : {0}' -f $bounds),
            ('px size   : {0}x{1}' -f ($r - $l), ($b - $t)),
            ('dp size   : {0:N1}x{1:N1}  (density {2} dpi => 1dp={3:N3}px)' -f (($r - $l) / $scale), (($b - $t) / $scale), $density, $scale),
            ('min-target: RearCueTouch.minTarget = 48dp => {0}px' -f [int](48 * $scale)),
            ('verdict   : {0}' -f $(if ((($r - $l) / $scale) -ge 48 -and (($b - $t) / $scale) -ge 48) { 'BOUNDS-OK >=48dp' } else { 'BOUNDS-TOO-SMALL' }))
        ) 'jump-button-bounds.txt'
        Log ("tapping jump button at ({0},{1}) bounds {2}" -f $cx, $cy, $bounds)
        Adb shell input tap $cx $cy | Out-Null
        Start-Sleep -Seconds 3
        Save (Adb shell dumpsys activity activities | Select-String 'topResumedActivity') 'jump-top-resumed.txt'
        Save (Adb shell dumpsys window | Select-String 'mCurrentFocus|mFocusedApp') 'jump-window-focus.txt'
        Dump 'ui-11-autostart-page.xml'
        Shot '11-autostart-page.png'
        LcSlice 'chain-b3-after-tap-logcat.txt'
    }

    # B2c back -> ON_RESUME recheck
    Adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Seconds 3
    Shot '12-banner-after-back.png'
    Dump 'ui-12-banner-after-back.xml'
    LcSlice 'chain-b4-after-back-logcat.txt'

    # B3 split ops -> AUTOSTART_IN_DOUBT (degraded form)
    Adb shell appops set $pkg 10008 allow | Out-Null
    Reopen
    ScrollTop
    Shot '13-banner-indoubt.png'
    Dump 'ui-13-banner-indoubt.xml'
    AppOpsSnapshot 'appops-b5-indoubt.txt'
    LcSlice 'chain-b5-logcat.txt'

    # B4 restore double allow -> banner gone
    Adb shell appops set $pkg 10053 allow | Out-Null
    Reopen
    ScrollTop
    Shot '14-banner-gone-restored.png'
    Dump 'ui-14-banner-gone.xml'
    AppOpsSnapshot 'appops-b6-restored.txt'
    LcSlice 'chain-b6-logcat.txt'

    # ---- cleanup / device-state hygiene ----
    Broadcast com.rearcue.poc.action.CANCEL_TEST
    Start-Sleep -Seconds 2
    Adb shell settings put system screen_off_timeout 60000 | Out-Null
    Log 'screen_off_timeout restored to 60000 (pre-backlog original)'
    AppOpsSnapshot 'appops-at-end.txt'
    Save (Adb shell dumpsys battery) 'battery-at-end.txt'
    Adb shell rm -f /sdcard/rc-shot.png /sdcard/rc-ui.xml | Out-Null
    Log 'photos-backfill done'
}
finally {
    try { $m.ReleaseMutex() | Out-Null } catch { }
    $m.Dispose()
}
