# drive-persist-reboot.ps1 -- one-off spec 0007 reboot persistence leg (issue #50, review item 4).
#
# The round 20260926 never ran this leg: the device dropped off USB at 22:47 mid-review. The
# re-verification protocol agreed on #50:
#   settings page -> privacy=false / auto-dismiss=7000ms (unreachable before the review fix)
#                    / charging master switch=off
#   -> adb reboot -> after boot: first-read lines `feed-settings privacy=false
#      autoDismiss=7000ms` + `charging-anim=false` + the STATE echo.
#
# Driving is the SAME UI path as tasks 21/22 (the settings activity is not exported): gear tap
# -> uiautomator dump -> row taps / unit chip / numeric field + `input text`. Every change is
# judged from the app's own log lines + the STATE debug echo, never from a tap exit code.
#
# Round 20260927-012838 lesson (first attempt of this leg): `adb shell input text` APPENDED to
# the field (10 -> 107 -> 1077) -- the select-all the app does on focus loses to the tap's own
# cursor placement, so typing cannot assume the old value is replaced. The fix here: after the
# focus tap, force KEYCODE_MOVE_END, then KEYCODE_DEL the old digits away, THEN type -- append
# onto an empty field is the desired overwrite. The charging toggle, in turn, was lost while
# the page sat in a scrolled/focused intermediate state: this script scrolls to the BOTTOM
# before hunting it and archives the dump when it still is not there.
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param([string] $Serial)

$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'persist-reboot' -Serial $Serial | Out-Null
Clear-ExLogcat

# ---- labels / constants (strings.xml, same escapes as 21/22) ------------------------------
$privacyLabel = 'Privacy Mode'                                      # ASCII in every locale
$autoDismissDesc = 'Auto-dismiss value'                             # settings_autodismiss_value_cd
$unitSecond = [regex]::Unescape('\u79D2')                           # settings_unit_second
$unlimitedLabel = [regex]::Unescape('\u65E0\u4E0A\u9650')           # settings_autodismiss_unlimited
$chargeLabel = [regex]::Unescape('\u5145\u7535\u52A8\u753B')        # settings_charging_switch
$unlimitedMs = 9223372036854775807L                                 # AutoDismissPolicy.UNLIMITED_MS
$targetPrivacy = $false
$targetDismiss = 7000L
$targetCharging = $false

# ---- drive functions (21/22 lineage; typing hardened per 20260927-012838) ------------------
function Find-ExSettingsNode {
    <# 20260927-013731 lesson: every node hunt starts by making sure the settings page is
      REALLY the front activity -- a stale `mInputShown` cost us one whole round to a blind
      BACK. Cheap check (top activity of display 0), reopen when it drifted. #>
    param([string] $Text, [string] $ContentDesc, [int] $Passes = 3)
    if (-not (Test-ExSettingsPageOpen)) {
        Write-ExNote 'settings page drifted to background mid-run; re-opening it'
        if (-not (Open-ExSettingsPage)) {
            Write-ExNote 'settings page would not come back; node hunt cannot continue'
            return $null
        }
    }
    if (-not $Text -and -not $ContentDesc) { throw 'Find-ExSettingsNode needs -Text or -ContentDesc' }
    for ($pass = 0; $pass -lt $Passes; $pass++) {
        $dump = Get-ExWindowDump
        $node = if ($ContentDesc) {
            Get-ExNodeCenter -WindowDump $dump -ContentDesc $ContentDesc
        } else {
            Get-ExNodeCenter -WindowDump $dump -Text $Text
        }
        if ($null -ne $node) { return $node }
        if ($pass -eq 0) {
            Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '2300', '550', '700', '350') -AllowFailure | Out-Null
        } elseif ($pass -eq 1) {
            Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '700', '550', '2300', '350') -AllowFailure | Out-Null
            Start-Sleep -Milliseconds 400
            Invoke-Adb -Arguments @('shell', 'input', 'swipe', '550', '700', '550', '2300', '350') -AllowFailure | Out-Null
        }
        Start-Sleep -Milliseconds 900
    }
    return $null
}

