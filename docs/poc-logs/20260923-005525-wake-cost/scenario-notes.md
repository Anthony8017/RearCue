## scenario notes (Wake Keep-alive cost, ticket #21)

Two locked-state legs under ONE lock: keep (Dashboard on the rear + the app`s own Wake
Keep-alive) first, then idle (notifications cleared -> ExitDashboard -> nothing projected).
projected). The keyguard on this phone is a SECURE lock (fingerprint/PIN) and cannot be
dismissed over adb, so the protocol locks ONCE and measures both legs under it -- that is
also the faithful comparison: both legs see the same keyguard, the same radios, the same
charger.
What each number is:
- **heat (MEASURED)**: battery temperature delta per leg; the keep-minus-idle delta is
  the keep-alive`s marginal warmth (charger warmth is common to both legs).
- **drain (MEASURED only on battery)**: `Charge counter` from `dumpsys battery`, the
  battery`s own micro-amp-hour counter. On a charger the load is fed externally and the
  counter cannot drift -- the verdict says COST-ESTIMATED-DRAIN in that case.
- **estimate-* (Android ESTIMATE)**: `dumpsys batterystats` power model (cpu time x
  power profile). Labeled as the estimate it is; good for ratios, never for absolutes.

- **Honest limit**: a phone on USB cannot yield a real drain number. For COST-MEASURED,
  unplug and rerun `ex.ps1 -Task wake-cost` (the script auto-picks the metric).
- **State discipline**: keep-leg samples must all read `rear=... owner=dashboard` and
  idle-leg samples must never; a hand on the phone during a leg invalidates that leg.
- **Interval is the strength knob** (`-WakeIntervalMs`, live-adjustable via the
  `WAKE_INTERVAL` debug action); cost numbers scale with ticks = duty / interval.
