# 12-wake-cost.ps1 -- ticket #21: what does the Wake Keep-alive actually cost (heat + drain)?
#
# Two locked-state legs on the same phone, ONE session and ONE lock (the keyguard on this phone
# is a secure lock -- fingerprint/PIN -- so the run locks ONCE and measures both legs under it;
# unlocking again is the human`s step at the end):
#   keep  first:  one allowlist notification -> Dashboard on the rear + the app`s own Wake
#                 Keep-alive at -WakeIntervalMs (the state this feature buys)
#   idle  second: notifications cleared -> ExitDashboard -> nothing projected, plain locked
#                 phone (the "phone just lying there" baseline)
#
# Per leg, before/after the duty window: `dumpsys battery` facts (Get-ExBatteryFacts) + the
# `dumpsys batterystats` power excerpt (Get-ExPowerEstimateLines). Provenance is labeled, never
# blurred (house rule: estimates must say they are estimates):
#   * heat  = battery temperature delta -- MEASURED, valid on charger too (the charger's own
#             warmth is common to both legs; the keep-minus-idle delta isolates the keep-alive)
#   * drain = charge counter delta -- MEASURED, meaningful only when nothing is powering the
#             load. A USB-powered phone shows no drift by physics, so when AcPowered/UsbPowered/
#             WirelessPowered is true the script reports Android's `batterystats` estimated mAh
#             instead and labels it ESTIMATE in every verdict line.
#
# Verdict words:
#   COST-MEASURED         both legs held their state, phone on battery: heat + drain measured
#   COST-ESTIMATED-DRAIN  both legs held, but the charger was feeding the load: heat measured,
#                         drain = Android's model estimate (labeled)
#   COST-INVALID          a leg's state did not hold (keep leg: Dashboard absent / rear not ON;
#                         idle leg: something was still projected) -- numbers archived, claimed
#                         nothing
#
# Run through the one-command entry point (`ex.ps1 -Task wake-cost` = authorize -> this ->
# collect), or alone:
#   .\tools\ex\12-wake-cost.ps1
#   .\tools\ex\12-wake-cost.ps1 -WakeIntervalMs 500 -DutySeconds 300
#
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

[CmdletBinding()]
param(
    [int] $WakeIntervalMs = 500,
    [int] $DutySeconds = 300,
    [int] $SampleSeconds = 60,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'wake-cost' -Serial $Serial | Out-Null }

function Get-ExCostSnapshot {
    <# One leg state look: rear + main display pair and the rear owner. #>
    $dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'display') -AllowFailure) -join "`n"
    $blocks = Get-DisplayInfoBlocks -DumpsysDisplay $dump
    $main = $blocks | Where-Object { $_.DisplayId -eq 0 } | Select-Object -First 1
    $rear = Get-RearDisplay -DumpsysDisplay $dump
    $owner = 'none'
    if ($rear) { $owner = Get-ExRearOwnerNow -DisplayId $rear.DisplayId }
    return [pscustomobject]@{
        RearPair = (Format-ExStatePair $rear)
        MainPair = (Format-ExStatePair $main)
        RearId   = if ($rear) { $rear.DisplayId } else { $null }
        Owner    = $owner
    }
}

function Get-ExBatteryReading {
    <# One battery reading: the dumpsys facts + the power-estimate excerpt, kept apart. #>
    $battery = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery') -AllowFailure)
    $stats = @(Invoke-Adb -Arguments @('shell', 'dumpsys', 'batterystats') -AllowFailure)
    return [pscustomobject]@{
        Facts    = Get-ExBatteryFacts -Battery $battery
        Estimate = Get-ExPowerEstimateLines -Batterystats $stats
        Raw      = $battery
    }
}

function Invoke-ExCostDuty {
    <#
      One measured leg UNDER the shared lock (the state is already established): reset the
      cumulative accountant -> reading BEFORE -> duty (sampling) -> reading AFTER. Claims no
      verdict (that is the caller's job).
    #>
    param([Parameter(Mandatory, Position = 0)][string] $Label)

    Write-ExNote ('---- leg {0} ({1}s duty, wake interval {2}ms) ----' -f $Label, $DutySeconds, $WakeIntervalMs)
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'batterystats', '--reset') -AllowFailure | Out-Null
    $before = Get-ExBatteryReading

    $samples = New-Object System.Collections.Generic.List[string]
    $start = Get-Date
    $deadline = $start.AddSeconds($DutySeconds)
    do {
        Start-Sleep -Seconds $SampleSeconds
        $elapsed = [int]((Get-Date) - $start).TotalSeconds
        $snap = Get-ExCostSnapshot
        $line = Format-ExWakeSampleLine -Elapsed $elapsed -RearPair $snap.RearPair -MainPair $snap.MainPair -Owner $snap.Owner
        $samples.Add($line)
        Write-ExNote $line
    } while ((Get-Date) -lt $deadline)

    $after = Get-ExBatteryReading
    return [pscustomobject]@{
        Label        = $Label
        Before       = $before
        After        = $after
        Samples      = $samples.ToArray()
        KeyguardHeld = (Test-ExKeyguardLocked)
    }
}

