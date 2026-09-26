# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260926-153234-dnd-follow
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 12648 |
| listener connected | True |
| posted / removed | 27 / 2 (allowlist hits: com.android.shell,com.rearcue.poc / com.rearcue.poc,com.android.shell) |
| effects | LaunchDashboard, ExitDashboard, UpdateIconSet |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals | android.intent.action.SCREEN_ON, android.intent.action.SCREEN_OFF, android.intent.action.MAIN, android.intent.action.VIEW |
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
- dnd-follow.txt (1,288 bytes)
- dnd-follow-logcat.txt (1,801,840 bytes)
- dumpsys-activities.txt (164,801 bytes)
- dumpsys-display.txt (347,879 bytes)
- dumpsys-window.txt (177,263 bytes)
- logcat-rearcue.txt (1,810,972 bytes)
- logcat-system-rear.txt (111,459 bytes)
- scenario-notes.md (1,466 bytes)
- screenshots\main.png (21,920 bytes)
- screenshots\notes.txt (191 bytes)
- session.md (303 bytes)
- state.txt (3,677 bytes)
- transcript.txt (12,729 bytes)
