# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260926-212450-charging
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 488 |
| listener connected | True |
| posted / removed | 23 / 1 (allowlist hits: com.android.shell / com.android.shell) |
| effects | LaunchDashboard, ExitDashboard, UpdateIconSet |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals | android.intent.action.ACTION_POWER_DISCONNECTED, android.intent.action.ACTION_POWER_CONNECTED, miui.intent.action.SUB_SCREEN_OFF, miui.intent.action.SUB_SCREEN_ON |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

## scenario notes (Charging Animation, ticket #57 / spec 0007)

- **How the plug was triggered (software only)**: `am broadcast -a
  android.intent.action.ACTION_POWER_CONNECTED` is refused on this build
  (`SecurityException: Permission Denial ... from pid=..., uid=2000` -- protected
  broadcast, captured in the session transcript). The battery service emits the two
  broadcasts itself when its plug state changes, so the legs drive it with
  `dumpsys battery unplug` (DISCONNECTED) and `dumpsys battery set ac 1`
  (CONNECTED, only after an unplug -- a bare `set ac 1` changes no plug type while
  the phone is already USB-powered, therefore no broadcast). `dumpsys battery
  reset` restores the real state in the restore step (mandatory).
- **Every leg is judged from the app own `power-connected` / `power-disconnected`
  refresh lines + the STATE echo (`castSource=CHARGING`), never from shell exit codes.
- **Posture Gate**: the proximity sensor is on-change with no adb path, so the run
  READS the committed posture: `exempt-posture` is judged only when the gate is
  physically closed at cast time (postureFaceDown=false, phone face-up -- the reading
  this device had on 2026-09-26: proximity 5.00 far, gravity z=-9.80 screen-up) and
  is SKIPPED when the gate is open (nothing to exempt). The DND leg carries the
  deterministic exemption proof either way.
- **Process start does not read the real plug state** (observed): core `plugged`
  starts false and only POWER_CONNECTED/DISCONNECTED broadcasts change it, so every
  leg works from a deliberate transition, never from the ambient charger state.
- **End state**: the device stays on USB power, so after `battery reset` the app
  re-casts the charging screen -- the truthful state of a plugged-in phone.

## artifacts
- 02-authorize.txt (873 bytes)
- charging.txt (2,226 bytes)
- charging-logcat.txt (64,524 bytes)
- dumpsys-activities.txt (162,612 bytes)
- dumpsys-display.txt (347,810 bytes)
- dumpsys-window.txt (162,682 bytes)
- logcat-rearcue.txt (77,883 bytes)
- logcat-system-rear.txt (117,278 bytes)
- scenario-notes.md (1,797 bytes)
- screenshots\charging-01-bolt-only.png (16,127 bytes)
- screenshots\charging-04-bolt-with-notification.png (22,410 bytes)
- screenshots\charging-06-switch-on-recovers.png (16,773 bytes)
- screenshots\main.png (714,277 bytes)
- screenshots\notes.txt (247 bytes)
- screenshots\rear.png (16,048 bytes)
- session.md (301 bytes)
- state.txt (12,323 bytes)
- transcript.txt (17,305 bytes)
