# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-125426-overlay-lock
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 176 |
| listener connected | True |
| posted / removed | 25 / 3 (allowlist hits: com.android.shell,com.rearcue.poc / com.rearcue.poc,com.android.shell) |
| effects | LaunchDashboard, UpdateIconSet, ExitDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals | miui.intent.action.SUB_SCREEN_OFF, android.intent.action.SCREEN_OFF, android.intent.action.SCREEN_ON, miui.intent.action.SUB_SCREEN_ON |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### e10-e11-lock.txt

```
granted              : True
overlay-add-accepted : False reason=BadTokenException: Unable to add window android.view.ViewRootImpl$W@fdecd91 -- permission denied for window type 2038
rear-policy-deny     : True
window-pre-lock      : absent
window-ever-on-target: False
e10                  : E10-BLOCKED-BY-E9 (the overlay window never reached display 1 while UNLOCKED: WindowManager denied the add for a non-system app on the rear display (see the deny line below); there is no lock survival to measure -- the overlay channel dies one stage before the lock)
e11                  : E11-BLOCKED-BY-E9 (no overlay window on display 1, so FLAG_KEEP_SCREEN_ON had nothing to hold; rear state left ON at +5s (followed the main screen))
activity-detach-s    : 1.3
activity-owner-lost-s: 5
rear-first-non-on-s  : 5
compare              : ACT-REMOVED-IN 1.3s (device clock: lock marker -> first Dashboard detach; samples lost the owner at +5s)
```

## artifacts
- 01-install.txt (546 bytes)
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (207,584 bytes)
- dumpsys-display.txt (339,618 bytes)
- dumpsys-window.txt (138,012 bytes)
- e10-e11-lock.txt (99,873 bytes)
- e10-samples.txt (3,165 bytes)
- logcat-rearcue.txt (21,034 bytes)
- logcat-system-rear.txt (115,998 bytes)
- screenshots\main.png (255,103 bytes)
- screenshots\notes.txt (192 bytes)
- session.md (305 bytes)
- state.txt (2,957 bytes)
- transcript.txt (12,961 bytes)
- usb-install-dialog.xml (8,291 bytes)
