# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260923-011205-lock-firstcast
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 2235 |
| listener connected | True |
| posted / removed | 45 / 0 (allowlist hits: com.android.shell,com.rearcue.poc / ) |
| effects | LaunchDashboard, UpdateIconSet |
| projection sent / confirmed | True / True |
| icon set updates | 1 |
| exit requested / detached | False / True |
| takeover signals | miui.intent.action.SUB_SCREEN_OFF, android.intent.action.SCREEN_OFF, android.intent.action.SCREEN_ON, miui.intent.action.SUB_SCREEN_ON, android.intent.action.MAIN, android.intent.action.VIEW |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

## scenario notes (lock-screen first cast, ticket #22)

- **Clean slate first**: both test notifications are cancelled and any Dashboard is
  confirmed gone BEFORE the lock -- this is a FIRST cast, not a re-projection.
- **The route is the E14 transaction**: `service call activity_task 51 i32 <taskId>
  i32 1` (moveRootTaskToDisplay; code 51 is the one E14 verified -- MRSS`s 50 is a
  silent no-op on this build). The `am start --display` path is refused while locked
  (`rearDisplay check locked -> deny`) and is deliberately skipped by the app.
- **The trigger is the real-world one**: one allowlist notification while locked (the
  E1 drive method: shell notification + debug POST_TEST -- both are allowlist apps).
- **Judged from device facts only**: owner=dashboard per 2s sample + the app`s ASCII
  `task-move word=...` markers (Get-ExTaskMoveAppFacts) + system refusal lines; the
  `service call` return is archived (`task-move txn-raw`) but never judged (E14 rule).
- **The exit half**: clearing the notifications must hand the rear back to the native
  interface (`firstcast-exit : native-returned`); the app also moves a task-move-moved
  root task back to display 0 (`task-move hand-back` marker).
- **Icon rendering**: the device facts prove the Dashboard (with the Icon Set content
  logged by `ACTION_STATE`) is on the rear; the pixels themselves are the app`s Compose
  UI -- for human-grade proof take a photo at the peak per the photo-checkpoints practice.
- **Deviation**: the main display state is not sampled (the question is the rear chain);
  the wire keeps the slot as `main=not-sampled`. The app process is alive but has NO
  foreground activity -- the real "phone idle" condition (BAL applies).

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (211,690 bytes)
- dumpsys-display.txt (341,341 bytes)
- dumpsys-window.txt (170,393 bytes)
- firstcast.txt (727 bytes)
- firstcast-samples.txt (1,262 bytes)
- logcat-rearcue.txt (343,214 bytes)
- logcat-system-rear.txt (111,775 bytes)
- scenario-notes.md (1,745 bytes)
- screenshots\main.png (29,620 bytes)
- screenshots\notes.txt (192 bytes)
- session.md (307 bytes)
- state.txt (2,637 bytes)
- transcript.txt (8,842 bytes)
