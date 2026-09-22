# 12-wake-cost.ps1 -- ticket #21: what does the Wake Keep-alive actually cost (heat + drain)?
#
# Two locked-state legs on the same phone, one session (the states a owner really toggles
# between -- the comparison is NOT "dashboard lit vs dark", it is the whole feature state):
#   idle  no Active Notification, nothing projected, plain locked phone
#   keep  one allowlist notification -> Dashboard on the rear + the app's own Wake Keep-alive at
#         -WakeIntervalMs (the state this feature buys: indicators visible while locked)
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

function Invoke-ExCostLeg {
    <#
      One measured leg: set the state -> settle -> reading BEFORE -> lock -> duty (sampling) ->
      reading AFTER -> unlock. Returns the leg facts; claims no verdict (that is the caller's job).
    #>
    param(
        [Parameter(Mandatory, Position = 0)][string] $Label,
        [Parameter(Mandatory)][bool] $KeepAlive
    )

    Write-ExNote ('---- leg {0} ({1}s duty, wake interval {2}ms) ----' -f $Label, $DutySeconds, $WakeIntervalMs)
    if ($KeepAlive) {
        Invoke-ExDebugAction -Action 'WAKE_INTERVAL' -Extra @{ ms = $WakeIntervalMs }
        Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'post', '-t', 'RearCue ex', 'rearcue-ex',
            'wake cost keep leg') -AllowFailure | Out-Null
        Invoke-ExDebugAction -Action 'POST_TEST'
        $snap0 = Get-ExCostSnapshot
        $up = $false
        if ($null -ne $snap0.RearId) { $up = (Wait-ExRearOwner -Owner 'dashboard' -DisplayId $snap0.RearId -TimeoutSec 20) }
    } else {
        Invoke-ExDebugAction -Action 'CANCEL_TEST'
        Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }
        Start-Sleep -Seconds 3
    }
    Start-Sleep -Seconds 5

    # batterystats is a CUMULATIVE accountant: reset it per leg so the excerpt below is the leg's.
    Invoke-Adb -Arguments @('shell', 'dumpsys', 'batterystats', '--reset') -AllowFailure | Out-Null
    $before = Get-ExBatteryReading

    Invoke-Adb -Arguments @('shell', 'log', '-t', $config.LogTag, ('pc-cost-lock-' + $Label)) -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_POWER') -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    $locked = (Get-ExWakefulness) -ne 'Awake'
    Write-ExNote ('{0}: locked={1}' -f $Label, $locked)

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
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    Invoke-ExKeyguardDismiss | Out-Null
    Start-Sleep -Seconds 1

    return [pscustomobject]@{
        Label   = $Label
        Keep    = $KeepAlive
        Locked  = $locked
        Before  = $before
        After   = $after
        Samples = $samples.ToArray()
    }
}

# ---- 0. preflight -------------------------------------------------------------
$snap0 = Get-ExCostSnapshot
Write-ExNote ('rear display: state={0}; main state={1}; wakefulness={2}' -f $snap0.RearPair, $snap0.MainPair, (Get-ExWakefulness))
if (Test-ExKeyguardLocked) {
    Write-ExNote 'WARNING: the phone is keyguard-locked; unlock it first (a secure lock cannot be'
    Write-ExNote '         dismissed over adb, ticket #7). The legs would be unreadable.'
}
Write-ExNote ('protocol: two locked legs (idle -> keep), {0}s duty each, sample every {1}s' -f $DutySeconds, $SampleSeconds)

# ---- 1. legs -------------------------------------------------------------------
$idle = Invoke-ExCostLeg -Label 'idle' -KeepAlive $false
$keep = Invoke-ExCostLeg -Label 'keep' -KeepAlive $true

# ---- 2. teardown: empty the Icon Set so the keep-alive stops (lifecycle, #21) ---
Invoke-ExDebugAction -Action 'CANCEL_TEST'
Invoke-ExDebugAction -Action 'CANCEL_PACKAGE' -Extra @{ pkg = 'com.android.shell' }

# ---- 3. facts ------------------------------------------------------------------
$dutyHours = [math]::Round($DutySeconds / 3600.0, 4)
$tempDeltaIdle = if (($null -ne $idle.Before.Facts.TemperatureDc) -and ($null -ne $idle.After.Facts.TemperatureDc)) { $idle.After.Facts.TemperatureDc - $idle.Before.Facts.TemperatureDc } else { $null }
$tempDeltaKeep = if (($null -ne $keep.Before.Facts.TemperatureDc) -and ($null -ne $keep.After.Facts.TemperatureDc)) { $keep.After.Facts.TemperatureDc - $keep.Before.Facts.TemperatureDc } else { $null }
$chargeDeltaIdle = if (($null -ne $idle.Before.Facts.ChargeCounterUah) -and ($null -ne $idle.After.Facts.ChargeCounterUah)) { $idle.Before.Facts.ChargeCounterUah - $idle.After.Facts.ChargeCounterUah } else { $null }
$chargeDeltaKeep = if (($null -ne $keep.Before.Facts.ChargeCounterUah) -and ($null -ne $keep.After.Facts.ChargeCounterUah)) { $keep.Before.Facts.ChargeCounterUah - $keep.After.Facts.ChargeCounterUah } else { $null }
$onCharger = (($idle.Before.Facts.AcPowered -eq 'true') -or ($idle.Before.Facts.UsbPowered -eq 'true') -or ($idle.Before.Facts.WirelessPowered -eq 'true'))

