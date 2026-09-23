# 16-autostart-probe.ps1 -- ticket #27: MIUI autostart whitelist probe (detection + jump entry).
#
# Run through the one-command entry point (`ex.ps1 -Task autostart-probe` = this -> collect), or alone:
#   .\tools\ex\16-autostart-probe.ps1                # surfaces + jump candidates + toggle diff + restore
#   .\tools\ex\16-autostart-probe.ps1 -NoToggle      # read-only: surfaces + jump candidates only
#
# The two fact questions (ticket #27):
#
#   Q1 can the MIUI autostart whitelist state be detected?
#      Phase 3 flips the app's own switch on the settings page and diffs `appops get`:
#        DETECT-TRACKS-SWITCH      MIUIOP modes move with the switch (the detection mouth)
#        DETECT-NO-TRACK           the switch flipped but no MIUIOP mode changed
#        DETECT-SWITCH-UNFLIPPED   the tap did not move the switch (run inconclusive)
#        DETECT-SWITCH-UNREACHABLE the row was not found (keyguard up / not installed / renamed)
#
#   Q2 what is the exact jump entry (component/action)?
#      One verdict per candidate (action / component / negative-control):
#        JUMP-PASS        the autostart activity is among the resumed activities
#        JUMP-NO-TASK     the component/action does not resolve (negative-control must land here)
#        JUMP-WRONG-PAGE  something started, but not the autostart activity
#        JUMP-NO-EFFECT   no start and no error line
#
# Every verdict reads device facts (appops modes, resumed activity, switch `checked`), never a
# command exit code. The switch is ALWAYS flipped back afterwards: RESTORED / RESTORE-FAILED
# records how that went. Manual repro is the phase order below (also in scenario-notes.md).
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [string] $Package = 'com.rearcue.poc',
    [string] $Label = 'RearCue',
    [string] $AutostartAction = 'miui.intent.action.OP_AUTO_START',
    [string] $AutostartCategory = 'android.intent.category.DEFAULT',
    [string] $AutostartActivity = 'com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity',
    [string] $AutostartProvider = 'content://com.lbe.security.miui.autostartmgr',
    [int] $WaitSeconds = 3,
    [int] $SearchScrolls = 24,
    [switch] $NoToggle,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'autostart-probe' -Serial $Serial | Out-Null }

$keyguardLocked = Test-ExKeyguardLocked
Write-ExNote ('keyguard locked: {0} (the toggle phase needs the phone unlocked)' -f $keyguardLocked)

# Screen geometry for list scrolling, read at run time (never hardcoded device numbers).
$wmSize = Invoke-Adb -Arguments @('shell', 'wm', 'size') -AllowFailure
$screenW = 1080
$screenH = 2000
if ((($wmSize -join "`n") -match '(\d+)x(\d+)')) {
    $screenW = [int]$Matches[1]
    $screenH = [int]$Matches[2]
}
$midX = [string][int]($screenW / 2)
$highY = [string][int]($screenH * 0.28)
$lowY = [string][int]($screenH * 0.75)

function Invoke-ExListScroll {
    # finger down = toward the top of the list, finger up = toward the bottom.
    param([bool] $TowardTop)
    if ($TowardTop) {
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', $script:midX, $script:highY, $script:midX, $script:lowY, '300') -AllowFailure | Out-Null
    } else {
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', $script:midX, $script:lowY, $script:midX, $script:highY, '300') -AllowFailure | Out-Null
    }
}

function Find-ExAutostartRow {
    <#
      Locate the app's row wherever the list currently is. Rows RESHUFFLE between the allow/block
      sections the moment any switch flips, so the scan is deterministic: fling to the top of the
      list, then walk down one half-viewport at a time (overlap keeps rows from slipping by).
    #>
    param([string] $Wanted, [int] $MaxSteps)
    foreach ($i in 1..4) { Invoke-ExListScroll -TowardTop $true }
    Start-Sleep -Seconds 1
    for ($step = 0; $step -le $MaxSteps; $step++) {
        $dump = Get-ExWindowDump
        $row = Get-ExUiSwitchRow -WindowDump $dump -Label $Wanted
        if ($row.Found) { return $row }
        Invoke-ExListScroll -TowardTop $false
        Start-Sleep -Milliseconds 800
    }
    return $null
}

# ---- 1. detection surfaces (read-only) --------------------------------------------------
$appopsBefore = Invoke-Adb -Arguments @('shell', 'appops', 'get', $Package) -AllowFailure
$opsBefore = Get-ExMiuiOpFacts -AppopsText $appopsBefore
$namedOp = Invoke-Adb -Arguments @('shell', 'appops', 'get', $Package, 'AUTO_START') -AllowFailure
$op10008 = Invoke-Adb -Arguments @('shell', 'appops', 'get', $Package, '10008') -AllowFailure
$op10053 = Invoke-Adb -Arguments @('shell', 'appops', 'get', $Package, '10053') -AllowFailure
$providerRoot = Invoke-Adb -Arguments @('shell', 'content', 'query', '--uri', $AutostartProvider) -AllowFailure
$providerTable = Invoke-Adb -Arguments @('shell', 'content', 'query', '--uri', ($AutostartProvider + '/autostart')) -AllowFailure

