# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-125937-overlay-lock
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state DOZE_SUSPEND (committed DOZE_SUSPEND)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 63 |
| listener connected | True |
| posted / removed | 24 / 0 (allowlist hits: com.android.shell / ) |
| effects | LaunchDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 0 |
| exit requested / detached | False / True |
| takeover signals | miui.intent.action.SUB_SCREEN_OFF, android.intent.action.SCREEN_OFF |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### e10-e11-lock.txt

```
granted              : True
overlay-add-accepted : False reason=BadTokenException: Unable to add window android.view.ViewRootImpl$W@a509cce -- permission denied for window type 2038
rear-policy-deny     : True
window-pre-lock      : absent
window-ever-on-target: False
e10                  : E10-BLOCKED-BY-E9 (the overlay window never reached display 1 while UNLOCKED: WindowManager denied the add for a non-system app on the rear display (see the deny line below); there is no lock survival to measure -- the overlay channel dies one stage before the lock)
e11                  : E11-BLOCKED-BY-E9 (no overlay window on display 1, so FLAG_KEEP_SCREEN_ON had nothing to hold; rear display facts without the window: first non-ON at +5s and never ON again, ending System.Object[])
activity-detach-s    : 1.4
activity-owner-lost-s: 5
rear-first-non-on-s  : 5
rear-behavior        : first non-ON at +5s and never ON again, ending System.Object[]
compare              : ACT-REMOVED-IN 1.4s (device clock: lock marker -> first Dashboard detach; samples lost the owner at +5s)
```

## artifacts
- 01-install.txt (547 bytes)
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (207,452 bytes)
- dumpsys-display.txt (339,763 bytes)
- dumpsys-window.txt (149,497 bytes)
- e10-e11-lock.txt (79,259 bytes)
- e10-samples.txt (2,074 bytes)
- logcat-rearcue.txt (6,263 bytes)
- logcat-system-rear.txt (113,558 bytes)
- screenshots\main.png (210,732 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (305 bytes)
- state.txt (2,231 bytes)
- transcript.txt (11,942 bytes)
- usb-install-dialog.xml (8,291 bytes)