function Close-ExIme {
    <# Hide the soft keyboard when it is REALLY up -- and after the BACK, verify the settings
      page survived it (20260927-013731: a stale `mInputShown=true` cost the page itself; the
      BACK landed on the settings activity and dismissed it). Re-open when it drifted. #>
    $dump = (@(Invoke-Adb -Arguments @('shell', 'dumpsys', 'input_method') -AllowFailure) -join "`n")
    if ($dump -match 'mInputShown=true' -or $dump -match 'mIsInputViewShown=true') {
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 700
        if (-not (Test-ExSettingsPageOpen)) {
            Write-ExNote 'IME BACK took the settings page with it; re-opening'
            $null = Open-ExSettingsPage
        }
        return $true
    }
    return $false
}

function Set-ExFeedPrivacy {
    <# Baseline counts the TARGET line only (same polarity), never all `privacy=` lines. #>
    param([bool] $Enabled)
    $state = Get-ExAppStateNow
    if (($null -ne $state) -and ($state.FeedPrivacyMode -eq $Enabled)) { return $true }
    $node = Find-ExSettingsNode -Text $privacyLabel
    if ($null -eq $node) {
        Write-ExNote 'privacy row label not found on the settings page'
        return $false
    }
    $want = if ($Enabled) { 'feed-settings page privacy=true' } else { 'feed-settings page privacy=false' }
    $before = Get-ExLogMatchCount ([regex]::Escape($want))
    Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$node.X, [string]$node.Y) -AllowFailure | Out-Null
    $hit = Wait-ExNewLog -Pattern $want -Before $before -TimeoutSec 8
    $after = Get-ExAppStateNow
    return ((@($hit).Count -gt 0) -and ($null -ne $after) -and ($after.FeedPrivacyMode -eq $Enabled))
}

function Set-ExFeedAutoDismiss {
    <# leave unlimited row -> unit chip -> focus + CLEAR (MOVE_END + DELs) + one `input text`
      -> close IME; judged by the `page autoDismiss=<n>ms` line + STATE.

      20260927-012838: the app select-alls on focus, but the SAME tap that focuses the field
      places the caret afterwards -- the selection loses, and `input text` APPENDED (10 -> 107).
      Do not depend on the selection: move the caret to the end, delete every old digit (8
      key presses cover any value the field can show), then commit the new digits. #>
    param([long] $TargetMs)
    $want = ('feed-settings page autoDismiss={0}ms' -f $TargetMs)
    $wantPattern = [regex]::Escape($want)
    $before = Get-ExLogMatchCount $wantPattern
    $entry = Get-ExAppStateNow
    if (($null -ne $entry) -and ($entry.FeedAutoDismissMs -eq $TargetMs)) {
        Write-ExNote ('auto-dismiss already at {0}ms (no typing needed)' -f $TargetMs)
        return $true
    }
    if (($null -ne $entry) -and ($entry.FeedAutoDismissMs -ge $unlimitedMs)) {
        $node = Find-ExSettingsNode -Text $unlimitedLabel
        if ($null -eq $node) { Write-ExNote 'unlimited row not found on the settings page'; return $false }
        Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$node.X, [string]$node.Y) -AllowFailure | Out-Null
        $left = $false
        $deadline = (Get-Date).AddSeconds(10)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 600
            $entry = Get-ExAppStateNow
            if (($null -ne $entry) -and ($entry.FeedAutoDismissMs -lt $unlimitedMs)) { $left = $true; break }
        }
        if (-not $left) { Write-ExNote 'still on the unlimited row (value field stays disabled)'; return $false }
        Start-Sleep -Milliseconds 500
    }
    if (($TargetMs % 3600000) -eq 0) {
        $unitLabel = [regex]::Unescape('\u65F6'); $amount = [string][long]($TargetMs / 3600000)
    } elseif (($TargetMs % 60000) -eq 0) {
        $unitLabel = [regex]::Unescape('\u5206'); $amount = [string][long]($TargetMs / 60000)
    } else {
        $unitLabel = $unitSecond; $amount = [string][long]($TargetMs / 1000)
    }
    $unitNode = Find-ExSettingsNode -Text $unitLabel
    if ($null -eq $unitNode) {
        Write-ExNote ('unit chip "{0}" not found on the settings page' -f $unitLabel)
        return $false
    }
    Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$unitNode.X, [string]$unitNode.Y) -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 700
    $typed = $false
    $state = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $fieldNode = Find-ExSettingsNode -ContentDesc $autoDismissDesc
        if ($null -eq $fieldNode) {
            Write-ExNote 'auto-dismiss value field not found on the settings page'
            return $false
        }
        Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$fieldNode.X, [string]$fieldNode.Y) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 700
        # caret to the end, old digits away, then the new digits (append-safe overwrite)
        Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END') -AllowFailure | Out-Null
        for ($i = 0; $i -lt 8; $i++) {
            Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_DEL') -AllowFailure | Out-Null
        }
        Start-Sleep -Milliseconds 300
        Invoke-Adb -Arguments @('shell', 'input', 'text', $amount) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 900
        $hit = Wait-ExNewLog -Pattern $wantPattern -Before $before -TimeoutSec 6
        $state = Get-ExAppStateNow
        if ((@($hit).Count -gt 0) -and ($null -ne $state) -and ($state.FeedAutoDismissMs -eq $TargetMs)) {
            $typed = $true
            break
        }
        Write-ExNote ('auto-dismiss typing attempt {0} missed (state now {1})' -f $attempt,
            $(if ($state) { $state.FeedAutoDismissMs } else { 'n/a' }))
    }
    Close-ExIme | Out-Null
    if (-not $typed) {
        $state = Get-ExAppStateNow
        Write-ExNote ('auto-dismiss did not reach {0}ms (now {1})' -f $TargetMs,
            $(if ($state) { $state.FeedAutoDismissMs } else { 'n/a' }))
        return $false
    }
    return $true
}