Write-ExArtifact -Name 'autostart-surfaces.txt' -Lines (@(
        ('# detection surfaces for {0} (ticket #27)' -f $Package)
        ''
        '# named op (the AUTO_START name does not exist on this build)'
    ) + $namedOp + @(
        ''
        '# per-op reads (the detection mouth: MIUIOP 10008 / 10053)'
    ) + $op10008 + $op10053 + @(
        ''
        '# full `appops get` (before the toggle)'
    ) + $appopsBefore + @(
        ''
        ('# content query {0}' -f $AutostartProvider)
    ) + $providerRoot + @(
        ('# content query {0}/autostart' -f $AutostartProvider)
    ) + $providerTable) | Out-Null

# ---- 2. jump candidates (Q2) ------------------------------------------------------------
# The negative control is a component that cannot resolve: its error shape is what JUMP-NO-TASK
# keys on, and a real sample keeps the classifier honest (never invent the failure text).
$candidates = @(
    @{ Name = 'action'; Command = @('shell', 'am', 'start', '-a', $AutostartAction, '-c', $AutostartCategory) },
    @{ Name = 'component'; Command = @('shell', 'am', 'start', '-n', $AutostartActivity) },
    @{ Name = 'negative-control'; Command = @('shell', 'am', 'start', '-n', ($AutostartActivity + 'Missing')) }
)

$jumpLines = New-Object System.Collections.Generic.List[string]
$jumpLines.Add('# jump candidates (Q2: exact entry to the MIUI autostart settings page)')
$jumpLines.Add('')
$openerCommand = $candidates[0].Command
foreach ($candidate in $candidates) {
    $amOutput = Invoke-Adb -Arguments $candidate.Command -AllowFailure
    Start-Sleep -Seconds $WaitSeconds
    $activities = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure
    $windowDump = Get-ExWindowDump
    $facts = Get-ExAutostartJumpFacts -AmOutput $amOutput -DumpsysActivities $activities
    $page = Get-ExAutostartPageFacts -WindowDump $windowDump
    $word = if ($facts.NoTask) { 'JUMP-NO-TASK' }
    elseif ($facts.OnAutostartPage) { 'JUMP-PASS' }
    elseif ($facts.Started) { 'JUMP-WRONG-PAGE' }
    else { 'JUMP-NO-EFFECT' }

    $jumpLines.Add(('## candidate {0}' -f $candidate.Name))
    $jumpLines.Add(('command           : adb {0}' -f ($candidate.Command -join ' ')))
    foreach ($line in $amOutput) { $jumpLines.Add(('am                : {0}' -f $line)) }
    $jumpLines.Add(('started           : {0}' -f $facts.Started))
    $jumpLines.Add(('no-task           : {0}' -f $facts.NoTask))
    $jumpLines.Add(('error-text        : {0}' -f $facts.ErrorText))
    $jumpLines.Add(('resumed-component : {0}' -f $facts.ResumedComponent))
    $jumpLines.Add(('on-autostart-page : {0} (ui page={1} allowed={2} blocked={3})' -f $facts.OnAutostartPage, $page.IsAutostartPage, $page.AllowedCount, $page.BlockedCount))
    $jumpLines.Add(('verdict           : {0}' -f $word))
    $jumpLines.Add('')
    Write-ExNote ('jump {0}: {1} (resumed={2})' -f $candidate.Name, $word, $facts.ResumedComponent)
    if ($word -eq 'JUMP-PASS') { $openerCommand = $candidate.Command }
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', '4') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
}

# ---- 3. toggle diff (Q1) -----------------------------------------------------------------
$detectWord = 'DETECT-SWITCH-UNREACHABLE'
$restoreWord = 'not-attempted'
$diffLines = New-Object System.Collections.Generic.List[string]
$diffLines.Add('# toggle diff (Q1: does the whitelist state show up in `appops get`?)')
$diffLines.Add('')
$diffLines.Add(('switch-before      : (filled below, row of "{0}")' -f $Label))