# ---- 0. preflight -------------------------------------------------------------
$snap0 = Get-ExCostSnapshot
Write-ExNote ('rear display: state={0}; main state={1}; wakefulness={2}' -f $snap0.RearPair, $snap0.MainPair, (Get-ExWakefulness))
if (Test-ExKeyguardLocked) {
    Write-ExNote 'WARNING: the phone is keyguard-locked and this one is a SECURE lock (fingerprint'
    Write-ExNote '         or PIN): the keep leg needs an unlocked setup below. Unlock it first.'
}
Write-ExNote ('protocol: ONE lock covers both legs (secure keyguard, see header): keep {0}s -> clear -> idle {1}s; sample every {2}s' -f
    $DutySeconds, $DutySeconds, $SampleSeconds)

# ---- 1. establish the KEEP state while unlocked --------------------------------
Invoke-ExDebugAction -Action 'WAKE_INTERVAL' -Extra @{ ms = $WakeIntervalMs }
Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex',
    'wake cost keep leg') -AllowFailure | Out-Null
Invoke-ExDebugAction -Action 'POST_TEST'
$snapSetup = Get-ExCostSnapshot
$dashboardUp = $false
if ($null -ne $snapSetup.RearId) { $dashboardUp = (Wait-ExRearOwner -Owner 'dashboard' -DisplayId $snapSetup.RearId -TimeoutSec 20) }
Write-ExNote ('keep setup: dashboard-up={0} rear={1} owner={2}' -f $dashboardUp, $snapSetup.RearPair, $snapSetup.Owner)
Start-Sleep -Seconds 5

# ---- 2. the ONE lock (both legs measure under it) ------------------------------
Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, 'pc-cost-lock-issued') -AllowFailure | Out-Null
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
Start-Sleep -Seconds 2
if ((Get-ExWakefulness) -eq 'Awake') {
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
}
Write-ExNote ('locked: wakefulness={0} keyguard={1}' -f (Get-ExWakefulness), (Test-ExKeyguardLocked))

# ---- 3. leg KEEP ----------------------------------------------------------------
$keep = Invoke-ExCostDuty -Label 'keep'

# ---- 4. leg boundary: clear notifications -> ExitDashboard -> idle state --------
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Start-Sleep -Seconds 30
$snapIdle = Get-ExCostSnapshot
Write-ExNote ('idle established: rear={0} owner={1}' -f $snapIdle.RearPair, $snapIdle.Owner)

# ---- 5. leg IDLE ----------------------------------------------------------------
$idle = Invoke-ExCostDuty -Label 'idle'

# ---- 6. teardown: empty the Icon Set, wake, best-effort unlock -------------------
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
Start-Sleep -Seconds 1
Invoke-ExKeyguardDismiss | Out-Null

# ---- 7. facts ------------------------------------------------------------------
$dutyHours = [math]::Round($DutySeconds / 3600.0, 4)
$tempDeltaIdle = if (($null -ne $idle.Before.Facts.TemperatureDc) -and ($null -ne $idle.After.Facts.TemperatureDc)) { $idle.After.Facts.TemperatureDc - $idle.Before.Facts.TemperatureDc } else { $null }
$tempDeltaKeep = if (($null -ne $keep.Before.Facts.TemperatureDc) -and ($null -ne $keep.After.Facts.TemperatureDc)) { $keep.After.Facts.TemperatureDc - $keep.Before.Facts.TemperatureDc } else { $null }
$chargeDeltaIdle = if (($null -ne $idle.Before.Facts.ChargeCounterUah) -and ($null -ne $idle.After.Facts.ChargeCounterUah)) { $idle.Before.Facts.ChargeCounterUah - $idle.After.Facts.ChargeCounterUah } else { $null }
$chargeDeltaKeep = if (($null -ne $keep.Before.Facts.ChargeCounterUah) -and ($null -ne $keep.After.Facts.ChargeCounterUah)) { $keep.Before.Facts.ChargeCounterUah - $keep.After.Facts.ChargeCounterUah } else { $null }
$onCharger = (($keep.Before.Facts.AcPowered -eq 'true') -or ($keep.Before.Facts.UsbPowered -eq 'true') -or ($keep.Before.Facts.WirelessPowered -eq 'true'))