function Set-ExChargingEnabled {
    <# Toggle the master switch. The card sits at the page BOTTOM, so scroll down first (three
      full swipes, probe 20260927-013449 proved the row reachable that way), then hunt with
      fresh dumps. Judged by STATE + the `charging-anim=<v>` line, target polarity baseline. #>
    param([bool] $Enabled)
    $state = Get-ExAppStateNow
    if (($null -ne $state) -and ($state.ChargingEnabled -eq $Enabled)) {
        Write-ExNote ('charging switch already {0} (no tap needed)' -f $Enabled)
        return $true
    }
    Close-ExIme | Out-Null
    $want = if ($Enabled) { 'charging-anim=true' } else { 'charging-anim=false' }
    $before = Get-ExLogMatchCount ([regex]::Escape($want))
    for ($i = 0; $i -lt 3; $i++) {
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', '900', '2400', '900', '700', '500') -AllowFailure | Out-Null
        Start-Sleep -Seconds 1
    }
    $toggle = $null
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $dump = Get-ExWindowDump
        $toggle = Get-ExUiToggleForLabel -WindowDump $dump -Label $chargeLabel
        if ($toggle.Found) { break }
        Write-ExArtifact -Name ('charging-hunt-dump-{0}.xml' -f $attempt) -Lines @($dump) | Out-Null
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', '900', '2400', '900', '700', '500') -AllowFailure | Out-Null
        Start-Sleep -Seconds 1
    }
    if (-not $toggle.Found) {
        Write-ExNote 'charging switch not found on the settings page'
        return $false
    }
    Invoke-Adb -Arguments @('shell', 'input', 'tap', [string][int]$toggle.X, [string][int]$toggle.Y) -AllowFailure | Out-Null
    $hit = Wait-ExNewLog -Pattern ([regex]::Escape($want)) -Before $before -TimeoutSec 8
    $after = Get-ExAppStateNow
    return ((@($hit).Count -gt 0) -and ($null -ne $after) -and ($after.ChargingEnabled -eq $Enabled))
}

# ---- phase 1: set the three values on the settings page ------------------------------------
# Order: charging FIRST (needs the page scrolled to its bottom), then the top rows.
Write-ExNote 'phase 1: settings charging switch=off / privacy=false / autoDismiss=7000ms'
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
Start-ExApp -TimeoutSec 30 | Out-Null
Start-Sleep -Seconds 1
if (-not (Open-ExSettingsPage)) { throw 'settings page never came up (phase 1)' }
$c = Set-ExChargingEnabled -Enabled $targetCharging
$p = Set-ExFeedPrivacy -Enabled $targetPrivacy
$a = Set-ExFeedAutoDismiss -TargetMs $targetDismiss
$state1 = Get-ExAppStateNow
$setPass = $p -and $a -and $c -and ($null -ne $state1) -and
    ($state1.FeedPrivacyMode -eq $targetPrivacy) -and ($state1.FeedAutoDismissMs -eq $targetDismiss) -and
    ($state1.ChargingEnabled -eq $targetCharging)
