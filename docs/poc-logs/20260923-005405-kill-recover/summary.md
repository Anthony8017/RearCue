# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260923-005405-kill-recover
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 78 |
| listener connected | True |
| posted / removed | 23 / 0 (allowlist hits: com.android.shell,com.rearcue.poc / ) |
| effects | LaunchDashboard, UpdateIconSet |
| projection sent / confirmed | True / True |
| icon set updates | 1 |
| exit requested / detached | False / False |
| takeover signals |  |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

## scenario notes (process rebuild recovery, ticket #21)

- **What is killed**: `am force-stop` -- a real process death (not a thread restart).
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
- dumpsys-activities.txt (201,481 bytes)
- dumpsys-display.txt (341,217 bytes)
- dumpsys-window.txt (153,941 bytes)
- logcat-rearcue.txt (7,575 bytes)
- logcat-system-rear.txt (115,998 bytes)
- rebuild-recover.txt (605 bytes)
- rebuild-samples.txt (1,234 bytes)
- scenario-notes.md (902 bytes)
- screenshots\main.png (931,901 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (305 bytes)
- state.txt (2,785 bytes)
- transcript.txt (8,190 bytes)