$keepStateOk = (($keep.Samples.Count -gt 0) -and (@($keep.Samples | Where-Object { $_ -notmatch 'rear=ON/.*owner=dashboard' }).Count -eq 0))
$idleStateOk = (($idle.Samples.Count -gt 0) -and (@($idle.Samples | Where-Object { $_ -match 'owner=dashboard' }).Count -eq 0))
$bothLocked = ($keep.KeyguardHeld -and $idle.KeyguardHeld)

if ((-not $keepStateOk) -or (-not $idleStateOk) -or (-not $dashboardUp) -or (-not $bothLocked)) {
    $cost = 'COST-INVALID (a leg`s state did not hold -- keep-leg setup+samples must read rear=ON/... owner=dashboard, idle-leg samples must never read owner=dashboard, and the keyguard must hold through both legs; the numbers are archived but claim nothing)'
} elseif ($onCharger) {
    $cost = 'COST-ESTIMATED-DRAIN (both legs held their state; heat is MEASURED but the charger was feeding the load, so drain comes from Android`s batterystats model -- an ESTIMATE, not a measurement)'
} else {
    $cost = 'COST-MEASURED (both legs held their state and nothing powered the load: heat and drain deltas are measurements)'
}

# ---- 8. output + artifacts -----------------------------------------------------
$fmtDelta = {
    param($Value, $Unit)
    if ($null -eq $Value) { return 'not measured' }
    return ('{0}{1}' -f $Value, $Unit)
}
$out = New-Object System.Collections.Generic.List[string]
$out.Add('# wake-cost (ticket #21: Wake Keep-alive cost, keep leg vs idle leg)')
$out.Add(('leg-duty                : {0}s each under ONE lock (secure keyguard, see notes), sample every {1}s, wake interval {2}ms' -f $DutySeconds, $SampleSeconds, $WakeIntervalMs))
$out.Add(('on-charger              : {0} (AC={1} USB={2} wireless={3})' -f $onCharger, $keep.Before.Facts.AcPowered, $keep.Before.Facts.UsbPowered, $keep.Before.Facts.WirelessPowered))
$out.Add(('leg-states-ok           : keep={0} idle={1} (keep setup dashboard-up={2}; keyguard-held keep={3} idle={4})' -f $keepStateOk, $idleStateOk, $dashboardUp, $keep.KeyguardHeld, $idle.KeyguardHeld))
$out.Add(('heat-keep               : {0} (battery temperature delta over the leg, deci-degrees C, MEASURED)' -f (& $fmtDelta $tempDeltaKeep ' dC')))
$out.Add(('heat-idle               : {0} (battery temperature delta over the leg, deci-degrees C, MEASURED)' -f (& $fmtDelta $tempDeltaIdle ' dC')))
$out.Add(('heat-keep-minus-idle    : {0} (the keep-alive`s marginal warmth over {1}s)' -f (& $fmtDelta $(if (($null -ne $tempDeltaIdle) -and ($null -ne $tempDeltaKeep)) { $tempDeltaKeep - $tempDeltaIdle } else { $null }) ' dC'), $DutySeconds))
$out.Add(('drain-keep              : {0} (charge counter consumed over the leg, uAh, MEASURED)' -f (& $fmtDelta $chargeDeltaKeep ' uAh')))
$out.Add(('drain-idle              : {0} (charge counter consumed over the leg, uAh, MEASURED)' -f (& $fmtDelta $chargeDeltaIdle ' uAh')))
if (($null -ne $chargeDeltaIdle) -and (($null -ne $chargeDeltaKeep)) -and ($dutyHours -gt 0)) {
    $out.Add(('drain-keep-per-hour     : {0} uAh/h measured (idle {1} uAh/h)' -f [math]::Round($chargeDeltaKeep / $dutyHours), [math]::Round($chargeDeltaIdle / $dutyHours)))
}
$out.Add('estimate-keep           : <Android batterystats model ESTIMATE, not a measurement>')
$out.AddRange([string[]]$keep.After.Estimate)
$out.Add('estimate-idle           : <Android batterystats model ESTIMATE, not a measurement>')
$out.AddRange([string[]]$idle.After.Estimate)
$out.Add(('cost                    : {0}' -f $cost))
# ADR 0003 defers the DEFAULT interval to this cost data ("the default is set by ticket #21`s
# measured battery/heat cost", in the ADR`s own words).
if (($null -ne $chargeDeltaKeep) -and ($dutyHours -gt 0)) {
    $out.Add(('cost-recommendation     : keep-alive at {0}ms costs ~{1} uAh/h measured (idle ~{2} uAh/h); default-interval decision input for ADR 0003 / WakeKeepAlive.DEFAULT_INTERVAL_MS (currently provisional 500ms)' -f
        $WakeIntervalMs, [math]::Round($chargeDeltaKeep / $dutyHours), [math]::Round($(if ($null -ne $chargeDeltaIdle) { $chargeDeltaIdle } else { 0 }) / $dutyHours)))
} elseif (($null -ne $keep.After.Estimate) -and ($keep.After.Estimate.Count -gt 0)) {
    $out.Add(('cost-recommendation     : keep-alive at {0}ms -- drain only as Android`s model ESTIMATE (charger was feeding the load; unplug and rerun for measured uAh/h). Default-interval decision input for ADR 0003 / WakeKeepAlive.DEFAULT_INTERVAL_MS (currently provisional 500ms)' -f $WakeIntervalMs))
} else {
    $out.Add('cost-recommendation     : not measured (no leg data -- see cost verdict)')
}
Write-ExArtifact -Name 'wake-cost.txt' -Lines $out.ToArray() | Out-Null
Write-ExArtifact -Name 'wake-cost-battery.txt' -Lines (@(
        '# dumpsys battery, before/after each leg (verbatim)'
        '== keep before =='
    ) + $keep.Before.Raw + @('== keep after ==') + $keep.After.Raw + @('== idle before ==') + $idle.Before.Raw + @('== idle after ==') + $idle.After.Raw) | Out-Null