$keepStateOk = (($keep.Samples.Count -gt 0) -and (@($keep.Samples | Where-Object { $_ -notmatch 'rear=ON/.*owner=dashboard' }).Count -eq 0))
$idleStateOk = (($idle.Samples.Count -gt 0) -and (@($idle.Samples | Where-Object { $_ -match 'owner=dashboard' }).Count -eq 0))
$bothLocked = ($idle.Locked -and $keep.Locked)

if ((-not $keepStateOk) -or (-not $idleStateOk) -or (-not $bothLocked)) {
    $cost = 'COST-INVALID (a leg`s state did not hold -- keep-leg samples must all read rear=ON/... owner=dashboard, idle-leg samples must never read owner=dashboard, both legs must really lock; the numbers are archived but claim nothing)'
} elseif ($onCharger) {
    $cost = 'COST-ESTIMATED-DRAIN (both legs held their state; heat is MEASURED but the charger was feeding the load, so drain comes from Android`s batterystats model -- an ESTIMATE, not a measurement)'
} else {
    $cost = 'COST-MEASURED (both legs held their state and nothing powered the load: heat and drain deltas are measurements)'
}

# ---- 4. output + artifacts -----------------------------------------------------
$fmtDelta = {
    param($Value, $Unit)
    if ($null -eq $Value) { return 'not measured' }
    return ('{0}{1}' -f $Value, $Unit)
}
$out = New-Object System.Collections.Generic.List[string]
$out.Add('# wake-cost (ticket #21: Wake Keep-alive cost, idle leg vs keep leg)')
$out.Add(('leg-duty                : {0}s each, sample every {1}s, wake interval {2}ms' -f $DutySeconds, $SampleSeconds, $WakeIntervalMs))
$out.Add(('on-charger              : {0} (AC={1} USB={2} wireless={3})' -f $onCharger, $idle.Before.Facts.AcPowered, $idle.Before.Facts.UsbPowered, $idle.Before.Facts.WirelessPowered))
$out.Add(('leg-states-ok           : idle={0} keep={1} locked-both={2}' -f $idleStateOk, $keepStateOk, $bothLocked))
$out.Add(('heat-idle               : {0} (battery temperature delta over the leg, deci-degrees C, MEASURED)' -f (& $fmtDelta $tempDeltaIdle ' dC')))
$out.Add(('heat-keep               : {0} (battery temperature delta over the leg, deci-degrees C, MEASURED)' -f (& $fmtDelta $tempDeltaKeep ' dC')))
$out.Add(('heat-keep-minus-idle    : {0} (the keep-alive`s marginal warmth over {1}s)' -f (& $fmtDelta $(if (($null -ne $tempDeltaIdle) -and ($null -ne $tempDeltaKeep)) { $tempDeltaKeep - $tempDeltaIdle } else { $null }) ' dC'), $DutySeconds))
$out.Add(('drain-idle              : {0} (charge counter consumed over the leg, uAh, MEASURED)' -f (& $fmtDelta $chargeDeltaIdle ' uAh')))
$out.Add(('drain-keep              : {0} (charge counter consumed over the leg, uAh, MEASURED)' -f (& $fmtDelta $chargeDeltaKeep ' uAh')))
if (($null -ne $chargeDeltaIdle) -and (($null -ne $chargeDeltaKeep)) -and ($dutyHours -gt 0)) {
    $out.Add(('drain-keep-per-hour     : {0} uAh/h measured (idle {1} uAh/h)' -f [math]::Round($chargeDeltaKeep / $dutyHours), [math]::Round($chargeDeltaIdle / $dutyHours)))
}
$out.Add('estimate-idle           : <Android batterystats model ESTIMATE, not a measurement>')
$out.AddRange([string[]]$idle.After.Estimate)
$out.Add('estimate-keep           : <Android batterystats model ESTIMATE, not a measurement>')
$out.AddRange([string[]]$keep.After.Estimate)
$out.Add(('cost                    : {0}' -f $cost))
# ADR 0003 defers the DEFAULT interval to this cost data ("the default is set by ticket #21`s
# measured battery/heat cost", in the ADR`s own words).
# The recommendation line is the decision material: measured cost per hour at THIS interval, so
# the default can be set (or kept provisional) from numbers instead of taste.
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
        '== idle before =='
    ) + $idle.Before.Raw + @('== idle after ==') + $idle.After.Raw + @('== keep before ==') + $keep.Before.Raw + @('== keep after ==') + $keep.After.Raw) | Out-Null
Write-ExArtifact -Name 'wake-cost-samples.txt' -Lines (@('# idle leg samples', '# wire: ' + 'mm-dd HH:mm:ss elapsed=N rear=<pair> main=<pair> owner=<owner>') + $idle.Samples + @('', '# keep leg samples') + $keep.Samples) | Out-Null

foreach ($line in $out) { Write-ExNote $line }

# ---- 5. scenario notes ---------------------------------------------------------
$notes = New-Object System.Collections.Generic.List[string]
$notes.Add('## scenario notes (Wake Keep-alive cost, ticket #21)')
$notes.Add('')
$notes.Add('Two locked-state legs: idle (nothing projected) vs keep (Dashboard on the rear + the')
$notes.Add('app`s own Wake Keep-alive). What each number is:')
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
