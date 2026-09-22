# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-162618-wake-keepalive
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 142 |
| listener connected | False |
| posted / removed | 8 / 4 (allowlist hits: com.tencent.mm / ) |
| effects | LaunchDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 0 |
| exit requested / detached | False / False |
| takeover signals | android.intent.action.SCREEN_ON, miui.intent.action.SUB_SCREEN_OFF, android.intent.action.SCREEN_OFF, miui.intent.action.SUB_SCREEN_ON |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### e12-wake-keepalive.txt

```
inject-started        : True (pid=15186 display=1 sleep=0.5s)
inject-running        : True (ticks-in-window=121, expected~120, input-errors=0, ps-seen=True, wake-key-traces=3)
baseline-lock-poweroff: False (no PowerGroup group-1 power-off after the baseline lock marker)
keepalive-lock-poweroff: True (09-22 16:27:47.502  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 1, uid= 1000, millisSinceLastUserActivity=4550, lastUserActivityEvent=other)...)
baseline              : rear left ON at +6s and never ON again, ending DOZE_SUSPEND/DOZE_SUSPEND
baseline-left-on-s    : 6
baseline-valid        : False
keepalive-left-on-s   : 
rear-behavior         : rear stayed ON through the whole keep-alive watch
main-side-effect      : main display was lit during the keep-alive watch (first ON at +2s) -- the wake key is NOT free of the main screen
pollution             : none detected
e12                   : E12-NO-BASELINE (control leg does not show the rear leaving ON, so "it would have stayed on anyway" cannot be excluded; control: rear left ON at +6s and never ON again, ending DOZE_SUSPEND/DOZE_SUSPEND; lock power-off seen: False)
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
  falls back to a marked `KEYCODE_POWER` toggle wake, and every press is marked into the
  device log (`pc-e12-power-*`) so a human press stays separable from ours.
- **No install step**: E12 judges display power state only, so `-Task wake-keepalive` does not
  run `01-install` (no MIUI USB install dialog risk, no app dependency); `05-collect` simply
  finds no app state and records that.

## artifacts
- dumpsys-activities.txt (198,336 bytes)
- dumpsys-display.txt (339,101 bytes)
- dumpsys-window.txt (145,346 bytes)
- e12-inject-errors.txt (0 bytes)
- e12-inject-ps.txt (472 bytes)
- e12-power-group.txt (3,159 bytes)
- e12-samples-baseline.txt (2,215 bytes)
- e12-samples-keepalive.txt (2,152 bytes)
- e12-wake-keepalive.txt (30,674 bytes)
- e12-wake-ticks.txt (3,343 bytes)
- logcat-rearcue.txt (17,278 bytes)
- logcat-system-rear.txt (89,706 bytes)
- scenario-notes.md (2,419 bytes)
- screenshots\main.png (29,620 bytes)
- screenshots\notes.txt (191 bytes)
- session.md (307 bytes)
- state.txt (2,768 bytes)
- transcript.txt (17,414 bytes)
