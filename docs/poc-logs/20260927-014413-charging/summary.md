# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260927-014413-charging
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state OFF (committed OFF)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 97 |
| listener connected | True |
| posted / removed | 12 / 1 (allowlist hits: com.android.shell / com.android.shell) |
| effects |  |
| projection sent / confirmed | False / False |
| icon set updates | 0 |
| exit requested / detached | False / False |
| takeover signals | android.intent.action.ACTION_POWER_DISCONNECTED, android.intent.action.ACTION_POWER_CONNECTED |
| shizuku server / granted | false / false |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### charging.txt

```
cast                : pass=False -- disconnected line: True; connected line: True; owner=dashboard: False; castSource=; iconSet empty: True; LaunchDashboard(0) on connect: False
exempt-dnd          : pass=False -- handed back before: True; zen at connect: ZEN_MODE_IMPORTANT_INTERRUPTIONS (want != ZEN_MODE_OFF); connected line: False; owner=dashboard: False; castSource=; postureFaceDown= (gate closed only when false)
exempt-posture      : pass=SKIP  -- SKIPPED: posture gate OPEN at cast time (postureFaceDown=true -- phone face-down/covered): the closed-gate posture leg needs the phone physically face-up; the DND leg above carries the exemption proof
hold-vs-gates       : pass=False -- ExitDashboard 0->0 across DND off/on/off (delta must be 0); owner=native; castSource=; posture flip not drivable over adb (recorded, not asserted)
coexist             : pass=False -- ShowFeedBanner: False; owner=native; castSource=; Icon Set holds com.android.shell: False
handback-conjunction: pass=False -- banner hides while charging holds: False; ExitDashboard 0->0 after the clear (delta 0 = hold); owner still dashboard: native; Icon Set empty: True; disconnect line: False; handed back: True; ExitDashboard after unplug: False
master-switch       : pass=False -- switch off: True; connect line: True; LaunchDashboard 0->0 (delta 0 = no reaction); owner=native; switch on again: False; recovered owner=dashboard: False; castSource=null
overall             : CHG-RUN-INVALID (preflight: zen/rear/listener/channel/empty Icon Set/switch on/no cast at start not all up)
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
- charging.txt (2,175 bytes)
- charging-logcat.txt (12,884 bytes)
- dumpsys-activities.txt (53,072 bytes)
- dumpsys-display.txt (226,651 bytes)
- dumpsys-window.txt (98,206 bytes)
- logcat-rearcue.txt (13,848 bytes)
- logcat-system-rear.txt (108,025 bytes)
- scenario-notes.md (1,797 bytes)
- screenshots\charging-01-bolt-only.png (3,562 bytes)
- screenshots\charging-04-bolt-with-notification.png (3,562 bytes)
- screenshots\charging-06-switch-on-recovers.png (3,562 bytes)
- screenshots\main.png (161,986 bytes)
- screenshots\notes.txt (246 bytes)
- screenshots\rear.png (3,562 bytes)
- session.md (301 bytes)
- state.txt (6,788 bytes)
- transcript.txt (30,691 bytes)