if ($NoToggle) {
    $detectWord = 'not-attempted (-NoToggle)'
    $diffLines.Add('toggle             : skipped (-NoToggle: read-only run)')
} elseif ($keyguardLocked) {
    # the settings page sits behind the keyguard and a secure lock cannot be dismissed over adb:
    # do not fling swipes at the lock screen, just say so (rerun with the phone unlocked).
    $detectWord = 'DETECT-SWITCH-UNREACHABLE (keyguard locked: rerun unlocked)'
    $diffLines.Add('toggle             : skipped (keyguard locked)')
} else {
    Invoke-Adb -Arguments $openerCommand -AllowFailure | Out-Null
    Start-Sleep -Seconds $WaitSeconds
    $row = Find-ExAutostartRow -Wanted $Label -MaxSteps $SearchScrolls
    if ($null -eq $row) {
        Write-ExNote ('row "{0}" not found after {1} steps (keyguard up? app not installed? label changed?)' -f $Label, $SearchScrolls)
        $diffLines.Add(('row-found          : False after {0} scan steps' -f $SearchScrolls))
    } else {
        Write-ExArtifact -Name 'ui-toggle-before.xml' -Lines @(Get-ExWindowDump) | Out-Null
        $diffLines[2] = ('switch-before      : checked={0} tap=({1},{2})' -f $row.Checked, $row.X, $row.Y)
        # input tap wants integer pixels; the row center can land on .5 (bounds average).
        Invoke-Adb -Arguments @('shell', 'input', 'tap', [string][int]$row.X, [string][int]$row.Y) -AllowFailure | Out-Null
        Start-Sleep -Seconds $WaitSeconds
        Write-ExArtifact -Name 'ui-toggle-after.xml' -Lines @(Get-ExWindowDump) | Out-Null
        $rowAfter = Find-ExAutostartRow -Wanted $Label -MaxSteps $SearchScrolls
        $appopsAfter = Invoke-Adb -Arguments @('shell', 'appops', 'get', $Package) -AllowFailure
        $opsAfter = Get-ExMiuiOpFacts -AppopsText $appopsAfter
        # plain assignment on purpose: Compare-ExMiuiOpFacts follows the `,$arr` return
        # convention, and `@()` around it would count a non-empty result as ONE nested array.
        $changed = Compare-ExMiuiOpFacts -Before $opsBefore -After $opsAfter

        $flipped = ($null -ne $rowAfter -and $rowAfter.Checked -ne $row.Checked)
        $detectWord = if ($null -eq $rowAfter) { 'DETECT-SWITCH-UNFLIPPED (row gone after tap: state not confirmed)' }
        elseif (-not $flipped) { 'DETECT-SWITCH-UNFLIPPED' }
        elseif ($changed.Count -gt 0) { 'DETECT-TRACKS-SWITCH' }
        else { 'DETECT-NO-TRACK' }

        $diffLines.Add(('switch-after       : checked={0} (row re-found={1})' -f $(if ($rowAfter) { $rowAfter.Checked } else { 'unknown' }), ($null -ne $rowAfter)))
        $diffLines.Add(('changed miui ops   : {0}' -f $(if ($changed.Count -gt 0) { (($changed | ForEach-Object { ('{0} {1}->{2}' -f $_.Op, $_.Before, $_.After) }) -join '; ') } else { '(none)' })))
        foreach ($line in $appopsAfter) { $diffLines.Add(('appops-after       : {0}' -f $line)) }

        # ---- restore: flip the switch back and verify both the UI state and the op modes ----
        $rowRestore = Find-ExAutostartRow -Wanted $Label -MaxSteps $SearchScrolls
        if ($null -ne $rowRestore -and $rowRestore.Checked -ne $row.Checked) {
            Invoke-Adb -Arguments @('shell', 'input', 'tap', [string][int]$rowRestore.X, [string][int]$rowRestore.Y) -AllowFailure | Out-Null
            Start-Sleep -Seconds $WaitSeconds
        }
        Write-ExArtifact -Name 'ui-toggle-restored.xml' -Lines @(Get-ExWindowDump) | Out-Null
        $rowFinal = Find-ExAutostartRow -Wanted $Label -MaxSteps $SearchScrolls
        $appopsFinal = Invoke-Adb -Arguments @('shell', 'appops', 'get', $Package) -AllowFailure
        $opsFinal = Get-ExMiuiOpFacts -AppopsText $appopsFinal
        $leftover = Compare-ExMiuiOpFacts -Before $opsBefore -After $opsFinal
        $uiRestored = ($null -ne $rowFinal -and $rowFinal.Checked -eq $row.Checked)
        $restoreWord = if ($uiRestored -and $leftover.Count -eq 0) { 'RESTORED' }
        elseif ($uiRestored) { 'RESTORE-FAILED (ui back, op modes differ: {0})' -f (($leftover | ForEach-Object { ('{0} {1}->{2}' -f $_.Op, $_.Before, $_.After) }) -join '; ') }
        else { 'RESTORE-FAILED (switch state not back)' }
        $diffLines.Add(('switch-restored    : {0}' -f $uiRestored))
        $diffLines.Add(('op-modes-restored  : {0} (leftover diffs: {1})' -f ($leftover.Count -eq 0), $leftover.Count))
        $diffLines.Add(('restore verdict    : {0}' -f $restoreWord))
    }
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', '4') -AllowFailure | Out-Null
}