Write-ExArtifact -Name 'wake-cost-samples.txt' -Lines (@('# keep leg samples', '# wire: ' + 'mm-dd HH:mm:ss elapsed=N rear=<pair> main=<pair> owner=<owner>') + $keep.Samples + @('', '# idle leg samples') + $idle.Samples) | Out-Null

foreach ($line in $out) { Write-ExNote $line }

# ---- 9. scenario notes ---------------------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (Wake Keep-alive cost, ticket #21)')
$notes.Add('')
$notes.Add('Two locked-state legs under ONE lock: keep (Dashboard on the rear + the app`s own Wake')
$notes.Add('Keep-alive) first, then idle (notifications cleared -> ExitDashboard -> nothing projected).')
$notes.Add('The keyguard on this phone is a SECURE lock (fingerprint/PIN) and cannot be')
$notes.Add('dismissed over adb, so the protocol locks ONCE and measures both legs under it -- that is')
$notes.Add('also the faithful comparison: both legs see the same keyguard, the same radios, the same')
$notes.Add('charger.')
$notes.Add('What each number is:')
$notes.Add('- **heat (MEASURED)**: battery temperature delta per leg; the keep-minus-idle delta is')
$notes.Add('  the keep-alive`s marginal warmth (charger warmth is common to both legs).')
$notes.Add('- **drain (MEASURED only on battery)**: `Charge counter` from `dumpsys battery`, the')
$notes.Add('  battery`s own micro-amp-hour counter. On a charger the load is fed externally and the')
$notes.Add('  counter cannot drift -- the verdict says COST-ESTIMATED-DRAIN in that case.')
$notes.Add('- **estimate-* (Android ESTIMATE)**: `dumpsys batterystats` power model (cpu time x')
$notes.Add('  power profile). Labeled as the estimate it is; good for ratios, never for absolutes.')
$notes.Add('')
$notes.Add('- **Honest limit**: a phone on USB cannot yield a real drain number. For COST-MEASURED,')
$notes.Add('  unplug and rerun `ex.ps1 -Task wake-cost` (the script auto-picks the metric).')
$notes.Add('- **State discipline**: keep-leg samples must all read `rear=... owner=dashboard` and')
$notes.Add('  idle-leg samples must never; a hand on the phone during a leg invalidates that leg.')
$notes.Add('- **Interval is the strength knob** (`-WakeIntervalMs`, live-adjustable via the')
$notes.Add('  `WAKE_INTERVAL` debug action); cost numbers scale with ticks = duty / interval.')
Write-ExArtifact -Name 'scenario-notes.md' -Lines $notes.ToArray() | Out-Null

return @{
    Cost       = $cost
    HeatIdle   = $tempDeltaIdle
    HeatKeep   = $tempDeltaKeep
    DrainIdle  = $chargeDeltaIdle
    DrainKeep  = $chargeDeltaKeep
}
