# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-163828-wake-keepalive
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 13 |
| listener connected | False |
| posted / removed | 0 / 0 (allowlist hits:  / ) |
| effects | LaunchDashboard |
| projection sent / confirmed | True / False |
| icon set updates | 0 |
| exit requested / detached | False / True |
| takeover signals | miui.intent.action.SUB_SCREEN_OFF, android.intent.action.SCREEN_OFF |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### e12-wake-keepalive.txt

```
inject-started        : True (pid=22432 display=1 sleep=0.5s)
inject-running        : True (ticks-in-window=116, expected~120, input-errors=0, ps-seen=True, wake-key-traces=1)
baseline-lock-poweroff: True (09-22 16:38:38.665  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 1, uid= 1000, millisSinceLastUserActivity=633701, lastUserActivityEvent=other)...)
keepalive-lock-poweroff: True (09-22 16:39:55.891  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 1, uid= 1000, millisSinceLastUserActivity=4716, lastUserActivityEvent=other)...)
baseline              : rear left ON at +4s and never ON again, ending DOZE_SUSPEND/DOZE_SUSPEND
baseline-left-on-s    : 4
baseline-valid        : True
keepalive-left-on-s   : 
rear-behavior         : rear stayed ON through the whole keep-alive watch
main-side-effect      : main display stayed dark (no side effect observed)
pollution             : none detected
e12                   : E12-PASS (rear display stayed ON for the whole 60s keep-alive watch at 500ms injection interval; control leg left ON at +4s)
e12-class             : n/a (no loss to classify)
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
- **Injection interval**: `500ms`, a command parameter (`-WakeIntervalMs`), converted to a `sleep` argument of 0.5s.
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
- dumpsys-activities.txt (191,757 bytes)
- dumpsys-display.txt (338,740 bytes)
- dumpsys-window.txt (142,278 bytes)
- e12-inject-errors.txt (0 bytes)
- e12-inject-ps.txt (472 bytes)
- e12-power-group.txt (1,571 bytes)
- e12-samples-baseline.txt (2,147 bytes)
- e12-samples-keepalive.txt (2,071 bytes)
- e12-wake-keepalive.txt (12,738 bytes)
- e12-wake-ticks.txt (3,187 bytes)
- logcat-rearcue.txt (1,293 bytes)
- logcat-system-rear.txt (114,408 bytes)
- scenario-notes.md (2,795 bytes)
- screenshots\main.png (224,275 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (307 bytes)
- state.txt (1,966 bytes)
- transcript.txt (17,039 bytes)