Add-ExStep -Step 'persist-set' -Pass $setPass `
    -Note ('charging switch: {0}; privacy row: {1}; autoDismiss=7000ms typed: {2}; STATE privacy={3} autoDismiss={4}ms charging={5}' -f
        $c, $p, $a,
        $(if ($state1) { $state1.FeedPrivacyMode } else { 'n/a' }),
        $(if ($state1) { $state1.FeedAutoDismissMs } else { 'n/a' }),
        $(if ($state1) { $state1.ChargingEnabled } else { 'n/a' }))
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
Start-Sleep -Milliseconds 800
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
$appLog1 = @(Get-ExLogcat)
Write-ExArtifact -Name 'phase1-set-logcat.txt' -Lines $appLog1 | Out-Null
if (-not $setPass) { throw 'phase 1 failed: the three settings did not land (see phase1-set-logcat.txt)' }

# ---- phase 2: adb reboot -> first-read verification ----------------------------------------
Write-ExNote 'phase 2: adb reboot'
Invoke-Adb -Arguments @('reboot') -AllowFailure | Out-Null
Start-Sleep -Seconds 8
$null = Invoke-Adb -Arguments @('wait-for-device') -AllowFailure
$bootDone = $false
$deadline = (Get-Date).AddSeconds(240)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $v = ((Invoke-Adb -Arguments @('shell', 'getprop', 'sys.boot_completed') -AllowFailure) -join '').Trim()
    if ($v -eq '1') { $bootDone = $true; break }
}
Write-ExNote ('boot completed: {0}' -f $bootDone)
if (-not $bootDone) { throw 'device did not report sys.boot_completed=1 within 240s' }
Start-Sleep -Seconds 8   # let the boot settle before touching the UI

# Boot-time snapshot BEFORE we start the app ourselves: if HyperOS auto-started the process,
# its boot-time first-read lines are the most honest evidence of "survived the reboot".
$bootLog = @(Get-ExLogcat)
Write-ExArtifact -Name 'phase2-boot-logcat.txt' -Lines $bootLog | Out-Null
$bootFeed = @($bootLog | Where-Object { $_ -match 'feed-settings privacy=false autoDismiss=7000ms' }).Count
$bootChg = @($bootLog | Where-Object { $_ -match 'charging-anim=false' }).Count
Write-ExNote ('boot-time auto-start first-read lines: feed-settings={0} charging-anim={1}' -f $bootFeed, $bootChg)

# Controlled process start (same method as the #55 persist-restart leg): a fresh process
# first-reads the store and logs both lines; Start-ExApp also self-heals the MIUI rebind.
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null
$started = Start-ExApp -TimeoutSec 30
Start-Sleep -Seconds 2
$afterLog = @(Get-ExLogcat)
Write-ExArtifact -Name 'phase2-firstread-logcat.txt' -Lines $afterLog | Out-Null
$hitFeed = @($afterLog | Where-Object { $_ -match 'feed-settings privacy=false autoDismiss=7000ms' })
$hitChg = @($afterLog | Where-Object { $_ -match 'charging-anim=false' })
$state2 = Get-ExAppStateNow
$rebootPass = $started -and ($hitFeed.Count -gt 0) -and ($hitChg.Count -gt 0) -and ($null -ne $state2) -and
    ($state2.FeedPrivacyMode -eq $targetPrivacy) -and ($state2.FeedAutoDismissMs -eq $targetDismiss) -and
    ($state2.ChargingEnabled -eq $targetCharging)
Add-ExStep -Step 'persist-reboot' -Pass $rebootPass `
    -Note ('boot_completed: {0}; first-read `feed-settings privacy=false autoDismiss=7000ms`: {1} lines; first-read `charging-anim=false`: {2} lines; STATE privacy={3} autoDismiss={4}ms charging={5}' -f
        $bootDone, $hitFeed.Count, $hitChg.Count,
        $(if ($state2) { $state2.FeedPrivacyMode } else { 'n/a' }),
        $(if ($state2) { $state2.FeedAutoDismissMs } else { 'n/a' }),
        $(if ($state2) { $state2.ChargingEnabled } else { 'n/a' }))