# ---- 4. artifacts + verdicts -------------------------------------------------------------
$out = New-Object System.Collections.Generic.List[string]
$out.Add('# autostart-probe (ticket #27)')
$out.Add(('package            : {0} (label "{1}")' -f $Package, $Label))
$out.Add(('keyguard-locked    : {0}' -f $keyguardLocked))
$out.Add(('screen             : {0}x{1}' -f $screenW, $screenH))
$out.Add(('autostart action   : {0} (+ category {1})' -f $AutostartAction, $AutostartCategory))
$out.Add(('autostart component: {0}' -f $AutostartActivity))
$out.Add(('autostart provider : {0}' -f $AutostartProvider))
$out.Add('')
foreach ($line in $diffLines) { $out.Add($line) }
$out.Add('')
foreach ($line in $jumpLines) { $out.Add($line) }
$out.Add('')
$out.Add('# verdicts')
$out.Add(('detect             : {0}' -f $detectWord))
$out.Add(('restore            : {0}' -f $restoreWord))
Write-ExArtifact -Name 'autostart-probe.txt' -Lines $out.ToArray() | Out-Null

foreach ($line in ($out | Where-Object { $_ -match '^(detect|restore|verdict) ' })) { Write-ExNote $line }
foreach ($line in ($jumpLines | Where-Object { $_ -match '^verdict ' })) { Write-ExNote $line }

# ---- 5. scenario notes (summary.md embeds these) -------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (MIUI autostart probe, ticket #27)')
$notes.Add('')
$notes.Add('- **Manual repro** (same order the run performs):')
$notes.Add('  1. surfaces: `adb shell appops get <pkg>` (+ `AUTO_START` / `10008` / `10053` single-op reads) and')
$notes.Add('     `adb shell content query --uri content://com.lbe.security.miui.autostartmgr[/autostart]`;')
$notes.Add('  2. jump: `adb shell am start -a miui.intent.action.OP_AUTO_START -c android.intent.category.DEFAULT`')
$notes.Add('     and `adb shell am start -n com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`')
$notes.Add('     (the negative control appends `Missing` to the class), each followed by')
$notes.Add('     `adb shell dumpsys activity activities` (topResumedActivity is the verdict source) and a')
$notes.Add('     `uiautomator dump` page check (resource-id `auto_start_list` + section header counts);')
$notes.Add('  3. toggle: on the settings page find the app row (title text = the app label), read its')
$notes.Add('     `com.miui.securitycenter:id/sliding_button` `checked` state, tap it once, re-read')
$notes.Add('     `appops get` and diff the MIUIOP modes, then tap again to restore and verify.')
$notes.Add('- **Detection mouth and its boundary**: the verdict proves the mapping at SHELL level')
$notes.Add('  (`appops get` / `appops set`), which is the same AppOpsService an app talks to. The in-app')
$notes.Add('  call (`AppOpsManager.checkOpNoThrow(10008|10053, myUid, pkg)`) is NOT exercised here --')
$notes.Add('  this is a probe-only ticket and product code stays frozen. If the in-app read cannot see')
$notes.Add('  the numeric MIUI ops, the spec 0004 degrade applies: manual jump button + explicit copy,')
$notes.Add('  never a fabricated "healthy" state.')
$notes.Add('- **What is ruled out** (all device facts, see autostart-surfaces.txt): the named appop')
$notes.Add('  `AUTO_START` does not exist on this build (Unknown operation string); the LBE autostart')
$notes.Add('  provider answers plain queries with zero rows and its read/write permission is')
$notes.Add('  signature|privileged, so no third-party detection path lives there.')
$notes.Add('- **Op-mode divergence is possible** (and observed): `appops set` writes the same modes the')
$notes.Add('  UI writes, so a manual grant can desync a single op from the settings switch (ticket #21')
$notes.Add('  did exactly that with 10008). Read BOTH 10008 and 10053; a claim needs both to agree.')
$notes.Add('- **Restore duty**: the switch is always flipped back (RESTORED / RESTORE-FAILED above).')
$notes.Add('  Emergency restore without the UI: `adb shell appops set <pkg> 10008 allow|ignore` and the')
$notes.Add('  same for 10053 (the two modes the toggle writes).')
$notes.Add('- **MIUI may auto-adjust the whitelist**: the page itself says the system can optimize')
$notes.Add('  autostart of rarely-used apps, so the state is not guaranteed stable between runs.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

Write-ExNote ('autostart probe done: detect={0} restore={1}' -f $detectWord, $restoreWord)
return @{ Detect = $detectWord; Restore = $restoreWord; Session = (Get-ExSessionDir) }
