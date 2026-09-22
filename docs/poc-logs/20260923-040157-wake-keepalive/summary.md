# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue-wt\01\docs\poc-logs\20260923-040157-wake-keepalive
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 83 |
| listener connected | False |
| posted / removed | 0 / 0 (allowlist hits:  / ) |
| effects | LaunchDashboard |
| projection sent / confirmed | True / False |
| icon set updates | 0 |
| exit requested / detached | False / False |
| takeover signals | android.intent.action.SCREEN_ON, android.intent.action.SCREEN_OFF, miui.intent.action.SUB_SCREEN_OFF, miui.intent.action.SUB_SCREEN_ON |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### e12-wake-keepalive.txt

```
inject-started        : True (pid=8659 display=1 sleep=5s)
inject-running        : True (ticks-in-window=13, expected~12, input-errors=0, ps-seen=True, wake-key-traces=1)
baseline-lock-poweroff: True (09-23 04:02:13.885  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 1, uid= 1000, millisSinceLastUserActivity=339920, lastUserActivityEvent=other)...)
keepalive-lock-poweroff: True (09-23 04:03:34.746  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 1, uid= 1000, millisSinceLastUserActivity=79416, lastUserActivityEvent=other)...)
baseline              : rear stayed ON through the whole control watch
baseline-left-on-s    : 
baseline-valid        : False
keepalive-left-on-s   : 
rear-behavior         : rear stayed ON through the whole keep-alive watch
main-side-effect      : main display stayed dark (no side effect observed)
pollution             : none detected
e12                   : E12-NO-BASELINE (control samples do not show the rear leaving ON, so "it would have stayed on anyway" cannot be excluded; control: rear stayed ON through the whole control watch; lock power-off line (corroboration only): True)
e12-class             : n/a (no control to compare against)
```

## scenario notes (E12 wake keep-alive, ticket #16)

- **How the injection loop starts/stops (the recorded trade-off)**: a device-side sh loop
  (`/data/local/tmp/wake-keepalive.sh`, pushed from `tools/ex/device/`) started with
  `adb shell "nohup sh ... >/dev/null 2>&1 &"` and stopped by creating a stop file the loop
  checks each iteration. Chosen over an app-side Shizuku UserService because E12 is a pure
  display-power question -- no app, no binder, no UserService lifecycle is needed, and the
  loop runs as shell uid 2000, the identity a Shizuku UserService would have anyway. The PC
  only samples; every injection happens on the device and leaves its own tick line with the
  `input` exit code, plus a `ps` snapshot while it runs.
- **Injection interval**: `5000ms`, a command parameter (`-WakeIntervalMs`), converted to a `sleep` argument of 5s.
- **Legs**: baseline (control, no keep-alive) first, then keep-alive, 60s each, sample every 2s; the lock is KEYCODE_POWER (07-lock-compare precedent) and the loop runs continuously ACROSS the lock (MRSS-style: keep-alive means the wake keys are already flowing when the display group powers off).
- **Deviation -- inter-leg reset**: the ticket asks for "unlock back to steady state" between
  the legs. This device has a SECURE keyguard (PIN/biometric) that adb cannot dismiss
  (`wm dismiss-keyguard` no-ops; ticket #7 recorded the same), so the reset degrades to
  "wake + dismiss attempt + settle + require rear=ON at T0", applied IDENTICALLY to both legs.
  Both legs therefore start from the same state (keyguard up, rear ON) and remain a valid
  same-round control comparison; the absolute baseline may differ from a run made from the
  unlocked steady state.
- **Environment fact found while building**: with the proximity sensor covered (phone lying
  face down), MIUI vetoes a plain `KEYCODE_WAKEUP` (`BaseMiuiPhoneWindowManager: Going to
  sleep due to KEYCODE_WAKEUP/KEYCODE_DPAD_CENTER: proximity sensor too close`). The reset
  falls back to a marked `KEYCODE_POWER` toggle wake, and every press is stamped with the
  DEVICE clock straight from `date` so a human press stays separable from ours.
- **Why T0 is not a logcat marker**: the main log buffer wraps under injection load (2 keys/s
  for 60s flood it past the run start -- one round lost its baseline marker that way and
  misjudged the control leg). The authoritative stamps are the `date` reads next to each
  KEYCODE_POWER press and the tick log; `pc-e12-power-*` logcat lines remain as corroboration.
- **No install step**: E12 judges display power state only, so `-Task wake-keepalive` does not
  run `01-install` (no MIUI USB install dialog risk, no app dependency); `05-collect` simply
  finds no app state and records that.

## artifacts
- dumpsys-activities.txt (207,266 bytes)
- dumpsys-display.txt (339,781 bytes)
- dumpsys-window.txt (165,547 bytes)
- e12-inject-errors.txt (0 bytes)
- e12-inject-ps.txt (619 bytes)
- e12-power-group.txt (2,079 bytes)
- e12-samples-baseline.txt (2,131 bytes)
- e12-samples-keepalive.txt (2,153 bytes)
- e12-wake-keepalive.txt (17,349 bytes)
- e12-wake-ticks.txt (418 bytes)
- logcat-rearcue.txt (9,190 bytes)
- logcat-system-rear.txt (80,828 bytes)
- scenario-notes.md (2,794 bytes)
- screenshots\main.png (3,562 bytes)
- screenshots\notes.txt (190 bytes)
- session.md (313 bytes)
- state.txt (2,310 bytes)
- transcript.txt (18,422 bytes)
