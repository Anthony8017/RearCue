# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260926-152453-dnd-follow
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 224 |
| listener connected | True |
| posted / removed | 24 / 2 (allowlist hits: com.android.shell,com.rearcue.poc / com.rearcue.poc,com.android.shell) |
| effects | LaunchDashboard, ExitDashboard, UpdateIconSet |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals |  |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

## scenario notes (DND Follow, ticket #52 / spec 0006)

- **Public surface only**: DND is toggled with `cmd notification set_dnd priority|all`;
  the app hears it through the NLS interruption-filter callback -- the exact production
  seam the spec mandates (no new permission, no polling).
- **Judged from device facts**: `mZenMode` from dumpsys notification (the toggle really
  took), rear owner from `dumpsys activity activities` (dashboard / native), and the app`s
  ASCII effect lines (`dnd on`, `dnd off`, `LaunchDashboard`, `ExitDashboard`,
  `manual-cast`, `manual-exit`) -- the same anchor vocabulary as the E-series.
- **Suppress leg ordering**: DND goes on FIRST, then the notification posts; the pass
  condition is "no LaunchDashboard line after the dnd-on anchor AND owner never
  dashboard" (the notification itself is visible to the listener -- DND does not block
  posting, only the cast decision is gated).
- **Withdraw = hand-back**: the exit runs the same task-move hand-back as the
  notification-clear exit (owner must leave `dashboard`).
- **Manual exempt**: Debug Bypass PROJECT_REAR under DND (manual source tag in core),
  then EVERY notification is cancelled -- the dashboard must stay (no auto exit);
  EXIT_REAR (manual exit) is the only thing that takes it down.
- **Source-tag proof on device**: the JVM cases (DashboardCoreTest, 77 green) pin the
  auto/manual matrix; this run pins the wired behavior end to end.

## artifacts
- 02-authorize.txt (873 bytes)
- dnd-follow.txt (1,257 bytes)
- dnd-follow-logcat.txt (29,028 bytes)
- dumpsys-activities.txt (166,470 bytes)
- dumpsys-display.txt (347,946 bytes)
- dumpsys-window.txt (166,883 bytes)
- logcat-rearcue.txt (32,142 bytes)
- logcat-system-rear.txt (112,682 bytes)
- scenario-notes.md (1,466 bytes)
- screenshots\main.png (711,308 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (303 bytes)
- state.txt (3,415 bytes)
- transcript.txt (14,398 bytes)
