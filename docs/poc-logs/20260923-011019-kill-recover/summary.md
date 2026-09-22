# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260923-011019-kill-recover
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 4147 |
| listener connected | True |
| posted / removed | 24 / 1 (allowlist hits: com.android.shell,com.rearcue.poc / com.android.shell) |
| effects | LaunchDashboard, ExitDashboard, UpdateIconSet |
| projection sent / confirmed | True / True |
| icon set updates | 1 |
| exit requested / detached | True / True |
| takeover signals | miui.intent.action.SUB_SCREEN_ON, android.intent.action.MAIN, android.intent.action.VIEW, android.intent.action.SCREEN_ON |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

## scenario notes (process rebuild recovery, ticket #21)

  `am-kill` is the real-world death (backgrounded first, process dies WITHOUT the stopped
  state); `force-stop` is the harsher death whose stopped state blocks the listener rebind
  (measured REBUILD-NO-LISTENER 2026-09-23, session 20260923-005405).
- **Recovery trigger is the real-world one**: one more allowlist notification ~3s after
  the kill. NotificationManagerService must rebind the dead listener to deliver it; that
  rebind revives the process, IconSetFeed resyncs `getActiveNotifications` (= the current
  Icon Set), the core re-projects and WakeKeepAlive starts again.
- **Evidence split**: `rebuild-samples.txt` carries the pid per sample (the rebuild itself);
  `rebuild-recover.txt` splits the keep-alive markers into before/after the kill stamp, so
  the "loop came back" claim rests on markers from the NEW process only.
- **Deviation**: the main display state is not sampled (this question is about the
  rear chain only); the wire keeps the same slot with `main=no-display`.

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (200,632 bytes)
- dumpsys-display.txt (341,414 bytes)
- dumpsys-window.txt (163,656 bytes)
- logcat-rearcue.txt (591,956 bytes)
- logcat-system-rear.txt (115,994 bytes)
- rebuild-recover.txt (631 bytes)
- rebuild-samples.txt (1,234 bytes)
- scenario-notes.md (1,069 bytes)
- screenshots\main.png (29,620 bytes)
- screenshots\notes.txt (192 bytes)
- session.md (305 bytes)
- state.txt (3,095 bytes)
- transcript.txt (10,472 bytes)
