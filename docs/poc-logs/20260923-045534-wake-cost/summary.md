# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue-wt\01\docs\poc-logs\20260923-045534-wake-cost
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 52 |
| listener connected | True |
| posted / removed | 0 / 2 (allowlist hits:  / com.rearcue.poc,com.android.shell) |
| effects | LaunchDashboard, UpdateIconSet, ExitDashboard |
| projection sent / confirmed | True / False |
| icon set updates | 1 |
| exit requested / detached | True / True |
| takeover signals | miui.intent.action.SUB_SCREEN_OFF, android.intent.action.SCREEN_OFF, miui.intent.action.SUB_SCREEN_ON, android.intent.action.SCREEN_ON |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

## scenario notes (Wake Keep-alive cost, ticket #21)

Two locked-state legs under ONE lock: keep (Dashboard on the rear + the app`s own Wake
Keep-alive) first, then idle (notifications cleared -> ExitDashboard -> nothing projected).
The keyguard on this phone is a SECURE lock (fingerprint/PIN) and cannot be
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

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (201,843 bytes)
- dumpsys-display.txt (339,041 bytes)
- dumpsys-window.txt (160,951 bytes)
- logcat-rearcue.txt (5,657 bytes)
- logcat-system-rear.txt (114,532 bytes)
- scenario-notes.md (1,629 bytes)
- screenshots\main.png (130,287 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (308 bytes)
- state.txt (1,773 bytes)
- transcript.txt (18,383 bytes)
- wake-cost.txt (9,238 bytes)
- wake-cost-battery.txt (3,319 bytes)
- wake-cost-samples.txt (841 bytes)
