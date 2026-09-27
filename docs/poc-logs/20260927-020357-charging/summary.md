# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260927-020357-charging
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 476 |
| listener connected | True |
| posted / removed | 12 / 1 (allowlist hits: com.android.shell / com.android.shell) |
| effects | LaunchDashboard, ExitDashboard, UpdateIconSet |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals | android.intent.action.ACTION_POWER_DISCONNECTED, android.intent.action.ACTION_POWER_CONNECTED, miui.intent.action.SUB_SCREEN_ON, miui.intent.action.SUB_SCREEN_OFF |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### charging.txt

```
cast                : pass=True  -- disconnected line: True; connected line: True; owner=dashboard: True; castSource=CHARGING; iconSet empty: True; LaunchDashboard(0) on connect: True
exempt-dnd          : pass=True  -- handed back before: True; zen at connect: ZEN_MODE_IMPORTANT_INTERRUPTIONS (want != ZEN_MODE_OFF); connected line: True; owner=dashboard: True; castSource=CHARGING; postureFaceDown=False (gate closed only when false)
exempt-posture      : pass=True  -- posture gate CLOSED at cast time (postureFaceDown=false: phone physically face-up, proximity reads far) and the charging cast still landed (owner=dashboard: True) -> posture exemption proven under a closed gate
hold-vs-gates       : pass=True  -- ExitDashboard 2->2 across DND off/on/off (delta must be 0); owner=dashboard; castSource=CHARGING; posture flip not drivable over adb (recorded, not asserted)
coexist             : pass=True  -- ShowFeedBanner: True; owner=dashboard; castSource=CHARGING; Icon Set holds com.android.shell: True
handback-conjunction: pass=True  -- banner hides while charging holds: True; ExitDashboard 2->2 after the clear (delta 0 = hold); owner still dashboard: dashboard; Icon Set empty: True; disconnect line: True; handed back: True; ExitDashboard after unplug: True
master-switch       : pass=True  -- switch off: True; connect line: True; LaunchDashboard 8->8 (delta 0 = no reaction); owner=native; switch on again: True; recovered owner=dashboard: True; castSource=CHARGING
overall             : CHG-PASS (all 7 legs: cast / exempt-dnd / exempt-posture / hold-vs-gates / coexist / handback-conjunction / master-switch)
```

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
- charging.txt (2,240 bytes)
- charging-logcat.txt (63,440 bytes)
- dumpsys-activities.txt (73,443 bytes)
- dumpsys-display.txt (278,341 bytes)
- dumpsys-window.txt (110,876 bytes)
- logcat-rearcue.txt (76,687 bytes)
- logcat-system-rear.txt (119,645 bytes)
- scenario-notes.md (1,797 bytes)
- screenshots\charging-01-bolt-only.png (17,945 bytes)
- screenshots\charging-04-bolt-with-notification.png (24,086 bytes)
- screenshots\charging-06-switch-on-recovers.png (19,726 bytes)
- screenshots\main.png (163,907 bytes)
- screenshots\notes.txt (247 bytes)
- screenshots\rear.png (19,017 bytes)
- session.md (301 bytes)
- state.txt (11,689 bytes)
- transcript.txt (17,503 bytes)