# ---- phase 3: restore so the 21/22 re-runs start clean --------------------------------------
Write-ExNote 'phase 3: restore (charging on, privacy/auto-dismiss to defaults, DND off, notifications cleared)'
$restored = $false
if (Open-ExSettingsPage) {
    $r1 = Set-ExChargingEnabled -Enabled $true
    $r2 = Set-ExFeedPrivacy -Enabled $true
    $r3 = Set-ExFeedAutoDismiss -TargetMs 10000
    $restored = $r1 -and $r2 -and $r3
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 800
}
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') -AllowFailure | Out-Null
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'set_dnd', 'all') -AllowFailure | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_TEST' | Out-Null
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' } | Out-Null
$state3 = Get-ExAppStateNow
Write-ExNote ('restore check: {0}; state privacy={1} autoDismiss={2}ms charging={3}' -f $restored,
    $(if ($state3) { $state3.FeedPrivacyMode } else { 'n/a' }),
    $(if ($state3) { $state3.FeedAutoDismissMs } else { 'n/a' }),
    $(if ($state3) { $state3.ChargingEnabled } else { 'n/a' }))

# ---- summary --------------------------------------------------------------------------------
$overall = if ($setPass -and $rebootPass) { 'PERSIST-PASS' } else { 'PERSIST-FAIL' }
$out = New-Object System.Collections.Generic.List[string]
$out.Add('# persist-reboot (spec 0007 / issue #50 review item 4: settings survive adb reboot)')
$out.Add('protocol            : settings charging=off + privacy=false + autoDismiss=7000ms -> STATE verify -> adb reboot -> boot wait -> first-read lines + STATE verify -> restore')
$out.Add(('notices driving     : same UI path as tasks 21/22 (gear tap -> uiautomator dump -> row tap / unit chip / value field: MOVE_END + DELs + input text)'))
$out.Add('boot-time auto-start: first-read lines present in the boot snapshot BEFORE our own app start (see phase2-boot-logcat.txt)')
$out.Add('# verdicts')
foreach ($line in (Format-ExStepVerdictLines)) { $out.Add($line) }
$out.Add(('overall             : {0}' -f $overall))
Write-ExArtifact -Name 'persist-reboot.txt' -Lines $out.ToArray() | Out-Null
foreach ($line in $out) { Write-ExNote $line }

$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (persist-reboot, spec 0007 / issue #50 review item 4)')
$notes.Add('')
$notes.Add('- The leg the 20260926 acceptance round could not run (device physically dropped off USB')
$notes.Add('  at 22:47). Values chosen per the agreed re-verification protocol: privacy=false,')
$notes.Add('  auto-dismiss=7000ms (a value the pre-review tier ladder could NOT reach -- proves the')
$notes.Add('  range-complete numeric input actually persists), charging master switch=off.')
$notes.Add('- The drive functions are the 21/22 lineage with two hardenings learned in the first')
$notes.Add('  attempt (20260927-012838): (a) the value field is overwritten via KEYCODE_MOVE_END +')
$notes.Add('  8x KEYCODE_DEL + `input text` -- the focus select-all loses to the tap caret placement,')
$notes.Add('  so plain typing APPENDED (10 -> 107 -> 1077); (b) the charging toggle is hunted only')
$notes.Add('  after scrolling to the page bottom, with the failing dumps archived. Every change is')
$notes.Add('  judged from the app''s own log lines + the STATE debug echo.')
$notes.Add('- First-read evidence comes in two layers: the boot-time snapshot (phase2-boot-logcat.txt,')
$notes.Add('  before any PC-side app start) and the controlled Start-ExApp first read')
$notes.Add('  (phase2-firstread-logcat.txt). The verdict uses the controlled start -- the same method')
$notes.Add('  the #55 persist-restart leg used -- with the boot snapshot as corroboration.')
$notes.Add('- Phase 3 restores charging=on / privacy=true / autoDismiss=10000ms so the follow-up')
$notes.Add('  21/22 re-runs start from documented defaults.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

Write-ExNote ('persist-reboot finished; artifacts in {0}' -f (Get-ExSessionDir))
if ($overall -eq 'PERSIST-PASS') { exit 0 } else { exit 1 }
